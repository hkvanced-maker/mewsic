package com.meowsic.app;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Volume boost with a transparent look-ahead peak limiter (16-bit interleaved PCM).
 *
 * <p>The signal is multiplied by a smoothly changing pre-gain, then a look-ahead limiter lowers
 * the gain <em>before</em> a peak arrives so that the output never exceeds digital full scale.
 * That is why a quiet file can be boosted a lot without the harsh distortion of plain clipping.
 *
 * <p>Gain reduction = sliding minimum of the required gain over the look-ahead window, smoothed
 * with a moving average (attack ramp) and a slow exponential release. With a pre-gain of exactly
 * 1.0 the output is bit-identical to the input (only delayed by the look-ahead).
 *
 * <p>Pure Java, no Android dependencies. Not thread safe except {@link #setTargetGain(float)}.
 */
final class GainLimiter {
    private static final float LOOKAHEAD_SEC = 0.005f;
    private static final float RELEASE_SEC = 0.12f;
    private static final float GAIN_SMOOTH_SEC = 0.02f;

    private final int sampleRate;
    private final int channels;
    private final int n;                 // look-ahead window == delay in frames
    private final int dqCap;
    private final float[] delay;         // n * channels, raw input in [-1, 1)
    private final float[] gainRing;      // pre-gain that was used for each stored frame
    private final float[] g1Ring;        // history of the sliding minimum
    private final long[] dqIdx;          // monotonic deque: absolute frame index
    private final float[] dqVal;         // monotonic deque: required gain
    private int dqHead;
    private int dqSize;
    private double g1Sum;
    private long frame;
    private float gCur = 1f;
    private volatile float gTarget = 1f;
    private final float gainAlpha;
    private final float relAlpha;
    private float rel = 1f;

    /** Number of output samples that exceeded full scale (diagnostics / tests). */
    long overCount;

    GainLimiter(int sampleRate, int channels) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.n = Math.max(2, Math.round(LOOKAHEAD_SEC * sampleRate) + 1);
        this.dqCap = n + 1;
        this.delay = new float[n * channels];
        this.gainRing = new float[n];
        this.g1Ring = new float[n];
        this.dqIdx = new long[dqCap];
        this.dqVal = new float[dqCap];
        this.gainAlpha = 1f - (float) Math.exp(-1.0 / (GAIN_SMOOTH_SEC * sampleRate));
        this.relAlpha = 1f - (float) Math.exp(-1.0 / (RELEASE_SEC * sampleRate));
        reset();
    }

    int channels() {
        return channels;
    }

    int sampleRate() {
        return sampleRate;
    }

    int delayFrames() {
        return n - 1;
    }

    void setTargetGain(float linear) {
        gTarget = linear;
    }

    /** Clears all history (call after a seek / flush). */
    void reset() {
        Arrays.fill(delay, 0f);
        Arrays.fill(gainRing, 1f);
        Arrays.fill(g1Ring, 1f);
        dqHead = 0;
        dqSize = 0;
        g1Sum = n;
        frame = 0;
        rel = 1f;
        gCur = gTarget;
    }

    /** Frames of real audio still held in the delay line. */
    int tailFrames() {
        return (int) Math.min(frame, n - 1);
    }

    /** Processes {@code frames} frames from {@code in} into {@code out}. */
    void process(ByteBuffer in, ByteBuffer out, int frames) {
        for (int i = 0; i < frames; i++) {
            step(in, out);
        }
    }

    /** Pushes silence through so the remaining delayed audio is written to {@code out}. */
    void drain(ByteBuffer out) {
        int k = tailFrames();
        for (int i = 0; i < k; i++) {
            step(null, out);
        }
    }

    private void step(ByteBuffer in, ByteBuffer out) {
        // 1. smooth the pre-gain so moving the slider never clicks
        float target = gTarget;
        float d = target - gCur;
        if (d > 1e-6f || d < -1e-6f) {
            gCur += d * gainAlpha;
        } else {
            gCur = target;
        }

        // 2. store the new frame and find its peak
        int pos = (int) (frame % n);
        int base = pos * channels;
        float peak = 0f;
        if (in != null) {
            for (int c = 0; c < channels; c++) {
                float x = in.getShort() * (1f / 32768f);
                delay[base + c] = x;
                float a = x < 0f ? -x : x;
                if (a > peak) {
                    peak = a;
                }
            }
        } else {
            for (int c = 0; c < channels; c++) {
                delay[base + c] = 0f;
            }
        }
        gainRing[pos] = gCur;
        float req = peak * gCur;
        req = req > 1f ? 1f / req : 1f;

        // 3. sliding minimum of the required gain over the last n frames
        while (dqSize > 0) {
            int last = (dqHead + dqSize - 1) % dqCap;
            if (dqVal[last] >= req) {
                dqSize--;
            } else {
                break;
            }
        }
        int back = (dqHead + dqSize) % dqCap;
        dqIdx[back] = frame;
        dqVal[back] = req;
        dqSize++;
        while (dqIdx[dqHead] <= frame - n) {
            dqHead = (dqHead + 1) % dqCap;
            dqSize--;
        }
        float g1 = dqVal[dqHead];

        // 4. moving average of the minimum over n frames -> linear attack ramp
        g1Sum += g1 - g1Ring[pos];
        g1Ring[pos] = g1;
        float g2 = (float) (g1Sum / n);
        if (g2 > 1f) {
            g2 = 1f;
        }

        // 5. slow release, instant (already smooth) attack
        if (g2 <= rel) {
            rel = g2;
        } else {
            rel += (g2 - rel) * relAlpha;
        }

        // 6. emit the oldest stored frame (delay = n - 1 frames)
        int outPos = (pos + 1) % n;
        int obase = outPos * channels;
        float g = gainRing[outPos] * rel;
        for (int c = 0; c < channels; c++) {
            int s = Math.round(delay[obase + c] * g * 32768f);
            if (s > 32768 || s < -32768) {
                overCount++;
            }
            if (s > 32767) {
                s = 32767;
            } else if (s < -32768) {
                s = -32768;
            }
            out.putShort((short) s);
        }
        frame++;
    }
}

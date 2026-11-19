package com.meowsic.app;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;

/**
 * ExoPlayer audio processor that applies the volume boost through {@link GainLimiter}.
 * It sits in front of the speed (Sonic) stage, so "skip silence" also sees the boosted level.
 */
@OptIn(markerClass = UnstableApi.class)
public final class BoostProcessor extends BaseAudioProcessor {
    private volatile float gain = 1f;
    private GainLimiter limiter;

    /** Linear gain, 1.0 = unchanged. May be called from any thread. */
    public void setGain(float linear) {
        gain = linear;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT
                || inputAudioFormat.channelCount < 1
                || inputAudioFormat.channelCount > 8) {
            return AudioFormat.NOT_SET;
        }
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) {
            return;
        }
        int channels = inputAudioFormat.channelCount;
        int rate = inputAudioFormat.sampleRate;
        if (limiter == null || limiter.channels() != channels || limiter.sampleRate() != rate) {
            limiter = new GainLimiter(rate, channels);
        }
        limiter.setTargetGain(gain);
        int frames = remaining / (2 * channels);
        ByteBuffer out = replaceOutputBuffer(frames * 2 * channels);
        limiter.process(inputBuffer, out, frames);
        out.flip();
        inputBuffer.position(inputBuffer.limit());
    }

    // The three hooks below intentionally have no @Override: their signatures changed between
    // Media3 versions and a mismatch must not break the build (the effect is only a 5 ms tail).
    protected void onQueueEndOfStream() {
        if (limiter == null) {
            return;
        }
        int tail = limiter.tailFrames();
        if (tail <= 0) {
            return;
        }
        ByteBuffer out = replaceOutputBuffer(tail * 2 * limiter.channels());
        limiter.drain(out);
        out.flip();
    }

    protected void onFlush() {
        if (limiter != null) {
            limiter.reset();
        }
    }

    protected void onReset() {
        limiter = null;
    }
}

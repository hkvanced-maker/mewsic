package com.meowsic.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

public class GainLimiterTest {
    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + "  " + detail);
        if (!ok) failures++;
    }

    static ByteBuffer toBuf(short[] s) {
        ByteBuffer b = ByteBuffer.allocateDirect(s.length * 2).order(ByteOrder.nativeOrder());
        for (short v : s) b.putShort(v);
        b.flip();
        return b;
    }

    /** Runs the limiter in the given chunk pattern (in frames), drains, returns all output samples. */
    static short[] run(GainLimiter lim, short[] in, int ch, int[] chunks) {
        int frames = in.length / ch;
        ByteBuffer src = toBuf(in);
        ByteBuffer out = ByteBuffer.allocateDirect((frames + lim.delayFrames() + 8) * ch * 2).order(ByteOrder.nativeOrder());
        int done = 0, ci = 0;
        while (done < frames) {
            int c = Math.min(chunks[ci++ % chunks.length], frames - done);
            lim.process(src, out, c);
            done += c;
        }
        lim.drain(out);
        out.flip();
        short[] r = new short[out.remaining() / 2];
        for (int i = 0; i < r.length; i++) r[i] = out.getShort();
        return r;
    }

    static short[] sine(int frames, int ch, double sr, double freq, double amp, double ampR) {
        short[] s = new short[frames * ch];
        for (int i = 0; i < frames; i++) {
            double v = Math.sin(2 * Math.PI * freq * i / sr);
            for (int c = 0; c < ch; c++) s[i * ch + c] = (short) Math.round(v * (c == 0 ? amp : ampR) * 32767);
        }
        return s;
    }

    /** Level (linear, amplitude) of one frequency via a single-bin DFT. */
    static double bin(short[] s, int ch, int channel, int from, int to, double sr, double freq) {
        double re = 0, im = 0; int cnt = 0;
        for (int i = from; i < to; i++) {
            double x = s[i * ch + channel] / 32768.0;
            double ph = 2 * Math.PI * freq * i / sr;
            re += x * Math.cos(ph); im += x * Math.sin(ph); cnt++;
        }
        return 2 * Math.sqrt(re * re + im * im) / cnt;
    }

    static double thdDb(short[] s, int ch, int from, int to, double sr, double f0) {
        double f = bin(s, ch, 0, from, to, sr, f0), h = 0;
        for (int k = 2; k <= 9; k++) { double a = bin(s, ch, 0, from, to, sr, f0 * k); h += a * a; }
        return 20 * Math.log10(Math.sqrt(h) / f);
    }

    public static void main(String[] a) {
        double sr = 48000;
        int ch = 2;

        // 1. unity gain is bit exact (only delayed)
        {
            Random r = new Random(1);
            int frames = 30000;
            short[] in = new short[frames * ch];
            for (int i = 0; i < in.length; i++) in[i] = (short) (r.nextInt(65536) - 32768);
            GainLimiter lim = new GainLimiter(48000, ch);
            short[] out = run(lim, in, ch, new int[]{4096});
            int d = lim.delayFrames() * ch;
            boolean ok = out.length == in.length + d;
            for (int i = 0; ok && i < d; i++) if (out[i] != 0) ok = false;
            for (int i = 0; ok && i < in.length; i++) if (out[i + d] != in[i]) { ok = false; System.out.println("first diff at " + i); }
            check("unity gain bit-exact (full-scale noise)", ok, "delay=" + lim.delayFrames() + " frames, len=" + out.length);
        }

        // 2. chunk-size invariance with gain
        {
            short[] in = sine(40000, ch, sr, 440, 0.7, 0.3);
            GainLimiter l1 = new GainLimiter(48000, ch); l1.setTargetGain(3f);
            GainLimiter l2 = new GainLimiter(48000, ch); l2.setTargetGain(3f);
            short[] o1 = run(l1, in, ch, new int[]{40000});
            short[] o2 = run(l2, in, ch, new int[]{1, 7, 333, 4096, 13, 1024});
            check("chunk-size invariance", java.util.Arrays.equals(o1, o2), "len=" + o1.length);
        }

        // 3. +12 dB on a quiet sine: linear gain, no limiting
        {
            short[] in = sine(96000, ch, sr, 1000, 0.1, 0.1);
            GainLimiter l = new GainLimiter(48000, ch); l.setTargetGain((float) Math.pow(10, 12 / 20.0));
            short[] o = run(l, in, ch, new int[]{2048});
            double amp = bin(o, ch, 0, 48000, 90000, sr, 1000);
            double expect = 0.1 * Math.pow(10, 12 / 20.0);
            check("+12 dB quiet sine amplitude", Math.abs(amp / expect - 1) < 0.005, String.format("got %.4f expected %.4f thd=%.1f dB", amp, expect, thdDb(o, ch, 48000, 90000, sr, 1000)));
            check("+12 dB quiet sine no overs", l.overCount == 0, "overs=" + l.overCount);
        }

        // 4. +12 dB on a loud sine: limiter keeps it clean; compare with hard clipping
        {
            short[] in = sine(96000, ch, sr, 1000, 0.5, 0.5);
            double g = Math.pow(10, 12 / 20.0);
            GainLimiter l = new GainLimiter(48000, ch); l.setTargetGain((float) g);
            short[] o = run(l, in, ch, new int[]{2048});
            short[] clip = new short[in.length];
            for (int i = 0; i < in.length; i++) clip[i] = (short) Math.max(-32768, Math.min(32767, Math.round(in[i] * g)));
            double thdL = thdDb(o, ch, 48000, 90000, sr, 1000), thdC = thdDb(clip, ch, 48000, 90000, sr, 1000);
            int peak = 0; for (short v : o) peak = Math.max(peak, Math.abs(v));
            check("loud sine: limiter THD far below hard clip", thdL < thdC - 30 && thdL < -50, String.format("limiter %.1f dB, hardclip %.1f dB, peak=%d overs=%d", thdL, thdC, peak, l.overCount));
        }

        // 5. sudden loud burst after silence: never over full scale
        {
            int frames = 48000;
            short[] in = new short[frames * ch];
            for (int i = 24000; i < 36000; i++) { double v = Math.sin(2 * Math.PI * 300 * i / sr) * 0.9; for (int c = 0; c < ch; c++) in[i * ch + c] = (short) Math.round(v * 32767); }
            GainLimiter l = new GainLimiter(48000, ch); l.setTargetGain((float) Math.pow(10, 18 / 20.0));
            short[] o = run(l, in, ch, new int[]{512});
            check("burst +18 dB: no overs", l.overCount == 0, "overs=" + l.overCount);
        }

        // 6. gain slider jump: output envelope stays smooth (no zipper / click)
        {
            int frames = 96000;
            short[] in = sine(frames, 1, sr, 1000, 0.05, 0.05);
            GainLimiter l = new GainLimiter(48000, 1); l.setTargetGain(1f);
            ByteBuffer src = toBuf(in);
            ByteBuffer out = ByteBuffer.allocateDirect((frames + 64) * 2).order(ByteOrder.nativeOrder());
            l.process(src, out, 48000);
            l.setTargetGain(4f);
            l.process(src, out, 48000);
            out.flip();
            short[] o = new short[out.remaining() / 2]; for (int i = 0; i < o.length; i++) o[i] = out.getShort();
            double prev = -1, maxStep = 0;
            for (int i = 47000; i + 48 < o.length && i < 70000; i += 48) {
                double pk = 0; for (int k = 0; k < 48; k++) pk = Math.max(pk, Math.abs(o[i + k]));
                if (prev > 0) maxStep = Math.max(maxStep, Math.abs(pk - prev) / prev);
                prev = pk;
            }
            check("gain jump is smooth", maxStep < 0.20, String.format("max 1ms envelope step %.1f%%", maxStep * 100));
        }

        // 7. linked stereo: loud left, quiet right keep their ratio
        {
            short[] in = sine(96000, ch, sr, 500, 0.8, 0.1);
            GainLimiter l = new GainLimiter(48000, ch); l.setTargetGain(3f);
            short[] o = run(l, in, ch, new int[]{1000});
            double aL = bin(o, ch, 0, 60000, 90000, sr, 500), aR = bin(o, ch, 1, 60000, 90000, sr, 500);
            check("stereo image preserved", Math.abs(aL / aR - 8.0) < 0.1, String.format("L/R = %.2f (expected 8)", aL / aR));
        }

        // 8. mono, 22.05 kHz, and 6 channels do not crash and stay in range
        {
            for (int c : new int[]{1, 6}) for (int rate : new int[]{22050, 44100}) {
                short[] in = sine(20000, c, rate, 800, 0.6, 0.6);
                GainLimiter l = new GainLimiter(rate, c); l.setTargetGain(5f);
                short[] o = run(l, in, c, new int[]{999});
                check("format " + c + "ch/" + rate, o.length == in.length + l.delayFrames() * c && l.overCount == 0, "len ok, overs=" + l.overCount);
            }
        }

        // 9. performance
        {
            short[] in = sine(48000 * 20, 2, sr, 440, 0.4, 0.4);
            GainLimiter l = new GainLimiter(48000, 2); l.setTargetGain(2f);
            long t0 = System.nanoTime();
            run(l, in, 2, new int[]{4096});
            double ms = (System.nanoTime() - t0) / 1e6;
            check("speed: 20 s of stereo audio", ms < 2000, String.format("%.0f ms (%.0fx realtime)", ms, 20000 / ms));
        }

        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " TEST(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}

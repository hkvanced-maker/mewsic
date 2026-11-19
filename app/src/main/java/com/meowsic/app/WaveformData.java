package com.meowsic.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Peak envelope of one audio file: one byte per {@link #BUCKET_MS} milliseconds.
 * Values are stored with a square-root compander (stored = 255 * sqrt(peak)) so quiet files
 * still look good and loud peaks keep fine resolution.
 *
 * <p>Written by the analysis thread, read by the UI thread; fields are volatile so a partially
 * analysed file can already be drawn.
 */
public final class WaveformData {
    public static final int BUCKET_MS = 20;
    private static final int MAGIC = 0x4D575632; // "MWV2"

    public volatile byte[] peaks;
    public volatile int count;
    public volatile boolean complete;
    public volatile boolean failed;
    /** 0..1 while the file is being analysed. */
    public volatile float progress;
    /** Highest sample of the whole file, linear 0..1. */
    public volatile float globalPeak;
    public volatile long durationMs;

    public WaveformData(int capacity) {
        peaks = new byte[Math.max(256, capacity)];
    }

    /** Only the analysis thread may call this. */
    void append(byte v) {
        byte[] p = peaks;
        int c = count;
        if (c >= p.length) {
            p = Arrays.copyOf(p, p.length + p.length / 2 + 256);
            peaks = p;
        }
        p[c] = v;
        count = c + 1;
    }

    public static byte quantize(float norm) {
        float n = norm < 0f ? 0f : (norm > 1f ? 1f : norm);
        return (byte) Math.round(255f * (float) Math.sqrt(n));
    }

    public static float amplitudeOf(int stored) {
        float f = (stored & 0xFF) / 255f;
        return f * f;
    }

    /**
     * Boost (dB) that brings the loud passages to about -6 dBFS (the limiter catches the rest),
     * or NaN while the analysis is not finished / the file is silent.
     */
    public float suggestBoostDb(float maxDb) {
        if (!complete) {
            return Float.NaN;
        }
        byte[] p = peaks;
        int c = Math.min(count, p.length);
        if (c < 50) {
            return Float.NaN;
        }
        int[] hist = new int[256];
        int total = 0;
        for (int i = 0; i < c; i++) {
            int v = p[i] & 0xFF;
            if (v >= 8) { // ignore digital silence / noise floor below about -60 dBFS
                hist[v]++;
                total++;
            }
        }
        if (total < 20) {
            return Float.NaN;
        }
        int target = (int) (total * 0.95);
        int run = 0;
        int v95 = 255;
        for (int v = 8; v < 256; v++) {
            run += hist[v];
            if (run >= target) {
                v95 = v;
                break;
            }
        }
        float amp = Math.max(amplitudeOf(v95), 1e-6f);
        float db95 = 20f * (float) Math.log10(amp);
        float want = -6f - db95;
        return Math.max(0f, Math.min(maxDb, want));
    }

    boolean write(File f) {
        File dir = f.getParentFile();
        if (dir != null) {
            dir.mkdirs();
        }
        File tmp = new File(f.getPath() + ".tmp");
        int n = Math.min(count, peaks.length);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(BUCKET_MS);
            out.writeInt(n);
            out.writeFloat(globalPeak);
            out.writeLong(durationMs);
            out.write(peaks, 0, n);
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        if (f.exists()) {
            f.delete();
        }
        return tmp.renameTo(f);
    }

    static boolean readInto(File f, WaveformData d) {
        if (!f.isFile()) {
            return false;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != MAGIC || in.readInt() != BUCKET_MS) {
                return false;
            }
            int n = in.readInt();
            if (n < 0 || n > 20_000_000) {
                return false;
            }
            float gp = in.readFloat();
            long dur = in.readLong();
            byte[] p = new byte[Math.max(256, n)];
            in.readFully(p, 0, n);
            d.peaks = p;
            d.globalPeak = gp;
            d.durationMs = dur;
            d.count = n;
            d.progress = 1f;
            d.complete = true;
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

package com.meowsic.app;

import android.content.Context;

import java.io.File;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Starts / caches waveform analysis (memory + disk). Only the newest request keeps running. */
final class WaveformStore {
    private static final int MAX_MEMORY_ENTRIES = 4;
    private static final Map<String, WaveformData> MEM = new LinkedHashMap<>();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sovio-waveform");
        t.setDaemon(true);
        return t;
    });
    private static AtomicBoolean currentCancel;

    private WaveformStore() {
    }

    private static String idOf(Track t) {
        return t.key + "|" + t.size;
    }

    /** Returns the data if it is already known, never starts work. */
    static synchronized WaveformData peek(Track t) {
        return MEM.get(idOf(t));
    }

    /** Returns (possibly still growing) data for this track and starts the analysis if needed. */
    static synchronized WaveformData get(Context ctx, Track t) {
        final String id = idOf(t);
        WaveformData existing = MEM.get(id);
        if (existing != null && !existing.failed) {
            return existing;
        }
        final WaveformData data = new WaveformData(
                (int) Math.min(4_000_000L, Math.max(0L, t.durationMs) / WaveformData.BUCKET_MS + 512));
        MEM.put(id, data);
        while (MEM.size() > MAX_MEMORY_ENTRIES) {
            String eldest = MEM.keySet().iterator().next();
            MEM.remove(eldest);
        }
        if (currentCancel != null) {
            currentCancel.set(true);
        }
        final AtomicBoolean cancel = new AtomicBoolean(false);
        currentCancel = cancel;
        final Context app = ctx.getApplicationContext();
        final File cacheFile = cacheFile(app, id);
        final Track track = t;
        EXEC.execute(() -> {
            if (cancel.get()) {
                forget(id, data);
                return;
            }
            if (WaveformData.readInto(cacheFile, data)) {
                return;
            }
            WaveformExtractor.run(app, track, data, cancel);
            if (cancel.get() && !data.complete) {
                forget(id, data);
                return;
            }
            if (data.complete && !data.failed) {
                data.write(cacheFile);
            }
        });
        return data;
    }

    private static synchronized void forget(String id, WaveformData data) {
        if (MEM.get(id) == data) {
            MEM.remove(id);
        }
    }

    private static File cacheFile(Context app, String id) {
        String name;
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(id.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            name = sb.toString();
        } catch (Exception e) {
            name = Integer.toHexString(id.hashCode());
        }
        return new File(new File(app.getCacheDir(), "waveforms"), name + ".mwv");
    }
}

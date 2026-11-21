package com.meowsic.app;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Remembers the last queue so the app can reopen the last played file (like AntennaPod). */
final class QueueStore {
    static final class Session {
        final List<Track> tracks;
        final int index;

        Session(List<Track> tracks, int index) {
            this.tracks = tracks;
            this.index = index;
        }
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sovio-queue");
        t.setDaemon(true);
        return t;
    });

    private QueueStore() {
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "last_queue.json");
    }

    /** Saves the queue in the background; the index is stored right away. */
    static void save(Context ctx, List<Track> tracks, int index) {
        final Context app = ctx.getApplicationContext();
        final List<Track> copy = new ArrayList<>(tracks);
        Prefs.setLastIndex(app, index);
        EXEC.execute(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (Track t : copy) {
                    JSONObject o = new JSONObject();
                    o.put("k", t.key);
                    o.put("u", t.uri.toString());
                    o.put("t", t.title);
                    o.put("a", t.artist);
                    o.put("al", t.album);
                    o.put("f", t.folder);
                    o.put("d", t.durationMs);
                    o.put("da", t.dateAdded);
                    o.put("s", t.size);
                    if (t.artUri != null) {
                        o.put("art", t.artUri.toString());
                    }
                    arr.put(o);
                }
                File f = file(app);
                File tmp = new File(f.getPath() + ".tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(arr.toString().getBytes("UTF-8"));
                }
                if (f.exists()) {
                    f.delete();
                }
                tmp.renameTo(f);
            } catch (Exception ignored) {
                // losing the "last played" memory is not worth a crash
            }
        });
    }

    /** Returns the saved queue, or null if there is none. */
    static Session load(Context ctx) {
        File f = file(ctx);
        if (!f.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            JSONArray arr = new JSONArray(bos.toString("UTF-8"));
            List<Track> tracks = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String art = o.optString("art", "");
                tracks.add(new Track(o.getString("k"), Uri.parse(o.getString("u")),
                        o.optString("t", ""), o.optString("a", ""), o.optString("al", ""),
                        o.optString("f", ""), o.optLong("d", 0), o.optLong("da", 0), o.optLong("s", 0),
                        art.isEmpty() ? null : Uri.parse(art)));
            }
            if (tracks.isEmpty()) {
                return null;
            }
            int index = Math.max(0, Math.min(Prefs.getLastIndex(ctx), tracks.size() - 1));
            return new Session(tracks, index);
        } catch (Exception e) {
            return null;
        }
    }

    static void clear(Context ctx) {
        final Context app = ctx.getApplicationContext();
        Prefs.setLastIndex(app, 0);
        EXEC.execute(() -> file(app).delete());
    }
}

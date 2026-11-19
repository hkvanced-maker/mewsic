package com.meowsic.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads cover art (embedded picture first, MediaStore album art second) off the main thread. */
final class ArtLoader {
    interface Callback {
        /** Called on the main thread; bitmap is null when the file has no cover. */
        void onArt(String key, Bitmap bitmap);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "meowsic-art");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(6);

    private ArtLoader() {
    }

    static void load(Context ctx, final Track t, final int sizePx, final Callback cb) {
        Bitmap hit = CACHE.get(t.key);
        if (hit != null) {
            cb.onArt(t.key, hit);
            return;
        }
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            Bitmap bmp = null;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(app, t.uri);
                byte[] pic = r.getEmbeddedPicture();
                if (pic != null) {
                    bmp = decode(pic, sizePx);
                }
            } catch (Exception ignored) {
                // no embedded art
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                    // nothing to do
                }
            }
            if (bmp == null && t.artUri != null) {
                try (InputStream in = app.getContentResolver().openInputStream(t.artUri)) {
                    if (in != null) {
                        bmp = decode(readAll(in), sizePx);
                    }
                } catch (Exception ignored) {
                    // no album art
                }
            }
            final Bitmap result = bmp;
            if (result != null) {
                CACHE.put(t.key, result);
            }
            MAIN.post(() -> cb.onArt(t.key, result));
        });
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < 8 * 1024 * 1024) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static Bitmap decode(byte[] data, int target) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= target && o.outHeight / (sample * 2) >= target) {
            sample *= 2;
        }
        BitmapFactory.Options real = new BitmapFactory.Options();
        real.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, real);
    }
}

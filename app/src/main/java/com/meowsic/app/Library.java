package com.meowsic.app;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Finds audio on the device (MediaStore) and builds {@link Track}s for arbitrary Uris. */
public final class Library {
    public static final int SORT_NEWEST = 0;
    public static final int SORT_TITLE = 1;
    public static final int SORT_LONGEST = 2;

    private Library() {
    }

    @SuppressWarnings("deprecation")
    public static List<Track> load(Context ctx, boolean includeSystemSounds) {
        List<Track> out = new ArrayList<>();
        Uri collection = Build.VERSION.SDK_INT >= 29
                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.IS_RINGTONE,
                MediaStore.Audio.Media.IS_NOTIFICATION,
                MediaStore.Audio.Media.IS_ALARM
        };
        try (Cursor c = ctx.getContentResolver().query(collection, projection, null, null, null)) {
            if (c == null) {
                return out;
            }
            int iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
            int iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
            int iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
            int iRing = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RINGTONE);
            int iNotif = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_NOTIFICATION);
            int iAlarm = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_ALARM);
            while (c.moveToNext()) {
                if (!includeSystemSounds
                        && (c.getInt(iRing) != 0 || c.getInt(iNotif) != 0 || c.getInt(iAlarm) != 0)) {
                    continue;
                }
                long id = c.getLong(iId);
                String name = c.getString(iName);
                String title = clean(c.getString(iTitle));
                if (title.isEmpty()) {
                    title = stripExt(name == null ? "" : name);
                }
                if (title.isEmpty()) {
                    title = "Audio " + id;
                }
                String folder = "";
                String data = c.getString(iData);
                if (data != null) {
                    File parent = new File(data).getParentFile();
                    if (parent != null) {
                        folder = parent.getName();
                    }
                }
                Uri uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                Uri art = Uri.parse("content://media/external/audio/media/" + id + "/albumart");
                out.add(new Track("ms:" + id, uri, title, clean(c.getString(iArtist)),
                        clean(c.getString(iAlbum)), folder, c.getLong(iDur), c.getLong(iAdded),
                        c.getLong(iSize), art));
            }
        } catch (Exception e) {
            // no permission or provider problem: return what we have
        }
        return out;
    }

    public static void sort(List<Track> list, int mode) {
        Comparator<Track> cmp;
        if (mode == SORT_TITLE) {
            final Collator collator = Collator.getInstance();
            cmp = (a, b) -> collator.compare(a.title, b.title);
        } else if (mode == SORT_LONGEST) {
            cmp = (a, b) -> Long.compare(b.durationMs, a.durationMs);
        } else {
            cmp = (a, b) -> Long.compare(b.dateAdded, a.dateAdded);
        }
        Collections.sort(list, cmp);
    }

    /** Builds a Track for a file that was picked or opened from another app. */
    public static Track fromUri(Context ctx, Uri uri) {
        String name = null;
        long size = 0;
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (i >= 0 && !c.isNull(i)) {
                        name = c.getString(i);
                    }
                    int j = c.getColumnIndex(OpenableColumns.SIZE);
                    if (j >= 0 && !c.isNull(j)) {
                        size = c.getLong(j);
                    }
                }
            } catch (Exception ignored) {
                // some providers refuse the query
            }
        } else if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            name = f.getName();
            size = f.length();
        }
        if (name == null) {
            String seg = uri.getLastPathSegment();
            name = seg != null ? seg : "Audio";
        }
        String title = stripExt(name);
        String artist = "";
        String album = "";
        long duration = 0;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) {
                duration = Long.parseLong(d);
            }
            String t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            if (t != null && !t.trim().isEmpty()) {
                title = t.trim();
            }
            artist = clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            album = clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
        } catch (Exception ignored) {
            // unreadable metadata is fine, ExoPlayer may still play it
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
                // nothing to do
            }
        }
        return new Track(uri.toString(), uri, title, artist, album, "", duration, 0, size, null);
    }

    public static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        return "<unknown>".equals(s) ? "" : s;
    }
}

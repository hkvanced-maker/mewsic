package com.meowsic.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** All persisted settings. */
public final class Prefs {
    public static final float[] DEFAULT_PRESETS =
            {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f, 3f, 4f, 5f};

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("meowsic", Context.MODE_PRIVATE);
    }

    /** Per-track data (resume position, boost). */
    private static SharedPreferences tracks(Context c) {
        return c.getApplicationContext().getSharedPreferences("meowsic_tracks", Context.MODE_PRIVATE);
    }

    public static float getSpeed(Context c) {
        return sp(c).getFloat("speed", 1f);
    }

    public static void setSpeed(Context c, float v) {
        sp(c).edit().putFloat("speed", v).apply();
    }

    /** Pitch shift in semitones (0 = original pitch). */
    public static float getPitch(Context c) {
        return sp(c).getFloat("pitch", 0f);
    }

    public static void setPitch(Context c, float v) {
        sp(c).edit().putFloat("pitch", v).apply();
    }

    public static boolean isSkipSilence(Context c) {
        return sp(c).getBoolean("skip_silence", false);
    }

    public static void setSkipSilence(Context c, boolean v) {
        sp(c).edit().putBoolean("skip_silence", v).apply();
    }

    public static boolean isExtendedRange(Context c) {
        return sp(c).getBoolean("extended_range", false);
    }

    public static void setExtendedRange(Context c, boolean v) {
        sp(c).edit().putBoolean("extended_range", v).apply();
    }

    public static int getRewindSec(Context c) {
        return sp(c).getInt("rewind_sec", 20);
    }

    public static void setRewindSec(Context c, int v) {
        sp(c).edit().putInt("rewind_sec", v).apply();
    }

    public static int getForwardSec(Context c) {
        return sp(c).getInt("forward_sec", 30);
    }

    public static void setForwardSec(Context c, int v) {
        sp(c).edit().putInt("forward_sec", v).apply();
    }

    public static int getSort(Context c) {
        return sp(c).getInt("sort", Library.SORT_NEWEST);
    }

    public static void setSort(Context c, int v) {
        sp(c).edit().putInt("sort", v).apply();
    }

    public static boolean getShowSystemSounds(Context c) {
        return sp(c).getBoolean("system_sounds", false);
    }

    public static void setShowSystemSounds(Context c, boolean v) {
        sp(c).edit().putBoolean("system_sounds", v).apply();
    }

    /** One of androidx.media3.common.Player.REPEAT_MODE_*. */
    public static int getRepeatMode(Context c) {
        return sp(c).getInt("repeat", 0);
    }

    public static void setRepeatMode(Context c, int v) {
        sp(c).edit().putInt("repeat", v).apply();
    }

    public static boolean isShuffle(Context c) {
        return sp(c).getBoolean("shuffle", false);
    }

    public static void setShuffle(Context c, boolean v) {
        sp(c).edit().putBoolean("shuffle", v).apply();
    }

    public static List<Float> getPresets(Context c) {
        String s = sp(c).getString("presets", null);
        List<Float> out = new ArrayList<>();
        if (s != null) {
            for (String part : s.split(",")) {
                try {
                    out.add(Float.parseFloat(part.trim()));
                } catch (NumberFormatException ignored) {
                    // skip broken entry
                }
            }
        }
        if (out.isEmpty()) {
            for (float f : DEFAULT_PRESETS) {
                out.add(f);
            }
        }
        Collections.sort(out);
        return out;
    }

    public static void setPresets(Context c, List<Float> list) {
        StringBuilder sb = new StringBuilder();
        for (Float f : list) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US, "%.2f", f));
        }
        sp(c).edit().putString("presets", sb.toString()).apply();
    }

    public static long getResume(Context c, String key) {
        return tracks(c).getLong("p:" + key, 0L);
    }

    public static void setResume(Context c, String key, long ms) {
        SharedPreferences.Editor e = tracks(c).edit();
        if (ms <= 0) {
            e.remove("p:" + key);
        } else {
            e.putLong("p:" + key, ms);
        }
        e.apply();
    }

    /** Volume boost in dB for one track (0 = off). */
    public static float getBoost(Context c, String key) {
        return tracks(c).getFloat("b:" + key, 0f);
    }

    public static void setBoost(Context c, String key, float db) {
        SharedPreferences.Editor e = tracks(c).edit();
        if (db <= 0f) {
            e.remove("b:" + key);
        } else {
            e.putFloat("b:" + key, db);
        }
        e.apply();
    }
}

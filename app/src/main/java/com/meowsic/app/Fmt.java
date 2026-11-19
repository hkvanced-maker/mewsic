package com.meowsic.app;

import java.util.Locale;

/** Small formatting and math helpers. */
public final class Fmt {
    private Fmt() {
    }

    public static String time(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    public static String speed(float s) {
        return String.format(Locale.US, "%.2f\u00D7", s);
    }

    public static String percent(float speed) {
        return String.format(Locale.US, "%d%%", Math.round(speed * 100f));
    }

    public static String db(float db) {
        return String.format(Locale.US, "%+.1f dB", db);
    }

    public static String semitones(float st) {
        return String.format(Locale.US, "%+.1f", st);
    }

    public static float roundSpeed(float s) {
        return Math.round(s * 100f) / 100f;
    }

    public static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static float pitchFactor(float semitones) {
        return (float) Math.pow(2.0, semitones / 12.0);
    }

    public static float dbToGain(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    public static int boostPercent(float db) {
        return Math.round(dbToGain(db) * 100f);
    }
}

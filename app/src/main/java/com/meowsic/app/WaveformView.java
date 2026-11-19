package com.meowsic.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Zoomable waveform scrubber. The playhead stays in the middle and the waveform scrolls under
 * it; drag to scrub, tap to jump, pinch or use the zoom pill to zoom (the last zoom level
 * shows the whole file, so one swipe can cross a long recording).
 */
public class WaveformView extends View {
    /** Supplies the player state and receives seek requests. */
    public interface Host {
        long getPositionMs();

        long getDurationMs();

        void onScrub(long positionMs, boolean finished);
    }

    private static final long[] ZOOM_PRESETS = {10_000L, 30_000L, 120_000L, 600_000L};
    private static final long MIN_WINDOW_MS = 2_000L;

    private Host host;
    private WaveformData data;
    private long loopA = -1;
    private long loopB = -1;
    private boolean zoomAll;
    private long windowMs = 30_000L;

    private boolean dragging;
    private boolean pinching;
    private boolean zoomPressed;
    private float downX;
    private long downPos;
    private long scrubPos;
    private long holdUntil;
    private long lastPos = Long.MIN_VALUE;
    private int lastCount = -1;
    private boolean dirty = true;

    private final float density;
    private final int slop;
    private final Paint playedPaint;
    private final Paint restPaint;
    private final Paint playheadPaint;
    private final Paint loopFillPaint;
    private final Paint loopLinePaint;
    private final Paint markerTextPaint;
    private final Paint statusPaint;
    private final Paint pillPaint;
    private final Paint pillTextPaint;
    private final RectF zoomRect = new RectF();
    private final RectF tmp = new RectF();
    private final Drawable zoomIcon;
    private final ScaleGestureDetector scaleDetector;

    public WaveformView(Context context) {
        this(context, null);
    }

    public WaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        playedPaint = paint(ContextCompat.getColor(context, R.color.wave_played));
        restPaint = paint(ContextCompat.getColor(context, R.color.wave_rest));
        playheadPaint = paint(ContextCompat.getColor(context, R.color.wave_playhead));
        loopFillPaint = paint(ContextCompat.getColor(context, R.color.wave_loop_fill));
        loopLinePaint = paint(ContextCompat.getColor(context, R.color.wave_loop));
        markerTextPaint = paint(ContextCompat.getColor(context, R.color.wave_marker_text));
        markerTextPaint.setTextAlign(Paint.Align.CENTER);
        markerTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        markerTextPaint.setTextSize(11f * density);
        statusPaint = paint(ContextCompat.getColor(context, R.color.wave_text));
        statusPaint.setTextSize(12f * density);
        pillPaint = paint(ContextCompat.getColor(context, R.color.wave_pill));
        pillTextPaint = paint(ContextCompat.getColor(context, R.color.wave_text));
        pillTextPaint.setTextSize(12f * density);
        pillTextPaint.setTypeface(Typeface.DEFAULT_BOLD);

        Drawable icon = ContextCompat.getDrawable(context, R.drawable.ic_zoom_in);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(ContextCompat.getColor(context, R.color.wave_text));
        }
        zoomIcon = icon;

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        onPinch(detector.getScaleFactor());
                        return true;
                    }
                });
    }

    private static Paint paint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }

    public void setHost(Host h) {
        host = h;
        dirty = true;
    }

    public void setData(@Nullable WaveformData d) {
        data = d;
        lastCount = -1;
        dirty = true;
        invalidate();
    }

    public void setLoop(long a, long b) {
        loopA = a;
        loopB = b;
        dirty = true;
    }

    // ------------------------------------------------------------------ animation loop

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (host != null) {
                long pos = currentPos();
                int count = data != null ? data.count : 0;
                if (dirty || pos != lastPos || count != lastCount) {
                    dirty = false;
                    lastPos = pos;
                    lastCount = count;
                    invalidate();
                }
            }
            postOnAnimation(this);
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postOnAnimation(frame);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ state helpers

    private long currentPos() {
        if (dragging || SystemClock.uptimeMillis() < holdUntil) {
            return scrubPos;
        }
        return host != null ? host.getPositionMs() : 0;
    }

    private long duration() {
        long d = host != null ? host.getDurationMs() : 0;
        if (d <= 0 && data != null) {
            d = Math.max(data.durationMs, (long) data.count * WaveformData.BUCKET_MS);
        }
        return d;
    }

    private boolean allMode(long dur) {
        return zoomAll || (dur > 0 && windowMs >= dur);
    }

    private static long clampPos(long pos, long dur) {
        if (pos < 0) {
            pos = 0;
        }
        if (dur > 0 && pos > dur - 1) {
            pos = Math.max(0, dur - 1);
        }
        return pos;
    }

    private String zoomLabel() {
        if (allMode(duration())) {
            return getContext().getString(R.string.wave_zoom_all);
        }
        if (windowMs < 60_000L) {
            return Math.round(windowMs / 1000f) + "s";
        }
        return Math.round(windowMs / 60_000f) + "m";
    }

    private void cycleZoom() {
        if (zoomAll) {
            zoomAll = false;
            windowMs = ZOOM_PRESETS[0];
        } else {
            boolean found = false;
            for (long z : ZOOM_PRESETS) {
                if (z > windowMs + 500) {
                    windowMs = z;
                    found = true;
                    break;
                }
            }
            if (!found) {
                zoomAll = true;
            }
        }
        dirty = true;
    }

    private void onPinch(float factor) {
        long dur = duration();
        long maxWin = Math.max(60_000L, dur);
        long current = allMode(dur) ? Math.max(dur, MIN_WINDOW_MS) : windowMs;
        long next = (long) (current / factor);
        windowMs = Math.max(MIN_WINDOW_MS, Math.min(maxWin, next));
        zoomAll = false;
        dirty = true;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || host == null) {
            return;
        }
        long dur = duration();
        long pos = currentPos();
        boolean all = allMode(dur);
        long win = all ? Math.max(dur, 1000L) : windowMs;
        double msPerPx = (double) win / w;
        double viewStart = all ? 0 : pos - win / 2.0;
        float cy = h / 2f;
        float maxHalf = h * 0.40f;
        float pitch = 3f * density;
        float barW = 2f * density;
        double colMs = pitch * msPerPx;
        long firstCol = (long) Math.floor(viewStart / colMs);
        double firstColStart = firstCol * colMs;
        float xOff = (float) ((firstColStart - viewStart) / msPerPx);
        int cols = (int) Math.ceil(w / pitch) + 2;

        byte[] p = data != null ? data.peaks : null;
        int cnt = (data != null && p != null) ? Math.min(data.count, p.length) : 0;
        int bucketMs = WaveformData.BUCKET_MS;

        float xa = (float) ((loopA - viewStart) / msPerPx);
        float xb = (float) ((loopB - viewStart) / msPerPx);
        boolean hasRegion = loopA >= 0 && loopB > loopA;
        if (hasRegion) {
            tmp.set(Math.max(0f, xa), 0f, Math.min((float) w, xb), h);
            if (tmp.right > tmp.left) {
                canvas.drawRect(tmp, loopFillPaint);
            }
        }

        for (int i = 0; i < cols; i++) {
            double t0 = firstColStart + i * colMs;
            double t1 = t0 + colMs;
            float x = xOff + i * pitch + barW / 2f;
            float amp = -1f;
            if (cnt > 0 && t1 > 0 && t0 < (double) cnt * bucketMs) {
                int b0 = (int) Math.floor(t0 / bucketMs);
                int b1 = (int) Math.ceil(t1 / bucketMs);
                if (b0 < 0) {
                    b0 = 0;
                }
                if (b1 > cnt) {
                    b1 = cnt;
                }
                if (b1 <= b0) {
                    b1 = Math.min(cnt, b0 + 1);
                }
                int step = Math.max(1, (b1 - b0) / 8);
                int b = ((b0 + step - 1) / step) * step; // aligned grid: no shimmering while scrolling
                if (b >= b1) {
                    b = b0;
                }
                int m = 0;
                for (; b < b1; b += step) {
                    int v = p[b] & 0xFF;
                    if (v > m) {
                        m = v;
                    }
                }
                amp = m / 255f;
            }
            Paint paint = (t0 + t1) / 2.0 <= pos ? playedPaint : restPaint;
            if (amp < 0f) {
                tmp.set(x - barW / 2f, cy - density * 0.6f, x + barW / 2f, cy + density * 0.6f);
                canvas.drawRect(tmp, restPaint);
            } else {
                float half = Math.max(density, amp * maxHalf);
                tmp.set(x - barW / 2f, cy - half, x + barW / 2f, cy + half);
                canvas.drawRoundRect(tmp, barW / 2f, barW / 2f, paint);
            }
        }

        if (loopA >= 0) {
            drawMarker(canvas, xa, "A", h);
        }
        if (hasRegion) {
            drawMarker(canvas, xb, "B", h);
        }

        float px = (float) ((pos - viewStart) / msPerPx);
        canvas.drawRect(px - density, 0f, px + density, h, playheadPaint);

        if (data != null && data.failed) {
            canvas.drawText(getContext().getString(R.string.wave_unavailable),
                    8f * density, h - 8f * density, statusPaint);
        } else if (data != null && !data.complete) {
            canvas.drawText(getContext().getString(R.string.wave_analyzing, Math.round(data.progress * 100f)),
                    8f * density, h - 8f * density, statusPaint);
        }
        drawZoomPill(canvas, w);
    }

    private void drawMarker(Canvas c, float x, String label, int h) {
        if (x < -20f * density || x > getWidth() + 20f * density) {
            return;
        }
        c.drawRect(x - density, 0f, x + density, h, loopLinePaint);
        float fw = 16f * density;
        float fh = 16f * density;
        float left = "B".equals(label) ? x - fw : x;
        tmp.set(left, 0f, left + fw, fh);
        c.drawRect(tmp, loopLinePaint);
        c.drawText(label, left + fw / 2f, fh - 4f * density, markerTextPaint);
    }

    private void drawZoomPill(Canvas c, int w) {
        String label = zoomLabel();
        float padding = 8f * density;
        float iconSize = 16f * density;
        float textW = pillTextPaint.measureText(label);
        float pw = padding + iconSize + 4f * density + textW + padding;
        float ph = 28f * density;
        float right = w - 8f * density;
        float top = 8f * density;
        zoomRect.set(right - pw, top, right, top + ph);
        c.drawRoundRect(zoomRect, ph / 2f, ph / 2f, pillPaint);
        if (zoomIcon != null) {
            int ix = (int) (zoomRect.left + padding);
            int iy = (int) (zoomRect.centerY() - iconSize / 2f);
            zoomIcon.setBounds(ix, iy, (int) (ix + iconSize), (int) (iy + iconSize));
            zoomIcon.draw(c);
        }
        float ty = zoomRect.centerY() - (pillTextPaint.descent() + pillTextPaint.ascent()) / 2f;
        c.drawText(label, zoomRect.left + padding + iconSize + 4f * density, ty, pillTextPaint);
    }

    // ------------------------------------------------------------------ touch

    private long posForDrag(float x) {
        long dur = duration();
        int w = Math.max(1, getWidth());
        if (allMode(dur)) {
            return clampPos((long) (x / w * Math.max(dur, 1L)), dur);
        }
        return clampPos(downPos - (long) ((x - downX) * ((double) windowMs / w)), dur);
    }

    private long posForTap(float x) {
        long dur = duration();
        int w = Math.max(1, getWidth());
        if (allMode(dur)) {
            return clampPos((long) (x / w * Math.max(dur, 1L)), dur);
        }
        long now = host.getPositionMs();
        return clampPos(now + (long) ((x - w / 2f) * ((double) windowMs / w)), dur);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (host == null) {
            return false;
        }
        scaleDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                pinching = false;
                dragging = false;
                downX = e.getX();
                downPos = host.getPositionMs();
                zoomPressed = zoomRect.contains(e.getX(), e.getY());
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                pinching = true;
                dragging = false;
                zoomPressed = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pinching || zoomPressed) {
                    return true;
                }
                if (!dragging && Math.abs(e.getX() - downX) > slop) {
                    dragging = true;
                    scrubPos = downPos;
                }
                if (dragging) {
                    scrubPos = posForDrag(e.getX());
                    dirty = true;
                    host.onScrub(scrubPos, false);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (zoomPressed) {
                    zoomPressed = false;
                    if (zoomRect.contains(e.getX(), e.getY())) {
                        cycleZoom();
                    }
                    return true;
                }
                if (pinching) {
                    pinching = false;
                    return true;
                }
                if (dragging) {
                    dragging = false;
                    scrubPos = posForDrag(e.getX());
                } else {
                    scrubPos = posForTap(e.getX());
                }
                holdUntil = SystemClock.uptimeMillis() + 400;
                dirty = true;
                host.onScrub(scrubPos, true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                pinching = false;
                zoomPressed = false;
                return true;
            default:
                return true;
        }
    }
}

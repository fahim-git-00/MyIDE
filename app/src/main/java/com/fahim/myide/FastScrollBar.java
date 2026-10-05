package com.fahim.myide;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class FastScrollBar extends View {

    public interface Host {
        int getScrollY();
        int getScrollRange();
        int getViewHeight();
        void scrollToFraction(float frac);
    }

    private Host host;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final int TRACK_COLOR       = 0x14000000;
    private static final int THUMB_COLOR       = 0x88AAAAAA;
    private static final int THUMB_ACTIVE_COLOR= 0xFF4FC3F7;

    private float thumbTop, thumbBottom;
    private boolean dragging = false;
    private float dragTouchOffset = 0f;

    private final float minThumbH;
    private final float thumbWidth;
    private final float pad;

    public FastScrollBar(Context c) { this(c, null); }

    public FastScrollBar(Context c, AttributeSet a) {
        super(c, a);
        float d = c.getResources().getDisplayMetrics().density;
        minThumbH = 40f * d;
        thumbWidth = 3f * d;
        pad = 2f * d;
        trackPaint.setColor(TRACK_COLOR);
        thumbPaint.setColor(THUMB_COLOR);
        setClickable(true);
    }

    public void setHost(Host h) { this.host = h; invalidate(); }

    @Override protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        if (host == null) return;
        int h = getHeight();
        int range = host.getScrollRange();
        int viewH = host.getViewHeight();
        if (range <= 0 || h <= 0 || viewH <= 0) return;

        float frac = (float) viewH / (viewH + range);
        float thumbH = Math.max(minThumbH, frac * h);
        float availH = h - thumbH;
        if (availH < 0) availH = 0;

        int sy = host.getScrollY();
        float progress = range > 0 ? (float) sy / range : 0f;
        if (progress < 0) progress = 0;
        if (progress > 1) progress = 1;

        thumbTop = progress * availH;
        thumbBottom = thumbTop + thumbH;

        float right = getWidth() - pad;
        float left = right - thumbWidth;

        // Track
        cv.drawRect(left, 0, right, h, trackPaint);

        // Thumb
        thumbPaint.setColor(dragging ? THUMB_ACTIVE_COLOR : THUMB_COLOR);
        cv.drawRoundRect(left, thumbTop, right, thumbBottom,
                thumbWidth, thumbWidth, thumbPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (host == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float y = e.getY();
                if (y >= thumbTop - 20 && y <= thumbBottom + 20) {
                    dragging = true;
                    dragTouchOffset = y - thumbTop;
                } else {
                    dragging = true;
                    dragTouchOffset = (thumbBottom - thumbTop) / 2f;
                    applyTouch(y);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging) { applyTouch(e.getY()); return true; }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (dragging) { dragging = false; invalidate(); return true; }
                return false;
            }
        }
        return false;
    }

    private void applyTouch(float y) {
        int h = getHeight();
        int range = host.getScrollRange();
        int viewH = host.getViewHeight();
        if (range <= 0 || h <= 0 || viewH <= 0) return;

        float frac = (float) viewH / (viewH + range);
        float thumbH = Math.max(minThumbH, frac * h);
        float availH = h - thumbH;
        if (availH <= 0) return;

        float pos = y - dragTouchOffset;
        if (pos < 0) pos = 0;
        if (pos > availH) pos = availH;
        float progress = pos / availH;
        host.scrollToFraction(progress);
        invalidate();
    }

    public void refresh() { invalidate(); }
}

package com.example.lgremote.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * A multi-touch pad that turns finger gestures into WebOS pointer events:
 * <ul>
 *   <li>single-finger drag -> move cursor</li>
 *   <li>quick tap           -> click</li>
 *   <li>two-finger drag     -> scroll</li>
 * </ul>
 */
public class TouchpadView extends View {

    public interface Listener {
        void onMove(int dx, int dy);

        void onScroll(int dy);

        void onClick();
    }

    private static final float MOVE_SENSITIVITY = 5f;
    private static final long CLICK_MAX_MS = 250;
    private static final float CLICK_MAX_DIST = 40f;

    private final Paint bgPaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint dotPaint = new Paint();

    private Listener listener;
    private boolean down = false;
    private boolean twoFinger = false;
    private long downTime;
    private float downX, downY;
    private float lastX, lastY;
    private float scrollBase;

    public TouchpadView(Context context) {
        super(context);
        init();
    }

    public TouchpadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TouchpadView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setFocusable(true);
        bgPaint.setColor(0xFF141821);
        gridPaint.setColor(0xFF20242E);
        gridPaint.setStrokeWidth(dp(1));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(0xFF2A3040);
        borderPaint.setStrokeWidth(dp(1));
        dotPaint.setColor(0xFF38B6FF);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();

        canvas.drawRoundRect(dp(1), dp(1), w - dp(1), h - dp(1), dp(16), dp(16), bgPaint);

        float step = dp(28);
        gridPaint.setStrokeWidth(dp(1));
        for (float x = step; x < w; x += step) {
            canvas.drawLine(x, 0, x, h, gridPaint);
        }
        for (float y = step; y < h; y += step) {
            canvas.drawLine(0, y, w, y, gridPaint);
        }

        canvas.drawRoundRect(dp(1), dp(1), w - dp(1), h - dp(1), dp(16), dp(16), borderPaint);

        float cx = w / 2f;
        float cy = h / 2f;
        canvas.drawCircle(cx, cy, dp(5), dotPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        final int count = event.getPointerCount();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                down = true;
                twoFinger = false;
                downX = event.getX();
                downY = event.getY();
                lastX = downX;
                lastY = downY;
                downTime = System.currentTimeMillis();
                scrollBase = downY;
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (count >= 2) {
                    twoFinger = true;
                    scrollBase = getMidY(event);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (count >= 2 && twoFinger) {
                    float midY = getMidY(event);
                    float dy = midY - scrollBase;
                    scrollBase = midY;
                    if (listener != null && Math.abs(dy) >= 1f) {
                        listener.onScroll((int) dy);
                    }
                } else if (!twoFinger) {
                    float x = event.getX();
                    float y = event.getY();
                    int dx = Math.round((x - lastX) * MOVE_SENSITIVITY);
                    int dy = Math.round((y - lastY) * MOVE_SENSITIVITY);
                    lastX = x;
                    lastY = y;
                    if (listener != null && (dx != 0 || dy != 0)) {
                        listener.onMove(dx, dy);
                    }
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                if (count - 1 < 2) {
                    twoFinger = false;
                }
                break;

            case MotionEvent.ACTION_UP:
                if (!twoFinger && down) {
                    long elapsed = System.currentTimeMillis() - downTime;
                    float dist = Math.abs(event.getX() - downX) + Math.abs(event.getY() - downY);
                    if (elapsed < CLICK_MAX_MS && dist < CLICK_MAX_DIST) {
                        performClick();
                    }
                }
                down = false;
                twoFinger = false;
                break;

            case MotionEvent.ACTION_CANCEL:
                down = false;
                twoFinger = false;
                break;
        }
        invalidate();
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (listener != null) {
            listener.onClick();
        }
        return true;
    }

    private float getMidY(MotionEvent event) {
        return (event.getY(0) + event.getY(1)) / 2f;
    }
}

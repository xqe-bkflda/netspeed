package com.y.netspeed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 悬浮球：只显示一个内存百分比 + 一圈用量弧。
 * 数字用字体基线严格居中。
 * 单击 = 弹面板；拖动 = 移动；长按 = 收起隐藏。
 */
public class BallView extends View {

    public interface Callback {
        void onTap();
        void onLongPress();
        void onDragStart(float rawX, float rawY);
        void onDrag(float rawX, float rawY);
        void onDragEnd();
    }

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pctPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();

    private final float d;
    private int pct = -1;
    private Callback cb;

    private float downRawX, downRawY;
    private boolean moved;
    private long downTime;

    // 原版分档：90 以上红，75 以上黄，其余绿
    private static final int C_OK = 0xFF4CD07D;
    private static final int C_MID = 0xFFFFC24B;
    private static final int C_HOT = 0xFFFF5C5C;

    public BallView(Context c, Callback cb) {
        super(c);
        this.cb = cb;
        this.d = c.getResources().getDisplayMetrics().density;
        setClickable(true);

        bg.setColor(0xF014181D);
        bg.setStyle(Paint.Style.FILL);

        ringBg.setColor(0x33FFFFFF);
        ringBg.setStyle(Paint.Style.STROKE);
        ringBg.setStrokeWidth(dp(3.5f));
        ringBg.setStrokeCap(Paint.Cap.ROUND);

        ring.setColor(C_OK);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(3.5f));
        ring.setStrokeCap(Paint.Cap.ROUND);

        numPaint.setColor(Color.WHITE);
        numPaint.setTextSize(dp(19));
        numPaint.setTextAlign(Paint.Align.CENTER);
        numPaint.setFakeBoldText(true);

        pctPaint.setColor(0x99FFFFFF);
        pctPaint.setTextSize(dp(9));
        pctPaint.setTextAlign(Paint.Align.LEFT);
    }

    private float dp(float v) {
        return v * d;
    }

    public void setPct(int p) {
        if (p < 0) p = 0;
        if (p > 100) p = 100;
        if (p == pct) return;
        pct = p;
        ring.setColor(pct >= 90 ? C_HOT : (pct >= 75 ? C_MID : C_OK));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int size = (int) dp(58);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - dp(3);

        cv.drawCircle(cx, cy, r, bg);

        float sw = dp(3.5f);
        arc.set(cx - r + sw, cy - r + sw, cx + r - sw, cy + r - sw);
        cv.drawArc(arc, 0, 360, false, ringBg);
        cv.drawArc(arc, -90, 360f * Math.max(0, pct) / 100f, false, ring);

        String s = String.valueOf(Math.max(0, pct));
        float numW = numPaint.measureText(s);
        float pctW = pctPaint.measureText("%");
        float gap = dp(1.5f);

        // Android FontMetrics: ascent 为负、descent 为正，文字块中心 = cy - (ascent+descent)/2
        Paint.FontMetrics fm = numPaint.getFontMetrics();
        float baseY = cy - (fm.ascent + fm.descent) / 2f;

        // 数字 + % 作为一组整体水平居中
        float totalW = numW + gap + pctW;
        float numCx = cx - totalW / 2f + numW / 2f;
        cv.drawText(s, numCx, baseY, numPaint);

        // % 抬到数字高度约 45% 处（-fm.ascent 是数字字高）
        float pctBaseY = baseY - (-fm.ascent) * 0.45f;
        cv.drawText("%", numCx + numW / 2f + gap, pctBaseY, pctPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                moved = false;
                downTime = System.currentTimeMillis();
                setAlpha(0.8f);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (!moved && (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4))) {
                    moved = true;
                    if (cb != null) cb.onDragStart(downRawX, downRawY);
                }
                if (moved && cb != null) cb.onDrag(e.getRawX(), e.getRawY());
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                setAlpha(1f);
                boolean longPress = System.currentTimeMillis() - downTime > 480 && !moved;
                if (moved) {
                    if (cb != null) cb.onDragEnd();
                } else if (longPress) {
                    if (cb != null) cb.onLongPress();
                } else {
                    if (cb != null) cb.onTap();
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }
}

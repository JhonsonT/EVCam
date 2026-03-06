package com.kooo.evcam;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * 补盲悬浮窗状态栏 — 方向指示动效视图。
 * <p>
 * 转向时：流动 chevron 箭头 + 琥珀色渐变背景 + 呼吸脉冲；
 * 空闲时：中性灰标签。
 */
public class BlindSpotStatusBarView extends View {

    private static final int CHEVRON_COUNT = 3;

    private String direction = "";
    private String label = "补盲摄像头";

    private ValueAnimator flowAnimator;
    private ValueAnimator pulseAnimator;
    private ValueAnimator fadeAnimator;

    private float flowPhase = 0f;
    private float pulseValue = 1f;
    private float dirAlpha = 0f;

    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final Path chevronPath = new Path();

    private float dp;
    private float chevronH, chevronW, chevronGap;

    public BlindSpotStatusBarView(Context context) {
        super(context);
        init();
    }

    public BlindSpotStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BlindSpotStatusBarView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        dp = getResources().getDisplayMetrics().density;
        chevronH = 6 * dp;
        chevronW = 3.5f * dp;
        chevronGap = 12 * dp;

        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setStrokeWidth(1.8f * dp);
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);

        textPaint.setTextSize(13 * dp);

        flowAnimator = ValueAnimator.ofFloat(0f, 1f);
        flowAnimator.setDuration(1100);
        flowAnimator.setRepeatCount(ValueAnimator.INFINITE);
        flowAnimator.setInterpolator(new LinearInterpolator());
        flowAnimator.addUpdateListener(a -> {
            flowPhase = (float) a.getAnimatedValue();
            invalidate();
        });

        pulseAnimator = ValueAnimator.ofFloat(0.5f, 1f);
        pulseAnimator.setDuration(700);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.addUpdateListener(a -> pulseValue = (float) a.getAnimatedValue());
    }

    public void setDirection(String dir) {
        if (dir == null) dir = "";
        if (dir.equals(direction)) return;
        direction = dir;

        label = "left".equals(dir) ? "左转补盲"
                : "right".equals(dir) ? "右转补盲"
                : "补盲摄像头";

        float target = dir.isEmpty() ? 0f : 1f;

        if (fadeAnimator != null) fadeAnimator.cancel();
        fadeAnimator = ValueAnimator.ofFloat(dirAlpha, target);
        fadeAnimator.setDuration(350);
        fadeAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        fadeAnimator.addUpdateListener(a -> {
            dirAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        if (target == 0f) {
            fadeAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    if (direction.isEmpty()) {
                        flowAnimator.cancel();
                        pulseAnimator.cancel();
                    }
                }
            });
        }
        fadeAnimator.start();

        if (!dir.isEmpty()) {
            if (!flowAnimator.isRunning()) flowAnimator.start();
            if (!pulseAnimator.isRunning()) pulseAnimator.start();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        drawGradientBg(canvas, w, h);
        drawLabel(canvas, w, h);
        if (dirAlpha > 0.01f) drawChevrons(canvas, w, h);
    }

    private void drawGradientBg(Canvas canvas, int w, int h) {
        if (dirAlpha < 0.01f) return;
        int alpha = (int) (40 * dirAlpha * pulseValue);
        int amber = Color.argb(alpha, 255, 183, 77);
        boolean left = "left".equals(direction);
        bgPaint.setShader(new LinearGradient(
                left ? 0 : w, 0,
                left ? w * 0.65f : w * 0.35f, 0,
                amber, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, bgPaint);
        bgPaint.setShader(null);
    }

    private void drawLabel(Canvas canvas, int w, int h) {
        float tw = textPaint.measureText(label);
        float x = (w - tw) / 2f;
        float y = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;

        int r = blend(0xB0, 0xFF, dirAlpha);
        int g = blend(0xB0, 0xC0, dirAlpha);
        int b = blend(0xB0, 0x60, dirAlpha);
        textPaint.setColor(Color.rgb(r, g, b));
        canvas.drawText(label, x, y, textPaint);
    }

    private void drawChevrons(Canvas canvas, int w, int h) {
        boolean left = "left".equals(direction);
        float cy = h / 2f;
        float labelW = textPaint.measureText(label);
        float labelCx = w / 2f;

        float anchor = left
                ? labelCx - labelW / 2f - 10 * dp
                : labelCx + labelW / 2f + 10 * dp;

        for (int i = 0; i < CHEVRON_COUNT; i++) {
            float stagger = (flowPhase + i / (float) CHEVRON_COUNT) % 1f;

            float a = (float) Math.sin(stagger * Math.PI);
            a = a * a * dirAlpha * pulseValue;

            chevronPaint.setColor(Color.argb(clamp((int) (230 * a)), 255, 183, 77));

            float dx = (i + stagger) * chevronGap;
            float cx = left ? anchor - dx : anchor + dx;
            drawChevron(canvas, cx, cy, left);
        }
    }

    private void drawChevron(Canvas canvas, float cx, float cy, boolean pointLeft) {
        chevronPath.reset();
        if (pointLeft) {
            chevronPath.moveTo(cx + chevronW, cy - chevronH);
            chevronPath.lineTo(cx - chevronW, cy);
            chevronPath.lineTo(cx + chevronW, cy + chevronH);
        } else {
            chevronPath.moveTo(cx - chevronW, cy - chevronH);
            chevronPath.lineTo(cx + chevronW, cy);
            chevronPath.lineTo(cx - chevronW, cy + chevronH);
        }
        canvas.drawPath(chevronPath, chevronPaint);
    }

    private static int blend(int from, int to, float ratio) {
        return clamp((int) (from + (to - from) * ratio));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (flowAnimator != null) flowAnimator.cancel();
        if (pulseAnimator != null) pulseAnimator.cancel();
        if (fadeAnimator != null) fadeAnimator.cancel();
    }
}

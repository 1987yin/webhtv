package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;

import com.google.android.material.textview.MaterialTextView;

public class FastMarqueeTextView extends MaterialTextView {

    private static final float SPEED_DP_PER_SECOND = 120f;
    private static final long START_DELAY_MS = 150;
    private static final long END_HOLD_MS = 450;

    private ValueAnimator animator;
    private boolean marquee;
    private float marqueeOffset;

    public FastMarqueeTextView(Context context) {
        super(context);
    }

    public FastMarqueeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FastMarqueeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setFastMarquee(boolean marquee) {
        if (this.marquee == marquee) {
            if (marquee) post(this::startFastMarquee);
            return;
        }
        this.marquee = marquee;
        if (marquee) post(this::startFastMarquee);
        else stopFastMarquee();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int before, int count) {
        super.onTextChanged(text, start, before, count);
        if (marquee) post(this::startFastMarquee);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (marquee) post(this::startFastMarquee);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopFastMarquee();
        super.onDetachedFromWindow();
    }

    private void startFastMarquee() {
        cancelAnimator();
        if (!marquee || getWidth() <= 0 || TextUtils.isEmpty(getText())) {
            marqueeOffset = 0;
            invalidate();
            return;
        }
        int available = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        float overflow = Math.max(0, getPaint().measureText(getText().toString()) - available);
        if (overflow <= 0) {
            marqueeOffset = 0;
            invalidate();
            return;
        }
        float speedPxPerSecond = getResources().getDisplayMetrics().density * SPEED_DP_PER_SECOND;
        float holdDistance = Math.max(1, speedPxPerSecond * END_HOLD_MS / 1000f);
        long duration = Math.max(700, Math.round((overflow + holdDistance) * 1000f / speedPxPerSecond));
        animator = ValueAnimator.ofFloat(0, overflow + holdDistance);
        animator.setStartDelay(START_DELAY_MS);
        animator.setDuration(duration);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.addUpdateListener(animation -> {
            marqueeOffset = Math.min((float) animation.getAnimatedValue(), overflow);
            invalidate();
        });
        animator.start();
    }

    private void stopFastMarquee() {
        cancelAnimator();
        marqueeOffset = 0;
        invalidate();
    }

    private void cancelAnimator() {
        if (animator == null) return;
        animator.cancel();
        animator = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!marquee || TextUtils.isEmpty(getText())) {
            super.onDraw(canvas);
            return;
        }
        String text = getText().toString();
        Paint paint = getPaint();
        int available = getWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
        float textWidth = paint.measureText(text);
        if (available <= 0 || textWidth <= available) {
            super.onDraw(canvas);
            return;
        }
        int color = paint.getColor();
        Paint.Align align = paint.getTextAlign();
        paint.setColor(getCurrentTextColor());
        paint.setTextAlign(Paint.Align.LEFT);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float centerY = getCompoundPaddingTop() + (getHeight() - getCompoundPaddingTop() - getCompoundPaddingBottom()) / 2f;
        float baseline = centerY - (metrics.ascent + metrics.descent) / 2f;
        canvas.save();
        canvas.clipRect(getCompoundPaddingLeft(), getCompoundPaddingTop(), getWidth() - getCompoundPaddingRight(), getHeight() - getCompoundPaddingBottom());
        canvas.drawText(text, getCompoundPaddingLeft() - marqueeOffset, baseline, paint);
        canvas.restore();
        paint.setColor(color);
        paint.setTextAlign(align);
    }
}

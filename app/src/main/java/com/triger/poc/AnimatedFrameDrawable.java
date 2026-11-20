package com.triger.poc;

import android.animation.ObjectAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/** PNG-рамка с плавным свечением и, при необходимости, вращением. */
public final class AnimatedFrameDrawable extends Drawable implements Animatable {

    private final Bitmap bitmap;
    private final boolean rotates;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private ObjectAnimator rotationAnimator;
    private ObjectAnimator pulseAnimator;
    private float rotation;
    private float pulse = 1f;
    private int alpha = 255;
    private boolean running;

    public AnimatedFrameDrawable(Bitmap bitmap, boolean rotates) {
        this.bitmap = bitmap;
        this.rotates = rotates;
    }

    @Override
    public void draw(Canvas canvas) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        int size = Math.min(bounds.width(), bounds.height());
        float left = bounds.left + (bounds.width() - size) / 2f;
        float top = bounds.top + (bounds.height() - size) / 2f;
        destination.set(left, top, left + size, top + size);

        int save = canvas.save();
        if (rotates) {
            canvas.rotate(rotation, destination.centerX(), destination.centerY());
        }
        paint.setAlpha(Math.max(0, Math.min(255, Math.round(alpha * pulse))));
        canvas.drawBitmap(bitmap, null, destination, paint);
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    public float getRotation() {
        return rotation;
    }

    public void setRotation(float rotation) {
        this.rotation = rotation;
        invalidateSelf();
    }

    public float getPulse() {
        return pulse;
    }

    public void setPulse(float pulse) {
        this.pulse = pulse;
        invalidateSelf();
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        if (rotates) {
            if (rotationAnimator == null) {
                rotationAnimator = ObjectAnimator.ofFloat(this, "rotation", 0f, 360f);
                rotationAnimator.setDuration(26000L);
                rotationAnimator.setRepeatCount(ObjectAnimator.INFINITE);
                rotationAnimator.setRepeatMode(ObjectAnimator.RESTART);
                rotationAnimator.setInterpolator(new LinearInterpolator());
            }
            rotationAnimator.start();
        }
        if (pulseAnimator == null) {
            pulseAnimator = ObjectAnimator.ofFloat(this, "pulse", 0.78f, 1f);
            pulseAnimator.setDuration(2400L);
            pulseAnimator.setRepeatCount(ObjectAnimator.INFINITE);
            pulseAnimator.setRepeatMode(ObjectAnimator.REVERSE);
            pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        }
        pulseAnimator.start();
    }

    @Override
    public void stop() {
        running = false;
        if (rotationAnimator != null) {
            rotationAnimator.cancel();
        }
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

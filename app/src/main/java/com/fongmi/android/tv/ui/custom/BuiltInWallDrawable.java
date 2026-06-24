package com.fongmi.android.tv.ui.custom;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.setting.Setting;

public class BuiltInWallDrawable extends Drawable {

    private final Paint paint;
    private final Paint bitmapPaint;
    private final int wall;
    private Bitmap bitmap;
    private int bitmapWidth;
    private int bitmapHeight;
    private int alpha = 255;
    private ColorFilter colorFilter;

    public BuiltInWallDrawable(int wall) {
        this.wall = wall;
        this.paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        ensureBitmap(bounds.width(), bounds.height());
        bitmapPaint.setAlpha(alpha);
        bitmapPaint.setColorFilter(colorFilter);
        canvas.drawBitmap(bitmap, bounds.left, bounds.top, bitmapPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        this.colorFilter = colorFilter;
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }

    private void ensureBitmap(int width, int height) {
        if (bitmap != null && bitmapWidth == width && bitmapHeight == height) return;
        if (bitmap != null) bitmap.recycle();
        bitmapWidth = width;
        bitmapHeight = height;
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        render(new Canvas(bitmap), width, height);
    }

    private void render(Canvas canvas, int width, int height) {
        switch (wall) {
            case Setting.WALL_AURORA_GLASS -> auroraGlass(canvas, width, height);
            case Setting.WALL_SUNSET_PRISM -> sunsetPrism(canvas, width, height);
            case Setting.WALL_MINT_GLACIER -> mintGlacier(canvas, width, height);
            case Setting.WALL_LIQUID_CHROME -> liquidChrome(canvas, width, height);
            case Setting.WALL_NEON_BERRY -> neonBerry(canvas, width, height);
            case Setting.WALL_CHAMPAGNE_MIST -> champagneMist(canvas, width, height);
            default -> solid(canvas, width, height);
        }
        vignette(canvas, width, height);
        grain(canvas, width, height);
    }

    private void auroraGlass(Canvas canvas, int width, int height) {
        background(canvas, width, height, 0xFF142633, 0xFF315A70, 0xFF2B304A);
        glow(canvas, width * 0.16f, height * 0.24f, width * 0.62f, 0xAA47D6C6);
        glow(canvas, width * 0.84f, height * 0.18f, width * 0.58f, 0x885E8CFF);
        glow(canvas, width * 0.58f, height * 0.72f, width * 0.70f, 0x8842E8A9);
        glass(canvas, width * 0.08f, height * 0.13f, width * 0.56f, height * 0.27f, -10, 0x34FFFFFF);
        glass(canvas, width * 0.43f, height * 0.48f, width * 0.50f, height * 0.30f, 8, 0x24FFFFFF);
        ribbon(canvas, width, height, 0x38FFFFFF, 0.18f, 0.72f);
    }

    private void sunsetPrism(Canvas canvas, int width, int height) {
        background(canvas, width, height, 0xFF2B1838, 0xFF82435B, 0xFF26375C);
        glow(canvas, width * 0.18f, height * 0.28f, width * 0.72f, 0xAAFF7A7A);
        glow(canvas, width * 0.80f, height * 0.54f, width * 0.68f, 0x88F9C65C);
        glow(canvas, width * 0.64f, height * 0.10f, width * 0.52f, 0x775C7CFA);
        prism(canvas, width, height, 0x34FFFFFF, 0.10f, 0.38f, 0.78f);
        prism(canvas, width, height, 0x22FFE1AF, 0.38f, 0.66f, 0.94f);
        glass(canvas, width * 0.12f, height * 0.58f, width * 0.52f, height * 0.24f, 12, 0x2EFFFFFF);
    }

    private void mintGlacier(Canvas canvas, int width, int height) {
        background(canvas, width, height, 0xFF162F3A, 0xFF3F7067, 0xFF2D4367);
        glow(canvas, width * 0.72f, height * 0.20f, width * 0.62f, 0x996FFFE2);
        glow(canvas, width * 0.18f, height * 0.70f, width * 0.72f, 0x7747B8FF);
        glow(canvas, width * 0.48f, height * 0.48f, width * 0.50f, 0x55E7FFF6);
        glass(canvas, width * 0.10f, height * 0.12f, width * 0.80f, height * 0.18f, 0, 0x2EFFFFFF);
        glass(canvas, width * 0.20f, height * 0.40f, width * 0.64f, height * 0.34f, -14, 0x24FFFFFF);
        arc(canvas, width, height, 0x30FFFFFF, 0.66f);
    }

    private void liquidChrome(Canvas canvas, int width, int height) {
        background(canvas, width, height, 0xFF161B24, 0xFF3D4354, 0xFF232036);
        glow(canvas, width * 0.24f, height * 0.22f, width * 0.64f, 0x778FC7FF);
        glow(canvas, width * 0.82f, height * 0.72f, width * 0.78f, 0x88DDB3FF);
        chrome(canvas, width, height, 0x66FFFFFF, 0.18f);
        chrome(canvas, width, height, 0x44A7D7FF, 0.52f);
        chrome(canvas, width, height, 0x30F2E9FF, 0.74f);
    }

    private void neonBerry(Canvas canvas, int width, int height) {
        background(canvas, width, height, 0xFF1E1630, 0xFF573B74, 0xFF153249);
        glow(canvas, width * 0.20f, height * 0.22f, width * 0.68f, 0xAAFF4FCB);
        glow(canvas, width * 0.80f, height * 0.30f, width * 0.60f, 0x886BDBFF);
        glow(canvas, width * 0.50f, height * 0.78f, width * 0.78f, 0x88B76BFF);
        glass(canvas, width * 0.16f, height * 0.20f, width * 0.58f, height * 0.26f, 10, 0x28FFFFFF);
        ribbon(canvas, width, height, 0x44FF7BD7, 0.28f, 0.82f);
        prism(canvas, width, height, 0x22FFFFFF, 0.06f, 0.55f, 0.96f);
    }

    private void champagneMist(Canvas canvas, int width, int height) {
        background(canvas, width, height, 0xFF3B2E36, 0xFF705566, 0xFF5D4E40);
        glow(canvas, width * 0.18f, height * 0.20f, width * 0.78f, 0x88FFD8A8);
        glow(canvas, width * 0.82f, height * 0.52f, width * 0.70f, 0x77F5A1C7);
        glow(canvas, width * 0.44f, height * 0.82f, width * 0.76f, 0x66FFE9D1);
        glass(canvas, width * 0.12f, height * 0.16f, width * 0.70f, height * 0.22f, -8, 0x2EFFFFFF);
        glass(canvas, width * 0.28f, height * 0.50f, width * 0.54f, height * 0.30f, 12, 0x20FFFFFF);
        arc(canvas, width, height, 0x28FFE8C8, 0.42f);
    }

    private void solid(Canvas canvas, int width, int height) {
        paint.setShader(null);
        paint.setColor(Setting.getBuiltInWallColor(wall));
        canvas.drawRect(0, 0, width, height, paint);
    }

    private void background(Canvas canvas, int width, int height, int top, int center, int bottom) {
        paint.setShader(new LinearGradient(0, 0, width, height, new int[]{top, center, bottom}, new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(new LinearGradient(width, 0, 0, height, 0x44000000, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
    }

    private void glow(Canvas canvas, float cx, float cy, float radius, int color) {
        paint.setShader(new RadialGradient(cx, cy, radius, color, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);
    }

    private void glass(Canvas canvas, float left, float top, float width, float height, float degrees, int fill) {
        canvas.save();
        canvas.rotate(degrees, left + width / 2f, top + height / 2f);
        RectF rect = new RectF(left, top, left + width, top + height);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fill);
        canvas.drawRoundRect(rect, height * 0.22f, height * 0.22f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, height * 0.012f));
        paint.setColor(0x38FFFFFF);
        canvas.drawRoundRect(rect, height * 0.22f, height * 0.22f, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    private void ribbon(Canvas canvas, int width, int height, int color, float start, float end) {
        Path path = new Path();
        path.moveTo(-width * 0.10f, height * start);
        path.cubicTo(width * 0.22f, height * (start - 0.12f), width * 0.70f, height * (end - 0.22f), width * 1.10f, height * (end - 0.12f));
        path.lineTo(width * 1.10f, height * end);
        path.cubicTo(width * 0.70f, height * (end + 0.06f), width * 0.20f, height * (start + 0.18f), -width * 0.10f, height * (start + 0.14f));
        path.close();
        paint.setShader(new LinearGradient(0, height * start, width, height * end, color, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);
    }

    private void prism(Canvas canvas, int width, int height, int color, float a, float b, float c) {
        Path path = new Path();
        path.moveTo(width * a, -height * 0.05f);
        path.lineTo(width * b, height * 1.05f);
        path.lineTo(width * c, height * 1.05f);
        path.lineTo(width * (b + 0.12f), -height * 0.05f);
        path.close();
        paint.setShader(new LinearGradient(width * a, 0, width * c, height, color, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);
    }

    private void chrome(Canvas canvas, int width, int height, int color, float y) {
        Path path = new Path();
        path.moveTo(-width * 0.06f, height * y);
        path.cubicTo(width * 0.22f, height * (y - 0.10f), width * 0.42f, height * (y + 0.18f), width * 0.68f, height * (y + 0.04f));
        path.cubicTo(width * 0.84f, height * (y - 0.04f), width * 1.02f, height * (y + 0.02f), width * 1.10f, height * (y - 0.02f));
        path.lineTo(width * 1.10f, height * (y + 0.10f));
        path.cubicTo(width * 0.78f, height * (y + 0.22f), width * 0.46f, height * (y + 0.02f), -width * 0.06f, height * (y + 0.16f));
        path.close();
        paint.setShader(new LinearGradient(0, height * y, width, height * (y + 0.14f), new int[]{0x00FFFFFF, color, 0x18FFFFFF}, null, Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);
    }

    private void arc(Canvas canvas, int width, int height, int color, float y) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, width * 0.018f));
        paint.setColor(color);
        RectF rect = new RectF(-width * 0.25f, height * (y - 0.30f), width * 1.25f, height * (y + 0.35f));
        canvas.drawArc(rect, 198, 142, false, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void vignette(Canvas canvas, int width, int height) {
        float radius = Math.max(width, height) * 0.78f;
        paint.setShader(new RadialGradient(width / 2f, height / 2f, radius, 0x00000000, 0x88000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(new LinearGradient(0, 0, 0, height, 0x22000000, 0x66000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
    }

    private void grain(Canvas canvas, int width, int height) {
        paint.setShader(null);
        paint.setStrokeWidth(1f);
        for (int y = 0; y < height; y += Math.max(6, height / 180)) {
            int alpha = 10 + y % 11;
            paint.setColor(Color.argb(alpha, 255, 255, 255));
            canvas.drawLine(0, y, width, y, paint);
        }
    }
}

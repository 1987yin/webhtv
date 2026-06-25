package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.setting.Setting;

public class BuiltInWallView extends View {

    private BuiltInWallDrawable drawable;
    private int wall;

    public BuiltInWallView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setWall(int wall) {
        if (drawable != null && this.wall == wall) {
            invalidate();
            return;
        }
        this.wall = wall;
        this.drawable = new BuiltInWallDrawable(wall);
        setBackgroundColor(Setting.getBuiltInWallColor(wall));
        updateDrawableBounds();
        invalidate();
    }

    public void resume() {
        if (drawable == null || wall == 0) return;
        updateDrawableBounds();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateDrawableBounds();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (drawable == null) return;
        canvas.drawColor(Setting.getBuiltInWallColor(wall));
        drawable.draw(canvas);
    }

    private void updateDrawableBounds() {
        if (drawable == null) return;
        drawable.setBounds(0, 0, getWidth(), getHeight());
    }
}

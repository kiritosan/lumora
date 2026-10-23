package com.lumora.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small native line illustrations; no font-dependent symbols or bitmap assets. */
public final class GalleryMark extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int kind;
    private final Path arch = new Path();
    private final Path hill = new Path();
    private final RectF archOval = new RectF(11, 10, 21, 20);
    private final RectF shoulderOval = new RectF(6, 19, 26, 35);

    public GalleryMark(Context context, int kind) {
        super(context);
        this.kind = kind;
        arch.moveTo(6, 27); arch.lineTo(6, 14);
        arch.cubicTo(6, 1, 26, 1, 26, 14);
        arch.lineTo(26, 27); arch.close();
        hill.moveTo(7, 24); hill.lineTo(13, 17);
        hill.lineTo(18, 22); hill.lineTo(22, 18); hill.lineTo(26, 23);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        float scale = Math.min(getWidth(), getHeight()) / 32f;
        canvas.save();
        canvas.translate((getWidth() - 32 * scale) / 2, (getHeight() - 32 * scale) / 2);
        canvas.scale(scale, scale);
        paint.setColor(isSelected() ? Ui.INK : Ui.MUTED);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        if (kind == 0) {
            canvas.drawPath(arch, paint);
            canvas.drawLine(11, 26, 11, 15, paint);
            canvas.drawLine(21, 26, 21, 15, paint);
            canvas.drawArc(archOval, 180, 180, false, paint);
        } else if (kind == 1) {
            canvas.rotate(-9, 16, 16);
            canvas.drawRoundRect(6, 5, 26, 27, 4, 4, paint);
            canvas.drawCircle(19, 12, 3, paint);
            canvas.drawPath(hill, paint);
        } else {
            canvas.drawCircle(16, 11, 5, paint);
            canvas.drawArc(shoulderOval, 185, 170, false, paint);
            canvas.drawLine(7, 27, 25, 27, paint);
        }
        if (isSelected()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.COPPER);
            canvas.drawCircle(28, 5, 2, paint);
        }
        canvas.restore();
    }

    @Override public void setSelected(boolean selected) {
        super.setSelected(selected);
        invalidate();
    }
}

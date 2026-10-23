package com.lumora.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** A quiet architectural vignette for the editorial cover, drawn entirely with platform Canvas. */
public final class GalleryEmblem extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arch = new Path();
    private final Path shadow = new Path();

    public GalleryEmblem(Context c) {
        super(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        arch.moveTo(18, 110); arch.lineTo(18, 45);
        arch.cubicTo(18, 2, 86, 2, 86, 45); arch.lineTo(86, 110); arch.close();
        shadow.moveTo(18, 110); shadow.lineTo(86, 110);
        shadow.lineTo(110, 132); shadow.lineTo(42, 132); shadow.close();
    }

    @Override protected void onDraw(Canvas c) {
        float scale = Math.min(getWidth() / 110f, getHeight() / 138f);
        c.save(); c.translate((getWidth() - 110 * scale) / 2, (getHeight() - 138 * scale) / 2);
        c.scale(scale, scale);
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xFFDCE1D6); c.drawPath(shadow, paint);
        paint.setColor(0xFFDDD4BA); c.drawPath(arch, paint);
        c.save(); c.clipPath(arch);
        paint.setColor(0xFFECE3CA); c.drawCircle(72, 36, 32, paint);
        paint.setColor(Ui.COPPER); c.drawCircle(59, 43, 17, paint);
        paint.setColor(0xFF8EAA9A); c.drawOval(-32, 75, 136, 155, paint);
        paint.setColor(Ui.INK); c.drawOval(40, 84, 155, 155, paint);
        c.restore();
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1); paint.setColor(0xFFA99874);
        c.drawPath(arch, paint);
        c.drawLine(4, 120, 98, 120, paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Ui.COPPER); c.drawCircle(94, 15, 2, paint);
        c.restore();
    }
}

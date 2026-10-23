package com.lumora.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.MotionEvent;
import android.view.View;

/**
 * 藏品光学与触感检视视图：
 * 1. 重力/加速度传感器与单指抚摸驱动平滑追光、色散与轻微立体偏转；
 * 2. 双击在 1.0x 与 2.15x 微距细赏模式间丝滑切换（微距下拖动可漫游画作细部）；
 * 3. 开启「寻光镜（Loupe）」或长按时，指尖浮现高倍光学放大镜与黄铜刻度圈；
 * 4. 仅展示态，绝不修改任何藏品存储数据。
 */
public class ArtworkView extends View implements SensorEventListener {

    public interface GestureListener {
        default void onSwipeChange(int delta) { }
        default void onZoomStateChanged(boolean zoomed) { }
    }

    private static final float MAX_TILT = 8f;
    private static final float LERP = 0.16f;
    private static final float EPS = 0.02f;
    private static final float SENSOR_EPS = 0.12f;

    private Item item;
    private Bitmap photo;
    private int photoState = ArtworkRenderer.PHOTO_OK;
    private float tiltX = 0f, tiltY = 0f;
    private float wantX = 0f, wantY = 0f;
    private float zoom = 1f, wantZoom = 1f;
    private float panX = 0f, panY = 0f, wantPanX = 0f, wantPanY = 0f;
    private boolean motionActive = false;
    private boolean interactive = true;
    private boolean windowAttached = false;
    private boolean loupeEnabled = false;
    private boolean touching = false;
    private float touchX = 0f, touchY = 0f;
    private float downX = 0f, downY = 0f;
    private long downTime = 0L;
    private long lastTapTime = 0L;
    private float lastTapX = 0f, lastTapY = 0f;

    private SensorManager sm;
    private Sensor sensor;
    private boolean sensorRegistered;
    private final DisplayFrame displayFrame = new DisplayFrame();
    private final Paint loupePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path loupeClip = new Path();
    private int frame = DisplayFrame.EDGE;
    private boolean originalPhoto;
    private GestureListener gestureListener;

    public ArtworkView(Context context) {
        super(context);
        setClickable(true);
    }

    public void setGestureListener(GestureListener listener) {
        this.gestureListener = listener;
    }

    public void setPresentation(int frame, boolean originalPhoto) {
        this.frame = Math.max(0, Math.min(DisplayFrame.POLAROID, frame));
        this.originalPhoto = originalPhoto;
        wantX = wantY = tiltX = tiltY = 0;
        resetInspection();
        updateSensor();
        invalidate();
    }

    public void setLamp(int lamp) {
        displayFrame.setLamp(lamp);
        invalidate();
    }

    public int getLamp() {
        return displayFrame.getLamp();
    }

    public void setLoupeEnabled(boolean enabled) {
        if (this.loupeEnabled != enabled) {
            this.loupeEnabled = enabled;
            if (enabled) resetInspection();
            invalidate();
        }
    }

    public boolean isLoupeEnabled() {
        return loupeEnabled;
    }

    public boolean isZoomed() {
        return wantZoom > 1.05f;
    }

    public void resetInspection() {
        boolean wasZoomed = isZoomed();
        wantZoom = zoom = 1f;
        wantPanX = panX = 0f;
        wantPanY = panY = 0f;
        if (wasZoomed && gestureListener != null) {
            gestureListener.onZoomStateChanged(false);
        }
        invalidate();
    }

    /** 卡片缩略图用 false：不抢触摸、不响应传感器。 */
    public void setInteractive(boolean interactive) {
        this.interactive = interactive;
        if (!interactive) setMotionActive(false);
    }

    public void setItem(Item it, Bitmap photoBitmap) {
        this.item = it;
        this.photo = photoBitmap;
        if (it == null) {
            setContentDescription("空画框");
        } else {
            String desc = "藏品画作：" + (it.title == null ? "" : it.title)
                + "，材质 " + Ui.materialName(it.material)
                + (it.isPhoto() ? "，照片" : "，程序化画作");
            setContentDescription(desc);
        }
        invalidate();
    }

    public void setPhotoState(int state) {
        if (photoState != state) {
            photoState = state;
            invalidate();
        }
    }

    public Item getItem() {
        return item;
    }

    /** 由宿主在 onResume/面板可见时调用 true，onPause/切走时 false。 */
    public void setMotionActive(boolean active) {
        motionActive = active;
        updateSensor();
    }

    private void updateSensor() {
        if (motionActive && interactive && !originalPhoto && windowAttached && isShown()
            && Settings.tiltEnabled(getContext()) && Ui.animationsEnabled(getContext())) {
            if (sm == null) {
                sm = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
            }
            if (sm != null && sensor == null) {
                sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
                if (sensor == null) sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
            if (sm != null && sensor != null) {
                if (!sensorRegistered) {
                    sensorRegistered = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
                }
                return;
            }
        }
        if (sm != null && sensorRegistered) sm.unregisterListener(this);
        sensorRegistered = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        windowAttached = true;
        updateSensor();
    }

    @Override
    protected void onDetachedFromWindow() {
        windowAttached = false;
        updateSensor();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (windowAttached) updateSensor();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (windowAttached) updateSensor();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (touching || !motionActive || originalPhoto) return;
        float gx = e.values[0];
        float gz = e.values.length > 2 ? e.values[2] : 0f;
        float nx = sensorTilt(-gx);
        float ny = sensorTilt(-gz);
        if (Math.abs(nx - wantX) < SENSOR_EPS && Math.abs(ny - wantY) < SENSOR_EPS) {
            return;
        }
        wantX = nx;
        wantY = ny;
        invalidate();
    }

    static float sensorTilt(float gravity) {
        return clamp(gravity / 9.8f * MAX_TILT * 1.6f, -MAX_TILT, MAX_TILT);
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!interactive) return false;
        int w = getWidth(), h = getHeight();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                Ui.haptic(getContext(), this);
                touching = true;
                downX = touchX = ev.getX();
                downY = touchY = ev.getY();
                downTime = ev.getEventTime();
                if (!originalPhoto) {
                    updateTouchTilt(w, h, touchX, touchY);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = ev.getX() - touchX;
                float dy = ev.getY() - touchY;
                touchX = ev.getX();
                touchY = ev.getY();
                if (isZoomed() && !loupeEnabled) {
                    float maxPanX = w * 0.42f;
                    float maxPanY = h * 0.42f;
                    wantPanX = clamp(wantPanX + dx, -maxPanX, maxPanX);
                    wantPanY = clamp(wantPanY + dy, -maxPanY, maxPanY);
                }
                if (!originalPhoto) {
                    updateTouchTilt(w, h, touchX, touchY);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                touching = false;
                long now = ev.getEventTime();
                float totalDx = ev.getX() - downX;
                float totalDy = ev.getY() - downY;
                float slop = Ui.dp(getContext(), 18);
                if (Math.hypot(totalDx, totalDy) < slop && (now - downTime) < 280) {
                    if (now - lastTapTime < 320
                        && Math.hypot(ev.getX() - lastTapX, ev.getY() - lastTapY) < slop * 2.2f) {
                        toggleZoom(w, h, ev.getX(), ev.getY());
                        lastTapTime = 0L;
                    } else {
                        lastTapTime = now;
                        lastTapX = ev.getX();
                        lastTapY = ev.getY();
                        performClick();
                    }
                } else if (!isZoomed() && !loupeEnabled && gestureListener != null
                    && Math.abs(totalDx) > w * 0.24f && Math.abs(totalDx) > Math.abs(totalDy) * 1.6f
                    && (now - downTime) < 420) {
                    gestureListener.onSwipeChange(totalDx < 0 ? 1 : -1);
                }
                if (!Settings.tiltEnabled(getContext())) {
                    wantX = 0f;
                    wantY = 0f;
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    private void updateTouchTilt(int w, int h, float x, float y) {
        float nx = w > 0 ? (x / w - 0.5f) * 2f : 0f;
        float ny = h > 0 ? (y / h - 0.5f) * 2f : 0f;
        wantX = clamp(nx * MAX_TILT, -MAX_TILT, MAX_TILT);
        wantY = clamp(ny * MAX_TILT, -MAX_TILT, MAX_TILT);
    }

    private void toggleZoom(int w, int h, float tapX, float tapY) {
        if (loupeEnabled) return;
        if (isZoomed()) {
            wantZoom = 1f;
            wantPanX = 0f;
            wantPanY = 0f;
            if (!Ui.animationsEnabled(getContext())) {
                zoom = 1f;
                panX = 0f;
                panY = 0f;
            }
            if (gestureListener != null) gestureListener.onZoomStateChanged(false);
        } else {
            wantZoom = 2.1f;
            wantPanX = clamp((w / 2f - tapX) * 0.75f, -w * 0.36f, w * 0.36f);
            wantPanY = clamp((h / 2f - tapY) * 0.75f, -h * 0.36f, h * 0.36f);
            if (!Ui.animationsEnabled(getContext())) {
                zoom = wantZoom;
                panX = wantPanX;
                panY = wantPanY;
            }
            if (gestureListener != null) gestureListener.onZoomStateChanged(true);
        }
        Ui.haptic(getContext(), this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!Ui.animationsEnabled(getContext())) {
            tiltX = wantX;
            tiltY = wantY;
            zoom = wantZoom;
            panX = wantPanX;
            panY = wantPanY;
        } else {
            tiltX += (wantX - tiltX) * LERP;
            tiltY += (wantY - tiltY) * LERP;
            zoom += (wantZoom - zoom) * 0.22f;
            panX += (wantPanX - panX) * 0.22f;
            panY += (wantPanY - panY) * 0.22f;
        }
        boolean moving = Math.abs(wantX - tiltX) > EPS
            || Math.abs(wantY - tiltY) > EPS
            || Math.abs(wantZoom - zoom) > 0.01f
            || Math.abs(wantPanX - panX) > 0.5f
            || Math.abs(wantPanY - panY) > 0.5f;

        int w = getWidth(), h = getHeight();
        canvas.save();
        canvas.translate(w / 2f + panX, h / 2f + panY);
        if (frame == DisplayFrame.EDGE && !originalPhoto) {
            canvas.rotate(tiltX * 0.35f);
            canvas.scale(1.05f * zoom, 1.05f * zoom);
        } else {
            canvas.scale(zoom, zoom);
        }
        canvas.translate(-w / 2f, -h / 2f);
        displayFrame.draw(canvas, w, h, item, photo, photoState,
            tiltX, tiltY, frame, originalPhoto);
        canvas.restore();

        // 光学寻光放大镜（Loupe）：指尖按下或巡检模式开启时渲染 2.35x 局部放大镜
        if (interactive && loupeEnabled && w > 0 && h > 0) {
            float lx = touching ? clamp(touchX, w * 0.16f, w * 0.84f) : w * 0.5f;
            float ly = touching ? clamp(touchY - Ui.dp(getContext(), 28), h * 0.16f, h * 0.84f) : h * 0.45f;
            float focusX = touching ? clamp(touchX, 0, w) : w * 0.5f;
            float focusY = touching ? clamp(touchY, 0, h) : h * 0.45f;
            float radius = Math.min(w, h) * 0.22f;

            canvas.save();
            loupeClip.rewind();
            loupeClip.addCircle(lx, ly, radius, Path.Direction.CW);
            canvas.clipPath(loupeClip);
            canvas.translate(lx, ly);
            canvas.scale(2.35f, 2.35f);
            canvas.translate(-focusX, -focusY);
            displayFrame.draw(canvas, w, h, item, photo, photoState,
                tiltX * 1.2f, tiltY * 1.2f, frame, originalPhoto);
            canvas.restore();

            // 放大镜球面高光与黄铜镜圈
            loupePaint.setStyle(Paint.Style.FILL);
            loupePaint.setShader(new RadialGradient(
                lx - radius * 0.32f, ly - radius * 0.35f, radius * 1.25f,
                new int[]{Color.argb(65, 0xFF, 0xFF, 0xFF), Color.TRANSPARENT, Color.argb(55, 0x0C, 0x16, 0x15)},
                new float[]{0f, 0.65f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(lx, ly, radius, loupePaint);
            loupePaint.setShader(null);

            loupePaint.setStyle(Paint.Style.STROKE);
            loupePaint.setStrokeWidth(Math.max(3f, Ui.dp(getContext(), 2.5f)));
            loupePaint.setColor(0xFFD6B675);
            canvas.drawCircle(lx, ly, radius, loupePaint);
            loupePaint.setStrokeWidth(Math.max(1f, Ui.dp(getContext(), 1f)));
            loupePaint.setColor(0x88FFFDF6);
            canvas.drawCircle(lx, ly, radius - Ui.dp(getContext(), 3), loupePaint);
        }

        if (moving) postInvalidateOnAnimation();
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

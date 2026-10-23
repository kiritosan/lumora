package com.lumora.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.MotionEvent;
import android.view.View;

/**
 * 可交互画作视图：触摸拖动 / 重力感应（回退加速度计）驱动追光与轻微旋转。
 * 仅展示态，不写藏品数据；传感器只在「可交互 + 面板可见 + 已附着窗口 +
 * 用户开启 + 动画未关」时注册；微小传感器抖动被阈值过滤。
 */
public class ArtworkView extends View implements SensorEventListener {

    private static final float MAX_TILT = 8f;
    private static final float LERP = 0.16f;
    private static final float EPS = 0.02f;
    private static final float SENSOR_EPS = 0.12f;

    private Item item;
    private Bitmap photo;
    private int photoState = ArtworkRenderer.PHOTO_OK;
    private float tiltX = 0f, tiltY = 0f;
    private float wantX = 0f, wantY = 0f;
    private boolean motionActive = false;
    private boolean interactive = true;
    private boolean windowAttached = false;
    private SensorManager sm;
    private Sensor sensor;
    private boolean sensorRegistered;
    private boolean touching;

    public ArtworkView(Context context) {
        super(context);
        setClickable(true);
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
        if (motionActive && interactive && windowAttached && isShown()
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
        if (touching || !motionActive) return;
        float gx = e.values[0];
        float gz = e.values.length > 2 ? e.values[2] : 0f;
        float nx = sensorTilt(-gx);
        float ny = sensorTilt(-gz);
        // 抖动过滤：小于阈值不重绘
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
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                Ui.haptic(getContext(), this);
                touching = true;
                // fallthrough
            case MotionEvent.ACTION_MOVE:
                float nx = getWidth() > 0 ? (ev.getX() / getWidth() - 0.5f) * 2f : 0f;
                float ny = getHeight() > 0 ? (ev.getY() / getHeight() - 0.5f) * 2f : 0f;
                wantX = clamp(nx * MAX_TILT, -MAX_TILT, MAX_TILT);
                wantY = clamp(ny * MAX_TILT, -MAX_TILT, MAX_TILT);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                touching = false;
                invalidate();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!Ui.animationsEnabled(getContext())) {
            tiltX = wantX;
            tiltY = wantY;
        } else {
            tiltX += (wantX - tiltX) * LERP;
            tiltY += (wantY - tiltY) * LERP;
        }
        boolean moving = Math.abs(wantX - tiltX) > EPS || Math.abs(wantY - tiltY) > EPS;

        int w = getWidth(), h = getHeight();
        canvas.save();
        canvas.translate(w / 2f, h / 2f);
        canvas.rotate(tiltX * 0.35f);
        canvas.scale(1.05f, 1.05f);
        canvas.translate(-w / 2f, -h / 2f);
        ArtworkRenderer.drawArtwork(canvas, w, h, item, photo, tiltX, tiltY, photoState);
        canvas.restore();

        if (moving) postInvalidateOnAnimation();
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

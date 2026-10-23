package com.lumora.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 掌心物理把玩展盘（Tactile Study Tray）：
 * 纯原生 Canvas + 刚体碰撞/阻尼/磁力吸引排斥/倾斜重力沙盘。
 * 每次打开仅取最多 8 件藏品的只读快照，任何碰撞、抛掷或排布绝不写回藏品库。
 */
public final class TactileTrayView extends View implements SensorEventListener {

    public interface StatusListener {
        void onStatusChanged(String status);
    }

    public static final int MAGNET_OFF = 0, MAGNET_ATTRACT = 1, MAGNET_REPEL = 2;

    static final class Body {
        final Item item;
        final int index;
        final boolean circle;
        Bitmap thumb;
        float x, y, vx, vy;
        float w, h, mass, invMass;
        float angle;
        boolean dragging;

        Body(Item item, int index, boolean circle) {
            this.item = item.copy();
            this.index = index;
            this.circle = circle;
        }
    }

    private final List<Body> bodies = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cardRect = new RectF();
    private final RectF artRect = new RectF();

    private static final class Ripple {
        float x, y, radius, maxRadius, alpha;
        int color;
    }

    private final List<Ripple> ripples = new ArrayList<>();
    private StatusListener statusListener;

    private boolean gravityEnabled = true;
    private boolean tiltSensorEnabled = false;
    private int magnetMode = MAGNET_OFF;
    private float restitution = 0.52f;
    private float gx = 0f, gy = 680f;

    private Body selected;
    private Body draggingBody;
    private float lastTouchX, lastTouchY;
    private long lastTouchTime;
    private boolean fieldActive = false;
    private float fieldX, fieldY;
    private long lastFrameNanos = 0L;
    private long lastImpactTime = 0L;

    private SensorManager sm;
    private Sensor sensor;
    private boolean sensorRegistered = false;
    private boolean windowAttached = false;

    public TactileTrayView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setContentDescription("藏品物理把玩沙盘，可拖拽抛掷或按住空白处施展磁力");
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
        emitStatus(null);
    }

    public void setSnapshot(List<Item> source, ImageRepository repo, java.util.concurrent.Executor io) {
        bodies.clear();
        ripples.clear();
        selected = null;
        draggingBody = null;
        int limit = Math.min(8, source == null ? 0 : source.size());
        for (int i = 0; i < limit; i++) {
            Item it = source.get(i);
            boolean circle = (i % 3 == 1); // 穿插圆形徽章与典藏方卡，丰富碰撞体积感
            final Body b = new Body(it, i, circle);
            Bitmap photo = null;
            if (it.isPhoto() && repo != null) {
                photo = repo.peekThumb(it.photoName, 384);
            }
            b.thumb = ArtworkRenderer.renderArtBitmap(
                circle ? 180 : 170, circle ? 180 : 220, b.item, photo,
                it.isPhoto() && photo == null ? ArtworkRenderer.PHOTO_LOADING : ArtworkRenderer.PHOTO_OK);
            bodies.add(b);
            if (it.isPhoto() && photo == null && repo != null && io != null) {
                final String name = it.photoName;
                io.execute(() -> {
                    Bitmap decoded = repo.loadPhoto(name, 384);
                    if (decoded != null) {
                        Bitmap rendered = ArtworkRenderer.renderArtBitmap(
                            circle ? 180 : 170, circle ? 180 : 220, b.item, decoded, ArtworkRenderer.PHOTO_OK);
                        post(() -> {
                            if (b.thumb != null && !b.thumb.isRecycled()) b.thumb.recycle();
                            b.thumb = rendered;
                            invalidate();
                        });
                    }
                });
            }
        }
        if (getWidth() > 0 && getHeight() > 0) {
            layoutBodies(getWidth(), getHeight());
        }
        emitStatus("已陈列 " + bodies.size() + " 件藏品器物");
        invalidate();
    }

    public int bodyCount() {
        return bodies.size();
    }

    public void releaseBitmaps() {
        for (Body b : bodies) {
            if (b.thumb != null && !b.thumb.isRecycled()) {
                b.thumb.recycle();
                b.thumb = null;
            }
        }
    }

    public void setGravityEnabled(boolean enabled) {
        this.gravityEnabled = enabled;
        if (!enabled) {
            gx = 0f;
            gy = 0f;
        } else if (!tiltSensorEnabled) {
            gx = 0f;
            gy = 680f;
        }
        emitStatus(enabled ? "重力已开启" : "失重悬浮态");
        invalidate();
    }

    public boolean isGravityEnabled() {
        return gravityEnabled;
    }

    public void setTiltSensorEnabled(boolean enabled) {
        this.tiltSensorEnabled = enabled;
        updateSensor();
        if (!enabled && gravityEnabled) {
            gx = 0f;
            gy = 680f;
        }
        emitStatus(enabled ? "机身倾斜重力已开启" : "机身倾斜重力已关闭");
        invalidate();
    }

    public boolean isTiltSensorEnabled() {
        return tiltSensorEnabled;
    }

    public void setMagnetMode(int mode) {
        this.magnetMode = Math.max(MAGNET_OFF, Math.min(MAGNET_REPEL, mode));
        String label = this.magnetMode == MAGNET_ATTRACT ? "磁吸聚拢"
            : this.magnetMode == MAGNET_REPEL ? "斥力散射" : "磁力关闭";
        emitStatus(label);
        invalidate();
    }

    public int getMagnetMode() {
        return magnetMode;
    }

    public void shake() {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        for (int i = 0; i < bodies.size(); i++) {
            Body b = bodies.get(i);
            float dir = (i % 2 == 0 ? 1f : -1f);
            b.vx = clamp(b.vx + dir * (280f + (i * 55f)), -820f, 820f);
            b.vy = clamp(b.vy - (360f + (i % 3) * 110f), -820f, 820f);
        }
        spawnRipple(w / 2f, h / 2f, Math.min(w, h) * 0.36f, Ui.CHAMPAGNE);
        Ui.haptic(getContext(), this);
        emitStatus("轻摇展盘 · 藏品跃动");
        invalidate();
    }

    public void pulseCenter() {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        float cx = w / 2f, cy = h / 2f;
        boolean repel = magnetMode == MAGNET_REPEL;
        for (Body b : bodies) {
            float dx = cx - b.x;
            float dy = cy - b.y;
            float dist = (float) Math.hypot(dx, dy);
            if (dist < 1f) dist = 1f;
            float sign = repel ? -1f : 1f;
            float impulse = 420f * sign;
            b.vx = clamp(b.vx + (dx / dist) * impulse, -820f, 820f);
            b.vy = clamp(b.vy + (dy / dist) * impulse, -820f, 820f);
        }
        spawnRipple(cx, cy, Math.min(w, h) * 0.42f, repel ? 0xFFA4E5DA : Ui.CHAMPAGNE);
        Ui.haptic(getContext(), this);
        emitStatus(repel ? "中心斥力脉冲" : "中心引力聚拢");
        invalidate();
    }

    public void relayout() {
        if (getWidth() > 0 && getHeight() > 0) {
            layoutBodies(getWidth(), getHeight());
            Ui.haptic(getContext(), this);
            emitStatus("已重新陈列");
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            layoutBodies(w, h);
        }
    }

    void layoutBodies(int w, int h) {
        if (bodies.isEmpty() || w <= 0 || h <= 0) return;
        int cols = bodies.size() <= 4 ? 2 : 4;
        int rows = (bodies.size() + cols - 1) / cols;
        float base = Math.min(w / (cols + 1.15f), h / (rows + 1.35f));
        float cardW = clamp(base * 0.86f, 64f, 152f);
        for (int i = 0; i < bodies.size(); i++) {
            Body b = bodies.get(i);
            if (b.circle) {
                b.w = b.h = cardW * 0.94f;
            } else {
                b.w = cardW * 0.86f;
                b.h = cardW * 1.16f;
            }
            b.mass = Math.max(0.8f, (b.w * b.h) / 6800f);
            b.invMass = 1f / b.mass;
            int col = i % cols;
            int row = i / cols;
            float cellW = w / (float) cols;
            float cellH = h / (float) (rows + 0.4f);
            b.x = clamp((col + 0.5f) * cellW, b.w / 2f + 8f, w - b.w / 2f - 8f);
            b.y = clamp((row + 0.55f) * cellH, b.h / 2f + 8f, h - b.h / 2f - 8f);
            b.vx = ((i % 2 == 0) ? 1f : -1f) * 28f;
            b.vy = 0f;
            b.angle = 0f;
        }
    }

    /** 供单元测试与帧循环调用的单步物理推进。 */
    void stepPhysics(float dt, int w, int h) {
        if (w <= 0 || h <= 0 || bodies.isEmpty()) return;
        float damping = (float) Math.pow(0.985, dt * 60f);
        for (Body b : bodies) {
            if (b.dragging) continue;
            if (gravityEnabled) {
                b.vx += gx * dt;
                b.vy += gy * dt;
            }
            if (fieldActive && magnetMode != MAGNET_OFF) {
                float dx = fieldX - b.x;
                float dy = fieldY - b.y;
                float distSq = Math.max(900f, dx * dx + dy * dy);
                float dist = (float) Math.sqrt(distSq);
                float sign = magnetMode == MAGNET_ATTRACT ? 1f : -1f;
                float force = sign * (520000f / distSq) * b.invMass;
                b.vx += (dx / dist) * force * dt;
                b.vy += (dy / dist) * force * dt;
            }
            b.vx = clamp(b.vx * damping, -860f, 860f);
            b.vy = clamp(b.vy * damping, -860f, 860f);
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            b.angle = clamp(b.vx * 0.018f, -14f, 14f);

            // 边界碰撞与回弹
            float hw = b.w / 2f, hh = b.h / 2f;
            if (b.x - hw < 6f) {
                b.x = 6f + hw;
                if (b.vx < 0) {
                    onImpact(b.x - hw, b.y, -b.vx, b.item.material);
                    b.vx = -b.vx * restitution;
                }
            } else if (b.x + hw > w - 6f) {
                b.x = w - 6f - hw;
                if (b.vx > 0) {
                    onImpact(b.x + hw, b.y, b.vx, b.item.material);
                    b.vx = -b.vx * restitution;
                }
            }
            if (b.y - hh < 6f) {
                b.y = 6f + hh;
                if (b.vy < 0) {
                    onImpact(b.x, b.y - hh, -b.vy, b.item.material);
                    b.vy = -b.vy * restitution;
                }
            } else if (b.y + hh > h - 6f) {
                b.y = h - 6f - hh;
                if (b.vy > 0) {
                    onImpact(b.x, b.y + hh, b.vy, b.item.material);
                    b.vy = -b.vy * restitution;
                    b.vx *= 0.94f; // 台面绒布摩擦
                }
            }
        }

        // 多体弹性碰撞解算
        int n = bodies.size();
        for (int i = 0; i < n; i++) {
            Body a = bodies.get(i);
            for (int j = i + 1; j < n; j++) {
                Body b = bodies.get(j);
                resolveCollision(a, b);
            }
        }

        // 波纹衰减
        for (int i = ripples.size() - 1; i >= 0; i--) {
            Ripple r = ripples.get(i);
            r.radius += (r.maxRadius - r.radius) * 0.18f;
            r.alpha -= dt * 1.9f;
            if (r.alpha <= 0.02f) ripples.remove(i);
        }
    }

    private void resolveCollision(Body a, Body b) {
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        float overlapX = (a.w + b.w) * 0.5f - Math.abs(dx);
        float overlapY = (a.h + b.h) * 0.5f - Math.abs(dy);
        if (overlapX <= 0 || overlapY <= 0) return;

        float nx, ny, penetration;
        if (a.circle && b.circle) {
            float dist = (float) Math.hypot(dx, dy);
            float minDist = (a.w + b.w) * 0.5f;
            if (dist >= minDist) return;
            if (dist < 0.001f) {
                nx = 1f;
                ny = 0f;
                penetration = minDist;
            } else {
                nx = dx / dist;
                ny = dy / dist;
                penetration = minDist - dist;
            }
        } else if (overlapX < overlapY) {
            nx = dx < 0 ? -1f : 1f;
            ny = 0f;
            penetration = overlapX;
        } else {
            nx = 0f;
            ny = dy < 0 ? -1f : 1f;
            penetration = overlapY;
        }

        float invMassSum = (a.dragging ? 0f : a.invMass) + (b.dragging ? 0f : b.invMass);
        if (invMassSum <= 0f) return;

        // 位置修正
        float corr = (penetration / invMassSum) * 0.82f;
        if (!a.dragging) {
            a.x -= nx * corr * a.invMass;
            a.y -= ny * corr * a.invMass;
        }
        if (!b.dragging) {
            b.x += nx * corr * b.invMass;
            b.y += ny * corr * b.invMass;
        }

        // 冲量解算
        float rvx = b.vx - a.vx;
        float rvy = b.vy - a.vy;
        float velAlongNormal = rvx * nx + rvy * ny;
        if (velAlongNormal > 0) return;

        float j = -(1f + restitution) * velAlongNormal / invMassSum;
        float impulseX = j * nx;
        float impulseY = j * ny;
        if (!a.dragging) {
            a.vx = clamp(a.vx - impulseX * a.invMass, -860f, 860f);
            a.vy = clamp(a.vy - impulseY * a.invMass, -860f, 860f);
        }
        if (!b.dragging) {
            b.vx = clamp(b.vx + impulseX * b.invMass, -860f, 860f);
            b.vy = clamp(b.vy + impulseY * b.invMass, -860f, 860f);
        }
        onImpact((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f, Math.abs(velAlongNormal), a.item.material);
    }

    private void onImpact(float x, float y, float speed, int material) {
        if (speed < 95f) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastImpactTime < 90L) return;
        lastImpactTime = now;
        int color = material == Item.MATERIAL_SEA ? 0xFFA4E5DA
            : material == Item.MATERIAL_MIST ? 0xFFE2E6DE : Ui.CHAMPAGNE;
        float strength = clamp((speed - 95f) / 620f, 0.15f, 1f);
        spawnRipple(x, y, 18f + strength * 42f, color);
        if (speed > 230f) {
            Ui.haptic(getContext(), this);
        }
    }

    private void spawnRipple(float x, float y, float maxRadius, int color) {
        if (!Ui.animationsEnabled(getContext()) || ripples.size() >= 8) return;
        Ripple r = new Ripple();
        r.x = x;
        r.y = y;
        r.radius = 6f;
        r.maxRadius = maxRadius;
        r.alpha = 0.65f;
        r.color = color;
        ripples.add(r);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float x = ev.getX();
        float y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                Body hit = hitTest(x, y);
                if (hit != null) {
                    selected = hit;
                    draggingBody = hit;
                    hit.dragging = true;
                    hit.vx = hit.vy = 0f;
                    lastTouchX = x;
                    lastTouchY = y;
                    lastTouchTime = ev.getEventTime();
                    Ui.haptic(getContext(), this);
                    emitStatus("把玩：「" + hit.item.title + "」· 松手可抛掷");
                } else if (magnetMode != MAGNET_OFF) {
                    fieldActive = true;
                    fieldX = x;
                    fieldY = y;
                    Ui.haptic(getContext(), this);
                    emitStatus(magnetMode == MAGNET_ATTRACT ? "正在施展磁吸力场…" : "正在施展斥力力场…");
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (draggingBody != null) {
                    long now = ev.getEventTime();
                    float dt = Math.max(0.008f, (now - lastTouchTime) / 1000f);
                    draggingBody.vx = clamp((x - lastTouchX) / dt * 0.55f, -820f, 820f);
                    draggingBody.vy = clamp((y - lastTouchY) / dt * 0.55f, -820f, 820f);
                    draggingBody.x = clamp(x, draggingBody.w / 2f + 6f, getWidth() - draggingBody.w / 2f - 6f);
                    draggingBody.y = clamp(y, draggingBody.h / 2f + 6f, getHeight() - draggingBody.h / 2f - 6f);
                    lastTouchX = x;
                    lastTouchY = y;
                    lastTouchTime = now;
                } else if (fieldActive) {
                    fieldX = x;
                    fieldY = y;
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (draggingBody != null) {
                    draggingBody.dragging = false;
                    draggingBody = null;
                }
                fieldActive = false;
                performClick();
                invalidate();
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private Body hitTest(float x, float y) {
        for (int i = bodies.size() - 1; i >= 0; i--) {
            Body b = bodies.get(i);
            if (x >= b.x - b.w * 0.55f && x <= b.x + b.w * 0.55f
                && y >= b.y - b.h * 0.55f && y <= b.y + b.h * 0.55f) {
                return b;
            }
        }
        return null;
    }

    private void emitStatus(String note) {
        if (statusListener == null) return;
        String base = selected != null ? "已选「" + selected.item.title + "」" : "拖拽器物抛掷碰撞";
        statusListener.onStatusChanged(note != null && !note.isEmpty() ? base + " · " + note : base);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        long now = java.lang.System.nanoTime();
        float dt = lastFrameNanos == 0L ? (1f / 60f)
            : clamp((now - lastFrameNanos) / 1_000_000_000f, 1f / 240f, 1f / 24f);
        lastFrameNanos = now;

        stepPhysics(dt, w, h);

        // 丝绒暗室沙盘底座与微光坐标网
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(w * 0.5f, h * 0.36f, Math.max(w, h) * 0.72f,
            0xFF22302D, 0xFF0E1514, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(0, 0, w, h, Ui.dp(getContext(), 18), Ui.dp(getContext(), 18), paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(0x14D6B675);
        int step = Math.max(24, Ui.dp(getContext(), 32));
        for (int x = step; x < w; x += step) canvas.drawLine(x, 0, x, h, paint);
        for (int y = step; y < h; y += step) canvas.drawLine(0, y, w, y, paint);

        // 磁力场光环
        if (fieldActive && magnetMode != MAGNET_OFF) {
            int mc = magnetMode == MAGNET_ATTRACT ? Ui.CHAMPAGNE : 0xFFA4E5DA;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(getContext(), 1.5f));
            paint.setColor(mc);
            canvas.drawCircle(fieldX, fieldY, Ui.dp(getContext(), 42), paint);
            paint.setColor((mc & 0x00FFFFFF) | 0x44000000);
            canvas.drawCircle(fieldX, fieldY, Ui.dp(getContext(), 68), paint);
        }

        // 碰撞微光涟漪
        for (Ripple r : ripples) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(getContext(), 1.8f));
            int alpha = clampInt((int) (r.alpha * 255), 0, 255);
            paint.setColor((r.color & 0x00FFFFFF) | (alpha << 24));
            canvas.drawCircle(r.x, r.y, r.radius, paint);
        }

        // 渲染每件器物与动态高光
        for (Body b : bodies) {
            canvas.save();
            canvas.translate(b.x, b.y);
            canvas.rotate(b.angle);

            float hw = b.w / 2f, hh = b.h / 2f;
            cardRect.set(-hw, -hh, hw, hh);

            // 器物投影
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(b.dragging ? 0x66000000 : 0x44000000);
            float shadowOff = b.dragging ? Ui.dp(getContext(), 8) : Ui.dp(getContext(), 4);
            if (b.circle) {
                canvas.drawOval(-hw + 2, -hh + shadowOff, hw + 2, hh + shadowOff, paint);
            } else {
                canvas.drawRoundRect(-hw + 2, -hh + shadowOff, hw + 2, hh + shadowOff,
                    Ui.dp(getContext(), 10), Ui.dp(getContext(), 10), paint);
            }

            // 金属/香槟边框底板
            paint.setShader(new LinearGradient(-hw, -hh, hw, hh,
                new int[]{0xFFFFF8E8, 0xFFD6B675, 0xFF3E382E, 0xFFEAD3A3},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            if (b.circle) {
                canvas.drawOval(cardRect, paint);
            } else {
                canvas.drawRoundRect(cardRect, Ui.dp(getContext(), 10), Ui.dp(getContext(), 10), paint);
            }
            paint.setShader(null);

            float pad = Math.max(3f, Ui.dp(getContext(), 3.5f));
            artRect.set(-hw + pad, -hh + pad, hw - pad, hh - pad);
            if (b.thumb != null && !b.thumb.isRecycled()) {
                canvas.save();
                if (b.circle) {
                    android.graphics.Path p = new android.graphics.Path();
                    p.addOval(artRect, android.graphics.Path.Direction.CW);
                    canvas.clipPath(p);
                } else {
                    canvas.clipRect(artRect);
                }
                canvas.drawBitmap(b.thumb, null, artRect, paint);
                // 随速度与位置变化的动态流光
                float shineX = (b.x / Math.max(1f, w) - 0.5f) * hw;
                float shineY = (b.y / Math.max(1f, h) - 0.5f) * hh;
                paint.setShader(new RadialGradient(shineX, shineY, Math.max(b.w, b.h) * 0.7f,
                    Color.argb(68, 0xFF, 0xFA, 0xE6), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                canvas.drawRect(artRect, paint);
                paint.setShader(null);
                canvas.restore();
            }

            // 底部微缩展签（非圆形时）
            if (!b.circle && b.item.title != null) {
                paint.setColor(0xAA101B19);
                canvas.drawRect(artRect.left, artRect.bottom - Ui.dp(getContext(), 18),
                    artRect.right, artRect.bottom, paint);
                textPaint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
                textPaint.setTextSize(Math.max(8f, Ui.dp(getContext(), 9.5f)));
                textPaint.setColor(Ui.PAPER);
                textPaint.setTextAlign(Paint.Align.CENTER);
                String t = b.item.title.length() > 6 ? b.item.title.substring(0, 6) + "…" : b.item.title;
                canvas.drawText(t, 0, artRect.bottom - Ui.dp(getContext(), 5), textPaint);
            }

            if (b == selected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Ui.dp(getContext(), 2f));
                paint.setColor(Ui.CHAMPAGNE);
                if (b.circle) {
                    canvas.drawOval(cardRect, paint);
                } else {
                    canvas.drawRoundRect(cardRect, Ui.dp(getContext(), 10), Ui.dp(getContext(), 10), paint);
                }
            }
            canvas.restore();
        }

        // 外框细金线
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, Ui.dp(getContext(), 1f)));
        paint.setColor(0x55D6B675);
        canvas.drawRoundRect(1, 1, w - 1, h - 1, Ui.dp(getContext(), 18), Ui.dp(getContext(), 18), paint);

        if (isMoving()) {
            postInvalidateOnAnimation();
        }
    }

    private boolean isMoving() {
        if (!Ui.animationsEnabled(getContext())) return false;
        if (draggingBody != null || fieldActive || !ripples.isEmpty() || tiltSensorEnabled) return true;
        for (Body b : bodies) {
            if (Math.hypot(b.vx, b.vy) > 4f || gravityEnabled) return true;
        }
        return false;
    }

    private void updateSensor() {
        if (tiltSensorEnabled && windowAttached && isShown() && Ui.animationsEnabled(getContext())) {
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
    public void onSensorChanged(SensorEvent e) {
        if (!tiltSensorEnabled || !gravityEnabled) return;
        gx = clamp(-e.values[0] * 145f, -760f, 760f);
        gy = clamp(e.values[1] * 145f, -760f, 760f);
        invalidate();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

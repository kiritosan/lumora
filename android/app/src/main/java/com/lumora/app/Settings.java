package com.lumora.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 本机偏好：倾斜追光 / 轻触反馈开关，以及相机回写临时凭证。 */
public final class Settings {

    private static final String PREFS = "lumora_settings";
    private static final String KEY_TILT = "tilt_enabled";
    private static final String KEY_HAPTICS = "haptics_enabled";
    private static final String KEY_PENDING_CAMERA = "pending_camera_name";

    private Settings() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean tiltEnabled(Context c) {
        return prefs(c).getBoolean(KEY_TILT, true);
    }

    public static boolean hapticsEnabled(Context c) {
        return prefs(c).getBoolean(KEY_HAPTICS, true);
    }

    public static void setTilt(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_TILT, on).apply();
    }

    public static void setHaptics(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_HAPTICS, on).apply();
    }

    /** 相机回写期间暂存的文件名（ShareProvider 据此放行一次写）。 */
    public static void setPendingCamera(Context c, String name) {
        prefs(c).edit().putString(KEY_PENDING_CAMERA, name).apply();
    }

    public static String pendingCamera(Context c) {
        return prefs(c).getString(KEY_PENDING_CAMERA, null);
    }

    public static void clearPendingCamera(Context c) {
        prefs(c).edit().remove(KEY_PENDING_CAMERA).apply();
    }
}

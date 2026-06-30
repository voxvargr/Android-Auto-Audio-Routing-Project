package dev.voxvargr.aaarp;

import android.content.Context;
import android.content.SharedPreferences;

final class AppPrefs {
    static final String PREFS = "aaarp_prefs";
    static final String SELECTED_DEVICE_KEY = "selected_device_key";
    static final String SELECTED_BLUETOOTH_TARGET_KEY = "selected_bluetooth_target_key";
    static final String PREFERRED_BLUETOOTH_QUERY = "preferred_bluetooth_query";
    static final String CUSTOM_BLUETOOTH_QUERY = "custom_bluetooth_query";
    static final String USE_ROOT = "use_root";
    static final String MONITOR_ENABLED = "monitor_enabled";
    static final String WATCHDOG_MODE = "watchdog_mode";
    static final String AUTO_STOP_AFTER_ANDROID_AUTO = "auto_stop_after_android_auto";

    private AppPrefs() {
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

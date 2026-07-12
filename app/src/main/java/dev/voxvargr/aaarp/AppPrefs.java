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
    static final String RESTORE_MONITOR_AFTER_BOOT = "restore_monitor_after_boot";
    static final String AUTO_STOP_AFTER_ANDROID_AUTO = "auto_stop_after_android_auto";
    static final String RELEASE_ROUTE_AFTER_ANDROID_AUTO = "release_route_after_android_auto";
    static final String RESET_BLUETOOTH_AFTER_ANDROID_AUTO = "reset_bluetooth_after_android_auto";
    static final String NOTIFICATION_ROUTE_MODE = "notification_route_mode";
    static final String NOTIFICATION_ROUTE_OFF = "off";
    static final String NOTIFICATION_ROUTE_SPEAKER = "speaker";
    static final String NOTIFICATION_ROUTE_EARPIECE = "earpiece";
    static final String NOTIFICATION_ROUTE_BLUETOOTH = "bluetooth";
    static final String SUPPRESS_NOTIFICATION_DUCKING = "suppress_notification_ducking";
    static final String SUPPRESS_NOTIFICATION_DUCKING_ALWAYS = "suppress_notification_ducking_always";
    static final String MUTE_NOTIFICATIONS_DURING_PLAYBACK = "mute_notifications_during_playback";
    static final String MUTE_NOTIFICATIONS_DURING_PLAYBACK_ALWAYS = "mute_notifications_during_playback_always";

    private AppPrefs() {
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

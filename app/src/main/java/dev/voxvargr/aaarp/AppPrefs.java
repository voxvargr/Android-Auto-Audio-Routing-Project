package dev.voxvargr.aaarp;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

final class AppPrefs {
    static final String PREFS = "aaarp_prefs";
    static final String SELECTED_DEVICE_KEY = "selected_device_key";
    static final String SELECTED_BLUETOOTH_TARGET_KEY = "selected_bluetooth_target_key";
    static final String PREFERRED_BLUETOOTH_QUERY = "preferred_bluetooth_query";
    static final String CUSTOM_BLUETOOTH_QUERY = "custom_bluetooth_query";
    static final String USE_ROOT = "use_root";
    static final String MONITOR_ENABLED = "monitor_enabled";
    static final String WATCHDOG_MODE = "watchdog_mode";
    static final String GPS_WARMUP_DURING_ANDROID_AUTO = "gps_warmup_during_android_auto";
    static final String RESTORE_MONITOR_AFTER_BOOT = "restore_monitor_after_boot";
    static final String AUTO_STOP_AFTER_ANDROID_AUTO = "auto_stop_after_android_auto";
    static final String RELEASE_ROUTE_AFTER_ANDROID_AUTO = "release_route_after_android_auto";
    static final String RESET_BLUETOOTH_AFTER_ANDROID_AUTO = "reset_bluetooth_after_android_auto";
    static final String PAUSE_BLUETOOTH_SCO_DURING_MEDIA = "pause_bluetooth_sco_during_media";
    static final String PIN_MEDIA_TO_BLUETOOTH_DURING_ANDROID_AUTO = "pin_media_to_bluetooth_during_android_auto";
    static final String NOTIFICATION_ROUTE_MODE = "notification_route_mode";
    static final String NOTIFICATION_ROUTE_OFF = "off";
    static final String NOTIFICATION_ROUTE_SPEAKER = "speaker";
    static final String NOTIFICATION_ROUTE_EARPIECE = "earpiece";
    static final String NOTIFICATION_ROUTE_BLUETOOTH = "bluetooth";
    static final String SUPPRESS_NOTIFICATION_DUCKING = "suppress_notification_ducking";
    static final String SUPPRESS_NOTIFICATION_DUCKING_ALWAYS = "suppress_notification_ducking_always";
    static final String MUTE_NOTIFICATIONS_DURING_PLAYBACK = "mute_notifications_during_playback";
    static final String MUTE_NOTIFICATIONS_DURING_PLAYBACK_ALWAYS = "mute_notifications_during_playback_always";
    static final String NORMALIZE_MEDIA_DURING_ANDROID_AUTO = "normalize_media_during_android_auto";
    static final String NORMALIZE_MEDIA_ALWAYS = "normalize_media_always";
    static final String MEDIA_VOLUME_FLOOR_FALLBACK = "media_volume_floor_fallback";
    static final String MEDIA_DYNAMICS_PROCESSING = "media_dynamics_processing";
    static final String MEDIA_LEVELER_MODE = "media_leveler_mode";
    private static final String LEGACY_MEDIA_RELAY_ENABLED = "media_relay_enabled";
    private static final String LEGACY_PROFILE_MEDIA_RELAY_SUFFIX = "_media_relay_enabled";

    private AppPrefs() {
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Converts the retired "experimental stronger compressor" checkbox into the newer
     * leveler mode preference, for the default settings and every saved profile.
     */
    static void migrateLegacyDynamicsProcessingSettings(Context context) {
        SharedPreferences prefs = get(context);
        Map<String, ?> all = prefs.getAll();
        SharedPreferences.Editor editor = null;
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (!isLegacyDynamicsProcessingKey(key)) {
                continue;
            }
            String modeKey = legacyDynamicsProcessingModeKey(key);
            if (modeKey == null) {
                continue;
            }
            if (editor == null) {
                editor = prefs.edit();
            }
            if (!all.containsKey(modeKey)) {
                boolean legacyEnabled = Boolean.TRUE.equals(entry.getValue());
                editor.putString(modeKey, MediaLevelerProfile.migratedModeFromLegacyFlag(legacyEnabled));
            }
            editor.remove(key);
        }
        if (editor != null) {
            editor.apply();
        }
    }

    static boolean isLegacyDynamicsProcessingKey(String key) {
        return MEDIA_DYNAMICS_PROCESSING.equals(key)
                || (key != null
                && key.startsWith("profile_")
                && key.endsWith("_" + MEDIA_DYNAMICS_PROCESSING));
    }

    /** Returns the leveler-mode preference key matching a legacy dynamics-processing key. */
    static String legacyDynamicsProcessingModeKey(String legacyKey) {
        if (MEDIA_DYNAMICS_PROCESSING.equals(legacyKey)) {
            return MEDIA_LEVELER_MODE;
        }
        if (legacyKey == null
                || !legacyKey.startsWith("profile_")
                || !legacyKey.endsWith("_" + MEDIA_DYNAMICS_PROCESSING)) {
            return null;
        }
        String profilePart = legacyKey.substring(
                "profile_".length(),
                legacyKey.length() - ("_" + MEDIA_DYNAMICS_PROCESSING).length()
        );
        if (profilePart.length() == 0) {
            return null;
        }
        return "profile_" + profilePart + "_" + MEDIA_LEVELER_MODE;
    }

    /** Removes settings left by the retired Android Auto media relay. */
    static void removeLegacyMediaRelaySettings(Context context) {
        SharedPreferences prefs = get(context);
        SharedPreferences.Editor editor = null;
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!isLegacyMediaRelayKey(entry.getKey())) {
                continue;
            }
            if (editor == null) {
                editor = prefs.edit();
            }
            editor.remove(entry.getKey());
        }
        if (editor != null) {
            editor.apply();
        }
    }

    static boolean isLegacyMediaRelayKey(String key) {
        return LEGACY_MEDIA_RELAY_ENABLED.equals(key)
                || (key != null
                && key.startsWith("profile_")
                && key.endsWith(LEGACY_PROFILE_MEDIA_RELAY_SUFFIX));
    }
}

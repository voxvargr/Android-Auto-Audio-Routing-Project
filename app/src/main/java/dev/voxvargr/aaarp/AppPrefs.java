package dev.voxvargr.aaarp;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
    /** Retired: loudness leveling must never take ownership of STREAM_MUSIC. */
    static final String MEDIA_VOLUME_FLOOR_FALLBACK = "media_volume_floor_fallback";
    static final String MEDIA_DYNAMICS_PROCESSING = "media_dynamics_processing";
    static final String MEDIA_LEVELER_MODE = "media_leveler_mode";
    private static final String LOUDNESS_MIGRATION_VERSION = "loudness_migration_version";
    private static final int CURRENT_LOUDNESS_MIGRATION_VERSION = 2;
    private static final String LEGACY_MEDIA_RELAY_ENABLED = "media_relay_enabled";
    private static final String LEGACY_PROFILE_MEDIA_RELAY_SUFFIX = "_media_relay_enabled";

    private AppPrefs() {
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Migrates every retired loudness setting, including settings restored from an older
     * backup. The volume-floor fallback is deliberately deleted: loudness leveling changes
     * the audio signal, never the user's STREAM_MUSIC setting.
     */
    static void migrateLoudnessNormalizationSettings(Context context) {
        applyLoudnessNormalizationMigration(context);
    }

    /** Compatibility entry point for the monitor while older call sites are upgraded. */
    static void migrateLegacyDynamicsProcessingSettings(Context context) {
        applyLoudnessNormalizationMigration(context);
    }

    private static void applyLoudnessNormalizationMigration(Context context) {
        SharedPreferences prefs = get(context);
        Map<String, ?> all = prefs.getAll();
        Map<String, Object> migrated = new HashMap<>();
        migrated.putAll(all);
        migrateLoudnessNormalizationSettings(migrated);

        SharedPreferences.Editor editor = null;
        for (String key : all.keySet()) {
            if (!migrated.containsKey(key)) {
                if (editor == null) {
                    editor = prefs.edit();
                }
                editor.remove(key);
            }
        }
        for (Map.Entry<String, Object> entry : migrated.entrySet()) {
            if (all.containsKey(entry.getKey())
                    && Objects.equals(all.get(entry.getKey()), entry.getValue())) {
                continue;
            }
            if (editor == null) {
                editor = prefs.edit();
            }
            putMigratedPreference(editor, entry.getKey(), entry.getValue());
        }
        if (editor != null) {
            editor.apply();
        }
    }

    /**
     * Applies every current preference migration to a decoded backup before it is committed.
     * The input map is mutated deliberately so callers can perform one atomic replacement.
     */
    static void migrateRestoredPreferences(Map<String, Object> preferences) {
        for (String key : new HashMap<>(preferences).keySet()) {
            if (isLegacyMediaRelayKey(key)) {
                preferences.remove(key);
            }
        }
        migrateLoudnessNormalizationSettings(preferences);
    }

    static void migrateLoudnessNormalizationSettings(Map<String, Object> preferences) {
        Map<String, Object> original = new HashMap<>(preferences);
        boolean enablePreDriveLeveling = safeMigrationVersion(original)
                < CURRENT_LOUDNESS_MIGRATION_VERSION;
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            String key = entry.getKey();
            if (enablePreDriveLeveling && Boolean.TRUE.equals(entry.getValue())) {
                String alwaysKey = matchingAlwaysLevelingKey(key);
                if (alwaysKey != null) {
                    preferences.put(alwaysKey, true);
                }
            }
            if (isRetiredMediaVolumeFloorKey(key)) {
                preferences.remove(key);
                continue;
            }
            if (!isLegacyDynamicsProcessingKey(key)) {
                continue;
            }
            String modeKey = legacyDynamicsProcessingModeKey(key);
            if (modeKey != null && !original.containsKey(modeKey)) {
                boolean legacyEnabled = Boolean.TRUE.equals(entry.getValue());
                preferences.put(
                        modeKey,
                        MediaLevelerProfile.migratedModeFromLegacyFlag(legacyEnabled)
                );
            }
            preferences.remove(key);
        }
        if (enablePreDriveLeveling) {
            // Installing this migration replaces the process that owned the old monitor. Keep
            // the new leveling behavior opt-in until the user reviews it and starts monitoring
            // again; otherwise a stale true flag can also restart it automatically after boot.
            preferences.put(MONITOR_ENABLED, false);
            preferences.put(LOUDNESS_MIGRATION_VERSION, CURRENT_LOUDNESS_MIGRATION_VERSION);
        }
    }

    private static int safeMigrationVersion(Map<String, ?> preferences) {
        Object value = preferences.get(LOUDNESS_MIGRATION_VERSION);
        return value instanceof Integer ? (Integer) value : 0;
    }

    private static void putMigratedPreference(SharedPreferences.Editor editor,
                                              String key,
                                              Object value) {
        if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else {
            throw new IllegalStateException("Unsupported migrated preference type for " + key);
        }
    }

    /** Maps a global or profile-scoped Android Auto normalization key to its always-on key. */
    static String matchingAlwaysLevelingKey(String key) {
        if (NORMALIZE_MEDIA_DURING_ANDROID_AUTO.equals(key)) {
            return NORMALIZE_MEDIA_ALWAYS;
        }
        String suffix = "_" + NORMALIZE_MEDIA_DURING_ANDROID_AUTO;
        if (key == null || !key.startsWith("profile_") || !key.endsWith(suffix)) {
            return null;
        }
        return key.substring(0, key.length() - suffix.length()) + "_" + NORMALIZE_MEDIA_ALWAYS;
    }

    static boolean isRetiredMediaVolumeFloorKey(String key) {
        return MEDIA_VOLUME_FLOOR_FALLBACK.equals(key)
                || (key != null
                && key.startsWith("profile_")
                && key.endsWith("_" + MEDIA_VOLUME_FLOOR_FALLBACK));
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

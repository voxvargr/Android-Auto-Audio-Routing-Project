package dev.voxvargr.aaarp;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ProfileSettings {
    static final String DEFAULT_PROFILE_ID = "default";

    private static final String PROFILE_IDS = "profile_ids";
    private static final String ACTIVE_PROFILE_ID = "active_profile_id";
    private static final String CONNECTION_PROFILE_PREFIX = "connection_profile_";
    private static final String PROFILE_PREFIX = "profile_";
    private static final String PROFILE_LABEL = "label";

    private ProfileSettings() {
    }

    static List<ProfileEntry> listProfiles(Context context) {
        SharedPreferences prefs = AppPrefs.get(context);
        List<ProfileEntry> entries = new ArrayList<>();
        entries.add(new ProfileEntry(DEFAULT_PROFILE_ID, "Default"));
        List<String> profileIds = new ArrayList<>(safeStringSet(prefs, PROFILE_IDS));
        Collections.sort(profileIds, String.CASE_INSENSITIVE_ORDER);
        for (String id : profileIds) {
            if (DEFAULT_PROFILE_ID.equals(id)) {
                continue;
            }
            entries.add(new ProfileEntry(id, safeString(prefs, profileKey(id, PROFILE_LABEL), id)));
        }
        return entries;
    }

    static String activeProfileId(Context context) {
        return safeString(AppPrefs.get(context), ACTIVE_PROFILE_ID, DEFAULT_PROFILE_ID);
    }

    static void setActiveProfileId(Context context, String profileId) {
        AppPrefs.get(context).edit().putString(ACTIVE_PROFILE_ID, cleanProfileId(profileId)).apply();
    }

    static ProfileEntry saveCurrentSettingsForConnection(Context context, AndroidAutoConnection connection) {
        AndroidAutoConnection safeConnection = connection == null ? AndroidAutoConnection.fallback() : connection;
        String profileId = safeConnection.specific() ? safeConnection.key() : DEFAULT_PROFILE_ID;
        String label = safeConnection.specific() ? safeConnection.label() : "Default";
        ProfileEntry entry = saveCurrentSettingsToProfile(context, profileId, label);

        if (safeConnection.specific()) {
            SharedPreferences prefs = AppPrefs.get(context);
            Set<String> ids = new HashSet<>(safeStringSet(prefs, PROFILE_IDS));
            ids.add(profileId);
            prefs.edit()
                    .putStringSet(PROFILE_IDS, ids)
                    .putString(CONNECTION_PROFILE_PREFIX + safeConnection.key(), profileId)
                    .apply();
        }
        return entry;
    }

    static ProfileEntry saveCurrentSettingsToProfile(Context context, String profileId, String label) {
        String safeProfileId = cleanProfileId(profileId);
        String safeLabel = label == null || label.length() == 0 ? "Default" : label;
        SharedPreferences prefs = AppPrefs.get(context);
        SharedPreferences.Editor editor = prefs.edit();
        writeCurrentSettingsToProfile(prefs, editor, safeProfileId, safeLabel);
        if (!DEFAULT_PROFILE_ID.equals(safeProfileId)) {
            Set<String> ids = new HashSet<>(safeStringSet(prefs, PROFILE_IDS));
            ids.add(safeProfileId);
            editor.putStringSet(PROFILE_IDS, ids);
        }
        editor.putString(ACTIVE_PROFILE_ID, safeProfileId);
        editor.apply();
        return new ProfileEntry(safeProfileId, safeLabel);
    }

    static void loadProfileIntoCurrentSettings(Context context, String profileId) {
        String safeId = cleanProfileId(profileId);
        if (DEFAULT_PROFILE_ID.equals(safeId)) {
            setActiveProfileId(context, DEFAULT_PROFILE_ID);
            return;
        }

        SharedPreferences prefs = AppPrefs.get(context);
        SharedPreferences.Editor editor = prefs.edit();
        copyProfileString(prefs, editor, safeId, AppPrefs.SELECTED_DEVICE_KEY);
        copyProfileString(prefs, editor, safeId, AppPrefs.SELECTED_BLUETOOTH_TARGET_KEY);
        copyProfileString(prefs, editor, safeId, AppPrefs.PREFERRED_BLUETOOTH_QUERY);
        copyProfileString(prefs, editor, safeId, AppPrefs.CUSTOM_BLUETOOTH_QUERY);
        copyProfileString(prefs, editor, safeId, AppPrefs.NOTIFICATION_ROUTE_MODE);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.WATCHDOG_MODE, true);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.RELEASE_ROUTE_AFTER_ANDROID_AUTO, true);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.AUTO_STOP_AFTER_ANDROID_AUTO, false);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.RESET_BLUETOOTH_AFTER_ANDROID_AUTO, false);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.SUPPRESS_NOTIFICATION_DUCKING, false);
        editor.putString(ACTIVE_PROFILE_ID, safeId);
        editor.apply();
    }

    static MonitorSettings monitorSettings(Context context, AndroidAutoConnection connection) {
        SharedPreferences prefs = AppPrefs.get(context);
        String profileId = profileIdForConnection(prefs, connection);
        return new MonitorSettings(
                getString(prefs, profileId, AppPrefs.SELECTED_DEVICE_KEY, null),
                getString(prefs, profileId, AppPrefs.PREFERRED_BLUETOOTH_QUERY, null),
                getBoolean(prefs, profileId, AppPrefs.WATCHDOG_MODE, true),
                getBoolean(prefs, profileId, AppPrefs.AUTO_STOP_AFTER_ANDROID_AUTO, false),
                getBoolean(prefs, profileId, AppPrefs.RELEASE_ROUTE_AFTER_ANDROID_AUTO, true),
                getBoolean(prefs, profileId, AppPrefs.RESET_BLUETOOTH_AFTER_ANDROID_AUTO, false),
                getString(prefs, profileId, AppPrefs.NOTIFICATION_ROUTE_MODE, AppPrefs.NOTIFICATION_ROUTE_OFF),
                getBoolean(prefs, profileId, AppPrefs.SUPPRESS_NOTIFICATION_DUCKING, false),
                profileId
        );
    }

    static String profileIdForConnection(Context context, AndroidAutoConnection connection) {
        return profileIdForConnection(AppPrefs.get(context), connection);
    }

    private static String profileIdForConnection(SharedPreferences prefs, AndroidAutoConnection connection) {
        if (connection != null && connection.specific()) {
            String mapped = safeString(prefs, CONNECTION_PROFILE_PREFIX + connection.key(), null);
            if (mapped != null && mapped.length() > 0) {
                return mapped;
            }
        }
        return DEFAULT_PROFILE_ID;
    }

    private static void writeCurrentSettingsToProfile(SharedPreferences prefs, SharedPreferences.Editor editor,
                                                      String profileId, String label) {
        editor.putString(profileKey(profileId, PROFILE_LABEL), label);
        putProfileString(prefs, editor, profileId, AppPrefs.SELECTED_DEVICE_KEY);
        putProfileString(prefs, editor, profileId, AppPrefs.SELECTED_BLUETOOTH_TARGET_KEY);
        putProfileString(prefs, editor, profileId, AppPrefs.PREFERRED_BLUETOOTH_QUERY);
        putProfileString(prefs, editor, profileId, AppPrefs.CUSTOM_BLUETOOTH_QUERY);
        putProfileString(prefs, editor, profileId, AppPrefs.NOTIFICATION_ROUTE_MODE);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.WATCHDOG_MODE, true);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.RELEASE_ROUTE_AFTER_ANDROID_AUTO, true);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.AUTO_STOP_AFTER_ANDROID_AUTO, false);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.RESET_BLUETOOTH_AFTER_ANDROID_AUTO, false);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.SUPPRESS_NOTIFICATION_DUCKING, false);
    }

    private static void putProfileString(SharedPreferences prefs, SharedPreferences.Editor editor,
                                         String profileId, String key) {
        editor.putString(profileKey(profileId, key), safeString(prefs, key, ""));
    }

    private static void putProfileBoolean(SharedPreferences prefs, SharedPreferences.Editor editor,
                                          String profileId, String key, boolean fallback) {
        editor.putBoolean(profileKey(profileId, key), safeBoolean(prefs, key, fallback));
    }

    private static String getString(SharedPreferences prefs, String profileId, String key, String fallback) {
        if (!DEFAULT_PROFILE_ID.equals(profileId) && prefs.contains(profileKey(profileId, key))) {
            return safeString(prefs, profileKey(profileId, key), fallback);
        }
        return safeString(prefs, key, fallback);
    }

    private static boolean getBoolean(SharedPreferences prefs, String profileId, String key, boolean fallback) {
        if (!DEFAULT_PROFILE_ID.equals(profileId) && prefs.contains(profileKey(profileId, key))) {
            return safeBoolean(prefs, profileKey(profileId, key), fallback);
        }
        return safeBoolean(prefs, key, fallback);
    }

    private static void copyProfileString(SharedPreferences prefs, SharedPreferences.Editor editor,
                                          String profileId, String key) {
        String profileKey = profileKey(profileId, key);
        if (prefs.contains(profileKey)) {
            editor.putString(key, safeString(prefs, profileKey, ""));
        }
    }

    private static void copyProfileBoolean(SharedPreferences prefs, SharedPreferences.Editor editor,
                                           String profileId, String key, boolean fallback) {
        String profileKey = profileKey(profileId, key);
        if (prefs.contains(profileKey)) {
            editor.putBoolean(key, safeBoolean(prefs, profileKey, fallback));
        }
    }

    private static String safeString(SharedPreferences prefs, String key, String fallback) {
        try {
            return prefs.getString(key, fallback);
        } catch (RuntimeException e) {
            prefs.edit().remove(key).apply();
            return fallback;
        }
    }

    private static boolean safeBoolean(SharedPreferences prefs, String key, boolean fallback) {
        try {
            return prefs.getBoolean(key, fallback);
        } catch (RuntimeException e) {
            prefs.edit().remove(key).apply();
            return fallback;
        }
    }

    private static Set<String> safeStringSet(SharedPreferences prefs, String key) {
        try {
            Set<String> values = prefs.getStringSet(key, Collections.emptySet());
            return values == null ? Collections.emptySet() : values;
        } catch (RuntimeException e) {
            prefs.edit().remove(key).apply();
            return Collections.emptySet();
        }
    }

    private static String profileKey(String profileId, String key) {
        return PROFILE_PREFIX + cleanProfileId(profileId) + "_" + key;
    }

    private static String cleanProfileId(String profileId) {
        return profileId == null || profileId.length() == 0 ? DEFAULT_PROFILE_ID : profileId;
    }

    static final class ProfileEntry {
        final String id;
        final String label;

        ProfileEntry(String id, String label) {
            this.id = id;
            this.label = label == null || label.length() == 0 ? id : label;
        }

        String displayLabel() {
            return label;
        }
    }

    static final class MonitorSettings {
        final String selectedDeviceKey;
        final String preferredBluetoothTarget;
        final boolean watchdogMode;
        final boolean autoStopAfterAndroidAuto;
        final boolean releaseAfterAndroidAuto;
        final boolean resetBluetoothAfterAndroidAuto;
        final String notificationRouteMode;
        final boolean suppressNotificationDucking;
        final String profileId;

        MonitorSettings(String selectedDeviceKey, String preferredBluetoothTarget, boolean watchdogMode,
                        boolean autoStopAfterAndroidAuto, boolean releaseAfterAndroidAuto,
                        boolean resetBluetoothAfterAndroidAuto, String notificationRouteMode,
                        boolean suppressNotificationDucking, String profileId) {
            this.selectedDeviceKey = selectedDeviceKey;
            this.preferredBluetoothTarget = preferredBluetoothTarget;
            this.watchdogMode = watchdogMode;
            this.autoStopAfterAndroidAuto = autoStopAfterAndroidAuto;
            this.releaseAfterAndroidAuto = releaseAfterAndroidAuto;
            this.resetBluetoothAfterAndroidAuto = resetBluetoothAfterAndroidAuto;
            this.notificationRouteMode = notificationRouteMode == null
                    ? AppPrefs.NOTIFICATION_ROUTE_OFF
                    : notificationRouteMode;
            this.suppressNotificationDucking = suppressNotificationDucking;
            this.profileId = profileId;
        }
    }
}

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
    private static final String CONNECTION_LABEL_PROFILE_PREFIX = "connection_label_profile_";
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

    static void saveActiveString(Context context, String key, String value) {
        String safeValue = value == null ? "" : value;
        SharedPreferences prefs = AppPrefs.get(context);
        String activeId = activeProfileId(context);
        SharedPreferences.Editor editor = prefs.edit().putString(key, safeValue);
        if (!DEFAULT_PROFILE_ID.equals(activeId)) {
            editor.putString(profileKey(activeId, key), safeValue);
        }
        editor.apply();
    }

    static void removeActiveString(Context context, String key) {
        SharedPreferences prefs = AppPrefs.get(context);
        String activeId = activeProfileId(context);
        SharedPreferences.Editor editor = prefs.edit().remove(key);
        if (!DEFAULT_PROFILE_ID.equals(activeId)) {
            editor.remove(profileKey(activeId, key));
        }
        editor.apply();
    }

    static void saveActiveBoolean(Context context, String key, boolean value) {
        if (AppPrefs.MEDIA_RELAY_ENABLED.equals(key)) {
            saveActiveMediaRelayEnabled(context, value);
            return;
        }
        SharedPreferences prefs = AppPrefs.get(context);
        String activeId = activeProfileId(context);
        SharedPreferences.Editor editor = prefs.edit().putBoolean(key, value);
        if (!DEFAULT_PROFILE_ID.equals(activeId)) {
            editor.putBoolean(profileKey(activeId, key), value);
        }
        editor.apply();
    }

    static void saveActiveMediaRelayEnabled(Context context, boolean enabled) {
        saveMediaRelayEnabledForProfile(context, activeProfileId(context), enabled);
    }

    static void saveMediaRelayEnabledForProfile(
            Context context,
            String profileId,
            boolean enabled
    ) {
        SharedPreferences prefs = AppPrefs.get(context);
        String safeProfileId = cleanProfileId(profileId);
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(mediaRelayPreferenceKeyForProfile(safeProfileId), enabled);
        if (safeProfileId.equals(activeProfileId(context))) {
            editor.putBoolean(AppPrefs.MEDIA_RELAY_ENABLED, enabled);
        }
        editor.apply();
    }

    static boolean mediaRelayEnabledForActiveProfile(Context context) {
        return mediaRelayEnabledForProfile(context, activeProfileId(context));
    }

    static boolean mediaRelayEnabledForProfile(Context context, String profileId) {
        return mediaRelayEnabledForProfile(AppPrefs.get(context), profileId);
    }

    static boolean mediaRelayEnabled(Context context, AndroidAutoConnection connection) {
        SharedPreferences prefs = AppPrefs.get(context);
        String profileId = profileIdForConnection(prefs, connection);
        return mediaRelayEnabledForProfile(prefs, profileId);
    }

    static String mediaRelayPreferenceKeyForProfile(String profileId) {
        return profileKey(profileId, AppPrefs.MEDIA_RELAY_ENABLED);
    }

    static boolean isMediaRelayPreferenceKey(String key) {
        return AppPrefs.MEDIA_RELAY_ENABLED.equals(key)
                || (key != null
                && key.startsWith(PROFILE_PREFIX)
                && key.endsWith("_" + AppPrefs.MEDIA_RELAY_ENABLED));
    }

    static boolean isMediaRelayConfigurationKey(String key) {
        return isMediaRelayPreferenceKey(key)
                || ACTIVE_PROFILE_ID.equals(key)
                || (key != null && (key.startsWith(CONNECTION_PROFILE_PREFIX)
                || key.startsWith(CONNECTION_LABEL_PROFILE_PREFIX)));
    }

    static boolean relayValueForStoredPreference(boolean hasStoredValue, boolean storedValue) {
        return hasStoredValue && storedValue;
    }

    static boolean relayValueWhenSavingProfile(boolean profileAlreadyExists, boolean currentValue) {
        return profileAlreadyExists && currentValue;
    }

    static ProfileEntry saveCurrentSettingsForConnection(Context context, AndroidAutoConnection connection) {
        AndroidAutoConnection safeConnection = connection == null ? AndroidAutoConnection.fallback() : connection;
        String profileId = safeConnection.specific() ? safeConnection.key() : DEFAULT_PROFILE_ID;
        String label = safeConnection.specific() ? safeConnection.label() : "Default";
        return saveCurrentSettingsForConnection(context, safeConnection, profileId, label);
    }

    /** Saves the current settings into the selected profile and binds this connection to it. */
    static ProfileEntry saveCurrentSettingsForConnection(
            Context context,
            AndroidAutoConnection connection,
            String selectedProfileId,
            String selectedProfileLabel
    ) {
        AndroidAutoConnection safeConnection = connection == null
                ? AndroidAutoConnection.fallback()
                : connection;
        String safeProfileId = cleanProfileId(selectedProfileId);
        String safeLabel = selectedProfileLabel == null || selectedProfileLabel.length() == 0
                ? (DEFAULT_PROFILE_ID.equals(safeProfileId) ? "Default" : safeProfileId)
                : selectedProfileLabel;
        SharedPreferences prefs = AppPrefs.get(context);
        SharedPreferences.Editor editor = prefs.edit();
        writeCurrentSettingsToProfile(prefs, editor, safeProfileId, safeLabel);
        if (!DEFAULT_PROFILE_ID.equals(safeProfileId)) {
            Set<String> ids = new HashSet<>(safeStringSet(prefs, PROFILE_IDS));
            ids.add(safeProfileId);
            editor.putStringSet(PROFILE_IDS, ids);
        }
        if (safeConnection.specific()) {
            putConnectionMappings(editor, safeConnection, safeProfileId);
        }
        editor.putString(ACTIVE_PROFILE_ID, safeProfileId);
        editor.apply();
        return new ProfileEntry(safeProfileId, safeLabel);
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
        SharedPreferences prefs = AppPrefs.get(context);
        SharedPreferences.Editor editor = prefs.edit();
        copyProfileString(prefs, editor, safeId, AppPrefs.SELECTED_DEVICE_KEY);
        copyProfileString(prefs, editor, safeId, AppPrefs.SELECTED_BLUETOOTH_TARGET_KEY);
        copyProfileString(prefs, editor, safeId, AppPrefs.PREFERRED_BLUETOOTH_QUERY);
        copyProfileString(prefs, editor, safeId, AppPrefs.CUSTOM_BLUETOOTH_QUERY);
        copyProfileString(prefs, editor, safeId, AppPrefs.NOTIFICATION_ROUTE_MODE);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.WATCHDOG_MODE, true);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.GPS_WARMUP_DURING_ANDROID_AUTO, false);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.RELEASE_ROUTE_AFTER_ANDROID_AUTO, true);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.AUTO_STOP_AFTER_ANDROID_AUTO, false);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.RESET_BLUETOOTH_AFTER_ANDROID_AUTO, false);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.PAUSE_BLUETOOTH_SCO_DURING_MEDIA, true);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.PIN_MEDIA_TO_BLUETOOTH_DURING_ANDROID_AUTO, false);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.SUPPRESS_NOTIFICATION_DUCKING, false);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.SUPPRESS_NOTIFICATION_DUCKING_ALWAYS, false);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK, false);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK_ALWAYS, false);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.NORMALIZE_MEDIA_DURING_ANDROID_AUTO, false);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.NORMALIZE_MEDIA_ALWAYS, false);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.MEDIA_VOLUME_FLOOR_FALLBACK, false);
        copyProfileBoolean(prefs, editor, safeId, AppPrefs.MEDIA_DYNAMICS_PROCESSING, false);
        copyProfileBooleanOrDefault(prefs, editor, safeId, AppPrefs.MEDIA_RELAY_ENABLED, false);
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
                getBoolean(prefs, profileId, AppPrefs.GPS_WARMUP_DURING_ANDROID_AUTO, false),
                getBoolean(prefs, profileId, AppPrefs.AUTO_STOP_AFTER_ANDROID_AUTO, false),
                getBoolean(prefs, profileId, AppPrefs.RELEASE_ROUTE_AFTER_ANDROID_AUTO, true),
                getBoolean(prefs, profileId, AppPrefs.RESET_BLUETOOTH_AFTER_ANDROID_AUTO, false),
                getBoolean(prefs, profileId, AppPrefs.PAUSE_BLUETOOTH_SCO_DURING_MEDIA, true),
                getBoolean(prefs, profileId, AppPrefs.PIN_MEDIA_TO_BLUETOOTH_DURING_ANDROID_AUTO, false),
                getString(prefs, profileId, AppPrefs.NOTIFICATION_ROUTE_MODE, AppPrefs.NOTIFICATION_ROUTE_OFF),
                getBoolean(prefs, profileId, AppPrefs.SUPPRESS_NOTIFICATION_DUCKING, false),
                getBoolean(prefs, profileId, AppPrefs.SUPPRESS_NOTIFICATION_DUCKING_ALWAYS, false),
                getBoolean(prefs, profileId, AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK, false),
                getBoolean(prefs, profileId, AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK_ALWAYS, false),
                getBoolean(prefs, profileId, AppPrefs.NORMALIZE_MEDIA_DURING_ANDROID_AUTO, false),
                getBoolean(prefs, profileId, AppPrefs.NORMALIZE_MEDIA_ALWAYS, false),
                getBoolean(prefs, profileId, AppPrefs.MEDIA_VOLUME_FLOOR_FALLBACK, false),
                getBoolean(prefs, profileId, AppPrefs.MEDIA_DYNAMICS_PROCESSING, false),
                profileId
        );
    }

    static String profileIdForConnection(Context context, AndroidAutoConnection connection) {
        return profileIdForConnection(AppPrefs.get(context), connection);
    }

    static String mappedProfileIdForConnection(Context context, AndroidAutoConnection connection) {
        return mappedProfileIdForConnection(AppPrefs.get(context), connection);
    }

    private static String profileIdForConnection(SharedPreferences prefs, AndroidAutoConnection connection) {
        String mapped = mappedProfileIdForConnection(prefs, connection);
        return mapped == null ? DEFAULT_PROFILE_ID : mapped;
    }

    private static String mappedProfileIdForConnection(
            SharedPreferences prefs,
            AndroidAutoConnection connection
    ) {
        if (connection == null || !connection.specific()) {
            return null;
        }
        String exactMapped = safeString(
                prefs,
                connectionProfilePreferenceKey(connection),
                null
        );
        String aliasMapped = null;
        if (connection.normalizedLabelAlias().length() > 0) {
            aliasMapped = safeString(
                    prefs,
                    connectionLabelProfilePreferenceKey(connection),
                    null
            );
        }
        return mappedProfileId(exactMapped, aliasMapped);
    }

    static String mappedProfileId(String exactMapped, String labelAliasMapped) {
        String exact = cleanMappedProfileId(exactMapped);
        return exact == null ? cleanMappedProfileId(labelAliasMapped) : exact;
    }

    static String connectionProfilePreferenceKey(AndroidAutoConnection connection) {
        return CONNECTION_PROFILE_PREFIX + (connection == null ? "default" : connection.key());
    }

    static String connectionLabelProfilePreferenceKey(AndroidAutoConnection connection) {
        String alias = connection == null ? "" : connection.normalizedLabelAlias();
        return CONNECTION_LABEL_PROFILE_PREFIX + alias;
    }

    private static void putConnectionMappings(
            SharedPreferences.Editor editor,
            AndroidAutoConnection connection,
            String profileId
    ) {
        editor.putString(connectionProfilePreferenceKey(connection), profileId);
        if (connection.normalizedLabelAlias().length() > 0) {
            editor.putString(connectionLabelProfilePreferenceKey(connection), profileId);
        }
    }

    private static String cleanMappedProfileId(String profileId) {
        if (profileId == null || profileId.trim().length() == 0) {
            return null;
        }
        return profileId.trim();
    }

    private static void writeCurrentSettingsToProfile(SharedPreferences prefs, SharedPreferences.Editor editor,
                                                      String profileId, String label) {
        boolean profileAlreadyExists = profileExists(prefs, profileId);
        editor.putString(profileKey(profileId, PROFILE_LABEL), label);
        putProfileString(prefs, editor, profileId, AppPrefs.SELECTED_DEVICE_KEY);
        putProfileString(prefs, editor, profileId, AppPrefs.SELECTED_BLUETOOTH_TARGET_KEY);
        putProfileString(prefs, editor, profileId, AppPrefs.PREFERRED_BLUETOOTH_QUERY);
        putProfileString(prefs, editor, profileId, AppPrefs.CUSTOM_BLUETOOTH_QUERY);
        putProfileString(prefs, editor, profileId, AppPrefs.NOTIFICATION_ROUTE_MODE);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.WATCHDOG_MODE, true);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.GPS_WARMUP_DURING_ANDROID_AUTO, false);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.RELEASE_ROUTE_AFTER_ANDROID_AUTO, true);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.AUTO_STOP_AFTER_ANDROID_AUTO, false);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.RESET_BLUETOOTH_AFTER_ANDROID_AUTO, false);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.PAUSE_BLUETOOTH_SCO_DURING_MEDIA, true);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.PIN_MEDIA_TO_BLUETOOTH_DURING_ANDROID_AUTO, false);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.SUPPRESS_NOTIFICATION_DUCKING, false);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.SUPPRESS_NOTIFICATION_DUCKING_ALWAYS, false);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK, false);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK_ALWAYS, false);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.NORMALIZE_MEDIA_DURING_ANDROID_AUTO, false);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.NORMALIZE_MEDIA_ALWAYS, false);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.MEDIA_VOLUME_FLOOR_FALLBACK, false);
        putProfileBoolean(prefs, editor, profileId, AppPrefs.MEDIA_DYNAMICS_PROCESSING, false);
        boolean mediaRelayEnabled = relayValueWhenSavingProfile(
                profileAlreadyExists,
                mediaRelayEnabledForProfile(prefs, profileId)
        );
        editor.putBoolean(mediaRelayPreferenceKeyForProfile(profileId), mediaRelayEnabled);
        // The saved profile becomes active. Keep the current-settings mirror consistent with it.
        editor.putBoolean(AppPrefs.MEDIA_RELAY_ENABLED, mediaRelayEnabled);
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

    private static void copyProfileBooleanOrDefault(SharedPreferences prefs, SharedPreferences.Editor editor,
                                                    String profileId, String key, boolean fallback) {
        String profileKey = profileKey(profileId, key);
        editor.putBoolean(
                key,
                relayValueForStoredPreference(
                        prefs.contains(profileKey),
                        safeBoolean(prefs, profileKey, fallback)
                )
        );
    }

    private static boolean profileExists(SharedPreferences prefs, String profileId) {
        if (DEFAULT_PROFILE_ID.equals(profileId)) {
            return true;
        }
        return prefs.contains(profileKey(profileId, PROFILE_LABEL))
                || safeStringSet(prefs, PROFILE_IDS).contains(profileId);
    }

    private static boolean mediaRelayEnabledForProfile(SharedPreferences prefs, String profileId) {
        String key = mediaRelayPreferenceKeyForProfile(profileId);
        return relayValueForStoredPreference(prefs.contains(key), safeBoolean(prefs, key, false));
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
        final boolean gpsWarmupDuringAndroidAuto;
        final boolean autoStopAfterAndroidAuto;
        final boolean releaseAfterAndroidAuto;
        final boolean resetBluetoothAfterAndroidAuto;
        final boolean pauseBluetoothScoDuringMedia;
        final boolean pinMediaToBluetoothDuringAndroidAuto;
        final String notificationRouteMode;
        final boolean suppressNotificationDucking;
        final boolean suppressNotificationDuckingAlways;
        final boolean muteNotificationsDuringPlayback;
        final boolean muteNotificationsDuringPlaybackAlways;
        final boolean normalizeMediaDuringAndroidAuto;
        final boolean normalizeMediaAlways;
        final boolean mediaVolumeFloorFallback;
        final boolean mediaDynamicsProcessing;
        final String profileId;

        MonitorSettings(String selectedDeviceKey, String preferredBluetoothTarget, boolean watchdogMode,
                        boolean gpsWarmupDuringAndroidAuto,
                        boolean autoStopAfterAndroidAuto, boolean releaseAfterAndroidAuto,
                        boolean resetBluetoothAfterAndroidAuto, boolean pauseBluetoothScoDuringMedia,
                        boolean pinMediaToBluetoothDuringAndroidAuto,
                        String notificationRouteMode,
                        boolean suppressNotificationDucking, boolean suppressNotificationDuckingAlways,
                        boolean muteNotificationsDuringPlayback,
                        boolean muteNotificationsDuringPlaybackAlways,
                        boolean normalizeMediaDuringAndroidAuto, boolean normalizeMediaAlways,
                        boolean mediaVolumeFloorFallback, boolean mediaDynamicsProcessing,
                        String profileId) {
            this.selectedDeviceKey = selectedDeviceKey;
            this.preferredBluetoothTarget = preferredBluetoothTarget;
            this.watchdogMode = watchdogMode;
            this.gpsWarmupDuringAndroidAuto = gpsWarmupDuringAndroidAuto;
            this.autoStopAfterAndroidAuto = autoStopAfterAndroidAuto;
            this.releaseAfterAndroidAuto = releaseAfterAndroidAuto;
            this.resetBluetoothAfterAndroidAuto = resetBluetoothAfterAndroidAuto;
            this.pauseBluetoothScoDuringMedia = pauseBluetoothScoDuringMedia;
            this.pinMediaToBluetoothDuringAndroidAuto = pinMediaToBluetoothDuringAndroidAuto;
            this.notificationRouteMode = notificationRouteMode == null
                    ? AppPrefs.NOTIFICATION_ROUTE_OFF
                    : notificationRouteMode;
            this.suppressNotificationDucking = suppressNotificationDucking;
            this.suppressNotificationDuckingAlways = suppressNotificationDuckingAlways;
            this.muteNotificationsDuringPlayback = muteNotificationsDuringPlayback;
            this.muteNotificationsDuringPlaybackAlways = muteNotificationsDuringPlaybackAlways;
            this.normalizeMediaDuringAndroidAuto = normalizeMediaDuringAndroidAuto;
            this.normalizeMediaAlways = normalizeMediaAlways;
            this.mediaVolumeFloorFallback = mediaVolumeFloorFallback;
            this.mediaDynamicsProcessing = mediaDynamicsProcessing;
            this.profileId = profileId;
        }
    }
}

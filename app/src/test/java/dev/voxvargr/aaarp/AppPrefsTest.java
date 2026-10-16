package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public final class AppPrefsTest {
    @Test
    public void retiredMediaRelayKeysAreRecognizedForMigration() {
        assertTrue(AppPrefs.isLegacyMediaRelayKey("media_relay_enabled"));
        assertTrue(AppPrefs.isLegacyMediaRelayKey("profile_truck_media_relay_enabled"));
        assertFalse(AppPrefs.isLegacyMediaRelayKey("profile_truck_watchdog_mode"));
        assertFalse(AppPrefs.isLegacyMediaRelayKey(null));
    }

    @Test
    public void retiredDynamicsProcessingKeysAreRecognizedForMigration() {
        assertTrue(AppPrefs.isLegacyDynamicsProcessingKey("media_dynamics_processing"));
        assertTrue(AppPrefs.isLegacyDynamicsProcessingKey("profile_bike_media_dynamics_processing"));
        assertFalse(AppPrefs.isLegacyDynamicsProcessingKey("media_leveler_mode"));
        assertFalse(AppPrefs.isLegacyDynamicsProcessingKey("profile_bike_media_leveler_mode"));
        assertFalse(AppPrefs.isLegacyDynamicsProcessingKey(null));
    }

    @Test
    public void legacyDynamicsKeysMapOntoMatchingLevelerModeKeys() {
        assertEquals(
                "media_leveler_mode",
                AppPrefs.legacyDynamicsProcessingModeKey("media_dynamics_processing")
        );
        assertEquals(
                "profile_bike_media_leveler_mode",
                AppPrefs.legacyDynamicsProcessingModeKey("profile_bike_media_dynamics_processing")
        );
        assertNull(AppPrefs.legacyDynamicsProcessingModeKey("profile_bike_watchdog_mode"));
        assertNull(AppPrefs.legacyDynamicsProcessingModeKey(null));
    }

    @Test
    public void retiredVolumeFloorKeysAreRemovedForDefaultAndProfiles() {
        assertTrue(AppPrefs.isRetiredMediaVolumeFloorKey("media_volume_floor_fallback"));
        assertTrue(AppPrefs.isRetiredMediaVolumeFloorKey(
                "profile_motorcycle_media_volume_floor_fallback"
        ));
        assertFalse(AppPrefs.isRetiredMediaVolumeFloorKey("profile_motorcycle_media_leveler_mode"));
        assertFalse(AppPrefs.isRetiredMediaVolumeFloorKey(null));
    }

    @Test
    public void normalizationScopeMapsToPreDriveAlwaysScope() {
        assertEquals(
                AppPrefs.NORMALIZE_MEDIA_ALWAYS,
                AppPrefs.matchingAlwaysLevelingKey(AppPrefs.NORMALIZE_MEDIA_DURING_ANDROID_AUTO)
        );
        assertEquals(
                "profile_car_normalize_media_always",
                AppPrefs.matchingAlwaysLevelingKey(
                        "profile_car_normalize_media_during_android_auto"
                )
        );
        assertNull(AppPrefs.matchingAlwaysLevelingKey("profile_car_watchdog_mode"));
        assertNull(AppPrefs.matchingAlwaysLevelingKey(null));
    }

    @Test
    public void restoredLegacyPreferencesAreFullyMigratedBeforeCommit() {
        Map<String, Object> restored = new HashMap<>();
        restored.put(AppPrefs.NORMALIZE_MEDIA_DURING_ANDROID_AUTO, true);
        restored.put("profile_bike_normalize_media_during_android_auto", true);
        restored.put(AppPrefs.MEDIA_DYNAMICS_PROCESSING, false);
        restored.put("profile_bike_media_dynamics_processing", true);
        restored.put(AppPrefs.MEDIA_VOLUME_FLOOR_FALLBACK, true);
        restored.put("profile_bike_media_volume_floor_fallback", true);
        restored.put("profile_bike_media_relay_enabled", true);

        AppPrefs.migrateRestoredPreferences(restored);

        assertEquals(true, restored.get(AppPrefs.NORMALIZE_MEDIA_ALWAYS));
        assertEquals(true, restored.get("profile_bike_normalize_media_always"));
        assertEquals(MediaLevelerProfile.MODE_STRONG, restored.get(AppPrefs.MEDIA_LEVELER_MODE));
        assertEquals(
                MediaLevelerProfile.MODE_STRONG,
                restored.get("profile_bike_media_leveler_mode")
        );
        assertFalse(restored.containsKey(AppPrefs.MEDIA_DYNAMICS_PROCESSING));
        assertFalse(restored.containsKey("profile_bike_media_dynamics_processing"));
        assertFalse(restored.containsKey(AppPrefs.MEDIA_VOLUME_FLOOR_FALLBACK));
        assertFalse(restored.containsKey("profile_bike_media_volume_floor_fallback"));
        assertFalse(restored.containsKey("profile_bike_media_relay_enabled"));

        // Versioned migration must not overwrite a later explicit user decision.
        restored.put(AppPrefs.NORMALIZE_MEDIA_ALWAYS, false);
        AppPrefs.migrateRestoredPreferences(restored);
        assertEquals(false, restored.get(AppPrefs.NORMALIZE_MEDIA_ALWAYS));
    }

    @Test
    public void firstV2MigrationStopsMonitorUntilUserRestartsIt() {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put(AppPrefs.MONITOR_ENABLED, true);
        preferences.put(AppPrefs.NORMALIZE_MEDIA_DURING_ANDROID_AUTO, true);

        AppPrefs.migrateLoudnessNormalizationSettings(preferences);

        assertEquals(false, preferences.get(AppPrefs.MONITOR_ENABLED));

        // The version marker makes this one-shot: a later explicit Start Monitor action wins.
        preferences.put(AppPrefs.MONITOR_ENABLED, true);
        AppPrefs.migrateLoudnessNormalizationSettings(preferences);
        assertEquals(true, preferences.get(AppPrefs.MONITOR_ENABLED));
    }
}

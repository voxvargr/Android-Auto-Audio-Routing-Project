package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
}

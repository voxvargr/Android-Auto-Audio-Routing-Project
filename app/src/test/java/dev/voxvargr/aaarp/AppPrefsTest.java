package dev.voxvargr.aaarp;

import static org.junit.Assert.assertFalse;
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
}

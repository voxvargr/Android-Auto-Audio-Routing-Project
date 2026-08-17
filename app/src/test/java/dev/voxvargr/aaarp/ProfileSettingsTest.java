package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ProfileSettingsTest {
    @Test
    public void relayPreferenceKey_isScopedToProfile() {
        assertEquals(
                "profile_wifi_eaa5a098_media_relay_enabled",
                ProfileSettings.mediaRelayPreferenceKeyForProfile("wifi_eaa5a098")
        );
        assertEquals(
                "profile_default_media_relay_enabled",
                ProfileSettings.mediaRelayPreferenceKeyForProfile(null)
        );
    }

    @Test
    public void relayPreferenceSignal_matchesMirrorAndProfileKeysOnly() {
        assertTrue(ProfileSettings.isMediaRelayPreferenceKey(AppPrefs.MEDIA_RELAY_ENABLED));
        assertTrue(ProfileSettings.isMediaRelayPreferenceKey(
                "profile_bike_media_relay_enabled"
        ));
        assertFalse(ProfileSettings.isMediaRelayPreferenceKey("profile_bike_watchdog_mode"));
        assertFalse(ProfileSettings.isMediaRelayPreferenceKey(null));
    }

    @Test
    public void relayConfigurationSignal_includesProfileSelectionAndMapping() {
        assertTrue(ProfileSettings.isMediaRelayConfigurationKey("active_profile_id"));
        assertTrue(ProfileSettings.isMediaRelayConfigurationKey(
                "connection_profile_wifi_eaa5a098"
        ));
        assertTrue(ProfileSettings.isMediaRelayConfigurationKey(
                "connection_label_profile_carlinktest1234"
        ));
        assertTrue(ProfileSettings.isMediaRelayConfigurationKey(
                "profile_bike_media_relay_enabled"
        ));
        assertFalse(ProfileSettings.isMediaRelayConfigurationKey("profile_bike_watchdog_mode"));
    }

    @Test
    public void missingStoredRelayValue_defaultsOff() {
        assertFalse(ProfileSettings.relayValueForStoredPreference(false, true));
        assertFalse(ProfileSettings.relayValueForStoredPreference(false, false));
        assertTrue(ProfileSettings.relayValueForStoredPreference(true, true));
        assertFalse(ProfileSettings.relayValueForStoredPreference(true, false));
    }

    @Test
    public void newProfileDoesNotInheritCurrentRelayOptIn() {
        assertFalse(ProfileSettings.relayValueWhenSavingProfile(false, true));
        assertFalse(ProfileSettings.relayValueWhenSavingProfile(false, false));
    }

    @Test
    public void existingProfilePreservesSelectedRelayValue() {
        assertTrue(ProfileSettings.relayValueWhenSavingProfile(true, true));
        assertFalse(ProfileSettings.relayValueWhenSavingProfile(true, false));
    }

    @Test
    public void exactConnectionMappingWinsOverLabelAlias() {
        assertEquals(
                "exact_profile",
                ProfileSettings.mappedProfileId("exact_profile", "alias_profile")
        );
    }

    @Test
    public void labelAliasIsUsedWhenExactConnectionIsUnknown() {
        assertEquals(
                "bike",
                ProfileSettings.mappedProfileId(null, "bike")
        );
        assertEquals(
                "bike",
                ProfileSettings.mappedProfileId("  ", " bike ")
        );
    }

    @Test
    public void absentConnectionMappingsRemainUnmapped() {
        assertEquals(null, ProfileSettings.mappedProfileId(null, null));
        assertEquals(null, ProfileSettings.mappedProfileId("", "  "));
    }

    @Test
    public void connectionMappingKeysSeparateExactAndStableAlias() {
        AndroidAutoConnection connection = new AndroidAutoConnection(
                "wifi_example",
                "CARLINK_TEST1234",
                true
        );

        assertEquals(
                "connection_profile_wifi_example",
                ProfileSettings.connectionProfilePreferenceKey(connection)
        );
        assertEquals(
                "connection_label_profile_carlinktest1234",
                ProfileSettings.connectionLabelProfilePreferenceKey(connection)
        );
    }
}

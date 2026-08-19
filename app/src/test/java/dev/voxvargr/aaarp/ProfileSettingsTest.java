package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ProfileSettingsTest {
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

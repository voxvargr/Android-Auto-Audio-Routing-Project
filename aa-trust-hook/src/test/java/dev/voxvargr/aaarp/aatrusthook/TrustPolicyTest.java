package dev.voxvargr.aaarp.aatrusthook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TrustPolicyTest {
    @Test
    public void acceptsOnlyTheTwoVolumeHelperPackages() {
        assertTrue(TrustPolicy.shouldSpoofInstallSource(TrustPolicy.VOLUME_UP_PACKAGE));
        assertTrue(TrustPolicy.shouldSpoofInstallSource(TrustPolicy.VOLUME_DOWN_PACKAGE));

        assertFalse(TrustPolicy.shouldSpoofInstallSource(null));
        assertFalse(TrustPolicy.shouldSpoofInstallSource("com.google.android.apps.maps"));
        assertFalse(TrustPolicy.shouldSpoofInstallSource("com.google.android.apps.youtube.music"));
        assertFalse(TrustPolicy.shouldSpoofInstallSource(TrustPolicy.VOLUME_UP_PACKAGE + ".fake"));
        assertFalse(TrustPolicy.shouldSpoofInstallSource("DEV.VOXVARGR.AAARP.VOLUMEUP"));
    }

    @Test
    public void acceptsOnlyGearheadProcessFamily() {
        assertTrue(TrustPolicy.isGearheadProcess(TrustPolicy.GEARHEAD_PACKAGE));
        assertTrue(TrustPolicy.isGearheadProcess(TrustPolicy.GEARHEAD_PACKAGE + ":car"));

        assertFalse(TrustPolicy.isGearheadProcess(null));
        assertFalse(TrustPolicy.isGearheadProcess("com.google.android.projection.gearheadfake"));
        assertFalse(TrustPolicy.isGearheadProcess("other:" + TrustPolicy.GEARHEAD_PACKAGE));
    }

    @Test
    public void replacesOnlyTheFinalPhoneAndRecentDockFeeds() {
        assertEquals(
                TrustPolicy.VOLUME_UP_PACKAGE,
                TrustPolicy.shortcutPackageForDockFeed(TrustPolicy.PHONE_DOCK_FEED));
        assertEquals(
                "PHONE",
                TrustPolicy.shortcutRoleForDockFeed(TrustPolicy.PHONE_DOCK_FEED));
        assertEquals(
                TrustPolicy.VOLUME_DOWN_PACKAGE,
                TrustPolicy.shortcutPackageForDockFeed(TrustPolicy.RECENT_DOCK_FEED));
        assertEquals(
                "RECENT",
                TrustPolicy.shortcutRoleForDockFeed(TrustPolicy.RECENT_DOCK_FEED));

        assertNull(TrustPolicy.shortcutPackageForDockFeed(8));
        assertNull(TrustPolicy.shortcutPackageForDockFeed(11));
        assertNull(TrustPolicy.shortcutRoleForDockFeed(8));
        assertNull(TrustPolicy.shortcutRoleForDockFeed(11));
    }

    @Test
    public void exposesProviderMethodsOnlyForTheTwoShortcutPackages() {
        assertEquals(
                TrustPolicy.VOLUME_UP_PROVIDER_METHOD,
                TrustPolicy.providerMethodForShortcutPackage(TrustPolicy.VOLUME_UP_PACKAGE));
        assertEquals(
                TrustPolicy.VOLUME_DOWN_PROVIDER_METHOD,
                TrustPolicy.providerMethodForShortcutPackage(TrustPolicy.VOLUME_DOWN_PACKAGE));

        assertNull(TrustPolicy.providerMethodForShortcutPackage(null));
        assertNull(TrustPolicy.providerMethodForShortcutPackage("com.example.fake"));
    }

    @Test
    public void adaptsOnlyTheThirdAndFourthNamedDockSlots() {
        assertEquals(
                TrustPolicy.VOLUME_UP_PACKAGE,
                TrustPolicy.shortcutPackageForDockSlot("hotseat_three"));
        assertEquals(
                TrustPolicy.VOLUME_DOWN_PACKAGE,
                TrustPolicy.shortcutPackageForDockSlot("hotseat_four"));

        assertNull(TrustPolicy.shortcutPackageForDockSlot(null));
        assertNull(TrustPolicy.shortcutPackageForDockSlot("hotseat_one"));
        assertNull(TrustPolicy.shortcutPackageForDockSlot("hotseat_two"));
        assertNull(TrustPolicy.shortcutPackageForDockSlot("hotseat_five"));
        assertNull(TrustPolicy.shortcutPackageForDockSlot("HOTSEAT_THREE"));
        assertNull(TrustPolicy.shortcutPackageForDockSlot("prefix_hotseat_three"));
        assertNull(TrustPolicy.shortcutPackageForDockSlot("hotseat_four_extra"));
    }

    @Test
    public void namedAndProfileBasedDockRoutesUseTheSameVolumeCommands() {
        assertEquals(
                TrustPolicy.shortcutPackageForDockFeed(TrustPolicy.PHONE_DOCK_FEED),
                TrustPolicy.shortcutPackageForDockSlot(TrustPolicy.VOLUME_UP_DOCK_SLOT));
        assertEquals(
                TrustPolicy.shortcutPackageForDockFeed(TrustPolicy.RECENT_DOCK_FEED),
                TrustPolicy.shortcutPackageForDockSlot(TrustPolicy.VOLUME_DOWN_DOCK_SLOT));
        assertEquals(
                TrustPolicy.VOLUME_UP_PROVIDER_METHOD,
                TrustPolicy.providerMethodForShortcutPackage(
                        TrustPolicy.shortcutPackageForDockSlot(TrustPolicy.VOLUME_UP_DOCK_SLOT)));
        assertEquals(
                TrustPolicy.VOLUME_DOWN_PROVIDER_METHOD,
                TrustPolicy.providerMethodForShortcutPackage(
                        TrustPolicy.shortcutPackageForDockSlot(TrustPolicy.VOLUME_DOWN_DOCK_SLOT)));
    }
}

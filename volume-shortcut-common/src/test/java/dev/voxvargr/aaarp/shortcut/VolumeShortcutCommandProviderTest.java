package dev.voxvargr.aaarp.shortcut;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VolumeShortcutCommandProviderTest {
    private static final String GEARHEAD = "com.google.android.projection.gearhead";

    @Test
    public void acceptsOnlyExactSingletonGearheadIdentity() {
        assertTrue(VolumeShortcutCommandProvider.isAuthorizedCaller(
                GEARHEAD,
                new String[]{GEARHEAD}
        ));

        assertFalse(VolumeShortcutCommandProvider.isAuthorizedCaller(null, new String[]{GEARHEAD}));
        assertFalse(VolumeShortcutCommandProvider.isAuthorizedCaller(
                GEARHEAD,
                new String[]{GEARHEAD, "shared.uid.package"}
        ));
        assertFalse(VolumeShortcutCommandProvider.isAuthorizedCaller(
                GEARHEAD,
                new String[]{"com.example.fake"}
        ));
        assertFalse(VolumeShortcutCommandProvider.isAuthorizedCaller(
                "com.example.fake",
                new String[]{"com.example.fake"}
        ));
        assertFalse(VolumeShortcutCommandProvider.isAuthorizedCaller(GEARHEAD, null));
    }

    @Test
    public void acceptsOnlyTheTwoConfiguredVolumeActions() {
        assertTrue(VolumeShortcutCommandProvider.isSupportedAction(
                "dev.voxvargr.aaarp.action.VOLUME_UP"
        ));
        assertTrue(VolumeShortcutCommandProvider.isSupportedAction(
                "dev.voxvargr.aaarp.action.VOLUME_DOWN"
        ));

        assertFalse(VolumeShortcutCommandProvider.isSupportedAction(null));
        assertFalse(VolumeShortcutCommandProvider.isSupportedAction("unexpected"));
    }
}

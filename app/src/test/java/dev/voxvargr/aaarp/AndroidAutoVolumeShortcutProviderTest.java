package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioManager;

import org.junit.Test;

public final class AndroidAutoVolumeShortcutProviderTest {
    private static final String GEARHEAD = "com.google.android.projection.gearhead";

    @Test
    public void methodsMapToExactlyOneDirection() {
        assertEquals(
                AudioManager.ADJUST_LOWER,
                AndroidAutoVolumeShortcutProvider.directionForMethod(
                        AndroidAutoVolumeShortcutProvider.METHOD_VOLUME_DOWN));
        assertEquals(
                AudioManager.ADJUST_RAISE,
                AndroidAutoVolumeShortcutProvider.directionForMethod(
                        AndroidAutoVolumeShortcutProvider.METHOD_VOLUME_UP));
        assertEquals(
                AudioManager.ADJUST_SAME,
                AndroidAutoVolumeShortcutProvider.directionForMethod(null));
        assertEquals(
                AudioManager.ADJUST_SAME,
                AndroidAutoVolumeShortcutProvider.directionForMethod("adjust_volume"));
    }

    @Test
    public void callerMustBeTheOnlyPackageOnGearheadsUid() {
        assertTrue(AndroidAutoVolumeShortcutProvider.isAuthorizedCaller(
                GEARHEAD,
                new String[]{GEARHEAD}));

        assertFalse(AndroidAutoVolumeShortcutProvider.isAuthorizedCaller(
                null,
                new String[]{GEARHEAD}));
        assertFalse(AndroidAutoVolumeShortcutProvider.isAuthorizedCaller(
                GEARHEAD,
                null));
        assertFalse(AndroidAutoVolumeShortcutProvider.isAuthorizedCaller(
                GEARHEAD,
                new String[]{GEARHEAD, "com.example.shared"}));
        assertFalse(AndroidAutoVolumeShortcutProvider.isAuthorizedCaller(
                "com.example.fake",
                new String[]{"com.example.fake"}));
    }
}

package dev.voxvargr.aaarp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioManager;

import org.junit.Test;

public final class AudioRouteControllerAudioModeTest {
    @Test
    public void normalModeAllowsAutomaticRoutingAndOwnedRouteCleanup() {
        assertFalse(AudioRouteController.isAutomaticRoutingProtectedForMode(AudioManager.MODE_NORMAL));
        assertFalse(AudioRouteController.isOwnedRouteCleanupProtectedForMode(AudioManager.MODE_NORMAL));
    }

    @Test
    public void ringingAndEstablishedCallsProtectBothRoutingAndCleanup() {
        assertProtectedForRoutingAndCleanup(AudioManager.MODE_RINGTONE);
        assertProtectedForRoutingAndCleanup(AudioManager.MODE_IN_CALL);
    }

    @Test
    public void screeningAndRedirectedCallsProtectBothRoutingAndCleanup() {
        assertProtectedForRoutingAndCleanup(AudioManager.MODE_CALL_SCREENING);
        assertProtectedForRoutingAndCleanup(AudioManager.MODE_CALL_REDIRECT);
        assertProtectedForRoutingAndCleanup(AudioManager.MODE_COMMUNICATION_REDIRECT);
    }

    @Test
    public void communicationModeAllowsOnlyTheExistingOwnershipCheckedCleanupPath() {
        assertTrue(AudioRouteController.isAutomaticRoutingProtectedForMode(
                AudioManager.MODE_IN_COMMUNICATION));
        assertFalse(AudioRouteController.isOwnedRouteCleanupProtectedForMode(
                AudioManager.MODE_IN_COMMUNICATION));
    }

    @Test
    public void unrecognizedModesFailClosedForAutomaticRoutingAndCleanup() {
        // Assistant conversation and later platform modes can arrive before this app
        // compiles against their public constants. They must never look like idle audio.
        assertProtectedForRoutingAndCleanup(7);
        assertProtectedForRoutingAndCleanup(100);
        assertProtectedForRoutingAndCleanup(AudioManager.MODE_INVALID);
        assertProtectedForRoutingAndCleanup(AudioManager.MODE_CURRENT);
    }

    private static void assertProtectedForRoutingAndCleanup(int mode) {
        assertTrue("automatic routing must yield in mode " + mode,
                AudioRouteController.isAutomaticRoutingProtectedForMode(mode));
        assertTrue("owned cleanup must yield in mode " + mode,
                AudioRouteController.isOwnedRouteCleanupProtectedForMode(mode));
    }
}

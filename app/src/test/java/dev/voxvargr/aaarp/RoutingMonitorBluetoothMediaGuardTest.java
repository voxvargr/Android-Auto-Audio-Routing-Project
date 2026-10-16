package dev.voxvargr.aaarp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RoutingMonitorBluetoothMediaGuardTest {
    @Test
    public void shortSongGapDoesNotReopenScoButLongPauseDoes() {
        long stoppedAt = 100_000L;
        assertTrue(protectedAt(false, stoppedAt, stoppedAt));
        assertTrue(protectedAt(false, stoppedAt, stoppedAt + 2_000L));
        assertTrue(protectedAt(false, stoppedAt,
                stoppedAt + RoutingMonitorService.BLUETOOTH_MEDIA_IDLE_GRACE_MS));
        assertFalse(protectedAt(false, stoppedAt,
                stoppedAt + RoutingMonitorService.BLUETOOTH_MEDIA_IDLE_GRACE_MS + 1L));
        assertTrue("resuming playback protects immediately after a long pause",
                protectedAt(true, stoppedAt, stoppedAt + 60_000L));
    }

    @Test
    public void callAndRingtoneProtectionWinsEvenWhenMusicOrGapIsActive() {
        assertFalse(RoutingMonitorService.shouldPauseBluetoothScoForMedia(
                true, true, true, true, 10_000L, 10_001L));
        assertFalse(RoutingMonitorService.shouldPauseBluetoothScoForMedia(
                true, true, true, false, 10_000L, 10_001L));
        assertTrue("music can release AAARP's SCO request after communication ends",
                protectedAt(true, 10_000L, 10_002L));
    }

    @Test
    public void disabledGuardAndMediaOutputsKeepTheirExistingBehavior() {
        assertFalse(RoutingMonitorService.shouldPauseBluetoothScoForMedia(
                false, true, false, true, 10_000L, 10_001L));
        assertFalse(RoutingMonitorService.shouldPauseBluetoothScoForMedia(
                true, false, false, true, 10_000L, 10_001L));
    }

    @Test
    public void noPlaybackHistoryAndInvalidClockDoNotCreateAGapHold() {
        assertFalse(protectedAt(false, 0L, 1L));
        assertFalse(protectedAt(false, 10_000L, 9_999L));
        assertTrue(protectedAt(true, 0L, 1L));
    }

    private static boolean protectedAt(boolean mediaActive, long lastMediaAt, long now) {
        return RoutingMonitorService.shouldPauseBluetoothScoForMedia(
                true, true, false, mediaActive, lastMediaAt, now);
    }
}

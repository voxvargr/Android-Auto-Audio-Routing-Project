package dev.voxvargr.aaarp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioAttributes;

import org.junit.Test;

public final class RoutingMonitorLevelerPolicyTest {
    @Test
    public void firstTwoWatchdogMissesHoldLastConfirmedAndroidAutoState() {
        assertFalse(RoutingMonitorService.shouldHoldConfirmedAndroidAutoState(0));
        assertTrue(RoutingMonitorService.shouldHoldConfirmedAndroidAutoState(1));
        assertTrue(RoutingMonitorService.shouldHoldConfirmedAndroidAutoState(2));
        assertFalse(RoutingMonitorService.shouldHoldConfirmedAndroidAutoState(3));
    }

    @Test
    public void outsideLevelingPreventsConfiguredAutoStopOnlyWhenActuallyEnabled() {
        assertTrue(RoutingMonitorService.isOutsideMediaLevelingEnabled(
                true,
                true,
                MediaLevelerProfile.MODE_STRONG
        ));
        assertFalse(RoutingMonitorService.isOutsideMediaLevelingEnabled(
                true,
                false,
                MediaLevelerProfile.MODE_STRONG
        ));
        assertFalse(RoutingMonitorService.isOutsideMediaLevelingEnabled(
                false,
                true,
                MediaLevelerProfile.MODE_STRONG
        ));
        assertFalse(RoutingMonitorService.isOutsideMediaLevelingEnabled(
                true,
                true,
                MediaLevelerProfile.MODE_OFF
        ));
    }

    @Test
    public void activeEffectIsHeldForThreeMinuteInterSongGraceOnly() {
        long lastPlayback = 10_000L;
        assertFalse(RoutingMonitorService.shouldHoldMediaLevelerDuringPlaybackGap(
                false,
                lastPlayback,
                lastPlayback + 1L
        ));
        assertTrue(RoutingMonitorService.shouldHoldMediaLevelerDuringPlaybackGap(
                true,
                lastPlayback,
                lastPlayback + RoutingMonitorService.MEDIA_LEVELER_PLAYBACK_IDLE_GRACE_MS
        ));
        assertFalse(RoutingMonitorService.shouldHoldMediaLevelerDuringPlaybackGap(
                true,
                lastPlayback,
                lastPlayback + RoutingMonitorService.MEDIA_LEVELER_PLAYBACK_IDLE_GRACE_MS + 1L
        ));
        assertFalse(RoutingMonitorService.shouldHoldMediaLevelerDuringPlaybackGap(
                true,
                0L,
                1L
        ));
    }

    @Test
    public void eitherUsageCallbackOrMusicStreamCanConfirmMediaPlayback() {
        assertFalse(RoutingMonitorService.isMediaPlaybackConfirmed(false, false, false));
        assertTrue(RoutingMonitorService.isMediaPlaybackConfirmed(true, false, false));
        assertTrue(RoutingMonitorService.isMediaPlaybackConfirmed(false, true, false));
        assertTrue(RoutingMonitorService.isMediaPlaybackConfirmed(true, true, false));
        assertFalse("a protected-only sound on the music stream is not a song",
                RoutingMonitorService.isMediaPlaybackConfirmed(false, true, true));
        assertTrue("an explicit media usage wins when a prompt mixes over it",
                RoutingMonitorService.isMediaPlaybackConfirmed(true, true, true));
    }

    @Test
    public void interruptingAudioAlwaysSuspendsWhileOrdinaryPromptsMayMixWithMusic() {
        assertTrue(RoutingMonitorService.shouldSuspendLevelerForProtectedNonMedia(
                false, true, false
        ));
        assertTrue(RoutingMonitorService.shouldSuspendLevelerForProtectedNonMedia(
                false, true, true
        ));
        assertTrue(RoutingMonitorService.shouldSuspendLevelerForProtectedNonMedia(
                true, false, false
        ));
        assertFalse(RoutingMonitorService.shouldSuspendLevelerForProtectedNonMedia(
                true, false, true
        ));
        assertFalse(RoutingMonitorService.shouldSuspendLevelerForProtectedNonMedia(
                false, false, false
        ));
    }

    @Test
    public void ordinaryPromptPreservesOnlyAnExistingInterSongHold() {
        assertTrue(RoutingMonitorService.shouldPreserveLevelerForOrdinaryPromptGap(
                true, true, false, false, true
        ));
        assertFalse(RoutingMonitorService.shouldPreserveLevelerForOrdinaryPromptGap(
                false, true, false, false, true
        ));
        assertFalse(RoutingMonitorService.shouldPreserveLevelerForOrdinaryPromptGap(
                true, false, false, false, true
        ));
        assertFalse(RoutingMonitorService.shouldPreserveLevelerForOrdinaryPromptGap(
                true, true, true, false, true
        ));
        assertFalse(RoutingMonitorService.shouldPreserveLevelerForOrdinaryPromptGap(
                true, true, false, true, true
        ));
        assertFalse(RoutingMonitorService.shouldPreserveLevelerForOrdinaryPromptGap(
                true, true, false, false, false
        ));
    }

    @Test
    public void playbackUsagePolicySeparatesMusicFromProtectedMixedAudio() {
        assertTrue(RoutingMonitorService.isLevelerMediaUsage(AudioAttributes.USAGE_MEDIA));
        assertTrue(RoutingMonitorService.isLevelerMediaUsage(AudioAttributes.USAGE_GAME));
        assertFalse(RoutingMonitorService.isLevelerMediaUsage(AudioAttributes.USAGE_ALARM));

        assertTrue(RoutingMonitorService.isLevelerProtectedNonMediaUsage(
                AudioAttributes.USAGE_ALARM
        ));
        assertTrue(RoutingMonitorService.isLevelerProtectedNonMediaUsage(
                AudioAttributes.USAGE_NOTIFICATION
        ));
        assertTrue(RoutingMonitorService.isLevelerProtectedNonMediaUsage(
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        ));
        assertTrue(RoutingMonitorService.isLevelerProtectedNonMediaUsage(
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
        ));
        assertTrue(RoutingMonitorService.isLevelerProtectedNonMediaUsage(
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        ));
        assertTrue(RoutingMonitorService.isLevelerProtectedNonMediaUsage(
                AudioAttributes.USAGE_VOICE_COMMUNICATION
        ));
        assertFalse(RoutingMonitorService.isLevelerProtectedNonMediaUsage(
                AudioAttributes.USAGE_MEDIA
        ));

        assertTrue(RoutingMonitorService.isLevelerOrdinaryPromptUsage(
                AudioAttributes.USAGE_NOTIFICATION
        ));
        assertTrue(RoutingMonitorService.isLevelerOrdinaryPromptUsage(
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        ));
        assertTrue(RoutingMonitorService.isLevelerOrdinaryPromptUsage(
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
        ));
        assertFalse(RoutingMonitorService.isLevelerOrdinaryPromptUsage(
                AudioAttributes.USAGE_ALARM
        ));
        assertFalse(RoutingMonitorService.isLevelerOrdinaryPromptUsage(
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        ));
        assertFalse(RoutingMonitorService.isLevelerOrdinaryPromptUsage(
                AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        ));
        assertFalse(RoutingMonitorService.isLevelerOrdinaryPromptUsage(
                AudioAttributes.USAGE_VOICE_COMMUNICATION
        ));
    }
}

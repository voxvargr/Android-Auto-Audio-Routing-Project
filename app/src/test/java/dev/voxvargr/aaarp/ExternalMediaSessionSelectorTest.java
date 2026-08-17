package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public final class ExternalMediaSessionSelectorTest {
    @Test
    public void externalMediaKeyTargetWinsEvenWhenAnotherSessionIsSticky() {
        assertEquals(
                1,
                ExternalMediaSessionSelector.select(Arrays.asList(
                        candidate(true, true, false, active()),
                        candidate(true, false, true, paused()),
                        candidate(true, false, false, active())
                ))
        );
    }

    @Test
    public void ownMediaKeyTargetIsExcluded() {
        assertEquals(
                1,
                ExternalMediaSessionSelector.select(Arrays.asList(
                        candidate(false, false, true, active()),
                        candidate(true, false, false, active())
                ))
        );
    }

    @Test
    public void staleMediaKeyTargetIsSkippedForPlayingCandidate() {
        assertEquals(
                1,
                ExternalMediaSessionSelector.select(Arrays.asList(
                        candidate(
                                true,
                                false,
                                true,
                                ExternalMediaSessionSelector.ACTIVITY_OTHER
                        ),
                        candidate(true, false, false, active())
                ))
        );
    }

    @Test
    public void stickyActiveSessionDoesNotFlapWithPriorityOrder() {
        assertEquals(
                1,
                ExternalMediaSessionSelector.select(Arrays.asList(
                        candidate(true, false, false, active()),
                        candidate(true, true, false, active())
                ))
        );
    }

    @Test
    public void playingSessionReplacesStickyPausedSession() {
        assertEquals(
                1,
                ExternalMediaSessionSelector.select(Arrays.asList(
                        candidate(true, true, false, paused()),
                        candidate(true, false, false, active())
                ))
        );
    }

    @Test
    public void stickyPausedSessionWinsAmongPausedCandidates() {
        assertEquals(
                1,
                ExternalMediaSessionSelector.select(Arrays.asList(
                        candidate(true, false, false, paused()),
                        candidate(true, true, false, paused())
                ))
        );
    }

    @Test
    public void stickySessionSurvivesTransientPlaybackState() {
        assertEquals(
                1,
                ExternalMediaSessionSelector.select(Arrays.asList(
                        candidate(true, false, false, paused()),
                        candidate(
                                true,
                                true,
                                false,
                                ExternalMediaSessionSelector.ACTIVITY_TRANSIENT
                        )
                ))
        );
    }

    @Test
    public void unrelatedTransientSessionIsNotSelectedWithoutMediaKeyOwnership() {
        assertEquals(
                ExternalMediaSessionSelector.NONE,
                ExternalMediaSessionSelector.select(Collections.singletonList(
                        candidate(
                                true,
                                false,
                                false,
                                ExternalMediaSessionSelector.ACTIVITY_TRANSIENT
                        )
                ))
        );
    }

    @Test
    public void sessionsWithoutEligibleStateAreIgnored() {
        assertEquals(
                ExternalMediaSessionSelector.NONE,
                ExternalMediaSessionSelector.select(Collections.singletonList(
                        candidate(true, true, false, ExternalMediaSessionSelector.ACTIVITY_OTHER)
                ))
        );
    }

    @Test
    public void callbackGenerationMustMatchCurrentSelectionExactly() {
        assertTrue(ExternalMediaSessionSelector.isCurrentGeneration(7L, 7L));
        assertFalse(ExternalMediaSessionSelector.isCurrentGeneration(6L, 7L));
        assertFalse(ExternalMediaSessionSelector.isCurrentGeneration(Long.MAX_VALUE, Long.MIN_VALUE));
    }

    private static ExternalMediaSessionSelector.Candidate candidate(
            boolean external,
            boolean sticky,
            boolean mediaKeyTarget,
            int activity
    ) {
        return new ExternalMediaSessionSelector.Candidate(
                external,
                sticky,
                mediaKeyTarget,
                activity
        );
    }

    private static int active() {
        return ExternalMediaSessionSelector.ACTIVITY_PLAYING_OR_BUFFERING;
    }

    private static int paused() {
        return ExternalMediaSessionSelector.ACTIVITY_PAUSED_WITH_METADATA;
    }
}

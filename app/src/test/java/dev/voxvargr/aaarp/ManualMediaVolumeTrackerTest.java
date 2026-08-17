package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ManualMediaVolumeTrackerTest {
    @Test
    public void markSuccessfulStep_advancesGenerationExactlyOnce() {
        long before = ManualMediaVolumeTracker.generation();

        long marked = ManualMediaVolumeTracker.markSuccessfulStep();

        assertEquals(before + 1L, marked);
        assertEquals(marked, ManualMediaVolumeTracker.generation());
    }

    @Test
    public void serviceSnapshot_ignoresEarlierChangeAndSeesLaterChange() {
        ManualMediaVolumeTracker.markSuccessfulStep();
        long serviceSnapshot = ManualMediaVolumeTracker.generation();

        assertFalse(ManualMediaVolumeTracker.changedSince(
                serviceSnapshot,
                ManualMediaVolumeTracker.generation()
        ));

        long changedGeneration = ManualMediaVolumeTracker.markSuccessfulStep();

        assertTrue(ManualMediaVolumeTracker.changedSince(serviceSnapshot, changedGeneration));
    }

    @Test
    public void consumedGeneration_isNotReportedAgain() {
        long serviceSnapshot = ManualMediaVolumeTracker.generation();
        ManualMediaVolumeTracker.markSuccessfulStep();
        long consumedGeneration = ManualMediaVolumeTracker.generation();

        assertTrue(ManualMediaVolumeTracker.changedSince(serviceSnapshot, consumedGeneration));
        assertFalse(ManualMediaVolumeTracker.changedSince(
                consumedGeneration,
                ManualMediaVolumeTracker.generation()
        ));
    }

    @Test
    public void disabledFloorAcknowledgement_doesNotBecomeALaterChange() {
        long serviceSnapshot = ManualMediaVolumeTracker.generation();
        ManualMediaVolumeTracker.markSuccessfulStep();
        long acknowledgedWhileDisabled = ManualMediaVolumeTracker.generation();

        assertTrue(ManualMediaVolumeTracker.changedSince(
                serviceSnapshot,
                acknowledgedWhileDisabled
        ));
        assertFalse(ManualMediaVolumeTracker.changedSince(
                acknowledgedWhileDisabled,
                ManualMediaVolumeTracker.generation()
        ));
    }

    @Test
    public void changedSince_usesGenerationIdentityRatherThanOrdering() {
        assertFalse(ManualMediaVolumeTracker.changedSince(Long.MAX_VALUE, Long.MAX_VALUE));
        assertTrue(ManualMediaVolumeTracker.changedSince(Long.MAX_VALUE, Long.MIN_VALUE));
    }
}

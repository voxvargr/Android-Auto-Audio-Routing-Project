package dev.voxvargr.aaarp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RoutingMonitorVolumeOwnershipTest {
    @Test
    public void anyUnconsumedManualStepsRelinquishOwnedFloorEvenIfVolumeReturnsToRaisedIndex() {
        long observedGeneration = ManualMediaVolumeTracker.generation();

        ManualMediaVolumeTracker.markSuccessfulStep();
        ManualMediaVolumeTracker.markSuccessfulStep();
        long currentGeneration = ManualMediaVolumeTracker.generation();

        assertTrue(RoutingMonitorService.shouldRelinquishOwnedMediaVolume(
                observedGeneration,
                currentGeneration
        ));
        assertFalse(RoutingMonitorService.shouldRelinquishOwnedMediaVolume(
                currentGeneration,
                currentGeneration
        ));
    }
}

package dev.voxvargr.aaarp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaLevelerActivationTest {
    @Test
    public void onlyActiveAndDegradedStatusesRepresentLiveProcessing() {
        assertTrue(AudioRouteController.MediaLevelerActivation.active(
                "strong", "dynamics", "ok").processingActive());
        assertTrue(AudioRouteController.MediaLevelerActivation.degraded(
                "strong", "partial", "limited").processingActive());
        assertFalse(AudioRouteController.MediaLevelerActivation.failed(
                "strong", "dynamics", "unavailable").processingActive());
        assertFalse(AudioRouteController.MediaLevelerActivation.off(
                "off", "disabled").processingActive());
    }

    @Test
    public void summaryExposesRequestedAndEffectiveState() {
        String active = AudioRouteController.MediaLevelerActivation.active(
                "strong", "dynamics", "configured").summary();
        assertTrue(active.contains("levelerStatus=active"));
        assertTrue(active.contains("requested=strong"));
        assertTrue(active.contains("processor=dynamics"));

        String failed = AudioRouteController.MediaLevelerActivation.failed(
                "max", "dynamics", "bypassed").summary();
        assertTrue(failed.contains("levelerStatus=failed"));
        assertTrue(failed.contains("bypassed"));
    }
}

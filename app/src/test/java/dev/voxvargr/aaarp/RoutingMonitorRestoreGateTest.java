package dev.voxvargr.aaarp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RoutingMonitorRestoreGateTest {
    @Test
    public void restoreGateAllowsOnlyOneMutationWindowAndAlwaysReopens() {
        RoutingMonitorService.finishSettingsRestoreGate();
        try {
            assertTrue(RoutingMonitorService.beginSettingsRestoreGate());
            assertFalse(RoutingMonitorService.beginSettingsRestoreGate());
        } finally {
            RoutingMonitorService.finishSettingsRestoreGate();
        }

        assertTrue(RoutingMonitorService.beginSettingsRestoreGate());
        RoutingMonitorService.finishSettingsRestoreGate();
    }
}

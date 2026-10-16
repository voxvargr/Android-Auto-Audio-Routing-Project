package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RoutingMonitorLevelerRetryTest {
    @Test
    public void retryBackoffDoublesAndCapsAtOneMinute() {
        assertEquals(4000L, RoutingMonitorService.nextMediaLevelerRetryDelayMs(2000L));
        assertEquals(8000L, RoutingMonitorService.nextMediaLevelerRetryDelayMs(4000L));
        assertEquals(60000L, RoutingMonitorService.nextMediaLevelerRetryDelayMs(32000L));
        assertEquals(60000L, RoutingMonitorService.nextMediaLevelerRetryDelayMs(60000L));
        assertEquals(60000L, RoutingMonitorService.nextMediaLevelerRetryDelayMs(Long.MAX_VALUE));
    }

    @Test
    public void invalidRetryDelayIsNormalizedBeforeDoubling() {
        assertEquals(4000L, RoutingMonitorService.nextMediaLevelerRetryDelayMs(0L));
        assertEquals(4000L, RoutingMonitorService.nextMediaLevelerRetryDelayMs(-1L));
    }
}

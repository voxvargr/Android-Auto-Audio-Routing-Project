package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioDeviceInfo;

import org.junit.Test;

public final class RouteDeviceOutputMappingTest {
    @Test
    public void bleOutputsUseTheirDistinctPlatformDeviceIds() {
        assertBluetoothMediaOutput(AudioDeviceInfo.TYPE_BLE_HEADSET, 0x20000000);
        assertBluetoothMediaOutput(AudioDeviceInfo.TYPE_BLE_SPEAKER, 0x20000001);
        assertBluetoothMediaOutput(AudioDeviceInfo.TYPE_BLE_BROADCAST, 0x20000002);
    }

    @Test
    public void classicBluetoothMediaKeepsItsA2dpDeviceId() {
        assertBluetoothMediaOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 0x80);
    }

    @Test
    public void unsupportedOutputDoesNotIssueAPlatformRoutingDeviceId() {
        RouteDevice unsupported = route(AudioDeviceInfo.TYPE_HDMI);
        assertEquals(0, unsupported.audioSystemOutputDevice());
        assertFalse(unsupported.isBluetoothMediaOutput());

        assertEquals(0, route(100_000).audioSystemOutputDevice());
    }

    private static void assertBluetoothMediaOutput(int publicType, int platformDeviceId) {
        RouteDevice output = route(publicType);
        assertTrue(output.isBluetoothMediaOutput());
        assertTrue(output.isBluetooth());
        assertEquals(platformDeviceId, output.audioSystemOutputDevice());
    }

    private static RouteDevice route(int type) {
        return new RouteDevice(1, type, "Test output", "", false, true, true);
    }
}

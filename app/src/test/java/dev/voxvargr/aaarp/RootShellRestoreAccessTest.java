package dev.voxvargr.aaarp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class RootShellRestoreAccessTest {
    @Test
    public void restoreCommandChecksEveryRequestedAccessStateAndPropagatesFailure() {
        String command = RootShell.restoreAppAccessCommand(
                "dev.voxvargr.aaarp",
                Arrays.asList(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS
                ),
                true,
                10
        );

        assertTrue(command.contains("AAARP_USER=10"));
        assertTrue(command.contains("pm grant --user \"$AAARP_USER\""));
        assertFalse(command.contains("--user 0"));
        assertTrue(command.contains("dumpsys package check-permission"));
        assertTrue(command.contains("0|granted)"));
        assertFalse(command.contains("*granted*"));
        assertTrue(command.contains("VERIFIED: permission $AAARP_PERMISSION"));
        assertTrue(command.contains("appops set --user \"$AAARP_USER\""));
        assertTrue(command.contains("case \"$AAARP_POST_MODE\" in allow)"));
        assertTrue(command.contains("VERIFIED: POST_NOTIFICATION app-op"));
        assertTrue(command.contains("cmd deviceidle whitelist +\"$PKG\""));
        assertTrue(command.contains("VERIFIED: battery optimization exemption"));
        assertTrue(command.contains("AAARP_ACCESS_FAILED=1"));
        assertTrue(command.contains("exit 42"));
        assertFalse(command.contains("|| true"));
    }

    @Test
    public void unrelatedPermissionRestoreDoesNotInventNotificationOrDozeRequests() {
        String command = RootShell.restoreAppAccessCommand(
                "dev.voxvargr.aaarp",
                Collections.singletonList(Manifest.permission.BLUETOOTH_CONNECT),
                false,
                7
        );

        assertTrue(command.contains("AAARP_USER=7"));
        assertTrue(command.contains(Manifest.permission.BLUETOOTH_CONNECT));
        assertFalse(command.contains("POST_NOTIFICATION app-op"));
        assertFalse(command.contains("deviceidle whitelist"));
    }
}

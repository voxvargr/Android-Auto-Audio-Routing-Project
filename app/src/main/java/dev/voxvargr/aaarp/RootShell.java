package dev.voxvargr.aaarp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class RootShell {
    private static final int MAX_OUTPUT_CHARS = 24000;

    ShellResult run(String command, long timeoutMs) {
        StringBuilder output = new StringBuilder();
        AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        CountDownLatch done = new CountDownLatch(1);
        Process process;

        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return ShellResult.failure("Unable to start su: " + e.getMessage());
        }

        Thread worker = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < MAX_OUTPUT_CHARS) {
                        output.append(line).append('\n');
                    }
                }
                exitCode.set(process.waitFor());
            } catch (IOException e) {
                output.append("I/O error: ").append(e.getMessage()).append('\n');
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                output.append("Interrupted.\n");
            } finally {
                done.countDown();
            }
        }, "aaarp-root-shell");
        worker.start();

        try {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroy();
                return new ShellResult(false, -1, output.append("Timed out.\n").toString());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            return new ShellResult(false, -1, output.append("Interrupted.\n").toString());
        }

        int code = exitCode.get();
        return new ShellResult(code == 0, code, output.toString());
    }

    boolean isAvailable() {
        ShellResult result = run("id", 3000);
        return result.success && result.output.contains("uid=0");
    }

    ShellResult diagnostics() {
        String command = "echo '--- id ---'; id; "
                + "echo '--- Android Auto processes ---'; "
                + "pidof com.google.android.projection.gearhead 2>/dev/null || true; "
                + "ps -A 2>/dev/null | grep -F 'com.google.android.projection.gearhead' || true; "
                + "echo '--- Wi-Fi identity ---'; "
                + "cmd wifi status 2>/dev/null || true; "
                + "dumpsys wifi 2>/dev/null "
                + "| grep -i -E 'SSID|BSSID|WifiInfo|mWifiInfo|networkId|ephemeral|specifier|local.?only|validated|internet|restricted|trusted' "
                + "| head -n 80 || true; "
                + "echo '--- appops audio focus ---'; "
                + "cmd appops query-op TAKE_AUDIO_FOCUS 2>/dev/null || true; "
                + "cmd appops get android TAKE_AUDIO_FOCUS 2>/dev/null || true; "
                + "cmd appops get com.android.systemui TAKE_AUDIO_FOCUS 2>/dev/null || true; "
                + "cmd appops get com.google.android.projection.gearhead TAKE_AUDIO_FOCUS 2>/dev/null || true; "
                + "cmd appops get com.google.android.apps.messaging TAKE_AUDIO_FOCUS 2>/dev/null || true; "
                + "echo '--- audio focus and ducking ---'; "
                + "dumpsys audio 2>/dev/null "
                + "| grep -i -E 'focus|duck|attenuat|loss|gain|request|abandon|client|uid|package|notification|sonification|music|ring|alarm|a2dp|sco|bluetooth' "
                + "| head -n 220 || true; "
                + "echo '--- audio policy focus and strategies ---'; "
                + "dumpsys media.audio_policy 2>/dev/null "
                + "| grep -i -E 'focus|duck|attenuat|strategy|sonification|notification|music|product|preferred|device|a2dp|sco|bluetooth' "
                + "| head -n 220 || true; "
                + "echo '--- media sessions ---'; "
                + "dumpsys media_session 2>/dev/null "
                + "| grep -i -E 'session|package|state|playback|active|volume|duck|focus|bluetooth|a2dp|android auto|projection|gearhead' "
                + "| head -n 120 || true; "
                + "echo '--- Bluetooth audio state ---'; "
                + "dumpsys bluetooth_manager 2>/dev/null "
                + "| grep -i -E 'a2dp|headset|avrcp|connected|active|audio|sco|sink|source|device' "
                + "| head -n 160 || true; "
                + "echo '--- cmd audio help ---'; cmd audio help 2>/dev/null || cmd audio 2>/dev/null || true; "
                + "echo '--- dumpsys audio routes ---'; dumpsys audio 2>/dev/null | grep -i -E 'communication|bluetooth|sco|a2dp|route|device' | head -n 120 || true";
        return run(command, 8000);
    }

    ShellResult autoLogSnapshot() {
        String command = "echo '--- auto log snapshot ---'; date; "
                + "echo '--- Android Auto processes ---'; "
                + "ps -A 2>/dev/null | grep -F 'com.google.android.projection.gearhead' || true; "
                + "echo '--- Wi-Fi identity ---'; "
                + "cmd wifi status 2>/dev/null | grep -i -E 'connected to|WifiInfo|SSID|BSSID|Ephemeral|Requesting package|Trusted|Restricted' || true; "
                + "echo '--- appops audio focus ---'; "
                + "cmd appops query-op TAKE_AUDIO_FOCUS 2>/dev/null || true; "
                + "echo '--- audio focus and ducking ---'; "
                + "dumpsys audio 2>/dev/null "
                + "| grep -i -E 'focus|duck|attenuat|loss|gain|request|abandon|client|uid|package|notification|sonification|music|ring|alarm|a2dp|sco|bluetooth' "
                + "| head -n 160 || true; "
                + "echo '--- audio policy ---'; "
                + "dumpsys media.audio_policy 2>/dev/null "
                + "| grep -i -E 'focus|duck|attenuat|strategy|sonification|notification|music|product|preferred|device|a2dp|sco|bluetooth' "
                + "| head -n 160 || true; "
                + "echo '--- media sessions ---'; "
                + "dumpsys media_session 2>/dev/null "
                + "| grep -i -E 'session|package|state|playback|active|volume|duck|focus|bluetooth|a2dp|projection|gearhead' "
                + "| head -n 80 || true; "
                + "echo '--- Bluetooth audio state ---'; "
                + "dumpsys bluetooth_manager 2>/dev/null "
                + "| grep -i -E 'a2dp|headset|avrcp|connected|active|audio|sco|sink|source|device' "
                + "| head -n 100 || true";
        return run(command, 10000);
    }

    ShellResult currentWifiIdentity() {
        String command = "echo '--- cmd wifi status ---'; cmd wifi status 2>/dev/null || true; "
                + "echo '--- dumpsys wifi connection ---'; dumpsys wifi 2>/dev/null "
                + "| grep -i -E 'SSID|BSSID|WifiInfo|mWifiInfo|networkId|ephemeral|specifier|local.?only|validated|internet|restricted|trusted' "
                + "| head -n 80 || true";
        return run(command, 5000);
    }

    ShellResult resetBluetooth() {
        String command = "echo 'AAARP Bluetooth reset starting'; "
                + "svc bluetooth disable; "
                + "sleep 2; "
                + "svc bluetooth enable; "
                + "echo 'AAARP Bluetooth reset requested'";
        return run(command, 10000);
    }

    ShellResult restoreAppAccess(String packageName, Iterable<String> runtimePermissions,
                                 boolean restoreBatteryExemption) {
        StringBuilder command = new StringBuilder();
        command.append("echo '--- AAARP restoring app access ---'; ");
        command.append("PKG=").append(shellQuote(packageName)).append("; ");
        for (String permission : runtimePermissions) {
            if (permission == null || permission.length() == 0) {
                continue;
            }
            command.append("echo 'Granting ").append(permission).append("'; ");
            command.append("pm grant \"$PKG\" ")
                    .append(shellQuote(permission))
                    .append(" 2>&1 || pm grant --user 0 \"$PKG\" ")
                    .append(shellQuote(permission))
                    .append(" 2>&1 || true; ");
            if ("android.permission.POST_NOTIFICATIONS".equals(permission)) {
                command.append("cmd appops set \"$PKG\" POST_NOTIFICATION allow 2>&1 || true; ");
            }
        }
        if (restoreBatteryExemption) {
            command.append("echo 'Restoring battery optimization exemption'; ");
            command.append("cmd deviceidle whitelist +\"$PKG\" 2>&1 ")
                    .append("|| dumpsys deviceidle whitelist +\"$PKG\" 2>&1 || true; ");
        }
        return run(command.toString(), 10000);
    }

    ShellResult applyAndroidAutoAudioTweaks(boolean routeNotifications, int notificationDevice,
                                            String notificationAddress, boolean suppressDucking,
                                            boolean routeMedia, int mediaDevice, String mediaAddress) {
        StringBuilder command = new StringBuilder();
        command.append("echo '--- AAARP Android Auto audio tweaks ---'; ");
        if (routeNotifications) {
            appendStrategyIds(command, "AAARP_NOTIFICATION_STRATEGY_IDS", "SONIFICATION", "5", "Notification");
            command.append("echo 'Notification route device: ").append(notificationDevice).append("'; ");
            command.append("for ID in $AAARP_NOTIFICATION_STRATEGY_IDS; do ");
            command.append("cmd audio set-device-role-for-strategy \"$ID\" 1 ")
                    .append(notificationDevice)
                    .append(" ")
                    .append(shellQuote(notificationAddress))
                    .append(" 2>&1 || true; ");
            command.append("done; ");
        }
        if (routeMedia) {
            appendStrategyIds(command, "AAARP_MEDIA_STRATEGY_IDS", "STRATEGY_MEDIA", "5", "Media");
            command.append("echo 'Media route device: ").append(mediaDevice).append("'; ");
            command.append("for ID in $AAARP_MEDIA_STRATEGY_IDS; do ");
            command.append("cmd audio set-device-role-for-strategy \"$ID\" 1 ")
                    .append(mediaDevice)
                    .append(" ")
                    .append(shellQuote(mediaAddress))
                    .append(" 2>&1 || true; ");
            command.append("done; ");
            command.append("echo 'Blocking Android Auto audio focus while media pin is active'; ");
            command.append("cmd appops set com.google.android.projection.gearhead TAKE_AUDIO_FOCUS ignore 2>&1 || true; ");
        }
        if (suppressDucking) {
            command.append("echo 'Suppressing SystemUI audio focus while Android Auto is active'; ");
            command.append("cmd appops set com.android.systemui TAKE_AUDIO_FOCUS ignore 2>&1 || true; ");
        }
        return run(command.toString(), 8000);
    }

    ShellResult clearAndroidAutoAudioTweaks(boolean restoreDucking, boolean clearNotificationRoute,
                                            boolean clearMediaRoute) {
        StringBuilder command = new StringBuilder();
        command.append("echo '--- AAARP clearing Android Auto audio tweaks ---'; ");
        if (clearNotificationRoute) {
            appendStrategyIds(command, "AAARP_NOTIFICATION_STRATEGY_IDS", "SONIFICATION", "5", "Notification");
            command.append("for ID in $AAARP_NOTIFICATION_STRATEGY_IDS; do ");
            command.append("cmd audio clear-device-role-for-strategy \"$ID\" 1 2>&1 || true; ");
            command.append("done; ");
        }
        if (clearMediaRoute) {
            appendStrategyIds(command, "AAARP_MEDIA_STRATEGY_IDS", "STRATEGY_MEDIA", "5", "Media");
            command.append("for ID in $AAARP_MEDIA_STRATEGY_IDS; do ");
            command.append("cmd audio clear-device-role-for-strategy \"$ID\" 1 2>&1 || true; ");
            command.append("done; ");
            command.append("cmd appops set com.google.android.projection.gearhead TAKE_AUDIO_FOCUS allow 2>&1 || true; ");
        }
        if (restoreDucking) {
            command.append("cmd appops set com.android.systemui TAKE_AUDIO_FOCUS allow 2>&1 || true; ");
        }
        return run(command.toString(), 8000);
    }

    private void appendStrategyIds(StringBuilder command, String variableName, String grepPattern,
                                   String fallbackId, String label) {
        command.append(variableName).append("=\"$(dumpsys media.audio_policy 2>/dev/null ")
                .append("| grep -i ")
                .append(shellQuote(grepPattern))
                .append(" ")
                .append("| sed -n 's/.*id[:= ]*\\([0-9][0-9]*\\).*/\\1/p' ")
                .append("| tr '\\n' ' ')\"; ");
        command.append("[ -n \"$").append(variableName).append("\" ] || ")
                .append(variableName).append("=\"").append(fallbackId).append("\"; ");
        command.append("echo \"").append(label).append(" strategy ids: $")
                .append(variableName).append("\"; ");
    }

    private String shellQuote(String value) {
        if (value == null || value.length() == 0) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    static final class ShellResult {
        final boolean success;
        final int exitCode;
        final String output;

        ShellResult(boolean success, int exitCode, String output) {
            this.success = success;
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        static ShellResult failure(String message) {
            return new ShellResult(false, -1, message == null ? "" : message);
        }
    }
}

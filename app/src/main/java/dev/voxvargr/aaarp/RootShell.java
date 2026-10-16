package dev.voxvargr.aaarp;

import android.media.AudioManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class RootShell {
    static final int MAX_OUTPUT_CHARS = 32000;
    private static final String TRUNCATED_OUTPUT = "\n[AAARP diagnostics truncated]\n";
    private static final long MUSIC_VOLUME_ADJUST_TIMEOUT_MS = 2000L;
    private static final String[] SU_CANDIDATES = {
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/vendor/bin/su"
    };

    ShellResult run(String command, long timeoutMs) {
        StringBuilder output = new StringBuilder();
        AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        CountDownLatch done = new CountDownLatch(1);
        Process process;

        try {
            process = startSuProcess(command);
        } catch (IOException e) {
            return ShellResult.failure("Unable to start su: " + e.getMessage());
        }

        Thread worker = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendOutputLine(output, line);
                }
                exitCode.set(process.waitFor());
            } catch (IOException e) {
                appendOutputLine(output, "I/O error: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                appendOutputLine(output, "Interrupted.");
            } finally {
                done.countDown();
            }
        }, "aaarp-root-shell");
        worker.start();

        try {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroy();
                appendOutputLine(output, "Timed out.");
                return new ShellResult(false, -1, outputText(output));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            appendOutputLine(output, "Interrupted.");
            return new ShellResult(false, -1, outputText(output));
        }

        int code = exitCode.get();
        return new ShellResult(code == 0, code, outputText(output));
    }

    boolean isAvailable() {
        ShellResult result = run("id", 3000);
        return result.success && result.output.contains("uid=0");
    }

    ShellResult adjustMusicVolume(int direction) {
        String command = musicVolumeAdjustmentCommand(direction);
        if (command == null) {
            return ShellResult.failure("Invalid music-volume direction: " + direction);
        }
        return run(command, MUSIC_VOLUME_ADJUST_TIMEOUT_MS);
    }

    static String musicVolumeAdjustmentCommand(int direction) {
        if (direction == AudioManager.ADJUST_LOWER) {
            return "cmd media_session volume --stream 3 --adj lower";
        }
        if (direction == AudioManager.ADJUST_RAISE) {
            return "cmd media_session volume --stream 3 --adj raise";
        }
        return null;
    }

    ShellResult diagnostics() {
        return run(diagnosticsCommand(false), 8000);
    }

    ShellResult autoLogSnapshot() {
        return run(diagnosticsCommand(true), 10000);
    }

    static String diagnosticsCommand(boolean automatic) {
        StringBuilder command = new StringBuilder();
        command.append(automatic
                ? "echo '--- auto log snapshot ---'; date; "
                : "echo '--- id ---'; id; ");
        if (!automatic) {
            command.append("echo '--- Android build ---'; "
                    + "getprop ro.product.manufacturer; getprop ro.product.model; "
                    + "getprop ro.build.version.release; getprop ro.build.version.sdk; "
                    + "echo '--- installed AAARP identity ---'; "
                + "dumpsys package dev.voxvargr.aaarp 2>/dev/null "
                + "| grep -E 'versionCode=|versionName=|firstInstallTime=|lastUpdateTime=' "
                + "| head -n 4 || true; "
                + "AAARP_APK=\"$(pm path dev.voxvargr.aaarp 2>/dev/null "
                + "| sed -n 's/^package://p' | head -n 1)\"; "
                + "echo \"baseApk=$AAARP_APK\"; "
                + "if [ -n \"$AAARP_APK\" ]; then "
                + "echo 'base APK SHA-256:'; "
                + "sha256sum \"$AAARP_APK\" 2>/dev/null "
                + "|| toybox sha256sum \"$AAARP_APK\" 2>/dev/null "
                + "|| echo 'unavailable'; "
                + "fi; ");
        }

        // Reuse one coherent snapshot per service instead of dumping audio five times.
        command.append("AAARP_AUDIO_DUMP=\"$(dumpsys audio 2>/dev/null)\"; ");
        command.append("AAARP_POLICY_DUMP=\"$(dumpsys media.audio_policy 2>/dev/null)\"; ");
        String audio = "printf '%s\\n' \"$AAARP_AUDIO_DUMP\"";
        String policy = "printf '%s\\n' \"$AAARP_POLICY_DUMP\"";
        appendDiagnosticsSection(command, "audio mode and communication state", audio
                + " | grep -i -E 'Audio mode|mMode|mBluetoothSco|mScoAudioState|mScoConnectionState|mCommunication|mActiveCommunication|mPreferredCommunication|mBluetoothA2dpEnabled|mA2dpSuspended|mForcedUseForComm|In ring or call'",
                40, 2200);
        appendDiagnosticsSection(command, "Bluetooth audio state",
                "AAARP_BT_DUMP=\"$(dumpsys bluetooth_manager 2>/dev/null)\"; "
                        + "printf '%s\\n' \"$AAARP_BT_DUMP\""
                        + " | grep -i -E 'active.*device|device.*active|audiostate|audio state|mSco|mAudioRouteAllowed' | head -n 32; "
                        + "printf '%s\\n' \"$AAARP_BT_DUMP\""
                        + " | grep -i -E 'Bluetooth Status|enabled:|Profile:|connectionstate|a2dp|headset|leaudio|le_audio' | head -n 32",
                64, 3000);
        appendDiagnosticsSection(command, "recent audio recording history", audio
                + " | grep -i -E 'rec (start|update|stop).*src:' | tail -n " + (automatic ? 24 : 32),
                32, 2400);
        appendDiagnosticsSection(command, "current audio policy input clients", policy
                + " | sed -n '/^[[:space:]]*Inputs/,/^[[:space:]]*Total Effects/p'",
                48, 1800);
        // Android's EventLogger heading varies; the call flag ends the current stack itself.
        // Keep its beginning so an unrecognized end heading cannot replace owners with tables.
        appendDiagnosticsSection(command, "current audio focus stack", audio
                + " | sed -n '/Audio Focus stack entries/,/In ring or call/p'",
                40, 2400);
        appendDiagnosticsSection(command, "Android Auto processes",
                "pidof com.google.android.projection.gearhead 2>/dev/null; "
                        + "ps -A 2>/dev/null | grep -F 'com.google.android.projection.gearhead'",
                12, 1000);
        appendDiagnosticsSection(command, "appops audio focus",
                "cmd appops query-op TAKE_AUDIO_FOCUS 2>/dev/null; "
                        + "cmd appops get android TAKE_AUDIO_FOCUS 2>/dev/null; "
                        + "cmd appops get com.android.systemui TAKE_AUDIO_FOCUS 2>/dev/null; "
                        + "cmd appops get com.google.android.projection.gearhead TAKE_AUDIO_FOCUS 2>/dev/null; "
                        + "cmd appops get com.google.android.googlequicksearchbox TAKE_AUDIO_FOCUS 2>/dev/null; "
                        + "cmd appops get com.google.android.apps.messaging TAKE_AUDIO_FOCUS 2>/dev/null",
                24, 1400);
        appendDiagnosticsSection(command, "appops microphone access",
                "cmd appops get com.google.android.projection.gearhead RECORD_AUDIO 2>/dev/null; "
                        + "cmd appops get com.google.android.googlequicksearchbox RECORD_AUDIO 2>/dev/null; "
                        + "cmd appops get com.google.android.apps.messaging RECORD_AUDIO 2>/dev/null",
                16, 800);
        appendDiagnosticsSection(command, "recent audio focus history", audio
                + " | grep -i -E 'MediaFocusControl dump time|requestAudioFocus|abandonAudioFocus|focus owner:|In ring or call|Notify on duck'"
                + " | tail -n " + (automatic ? 40 : 60),
                60, 3600);
        appendDiagnosticsSection(command, "Wi-Fi identity", "cmd wifi status 2>/dev/null"
                + " | grep -i -E 'enabled|connected to|WifiInfo|NetworkCapabilities'",
                8, 1100);
        appendDiagnosticsSection(command, "audio policy focus and strategies", policy
                + " | grep -i -E 'focus|duck|attenuat|strategy|sonification|notification|music|product|preferred|device|a2dp|sco|bluetooth'",
                automatic ? 80 : 100, 4000);
        appendDiagnosticsSection(command, "media sessions", "dumpsys media_session 2>/dev/null"
                + " | grep -i -E 'session|package|state|playback|active|volume|duck|focus|bluetooth|a2dp|android auto|projection|gearhead'",
                automatic ? 48 : 64, 2200);
        if (!automatic) {
            appendDiagnosticsSection(command, "cmd audio help",
                    "cmd audio help 2>/dev/null || cmd audio 2>/dev/null", 48, 1600);
        }
        return command.toString();
    }

    private static void appendDiagnosticsSection(StringBuilder command, String label,
                                                  String source, int maxLines, int maxChars) {
        command.append("echo '--- ").append(label).append(" ---'; (")
                .append(source).append(") | cut -c 1-400 | head -n ")
                .append(maxLines).append(" | head -c ").append(maxChars)
                .append("; printf '\\n'; ");
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
                                 boolean restoreBatteryExemption, int userId) {
        return run(restoreAppAccessCommand(
                packageName,
                runtimePermissions,
                restoreBatteryExemption,
                userId
        ), 10000);
    }

    static String restoreAppAccessCommand(String packageName,
                                          Iterable<String> runtimePermissions,
                                          boolean restoreBatteryExemption,
                                          int userId) {
        StringBuilder command = new StringBuilder();
        command.append("echo '--- AAARP restoring app access ---'; ");
        command.append("AAARP_ACCESS_FAILED=0; ");
        command.append("PKG=").append(shellQuote(packageName)).append("; ");
        command.append("AAARP_USER=").append(userId).append("; ");
        for (String permission : runtimePermissions) {
            if (permission == null || permission.length() == 0) {
                continue;
            }
            command.append("AAARP_PERMISSION=").append(shellQuote(permission)).append("; ");
            command.append("echo \"Granting $AAARP_PERMISSION\"; ");
            command.append("if pm grant --user \"$AAARP_USER\" \"$PKG\" ")
                    .append("\"$AAARP_PERMISSION\" 2>&1; then :; ")
                    .append("else echo \"ERROR: grant command failed for $AAARP_PERMISSION\"; ")
                    .append("AAARP_ACCESS_FAILED=1; fi; ");
            command.append("AAARP_PERMISSION_STATE=\"$(dumpsys package check-permission ")
                    .append("\"$AAARP_PERMISSION\" \"$PKG\" \"$AAARP_USER\" 2>&1)\"; ");
            command.append("case \"$AAARP_PERMISSION_STATE\" in ")
                    .append("0|granted) echo \"VERIFIED: permission $AAARP_PERMISSION\" ;; ")
                    .append("*) echo \"ERROR: permission not verified: $AAARP_PERMISSION\"; ")
                    .append("echo \"$AAARP_PERMISSION_STATE\"; AAARP_ACCESS_FAILED=1 ;; esac; ");
            if ("android.permission.POST_NOTIFICATIONS".equals(permission)) {
                command.append("if cmd appops set --user \"$AAARP_USER\" \"$PKG\" ")
                        .append("POST_NOTIFICATION allow 2>&1; then :; ")
                        .append("else echo 'ERROR: POST_NOTIFICATION app-op request failed'; ")
                        .append("AAARP_ACCESS_FAILED=1; fi; ");
                command.append("AAARP_POST_MODE=\"$(cmd appops get --user \"$AAARP_USER\" ")
                        .append("\"$PKG\" ")
                        .append("POST_NOTIFICATION 2>&1 ")
                        .append("| sed -n 's/^[[:space:]]*POST_NOTIFICATION:[[:space:]]*\\([^;[:space:]]*\\).*/\\1/p' ")
                        .append("| head -n 1)\"; ");
                command.append("case \"$AAARP_POST_MODE\" in ")
                        .append("allow) echo 'VERIFIED: POST_NOTIFICATION app-op' ;; ")
                        .append("*) echo 'ERROR: POST_NOTIFICATION app-op not verified'; ")
                        .append("echo \"mode=$AAARP_POST_MODE\"; AAARP_ACCESS_FAILED=1 ;; esac; ");
            }
        }
        if (restoreBatteryExemption) {
            command.append("echo 'Restoring battery optimization exemption'; ");
            command.append("if cmd deviceidle whitelist +\"$PKG\" 2>&1; then :; ")
                    .append("elif dumpsys deviceidle whitelist +\"$PKG\" 2>&1; then :; ")
                    .append("else echo 'ERROR: battery exemption request failed'; ")
                    .append("AAARP_ACCESS_FAILED=1; fi; ");
            command.append("AAARP_DOZE_STATE=\"$(cmd deviceidle whitelist 2>&1)\"; ");
            command.append("case \"$AAARP_DOZE_STATE\" in ")
                    .append("*\"$PKG\"*) echo 'VERIFIED: battery optimization exemption' ;; ")
                    .append("*) echo 'ERROR: battery optimization exemption not verified'; ")
                    .append("echo \"$AAARP_DOZE_STATE\"; AAARP_ACCESS_FAILED=1 ;; esac; ");
        }
        command.append("if [ \"$AAARP_ACCESS_FAILED\" -eq 0 ]; then ")
                .append("echo 'AAARP app access restore verified'; exit 0; ")
                .append("else echo 'AAARP app access restore verification failed'; exit 42; fi");
        return command.toString();
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

    private static String shellQuote(String value) {
        if (value == null || value.length() == 0) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private Process startSuProcess(String command) throws IOException {
        IOException lastError = null;
        StringBuilder failures = new StringBuilder();
        for (String suPath : SU_CANDIDATES) {
            try {
                return new ProcessBuilder(suPath, "-c", command)
                        .redirectErrorStream(true)
                        .start();
            } catch (IOException e) {
                lastError = e;
                if (failures.length() > 0) {
                    failures.append("; ");
                }
                failures.append(suPath).append(": ").append(e.getMessage());
            }
        }
        throw new IOException(failures.toString(), lastError);
    }

    static void appendOutputLine(StringBuilder output, String line) {
        synchronized (output) {
            String value = (line == null ? "" : line) + '\n';
            if (value.length() <= MAX_OUTPUT_CHARS - output.length()) {
                output.append(value);
                return;
            }
            // Overflow can arrive after a line filled the buffer exactly. Make room in the
            // existing text too, so every truncated result has a complete, explicit marker.
            int retainedChars = MAX_OUTPUT_CHARS - TRUNCATED_OUTPUT.length();
            if (output.length() > retainedChars) {
                output.setLength(retainedChars);
            }
            int remaining = retainedChars - output.length();
            output.append(value, 0, Math.min(value.length(), remaining));
            output.append(TRUNCATED_OUTPUT);
        }
    }

    private static String outputText(StringBuilder output) {
        synchronized (output) {
            return output.toString();
        }
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

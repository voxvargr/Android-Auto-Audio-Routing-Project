package dev.voxvargr.aaarp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class RootShell {
    private static final int MAX_OUTPUT_CHARS = 12000;

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
                + "echo '--- Android Auto PID ---'; pidof com.google.android.projection.gearhead 2>/dev/null || true; "
                + "echo '--- cmd audio help ---'; cmd audio help 2>/dev/null || cmd audio 2>/dev/null || true; "
                + "echo '--- dumpsys audio routes ---'; dumpsys audio 2>/dev/null | grep -i -E 'communication|bluetooth|sco|a2dp|route|device' | head -n 120 || true";
        return run(command, 8000);
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

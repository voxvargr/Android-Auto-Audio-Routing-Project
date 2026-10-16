package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class RootShellDiagnosticsTest {
    private static final String MARKER = "\n[AAARP diagnostics truncated]\n";

    @Test
    public void completeOutputIsPreservedWithoutTruncationMarker() {
        StringBuilder output = new StringBuilder();
        RootShell.appendOutputLine(output, "mode=normal");
        RootShell.appendOutputLine(output, "Bluetooth SCO=off");
        assertEquals("mode=normal\nBluetooth SCO=off\n", output.toString());
    }

    @Test
    public void overflowAfterExactlyFullLineStillHasCompleteMarker() {
        StringBuilder output = new StringBuilder();
        RootShell.appendOutputLine(output, repeated('x', RootShell.MAX_OUTPUT_CHARS - 1));
        assertEquals(RootShell.MAX_OUTPUT_CHARS, output.length());
        RootShell.appendOutputLine(output, "otherwise invisible Bluetooth state");
        assertEquals(RootShell.MAX_OUTPUT_CHARS, output.length());
        assertTrue(output.toString().endsWith(MARKER));
        String truncated = output.toString();
        RootShell.appendOutputLine(output, "another line");
        assertEquals(truncated, output.toString());
    }

    @Test
    public void overflowWithLessRoomThanMarkerStillIdentifiesMissingData() {
        StringBuilder output = new StringBuilder();
        RootShell.appendOutputLine(output, repeated('x', RootShell.MAX_OUTPUT_CHARS - 6));
        RootShell.appendOutputLine(output, repeated('y', 100));
        assertEquals(RootShell.MAX_OUTPUT_CHARS, output.length());
        assertTrue(output.toString().endsWith(MARKER));
    }

    @Test
    public void oversizedSingleLineIsBoundedAndMarked() {
        StringBuilder output = new StringBuilder();
        RootShell.appendOutputLine(output, repeated('x', RootShell.MAX_OUTPUT_CHARS * 2));
        assertEquals(RootShell.MAX_OUTPUT_CHARS, output.length());
        assertTrue(output.toString().endsWith(MARKER));
    }

    @Test
    public void pixelFocusOwnersAndBluetoothSurviveLargeUnrelatedDumps() throws Exception {
        // Run the actual generated Android shell pipeline against synthetic service dumps.
        // EventLogger uses "Event log", whereas older diagnostics expected "Events log".
        String fixture = "dumpsys() { case \"$1\" in "
                + "audio) printf '%s\\n' 'Audio mode: MODE_NORMAL' 'mBluetoothScoOn=false' "
                + "'Audio Focus stack entries (last is top of stack):' "
                + "'  pack: test.music gain: GAIN' ' Notify on duck: true' ' In ring or call: false' "
                + "'Event log: focus commands as seen by MediaFocusControl' "
                + "'10-16 requestAudioFocus() callingPack=test.music'; "
                + "i=0; while [ $i -lt 400 ]; do printf '%s\\n' 'AudioAttributes: routing-table-noise'; i=$((i+1)); done ;; "
                + "media.audio_policy) printf '%s\\n' ' Inputs (0):' ' Total Effects CPU: 0'; "
                + "i=0; while [ $i -lt 400 ]; do printf '%s\\n' 'device verbose-policy-noise'; i=$((i+1)); done ;; "
                + "bluetooth_manager) printf '%s\\n' 'mActiveDevice=test.headset' 'mAudioState=disconnected'; "
                + "i=0; while [ $i -lt 400 ]; do printf '%s\\n' 'headset verbose-bluetooth-noise'; i=$((i+1)); done ;; "
                + "media_session) printf '%s\\n' 'package=test.music state=PLAYING' ;; "
                + "esac; }; "
                + "cmd() { printf '%s\\n' 'fixture command state'; }; "
                + "getprop() { printf '%s\\n' 'fixture build'; }; "
                + "pm() { :; }; pidof() { :; }; ps() { :; }; ";

        for (boolean automatic : new boolean[]{false, true}) {
            String result = runShell(fixture + RootShell.diagnosticsCommand(automatic));
            assertTrue(result.contains("Audio mode: MODE_NORMAL"));
            assertTrue(result.contains("mBluetoothScoOn=false"));
            assertTrue(result.contains("mActiveDevice=test.headset"));
            assertTrue(result.contains("mAudioState=disconnected"));
            String focus = result.substring(result.indexOf("--- current audio focus stack ---"),
                    result.indexOf("--- Android Auto processes ---"));
            assertTrue(focus.contains("pack: test.music"));
            assertTrue(focus.contains("In ring or call: false"));
            assertFalse(focus.contains("routing-table-noise"));
            assertFalse(focus.contains("requestAudioFocus()"));
            assertTrue(result.contains("--- media sessions ---"));
            assertTrue(result.contains("package=test.music state=PLAYING"));
            assertTrue(result.length() < RootShell.MAX_OUTPUT_CHARS);
        }
    }

    private static String runShell(String script) throws Exception {
        String shell = null;
        for (String candidate : new String[]{"/bin/sh", "C:/Program Files/Git/bin/sh.exe"}) {
            if (new File(candidate).isFile()) {
                shell = candidate;
                break;
            }
        }
        Assume.assumeNotNull(shell);
        Process process = new ProcessBuilder(shell, "-s").redirectErrorStream(true).start();
        try (OutputStream input = process.getOutputStream()) {
            input.write(script.getBytes(StandardCharsets.UTF_8));
        }
        String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue("Fixture command timed out", process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(result, 0, process.exitValue());
        return result;
    }

    private static String repeated(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}

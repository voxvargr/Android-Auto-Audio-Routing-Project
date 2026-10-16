package dev.voxvargr.aaarp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class DiagnosticsFileWriterTest {
    @Test
    public void streamedHistoryPreservesAllUtf8BytesBeyondSnapshotLimit() throws Exception {
        File file = File.createTempFile("aaarp-history-export", ".txt");
        byte[] chunk = "Bluetooth connected → music resumed\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        try {
            DiagnosticsFileWriter.writeFile(file, output -> {
                for (int i = 0; i < 4_000; i++) {
                    output.write(chunk);
                    expected.write(chunk);
                }
            });
            assertTrue(file.length() > 32_000L);
            assertArrayEquals(expected.toByteArray(), Files.readAllBytes(file.toPath()));
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }

    @Test
    public void failedStreamingExportDoesNotLeaveAPartialFile() throws Exception {
        File file = File.createTempFile("aaarp-history-export", ".txt");
        try {
            DiagnosticsFileWriter.writeFile(file, output -> {
                output.write("partial report".getBytes(StandardCharsets.UTF_8));
                throw new IOException("history read failed");
            });
            fail("Expected the history failure to reach the caller");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("history read failed"));
            assertFalse(file.exists());
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }
}

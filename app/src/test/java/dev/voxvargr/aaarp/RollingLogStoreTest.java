package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.TimeZone;

public final class RollingLogStoreTest {
    private static final long HOUR = RollingLogStore.HOUR_MS;
    private static final long NOW = 1_792_151_195_678L;
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void exact48HourBoundaryFiltersEntireOldMultilineRecord() throws Exception {
        File directory = temporary.newFolder();
        RollingLogStore store = new RollingLogStore(directory, null);
        long cutoff = NOW - RollingLogStore.RETENTION_MS;
        store.append(cutoff - 1L, "expired start\nexpired detail");
        store.append(cutoff, "boundary start\r\nboundary detail");
        store.append(NOW, "latest");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RollingLogStore.HistorySummary summary = store.writeHistory(output, NOW);
        String report = utf8(output);
        assertFalse(report.contains("expired start"));
        assertFalse(report.contains("expired detail"));
        assertTrue(report.contains("boundary start\n    boundary detail"));
        assertEquals(2L, summary.recordCount);
        assertEquals(cutoff, summary.firstTimestamp);
        assertEquals(NOW, summary.lastTimestamp);
    }

    @Test
    public void restartPreservesRecentRecordsAndDeletesExpiredHourSegments() throws Exception {
        File directory = temporary.newFolder();
        RollingLogStore first = new RollingLogStore(directory, null);
        first.append(NOW - 49L * HOUR, "expired previous day");
        first.append(NOW - 47L * HOUR, "recent previous day");
        first.append(NOW, "current day");
        assertEquals(2, logFiles(directory).length);

        RollingLogStore restarted = new RollingLogStore(directory, null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(2L, restarted.writeHistory(output, NOW).recordCount);
        assertTrue(utf8(output).contains("recent previous day"));
        assertFalse(utf8(output).contains("expired previous day"));

        output.reset();
        restarted.writeHistory(output, NOW + 49L * HOUR);
        assertEquals(0, logFiles(directory).length);
        assertFalse(utf8(output).contains("current day"));
    }

    @Test
    public void moreThanNineRotationsRemainOrderedAndContinueAfterRestart() throws Exception {
        File directory = temporary.newFolder();
        RollingLogStore store = new RollingLogStore(directory, null, 48L * HOUR, 100L, 100_000L);
        for (int index = 0; index < 25; index++) {
            store.append(NOW + index, String.format("entry-%02d %s", index, repeated('x', 60)));
        }
        RollingLogStore restarted = new RollingLogStore(directory, null, 48L * HOUR, 100L, 100_000L);
        restarted.append(NOW + 25L, "entry-25 " + repeated('x', 60));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(26L, restarted.writeHistory(output, NOW + 25L).recordCount);
        String report = utf8(output);
        int previous = -1;
        for (int index = 0; index < 26; index++) {
            int position = report.indexOf(String.format("entry-%02d ", index));
            assertTrue("Incorrect numeric rotation order at " + index, position > previous);
            previous = position;
        }
        assertEquals(26, logFiles(directory).length);
    }

    @Test
    public void diskLimitEvictsOldestDataAndPersistsCoverageWarning() throws Exception {
        File directory = temporary.newFolder();
        RollingLogStore store = new RollingLogStore(directory, null, 48L * HOUR, 100L, 300L);
        for (int index = 0; index < 20; index++) {
            store.append(NOW + index, "entry-" + index + " " + repeated('x', 60));
        }
        long total = 0L;
        for (File file : logFiles(directory)) {
            total += file.length();
        }
        assertTrue("History exceeded its configured disk limit", total <= 300L);

        RollingLogStore restarted = new RollingLogStore(directory, null, 48L * HOUR, 100L, 300L);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RollingLogStore.HistorySummary summary = restarted.writeHistory(output, NOW + 20L);
        String report = utf8(output);
        assertTrue(report.contains("WARNING: the disk limit removed"));
        assertTrue(summary.diskLimitReachedAt > 0L);
        assertFalse(report.contains("entry-0 "));
        assertTrue(report.contains("entry-19 "));
    }

    @Test
    public void recentLegacyFilesAreIntactAndUseNumericRotationOrder() throws Exception {
        File directory = temporary.newFolder();
        File legacy = temporary.newFolder();
        legacyFile(legacy, "AAARP-auto-20261016-10.log", "legacy ten\nintact second line", NOW);
        legacyFile(legacy, "AAARP-auto-20261016-2.log", "legacy two", NOW);
        legacyFile(legacy, "AAARP-auto-20261016-1.log", "legacy one", NOW);
        File expired = legacyFile(legacy, "AAARP-auto-20261012.log", "expired legacy", NOW - 49L * HOUR);
        RollingLogStore store = new RollingLogStore(directory, legacy);
        store.append(NOW, "new UTC history");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(3L, store.writeHistory(output, NOW).legacyFileCount);
        String report = utf8(output);
        assertTrue(report.indexOf("legacy one") < report.indexOf("legacy two"));
        assertTrue(report.indexOf("legacy two") < report.indexOf("legacy ten"));
        assertTrue(report.contains("legacy ten\nintact second line"));
        assertFalse(report.contains("expired legacy"));
        assertFalse(expired.exists());
    }

    @Test
    public void unavailableLegacyFolderDoesNotBreakNewInternalHistory() throws Exception {
        File directory = temporary.newFolder();
        File unavailable = temporary.newFile();
        RollingLogStore store = new RollingLogStore(directory, unavailable);
        store.append(NOW, "new storage remains usable");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        store.writeHistory(output, NOW);
        assertTrue(utf8(output).contains("new storage remains usable"));
        assertTrue(utf8(output).contains("WARNING: external legacy logs were unavailable"));
    }

    @Test
    public void corruptCoverageMetadataDoesNotMakeRecordsInaccessible() throws Exception {
        File directory = temporary.newFolder();
        Files.write(new File(directory, "history-state.properties").toPath(),
                "capacityEvictedUntil=\\uGGGG".getBytes(StandardCharsets.UTF_8));
        RollingLogStore store = new RollingLogStore(directory, null);
        store.append(NOW, "preserved after damaged metadata");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        store.writeHistory(output, NOW);
        assertTrue(utf8(output).contains("preserved after damaged metadata"));
        assertTrue(utf8(output).contains("coverage metadata was unreadable"));
    }

    @Test
    public void unicodeAndReportsWellBeyond32kAreStreamedWithoutClosingCallerOutput() throws Exception {
        RollingLogStore store = new RollingLogStore(temporary.newFolder(), null);
        String large = "root snapshot café 🎵\n" + repeated('x', 80_000) + "\nlast Bluetooth details";
        store.append(NOW, large);
        class TrackingOutput extends ByteArrayOutputStream {
            boolean closed;
            @Override public void close() {
                closed = true;
            }
        }
        TrackingOutput output = new TrackingOutput();
        assertEquals(1L, store.writeHistory(output, NOW).recordCount);
        String report = utf8(output);
        assertTrue(report.length() > 80_000);
        assertTrue(report.contains("café 🎵"));
        assertTrue(report.contains(repeated('x', 80_000)));
        assertTrue(report.contains("\n    last Bluetooth details"));
        assertFalse(output.closed);
    }

    @Test
    public void utcRecordsSurviveTimezoneChangesAndFutureClockEntriesAreDisclosed() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
            RollingLogStore store = new RollingLogStore(temporary.newFolder(), null);
            store.append(NOW, "before timezone change");
            TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"));
            store.append(NOW + HOUR, "clock moved back after this entry");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            RollingLogStore.HistorySummary summary = store.writeHistory(output, NOW);
            assertEquals(2L, summary.recordCount);
            assertEquals(1L, summary.futureRecordCount);
            assertTrue(utf8(output).contains("possibly because the phone clock changed"));
            assertTrue(utf8(output).contains("before timezone change"));
            assertTrue(utf8(output).contains("clock moved back after this entry"));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    public void failedExportIsReportedInsteadOfReturningPartialSuccess() throws Exception {
        File directory = temporary.newFolder();
        RollingLogStore store = new RollingLogStore(directory, null);
        store.append(NOW, "stored safely");
        try {
            store.writeHistory(new OutputStream() {
                @Override public void write(int value) throws IOException {
                    throw new IOException("destination full");
                }
            }, NOW);
            fail("Expected export failure");
        } catch (IOException expected) {
            assertEquals("destination full", expected.getMessage());
        }
        assertEquals(1, logFiles(directory).length);
        ByteArrayOutputStream retry = new ByteArrayOutputStream();
        assertEquals(1L, store.writeHistory(retry, NOW).recordCount);
        assertTrue(utf8(retry).contains("stored safely"));
    }

    private static File legacyFile(File directory, String name, String text, long modified) throws Exception {
        File file = new File(directory, name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setLastModified(modified));
        return file;
    }

    private static File[] logFiles(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".log"));
        return files == null ? new File[0] : files;
    }

    private static String utf8(ByteArrayOutputStream output) {
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String repeated(char value, int count) {
        char[] characters = new char[count];
        java.util.Arrays.fill(characters, value);
        return new String(characters);
    }
}

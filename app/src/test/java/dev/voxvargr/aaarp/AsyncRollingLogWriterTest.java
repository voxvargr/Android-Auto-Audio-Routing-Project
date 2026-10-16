package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AsyncRollingLogWriterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void queueSaturationNeverBlocksAppendsAndExportIdentifiesTheGap() throws Exception {
        File directory = temporary.newFolder();
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch factoryRelease = new CountDownLatch(1);
        AsyncRollingLogWriter writer = new AsyncRollingLogWriter(() -> {
            factoryEntered.countDown();
            await(factoryRelease);
            return new RollingLogStore(directory, null);
        }, 2, 1_000_000L);
        ExecutorService exporter = Executors.newSingleThreadExecutor();
        try {
            long now = System.currentTimeMillis();
            writer.append(now, "accepted initial entry");
            assertTrue(factoryEntered.await(5L, TimeUnit.SECONDS));
            writer.append(now, "accepted queued entry one");
            writer.append(now, "accepted queued entry two");
            long start = System.nanoTime();
            for (int index = 0; index < 100; index++) {
                writer.append(now, "rejected queue entry " + index);
            }
            assertTrue("Appending waited on a stalled disk", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1L));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Future<RollingLogStore.HistorySummary> result = exporter.submit(() -> writer.writeHistory(output));
            factoryRelease.countDown();
            assertEquals(4L, result.get(5L, TimeUnit.SECONDS).recordCount);
            String report = utf8(output);
            assertTrue(report.contains("accepted initial entry"));
            assertTrue(report.contains("accepted queued entry two"));
            assertTrue(report.contains("HISTORY GAP: writer queue reached its safety limit; 100 record(s)"));
            assertFalse(report.contains("rejected queue entry"));
        } finally {
            factoryRelease.countDown();
            writer.close();
            exporter.shutdownNow();
        }
    }

    @Test
    public void stalledExportHasConsistentSnapshotAndAllowsBoundedLoggingBehindIt() throws Exception {
        AsyncRollingLogWriter writer = new AsyncRollingLogWriter(
                () -> new RollingLogStore(temporary.newFolder(), null), 2, 1_000_000L);
        CountDownLatch exportEntered = new CountDownLatch(1);
        CountDownLatch exportRelease = new CountDownLatch(1);
        ExecutorService exporter = Executors.newSingleThreadExecutor();
        class StalledOutput extends ByteArrayOutputStream {
            boolean firstWrite = true;
            @Override public void write(byte[] bytes, int offset, int count) {
                if (firstWrite) {
                    firstWrite = false;
                    exportEntered.countDown();
                    try {
                        assertTrue(exportRelease.await(5L, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }
                super.write(bytes, offset, count);
            }
        }
        try {
            long now = System.currentTimeMillis();
            writer.append(now, "before export barrier");
            StalledOutput first = new StalledOutput();
            Future<?> result = exporter.submit(() -> writer.writeHistory(first));
            assertTrue(exportEntered.await(5L, TimeUnit.SECONDS));
            writer.append(now, "after export barrier one");
            writer.append(now, "after export barrier two");
            writer.append(now, "queue overflow during export");
            exportRelease.countDown();
            result.get(5L, TimeUnit.SECONDS);
            assertTrue(utf8(first).contains("before export barrier"));
            assertFalse(utf8(first).contains("after export barrier"));
            ByteArrayOutputStream second = new ByteArrayOutputStream();
            writer.writeHistory(second);
            assertTrue(utf8(second).contains("after export barrier one"));
            assertTrue(utf8(second).contains("after export barrier two"));
            assertTrue(utf8(second).contains("HISTORY GAP:"));
        } finally {
            exportRelease.countDown();
            writer.close();
            exporter.shutdownNow();
        }
    }

    @Test
    public void concurrentProducersAreFlushedByExportWithoutLosingAcceptedRecords() throws Exception {
        AsyncRollingLogWriter writer = new AsyncRollingLogWriter(
                () -> new RollingLogStore(temporary.newFolder(), null));
        ExecutorService producers = Executors.newFixedThreadPool(4);
        try {
            long now = System.currentTimeMillis();
            Future<?>[] futures = new Future<?>[4];
            for (int producer = 0; producer < futures.length; producer++) {
                final int number = producer;
                futures[producer] = producers.submit(() -> {
                    for (int index = 0; index < 20; index++) {
                        writer.append(now, "producer=" + number + " entry=" + index);
                    }
                });
            }
            for (Future<?> future : futures) {
                future.get(5L, TimeUnit.SECONDS);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertEquals(80L, writer.writeHistory(output).recordCount);
            String report = utf8(output);
            for (int producer = 0; producer < 4; producer++) {
                for (int index = 0; index < 20; index++) {
                    assertTrue(report.contains("producer=" + producer + " entry=" + index + "\n"));
                }
            }
            assertFalse(report.contains("HISTORY GAP:"));
        } finally {
            writer.close();
            producers.shutdownNow();
        }
    }

    @Test
    public void temporaryStorageFailureIsDisclosedWhenLoggingRecovers() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AsyncRollingLogWriter writer = new AsyncRollingLogWriter(() -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IOException("storage temporarily unavailable");
            }
            return new RollingLogStore(temporary.newFolder(), null);
        });
        try {
            long now = System.currentTimeMillis();
            writer.append(now, "lost during storage failure");
            writer.append(now, "storage recovered");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writer.writeHistory(output);
            assertTrue(utf8(output).contains("HISTORY GAP: storage write failed; 1 record(s)"));
            assertTrue(utf8(output).contains("storage recovered"));
            assertFalse(utf8(output).contains("lost during storage failure"));
        } finally {
            writer.close();
        }
    }

    @Test
    public void memoryBudgetRejectsOversizedBacklogWithoutTruncatingNormalSnapshots() throws Exception {
        File directory = temporary.newFolder();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AsyncRollingLogWriter writer = new AsyncRollingLogWriter(() -> {
            entered.countDown();
            await(release);
            return new RollingLogStore(directory, null);
        }, 128, 1_000L);
        try {
            long now = System.currentTimeMillis();
            writer.append(now, "initial");
            assertTrue(entered.await(5L, TimeUnit.SECONDS));
            writer.append(now, "too large for this test's tiny queue budget " + repeated('x', 1000));
            release.countDown();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writer.writeHistory(output);
            assertTrue(utf8(output).contains("HISTORY GAP:"));
            assertFalse(utf8(output).contains("too large for this test"));
        } finally {
            release.countDown();
            writer.close();
        }
    }

    @Test
    public void recordSafetyLimitIsExplicitAndDoesNotApplyToWholeHistory() throws Exception {
        AsyncRollingLogWriter writer = new AsyncRollingLogWriter(
                () -> new RollingLogStore(temporary.newFolder(), null));
        try {
            long now = System.currentTimeMillis();
            writer.append(now, "normal snapshot " + repeated('x', 80_000) + " normal tail");
            writer.append(now, repeated('y', AsyncRollingLogWriter.MAX_RECORD_CHARS + 1));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertEquals(2L, writer.writeHistory(output).recordCount);
            assertTrue(utf8(output).contains("normal tail"));
            assertTrue(utf8(output).contains("individual record exceeded"));
            assertTrue(utf8(output).length() > AsyncRollingLogWriter.MAX_RECORD_CHARS);
        } finally {
            writer.close();
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for test latch.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
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

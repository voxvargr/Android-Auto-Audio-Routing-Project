package dev.voxvargr.aaarp;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Logging cannot hold up routing or let a stalled disk grow an unbounded memory queue. */
final class AsyncRollingLogWriter {
    static final int MAX_RECORD_CHARS = 256 * 1024;
    private static final long MAX_PENDING_BYTES = 8L * 1024L * 1024L;
    private final StoreFactory factory;
    private final ThreadPoolExecutor executor;
    private final Object pendingLock = new Object();
    private final long maxPendingBytes;
    private long pendingBytes;
    private long lostRecords;
    private long lostFrom;
    private long lostUntil;
    private long failedRecords;
    private long failedFrom;
    private long failedUntil;
    private RollingLogStore store;

    AsyncRollingLogWriter(StoreFactory factory) {
        this(factory, 128, MAX_PENDING_BYTES);
    }

    AsyncRollingLogWriter(StoreFactory factory, int queueCapacity, long maxPendingBytes) {
        this.factory = factory;
        this.maxPendingBytes = maxPendingBytes;
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), task -> {
                    Thread thread = new Thread(task, "aaarp-history-writer");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    void append(long timestamp, String original) {
        String text = boundedRecord(original);
        // A UTF-16 character needs at most three UTF-8 bytes, plus four indentation bytes
        // if it is a newline. Count conservatively before doing any encoding on the worker.
        long reservedBytes = text.length() * 7L + 128L;
        synchronized (pendingLock) {
            if (reservedBytes > maxPendingBytes - pendingBytes) {
                recordQueueLoss(timestamp);
                return;
            }
            pendingBytes += reservedBytes;
            try {
                executor.execute(() -> {
                    synchronized (pendingLock) {
                        pendingBytes -= reservedBytes;
                    }
                    try {
                        RollingLogStore target = getStore();
                        writeLossNotice(target, timestamp);
                        target.append(timestamp, text);
                    } catch (IOException | RuntimeException ignored) {
                        // Persist a visible gap if storage becomes available again.
                        if (failedRecords++ == 0L) {
                            failedFrom = timestamp;
                        }
                        failedUntil = timestamp;
                    }
                });
            } catch (RejectedExecutionException ignored) {
                pendingBytes -= reservedBytes;
                recordQueueLoss(timestamp);
            }
        }
    }

    RollingLogStore.HistorySummary writeHistory(OutputStream output) throws IOException {
        FutureTask<RollingLogStore.HistorySummary> task = new FutureTask<>(() -> {
            long now = System.currentTimeMillis();
            RollingLogStore target = getStore();
            writeLossNotice(target, now);
            return target.writeHistory(output, now);
        });
        try {
            executor.execute(task);
        } catch (RejectedExecutionException full) {
            if (executor.isShutdown()) {
                throw new IOException("The history writer has stopped.", full);
            }
            try {
                // The caller is a background exporter. Appends never wait for queue space.
                executor.getQueue().put(task);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted before history export could start.", interrupted);
            }
        }
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    return task.get();
                } catch (InterruptedException ignored) {
                    // Keep the caller's output stream alive until the writer is finished.
                    interrupted = true;
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof IOException) {
                        throw (IOException) cause;
                    }
                    throw new IOException("Could not export the rolling history.", cause);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // For deterministic unit-test cleanup; the app retains its writer for the process lifetime.
    void close() {
        executor.shutdown();
    }

    private RollingLogStore getStore() throws IOException {
        if (store == null) {
            store = factory.create();
        }
        return store;
    }

    private void recordQueueLoss(long timestamp) {
        if (lostRecords++ == 0L) {
            lostFrom = timestamp;
        }
        lostUntil = timestamp;
    }

    private void writeLossNotice(RollingLogStore target, long timestamp) throws IOException {
        long count;
        long from;
        long until;
        synchronized (pendingLock) {
            count = lostRecords;
            from = lostFrom;
            until = lostUntil;
            lostRecords = 0L;
        }
        if (count > 0L) {
            try {
                target.append(timestamp, "HISTORY GAP: writer queue reached its safety limit; "
                        + count + " record(s) were not retained. UTC epoch milliseconds: "
                        + from + " through " + until + ".");
            } catch (IOException | RuntimeException failure) {
                synchronized (pendingLock) {
                    if (lostRecords == 0L) {
                        lostFrom = from;
                        lostUntil = until;
                    } else {
                        lostFrom = Math.min(lostFrom, from);
                        lostUntil = Math.max(lostUntil, until);
                    }
                    lostRecords += count;
                }
                throw failure;
            }
        }
        if (failedRecords > 0L) {
            target.append(timestamp, "HISTORY GAP: storage write failed; " + failedRecords
                    + " record(s) could not be retained. UTC epoch milliseconds: "
                    + failedFrom + " through " + failedUntil + ".");
            failedRecords = 0L;
        }
    }

    private static String boundedRecord(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_RECORD_CHARS) {
            return text;
        }
        int end = MAX_RECORD_CHARS;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end)
                + "\n[AAARP individual record exceeded the 256 KiB character safety limit; "
                + "this record was truncated. Rolling-history exports have no text-size limit.]";
    }

    interface StoreFactory {
        RollingLogStore create() throws IOException;
    }
}

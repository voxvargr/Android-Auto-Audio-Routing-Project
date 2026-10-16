package dev.voxvargr.aaarp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain-Java storage, confined to AsyncRollingLogWriter's one worker. */
final class RollingLogStore {
    static final long HOUR_MS = 60L * 60L * 1000L;
    static final long RETENTION_MS = 48L * HOUR_MS;
    static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    static final long MAX_TOTAL_BYTES = 128L * 1024L * 1024L;
    private static final long PRUNE_INTERVAL_MS = 60L * 1000L;
    private static final Pattern SEGMENT_NAME = Pattern.compile("AAARP-history-(\\d+)-(\\d+)\\.log");
    private static final Pattern LEGACY_NAME = Pattern.compile("AAARP-auto-(\\d{8})(?:-(\\d+))?\\.log");
    private static final String STATE_NAME = "history-state.properties";

    private final File directory;
    private final File legacyDirectory;
    private final long retentionMillis;
    private final long maxFileBytes;
    private final long maxTotalBytes;
    private final SimpleDateFormat timestampFormat;
    private List<Segment> segments;
    private long storedBytes;
    private long lastPruneAt = Long.MIN_VALUE;
    private long capacityEvictedUntil;
    private long capacityEvictedAt;
    private long capacityEvictedFiles;
    private Segment current;
    private String legacyWarning;
    private String stateWarning;

    RollingLogStore(File directory, File legacyDirectory) {
        this(directory, legacyDirectory, RETENTION_MS, MAX_FILE_BYTES, MAX_TOTAL_BYTES);
    }

    RollingLogStore(File directory, File legacyDirectory, long retentionMillis,
                    long maxFileBytes, long maxTotalBytes) {
        if (retentionMillis <= 0L || maxFileBytes <= 0L || maxTotalBytes < maxFileBytes) {
            throw new IllegalArgumentException("Invalid history storage limits.");
        }
        this.directory = directory;
        this.legacyDirectory = legacyDirectory;
        this.retentionMillis = retentionMillis;
        this.maxFileBytes = maxFileBytes;
        this.maxTotalBytes = maxTotalBytes;
        timestampFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        timestampFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        timestampFormat.setLenient(false);
    }

    synchronized void append(long timestamp, String text) throws IOException {
        initialize();
        String formatted = formatTimestamp(timestamp) + " | " + readable(text) + "\n";
        byte[] bytes = formatted.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxTotalBytes) {
            throw new IOException("An individual log record exceeds the history storage limit.");
        }
        long hour = timestamp - timestamp % HOUR_MS;
        boolean rotated = current == null || current.start != hour
                || (current.file.length() > 0L && current.file.length() + bytes.length > maxFileBytes);
        if (rotated) {
            current = segmentForHour(hour, bytes.length);
        }
        try (FileOutputStream output = new FileOutputStream(current.file, true)) {
            output.write(bytes);
        }
        storedBytes += bytes.length;
        if (rotated || storedBytes > maxTotalBytes
                || lastPruneAt == Long.MIN_VALUE || timestamp < lastPruneAt
                || timestamp - lastPruneAt >= PRUNE_INTERVAL_MS) {
            prune(timestamp);
        }
    }

    synchronized HistorySummary writeHistory(OutputStream output, long now) throws IOException {
        initialize();
        // Also enforce retention after an idle period or a process restart.
        prune(now);
        long cutoff = now - retentionMillis;
        Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        writer.write("\n--- Rolling AAARP history ---\n");
        writer.write("Requested window (UTC): " + formatTimestamp(cutoff) + " through "
                + formatTimestamp(now) + "\n");
        writer.write("Retention: " + retentionMillis / HOUR_MS + " hours; storage limit: "
                + maxTotalBytes + " bytes. History survives app updates and phone restarts.\n");
        writer.write("Only periods when AAARP produced records are covered; the first record is not "
                + "a promise of continuous monitoring.\n");
        if (legacyWarning != null) {
            writer.write(legacyWarning + "\n");
        }
        if (stateWarning != null) {
            writer.write(stateWarning + "\n");
        }
        if (capacityEvictedUntil > cutoff) {
            writer.write("WARNING: the disk limit removed " + capacityEvictedFiles
                    + " history file(s); records before approximately "
                    + formatTimestamp(capacityEvictedUntil) + " may be missing. Last removal: "
                    + formatTimestamp(capacityEvictedAt) + ".\n");
        }

        HistorySummary summary = new HistorySummary();
        summary.diskLimitReachedAt = capacityEvictedUntil > cutoff ? capacityEvictedAt : 0L;
        List<Segment> ordered = new ArrayList<>(segments);
        java.util.Collections.sort(ordered, SEGMENT_ORDER);
        boolean legacyHeadingWritten = false;
        boolean newHeadingWritten = false;
        for (Segment segment : ordered) {
            if (segment.legacy) {
                if (segment.file.lastModified() < cutoff) {
                    continue;
                }
                if (!legacyHeadingWritten) {
                    writer.write("\n--- Legacy logs from before the rolling-history upgrade ---\n");
                    writer.write("Recent legacy files are preserved intact and ordered by day and numeric "
                            + "rotation. Their original local timestamps did not include a time zone; "
                            + "they are not used to infer exact UTC coverage.\n");
                    legacyHeadingWritten = true;
                }
                writer.write("\n[" + segment.file.getName() + "]\n");
                copyLegacy(segment.file, writer);
                summary.legacyFileCount++;
                continue;
            }
            if (!newHeadingWritten) {
                writer.write("\n--- Recorded history (UTC; oldest segments first) ---\n");
                newHeadingWritten = true;
            }
            copyRetainedRecords(segment.file, writer, cutoff, now, summary);
        }
        writer.write("\n--- History coverage ---\n");
        writer.write("Recorded entries exported: " + summary.recordCount + "\n");
        writer.write("Legacy files exported intact: " + summary.legacyFileCount + "\n");
        if (summary.recordCount > 0L) {
            writer.write("First recorded entry (UTC): " + formatTimestamp(summary.firstTimestamp) + "\n");
            writer.write("Last recorded entry (UTC): " + formatTimestamp(summary.lastTimestamp) + "\n");
        } else {
            writer.write("No new-format entries are available in the requested window yet.\n");
        }
        if (summary.futureRecordCount > 0L) {
            writer.write("WARNING: " + summary.futureRecordCount + " entry/entries are dated after "
                    + "the export time, possibly because the phone clock changed. They were retained.\n");
        }
        if (summary.unparsedFileCount > 0L) {
            writer.write("WARNING: " + summary.unparsedFileCount + " file(s) contained records without "
                    + "a readable timestamp. Those records were included without an age filter.\n");
        }
        writer.write("--- End of rolling history ---\n");
        writer.flush();
        return summary;
    }

    private void initialize() throws IOException {
        if (segments != null) {
            return;
        }
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create the rolling history directory.");
        }
        loadState();
        List<Segment> found = new ArrayList<>();
        readSegments(directory, false, found);
        if (legacyDirectory != null && !directory.equals(legacyDirectory)) {
            try {
                readSegments(legacyDirectory, true, found);
            } catch (IOException | SecurityException unavailable) {
                legacyWarning = "WARNING: external legacy logs were unavailable during this process; "
                        + "new internal rolling history remains available.";
            }
        }
        segments = found;
        for (Segment segment : segments) {
            storedBytes += segment.file.length();
        }
    }

    private void readSegments(File dir, boolean legacyOnly, List<Segment> found) throws IOException {
        if (!dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            throw new IOException("Could not read history files in " + dir.getAbsolutePath());
        }
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            Matcher matcher = (legacyOnly ? LEGACY_NAME : SEGMENT_NAME).matcher(file.getName());
            if (!matcher.matches()) {
                continue;
            }
            try {
                long start = Long.parseLong(matcher.group(1));
                long index = matcher.group(2) == null ? 0L : Long.parseLong(matcher.group(2));
                found.add(new Segment(file, start, index, legacyOnly));
            } catch (NumberFormatException ignored) {
                // Unrelated files and impossible numeric filenames do not belong to this store.
            }
        }
    }

    private Segment segmentForHour(long hour, int recordBytes) {
        Segment latest = null;
        for (Segment segment : segments) {
            if (!segment.legacy && segment.start == hour
                    && (latest == null || segment.index > latest.index)) {
                latest = segment;
            }
        }
        if (latest != null && latest.file.length() + recordBytes <= maxFileBytes) {
            return latest;
        }
        long index = latest == null ? 0L : latest.index + 1L;
        File file = new File(directory, "AAARP-history-" + hour + "-" + index + ".log");
        Segment created = new Segment(file, hour, index, false);
        segments.add(created);
        return created;
    }

    private void prune(long now) throws IOException {
        long cutoff = now - retentionMillis;
        java.util.Collections.sort(segments, SEGMENT_ORDER);
        List<Segment> remaining = new ArrayList<>();
        storedBytes = 0L;
        for (Segment segment : segments) {
            boolean expired = segment.legacy ? segment.file.lastModified() < cutoff
                    : segment.start + HOUR_MS <= cutoff;
            if (expired && (segment.file.delete() || !segment.file.exists())) {
                if (segment == current) {
                    current = null;
                }
                continue;
            }
            remaining.add(segment);
            storedBytes += segment.file.length();
        }
        segments = remaining;
        boolean stateChanged = false;
        while (storedBytes > maxTotalBytes) {
            Segment oldest = oldestForEviction();
            if (oldest == null) {
                break;
            }
            long length = oldest.file.length();
            long evictedUntil = oldest.legacy ? oldest.file.lastModified() : oldest.start + HOUR_MS;
            if (oldest.file.delete() || !oldest.file.exists()) {
                storedBytes -= length;
                segments.remove(oldest);
                if (oldest == current) {
                    current = null;
                }
                capacityEvictedUntil = Math.max(capacityEvictedUntil, evictedUntil);
                capacityEvictedAt = now;
                capacityEvictedFiles++;
                stateChanged = true;
            } else {
                throw new IOException("Could not remove an old history file at the storage limit.");
            }
        }
        if (stateChanged) {
            saveState();
        }
        lastPruneAt = now;
    }

    private Segment oldestForEviction() {
        Segment oldest = null;
        for (Segment segment : segments) {
            long time = segment.legacy ? segment.file.lastModified() : segment.start;
            long oldestTime = oldest == null ? Long.MAX_VALUE
                    : oldest.legacy ? oldest.file.lastModified() : oldest.start;
            if (oldest == null || time < oldestTime
                    || (time == oldestTime && segment.index < oldest.index)) {
                oldest = segment;
            }
        }
        return oldest;
    }

    private void copyRetainedRecords(File file, Writer writer, long cutoff, long now,
                                     HistorySummary summary) throws IOException {
        boolean include = true;
        boolean unparsed = false;
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                Long timestamp = parseTimestamp(line);
                if (timestamp != null) {
                    include = timestamp >= cutoff;
                    if (include) {
                        if (summary.recordCount++ == 0L) {
                            summary.firstTimestamp = timestamp;
                            summary.lastTimestamp = timestamp;
                        } else {
                            summary.firstTimestamp = Math.min(summary.firstTimestamp, timestamp);
                            summary.lastTimestamp = Math.max(summary.lastTimestamp, timestamp);
                        }
                        if (timestamp > now) {
                            summary.futureRecordCount++;
                        }
                    }
                } else if (!line.startsWith("    ")) {
                    include = true;
                    unparsed = true;
                }
                if (include) {
                    writer.write(line);
                    writer.write('\n');
                }
            }
        }
        if (unparsed) {
            summary.unparsedFileCount++;
        }
    }

    private static void copyLegacy(File file, Writer writer) throws IOException {
        try (InputStreamReader input = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                writer.write(buffer, 0, count);
            }
        }
        writer.write('\n');
    }

    private Long parseTimestamp(String line) {
        if (line.length() < 27 || !line.startsWith(" | ", 24)) {
            return null;
        }
        try {
            return timestampFormat.parse(line.substring(0, 24)).getTime();
        } catch (ParseException ignored) {
            return null;
        }
    }

    private void loadState() {
        File stateFile = new File(directory, STATE_NAME);
        if (!stateFile.isFile()) {
            return;
        }
        Properties properties = new Properties();
        try {
            try (FileInputStream input = new FileInputStream(stateFile)) {
                properties.load(input);
            }
            capacityEvictedUntil = Long.parseLong(properties.getProperty("capacityEvictedUntil", "0"));
            capacityEvictedAt = Long.parseLong(properties.getProperty("capacityEvictedAt", "0"));
            capacityEvictedFiles = Long.parseLong(properties.getProperty("capacityEvictedFiles", "0"));
        } catch (IOException | IllegalArgumentException ignored) {
            // A damaged state file must not make the recorded history inaccessible.
            capacityEvictedUntil = 0L;
            capacityEvictedAt = 0L;
            capacityEvictedFiles = 0L;
            stateWarning = "WARNING: the history coverage metadata was unreadable; "
                    + "older disk-limit losses cannot be determined. Recorded files are preserved.";
        }
    }

    private void saveState() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("capacityEvictedUntil", Long.toString(capacityEvictedUntil));
        properties.setProperty("capacityEvictedAt", Long.toString(capacityEvictedAt));
        properties.setProperty("capacityEvictedFiles", Long.toString(capacityEvictedFiles));
        try (FileOutputStream output = new FileOutputStream(new File(directory, STATE_NAME))) {
            properties.store(output, "AAARP rolling history coverage");
        }
    }

    private String formatTimestamp(long timestamp) {
        return timestampFormat.format(new Date(timestamp));
    }

    private static String readable(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n')
                .replace("\n", "\n    ");
    }

    private static final Comparator<Segment> SEGMENT_ORDER = (left, right) -> {
        if (left.legacy != right.legacy) {
            return left.legacy ? -1 : 1;
        }
        int startOrder = Long.compare(left.start, right.start);
        return startOrder != 0 ? startOrder : Long.compare(left.index, right.index);
    };

    private static final class Segment {
        final File file;
        final long start;
        final long index;
        final boolean legacy;

        Segment(File file, long start, long index, boolean legacy) {
            this.file = file;
            this.start = start;
            this.index = index;
            this.legacy = legacy;
        }
    }

    static final class HistorySummary {
        long recordCount;
        long firstTimestamp;
        long lastTimestamp;
        long diskLimitReachedAt;
        long legacyFileCount;
        long futureRecordCount;
        long unparsedFileCount;
    }
}

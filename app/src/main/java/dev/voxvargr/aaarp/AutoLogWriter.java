package dev.voxvargr.aaarp;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

final class AutoLogWriter {
    private static final String DIR_NAME = "AAARP-auto-logs";
    private static final SimpleDateFormat FILE_FORMAT = new SimpleDateFormat("yyyyMMdd", Locale.US);
    private static final SimpleDateFormat LINE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_LOG_AGE_MS = 14L * 24L * 60L * 60L * 1000L;

    private AutoLogWriter() {
    }

    static synchronized void append(Context context, String text) {
        try {
            File file = logFile(context);
            String line = LINE_FORMAT.format(new Date()) + " " + clean(text) + "\n";
            try (FileOutputStream stream = new FileOutputStream(file, true)) {
                stream.write(line.getBytes(StandardCharsets.UTF_8));
            }
            pruneLogs(file.getParentFile());
        } catch (IOException ignored) {
            // Logging must never disrupt routing.
        }
    }

    static String location(Context context) {
        File dir = logDir(context);
        return dir == null ? "auto log directory unavailable" : dir.getAbsolutePath();
    }

    private static File logFile(Context context) throws IOException {
        File dir = logDir(context);
        if (dir == null) {
            throw new IOException("Auto log directory unavailable.");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create auto log directory.");
        }
        String day = FILE_FORMAT.format(new Date());
        File file = new File(dir, "AAARP-auto-" + day + ".log");
        if (!file.exists() || file.length() < MAX_FILE_BYTES) {
            return file;
        }
        for (int index = 1; index < 100; index++) {
            File rotated = new File(dir, "AAARP-auto-" + day + "-" + index + ".log");
            if (!rotated.exists() || rotated.length() < MAX_FILE_BYTES) {
                return rotated;
            }
        }
        return file;
    }

    private static File logDir(Context context) {
        File documents = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (documents == null) {
            return null;
        }
        return new File(documents, DIR_NAME);
    }

    private static String clean(String text) {
        if (text == null || text.length() == 0) {
            return "";
        }
        return text.replace('\r', ' ').replace('\n', ' ');
    }

    private static void pruneLogs(File dir) {
        if (dir == null) {
            return;
        }
        File[] files = dir.listFiles((file, name) ->
                name.startsWith("AAARP-auto-") && name.endsWith(".log"));
        if (files == null || files.length == 0) {
            return;
        }

        long now = System.currentTimeMillis();
        for (File file : files) {
            if (now - file.lastModified() > MAX_LOG_AGE_MS) {
                // Best effort; failure just leaves the file for the next prune.
                file.delete();
            }
        }

        files = dir.listFiles((file, name) ->
                name.startsWith("AAARP-auto-") && name.endsWith(".log"));
        if (files == null || files.length == 0) {
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                long rightModified = right.lastModified();
                long leftModified = left.lastModified();
                if (rightModified > leftModified) {
                    return 1;
                }
                if (rightModified < leftModified) {
                    return -1;
                }
                return right.getName().compareTo(left.getName());
            }
        });
        long totalBytes = 0L;
        for (File file : files) {
            totalBytes += Math.max(0L, file.length());
            if (totalBytes > MAX_TOTAL_BYTES) {
                file.delete();
            }
        }
    }
}

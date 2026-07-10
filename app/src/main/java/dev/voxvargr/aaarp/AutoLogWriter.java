package dev.voxvargr.aaarp;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class AutoLogWriter {
    private static final String DIR_NAME = "AAARP-auto-logs";
    private static final SimpleDateFormat FILE_FORMAT = new SimpleDateFormat("yyyyMMdd", Locale.US);
    private static final SimpleDateFormat LINE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private AutoLogWriter() {
    }

    static void append(Context context, String text) {
        try {
            File file = logFile(context);
            String line = LINE_FORMAT.format(new Date()) + " " + clean(text) + "\n";
            try (FileOutputStream stream = new FileOutputStream(file, true)) {
                stream.write(line.getBytes(StandardCharsets.UTF_8));
            }
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
        return new File(dir, "AAARP-auto-" + FILE_FORMAT.format(new Date()) + ".log");
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
}

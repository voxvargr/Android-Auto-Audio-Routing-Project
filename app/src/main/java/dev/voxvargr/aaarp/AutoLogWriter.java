package dev.voxvargr.aaarp;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

final class AutoLogWriter {
    private static final String DIR_NAME = "AAARP-history";
    private static final String LEGACY_DIR_NAME = "AAARP-auto-logs";
    private static AsyncRollingLogWriter writer;

    private AutoLogWriter() {
    }

    static void append(Context context, String text) {
        getWriter(context).append(System.currentTimeMillis(), text);
    }

    // Call from a background thread. The queue writes all previously accepted entries before
    // streaming the history, without assembling the complete report in memory.
    static RollingLogStore.HistorySummary writeHistory(Context context, OutputStream output)
            throws IOException {
        return getWriter(context).writeHistory(output);
    }

    static String retentionDescription() {
        return "Last 48 hours of recorded history; 128 MiB storage limit. "
                + "Any earlier loss is identified in the saved report.";
    }

    static String location(Context context) {
        return new File(context.getFilesDir(), DIR_NAME).getAbsolutePath();
    }

    private static synchronized AsyncRollingLogWriter getWriter(Context context) {
        if (writer == null) {
            Context application = context.getApplicationContext();
            writer = new AsyncRollingLogWriter(() -> {
                File documents = application.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                File legacy = documents == null ? null : new File(documents, LEGACY_DIR_NAME);
                return new RollingLogStore(new File(application.getFilesDir(), DIR_NAME), legacy);
            });
        }
        return writer;
    }
}

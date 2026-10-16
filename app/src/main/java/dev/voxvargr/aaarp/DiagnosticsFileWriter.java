package dev.voxvargr.aaarp;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class DiagnosticsFileWriter {
    interface ContentWriter {
        void write(OutputStream output) throws IOException;
    }

    private DiagnosticsFileWriter() {
    }

    static String write(Context context, String text) throws IOException {
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        return write(context, output -> output.write(bytes));
    }

    static String write(Context context, ContentWriter content) throws IOException {
        String fileName = "AAARP-diagnostics-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date())
                + ".txt";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return writeToDownloadsMediaStore(context, fileName, content);
        }
        return writeToAppDownloads(context, fileName, content);
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private static String writeToDownloadsMediaStore(Context context, String fileName,
                                                     ContentWriter content) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AAARP");
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Downloads provider did not return a file location.");
        }

        try {
            OutputStream opened = resolver.openOutputStream(uri);
            if (opened == null) {
                throw new IOException("Could not open diagnostics output stream.");
            }
            try (OutputStream outputStream = new BufferedOutputStream(opened)) {
                content.write(outputStream);
            }
            ContentValues completed = new ContentValues();
            completed.put(MediaStore.Downloads.IS_PENDING, 0);
            if (resolver.update(uri, completed, null, null) <= 0) {
                throw new IOException("Could not publish the completed diagnostics file.");
            }
        } catch (IOException | RuntimeException failure) {
            try {
                resolver.delete(uri, null, null);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        return "Downloads/AAARP/" + fileName;
    }

    private static String writeToAppDownloads(Context context, String fileName,
                                              ContentWriter content) throws IOException {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            throw new IOException("App downloads directory is unavailable.");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create app downloads directory.");
        }
        File file = new File(dir, fileName);
        writeFile(file, content);
        return file.getAbsolutePath();
    }

    static void writeFile(File file, ContentWriter content) throws IOException {
        try {
            try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(file))) {
                content.write(outputStream);
            }
        } catch (IOException | RuntimeException failure) {
            if (file.exists() && !file.delete()) {
                failure.addSuppressed(new IOException("Could not remove the incomplete diagnostics file."));
            }
            throw failure;
        }
    }
}

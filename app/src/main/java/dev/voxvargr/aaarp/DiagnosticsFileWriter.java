package dev.voxvargr.aaarp;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class DiagnosticsFileWriter {
    private DiagnosticsFileWriter() {
    }

    static String write(Context context, String text) throws IOException {
        String fileName = "AAARP-diagnostics-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".txt";
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return writeToDownloadsMediaStore(context, fileName, bytes);
        }
        return writeToAppDownloads(context, fileName, bytes);
    }

    private static String writeToDownloadsMediaStore(Context context, String fileName, byte[] bytes) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AAARP");

        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("Downloads provider did not return a file location.");
        }

        try (OutputStream outputStream = resolver.openOutputStream(uri)) {
            if (outputStream == null) {
                throw new IOException("Could not open diagnostics output stream.");
            }
            outputStream.write(bytes);
        }
        return "Downloads/AAARP/" + fileName;
    }

    private static String writeToAppDownloads(Context context, String fileName, byte[] bytes) throws IOException {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            throw new IOException("App downloads directory is unavailable.");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create app downloads directory.");
        }
        File file = new File(dir, fileName);
        try (OutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(bytes);
        }
        return file.getAbsolutePath();
    }
}

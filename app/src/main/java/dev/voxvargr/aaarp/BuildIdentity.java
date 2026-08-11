package dev.voxvargr.aaarp;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class BuildIdentity {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private BuildIdentity() {
    }

    static String describe(Context context) {
        StringBuilder summary = new StringBuilder();
        summary.append("App package: ").append(context.getPackageName()).append('\n');

        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : info.versionCode;
            summary.append("App version: ")
                    .append(info.versionName == null ? "unknown" : info.versionName)
                    .append(" (code ").append(versionCode).append(")\n");
            summary.append("First installed: ").append(formatTime(info.firstInstallTime)).append('\n');
            summary.append("Last updated: ").append(formatTime(info.lastUpdateTime)).append('\n');
        } catch (Exception e) {
            summary.append("App version: unavailable (").append(shortMessage(e)).append(")\n");
        }

        try {
            summary.append("Base APK SHA-256: ")
                    .append(sha256(context.getApplicationInfo().sourceDir))
                    .append('\n');
        } catch (Exception e) {
            summary.append("Base APK SHA-256: unavailable (").append(shortMessage(e)).append(")\n");
        }
        return summary.toString();
    }

    private static String sha256(String path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32 * 1024];
        try (InputStream input = new BufferedInputStream(new FileInputStream(path))) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        byte[] value = digest.digest();
        char[] output = new char[value.length * 2];
        for (int index = 0; index < value.length; index++) {
            int unsigned = value[index] & 0xff;
            output[index * 2] = HEX[unsigned >>> 4];
            output[index * 2 + 1] = HEX[unsigned & 0x0f];
        }
        return new String(output);
    }

    private static String formatTime(long timeMs) {
        if (timeMs <= 0L) {
            return "unknown";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date(timeMs));
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().length() == 0
                ? e.getClass().getSimpleName()
                : message.replace('\n', ' ').replace('\r', ' ');
    }
}

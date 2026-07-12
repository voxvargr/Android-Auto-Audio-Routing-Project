package dev.voxvargr.aaarp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class SettingsBackup {
    private static final String FORMAT = "dev.voxvargr.aaarp.settings";
    private static final int VERSION = 1;

    private SettingsBackup() {
    }

    static int write(Context context, OutputStream outputStream) throws IOException {
        try {
            SharedPreferences prefs = AppPrefs.get(context);
            TreeMap<String, Object> sortedPrefs = new TreeMap<>();
            sortedPrefs.putAll(prefs.getAll());

            JSONObject root = new JSONObject();
            JSONObject preferences = new JSONObject();
            root.put("format", FORMAT);
            root.put("version", VERSION);
            root.put("createdAtMillis", System.currentTimeMillis());
            root.put("preferences", preferences);
            root.put("access", buildAccessSnapshot(context, prefs));

            for (Map.Entry<String, Object> entry : sortedPrefs.entrySet()) {
                if (!shouldBackupKey(entry.getKey())) {
                    continue;
                }
                JSONObject value = encodePreferenceValue(entry.getValue());
                if (value != null) {
                    preferences.put(entry.getKey(), value);
                }
            }

            try (OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
                writer.write(root.toString(2));
                writer.write('\n');
            }
            return preferences.length();
        } catch (JSONException e) {
            throw new IOException("Could not build settings backup.", e);
        }
    }

    static RestoreResult restore(Context context, InputStream inputStream) throws IOException {
        try {
            JSONObject root = new JSONObject(readUtf8(inputStream));
            String format = root.optString("format", "");
            if (!FORMAT.equals(format)) {
                throw new IOException("This is not an AAARP settings backup.");
            }
            int version = root.optInt("version", -1);
            if (version != VERSION) {
                throw new IOException("Unsupported AAARP settings backup version: " + version);
            }

            JSONObject preferences = root.optJSONObject("preferences");
            if (preferences == null) {
                throw new IOException("Settings backup does not contain preferences.");
            }

            SharedPreferences.Editor editor = AppPrefs.get(context).edit();
            editor.clear();
            JSONArray names = preferences.names();
            int restored = 0;
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.getString(i);
                    if (!shouldBackupKey(key)) {
                        continue;
                    }
                    JSONObject value = preferences.optJSONObject(key);
                    if (value != null && restorePreferenceValue(editor, key, value)) {
                        restored++;
                    }
                }
            }
            if (!editor.commit()) {
                throw new IOException("Android rejected the restored settings.");
            }
            RootRestoreResult rootRestoreResult = restoreAccessWithRootIfRequested(
                    context,
                    root.optJSONObject("access")
            );
            return new RestoreResult(restored, rootRestoreResult);
        } catch (JSONException e) {
            throw new IOException("Could not read settings backup.", e);
        }
    }

    private static JSONObject buildAccessSnapshot(Context context, SharedPreferences prefs) throws JSONException {
        JSONObject access = new JSONObject();
        JSONObject runtimePermissions = new JSONObject();
        access.put("rootDiagnosticsEnabled", safeBoolean(prefs, AppPrefs.USE_ROOT, false));
        access.put("batteryOptimizationExempt", isBatteryOptimizationExempt(context));
        for (String permission : restorableRuntimePermissions(context)) {
            if (isRuntimePermissionRelevant(permission)) {
                runtimePermissions.put(permission, isPermissionGranted(context, permission));
            }
        }
        access.put("runtimePermissions", runtimePermissions);
        return access;
    }

    private static RootRestoreResult restoreAccessWithRootIfRequested(Context context,
                                                                      JSONObject access) throws JSONException {
        if (access == null) {
            return RootRestoreResult.skipped("No root access snapshot in backup.");
        }
        boolean rootDiagnosticsEnabled = access.optBoolean("rootDiagnosticsEnabled", false);
        if (!rootDiagnosticsEnabled) {
            return RootRestoreResult.skipped("Root diagnostics were off in the backup.");
        }

        RootShell rootShell = new RootShell();
        if (!rootShell.isAvailable()) {
            return RootRestoreResult.unavailable();
        }

        ArrayList<String> grantedPermissions = new ArrayList<>();
        JSONObject runtimePermissions = access.optJSONObject("runtimePermissions");
        if (runtimePermissions != null) {
            JSONArray names = runtimePermissions.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String permission = names.getString(i);
                    if (runtimePermissions.optBoolean(permission, false)
                            && isRuntimePermissionRelevant(permission)) {
                        grantedPermissions.add(permission);
                    }
                }
            }
        }
        Collections.sort(grantedPermissions);

        boolean restoreBatteryExemption = access.optBoolean("batteryOptimizationExempt", false);
        if (grantedPermissions.isEmpty() && !restoreBatteryExemption) {
            return RootRestoreResult.skipped("No granted runtime permissions or battery exemption were in the backup.");
        }

        RootShell.ShellResult result = rootShell.restoreAppAccess(
                context.getPackageName(),
                grantedPermissions,
                restoreBatteryExemption
        );
        return RootRestoreResult.attempted(result, grantedPermissions.size(), restoreBatteryExemption);
    }

    private static boolean shouldBackupKey(String key) {
        return !AppPrefs.MONITOR_ENABLED.equals(key);
    }

    private static boolean safeBoolean(SharedPreferences prefs, String key, boolean fallback) {
        try {
            return prefs.getBoolean(key, fallback);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean isRuntimePermissionRelevant(String permission) {
        if (Manifest.permission.BLUETOOTH_CONNECT.equals(permission)) {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        }
        if (Manifest.permission.POST_NOTIFICATIONS.equals(permission)) {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        }
        return true;
    }

    private static ArrayList<String> restorableRuntimePermissions(Context context) {
        ArrayList<String> permissions = new ArrayList<>();
        try {
            PackageInfo packageInfo = currentPackageInfo(context);
            if (packageInfo.requestedPermissions == null) {
                return permissions;
            }
            for (String permission : packageInfo.requestedPermissions) {
                if (isDangerousPermission(context, permission)) {
                    permissions.add(permission);
                }
            }
            Collections.sort(permissions);
            return permissions;
        } catch (PackageManager.NameNotFoundException e) {
            return permissions;
        }
    }

    private static PackageInfo currentPackageInfo(Context context) throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.getPackageManager().getPackageInfo(
                    context.getPackageName(),
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS)
            );
        }
        return currentPackageInfoLegacy(context);
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo currentPackageInfoLegacy(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(
                context.getPackageName(),
                PackageManager.GET_PERMISSIONS
        );
    }

    @SuppressWarnings("deprecation")
    private static boolean isDangerousPermission(Context context, String permission) {
        try {
            PermissionInfo permissionInfo = context.getPackageManager().getPermissionInfo(permission, 0);
            int baseProtection = permissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
            return baseProtection == PermissionInfo.PROTECTION_DANGEROUS;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static boolean isPermissionGranted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isBatteryOptimizationExempt(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        PowerManager powerManager = context.getSystemService(PowerManager.class);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private static JSONObject encodePreferenceValue(Object rawValue) throws JSONException {
        JSONObject value = new JSONObject();
        if (rawValue instanceof String) {
            value.put("type", "string");
            value.put("value", rawValue);
            return value;
        }
        if (rawValue instanceof Boolean) {
            value.put("type", "boolean");
            value.put("value", rawValue);
            return value;
        }
        if (rawValue instanceof Set<?>) {
            value.put("type", "stringSet");
            JSONArray array = new JSONArray();
            ArrayList<String> strings = new ArrayList<>();
            for (Object item : (Set<?>) rawValue) {
                if (item instanceof String) {
                    strings.add((String) item);
                }
            }
            Collections.sort(strings);
            for (String item : strings) {
                array.put(item);
            }
            value.put("value", array);
            return value;
        }
        return null;
    }

    private static boolean restorePreferenceValue(SharedPreferences.Editor editor,
                                                  String key, JSONObject value) throws JSONException {
        String type = value.optString("type", "");
        if ("string".equals(type)) {
            editor.putString(key, value.optString("value", ""));
            return true;
        }
        if ("boolean".equals(type)) {
            editor.putBoolean(key, value.optBoolean("value", false));
            return true;
        }
        if ("stringSet".equals(type)) {
            JSONArray array = value.optJSONArray("value");
            Set<String> set = new HashSet<>();
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    set.add(array.optString(i, ""));
                }
            }
            editor.putStringSet(key, set);
            return true;
        }
        return false;
    }

    private static String readUtf8(InputStream inputStream) throws IOException {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8)
        )) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                body.append(buffer, 0, count);
            }
        }
        return body.toString();
    }

    static final class RestoreResult {
        final int preferenceCount;
        final RootRestoreResult rootRestoreResult;

        RestoreResult(int preferenceCount, RootRestoreResult rootRestoreResult) {
            this.preferenceCount = preferenceCount;
            this.rootRestoreResult = rootRestoreResult;
        }
    }

    static final class RootRestoreResult {
        final boolean attempted;
        final boolean rootAvailable;
        final boolean success;
        final int runtimePermissionCount;
        final boolean batteryExemptionAttempted;
        final String message;
        final String output;

        private RootRestoreResult(boolean attempted, boolean rootAvailable, boolean success,
                                  int runtimePermissionCount, boolean batteryExemptionAttempted,
                                  String message, String output) {
            this.attempted = attempted;
            this.rootAvailable = rootAvailable;
            this.success = success;
            this.runtimePermissionCount = runtimePermissionCount;
            this.batteryExemptionAttempted = batteryExemptionAttempted;
            this.message = message == null ? "" : message;
            this.output = output == null ? "" : output;
        }

        static RootRestoreResult skipped(String message) {
            return new RootRestoreResult(false, false, false, 0, false, message, "");
        }

        static RootRestoreResult unavailable() {
            return new RootRestoreResult(true, false, false, 0, false,
                    "Root diagnostics were on in the backup, but su is not available now.", "");
        }

        static RootRestoreResult attempted(RootShell.ShellResult result,
                                           int runtimePermissionCount,
                                           boolean batteryExemptionAttempted) {
            return new RootRestoreResult(true, true, result.success, runtimePermissionCount,
                    batteryExemptionAttempted, result.success
                    ? "Root access restore finished."
                    : "Root access restore returned errors.", result.output);
        }
    }
}

package dev.voxvargr.aaarp;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class SettingsBackup {
    private static final String FORMAT = "dev.voxvargr.aaarp.settings";
    private static final int VERSION = 2;
    private static final int MIN_SUPPORTED_VERSION = 1;
    static final int MAX_BACKUP_CHARACTERS = 1_048_576;
    /** AOSP UserHandle.PER_USER_RANGE; public SDK stubs do not expose myUserId(). */
    private static final int PER_USER_RANGE = 100_000;

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
                try {
                    preferences.put(entry.getKey(), encodePreferenceValue(entry.getValue()));
                } catch (IllegalArgumentException e) {
                    throw new IOException("Could not back up setting " + entry.getKey() + ".", e);
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
        ValidatedBackup backup;
        try {
            backup = validateBackupDocument(new JSONObject(readUtf8(inputStream)));
        } catch (JSONException e) {
            throw new IOException("Could not read settings backup.", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Settings backup contains an invalid value: " + e.getMessage(), e);
        }

        SharedPreferences prefs = AppPrefs.get(context);
        if (!RoutingMonitorService.beginSettingsRestoreGate()) {
            throw new IOException("Another settings restore is already in progress.");
        }
        try {
            stopMonitorForRestore(context, prefs);

            // Validation above is deliberately complete before this point. One editor commit then
            // replaces the preferences as a unit; if Android reports a write failure, restore the
            // exact pre-import snapshot before reporting the error.
            replacePreferencesWithRollback(prefs, backup.preferences);
            RootRestoreResult rootRestoreResult = restoreAccessWithRootIfRequested(
                    context,
                    backup.access
            );
            return new RestoreResult(backup.importedPreferenceCount, rootRestoreResult);
        } finally {
            RoutingMonitorService.finishSettingsRestoreGate();
        }
    }

    private static ValidatedBackup validateBackupDocument(JSONObject root)
            throws IOException, JSONException {
        Object rawFormat = requiredValue(root, "format", "Settings backup does not identify its format.");
        if (!(rawFormat instanceof String) || !FORMAT.equals(rawFormat)) {
            throw new IOException("This is not an AAARP settings backup.");
        }

        Object rawVersion = requiredValue(root, "version", "Settings backup does not identify its version.");
        final int version;
        try {
            version = (Integer) SettingsBackupValueCodec.decode(
                    SettingsBackupValueCodec.TYPE_INTEGER,
                    rawVersion
            );
        } catch (IllegalArgumentException e) {
            throw new IOException("Settings backup version is invalid.", e);
        }
        if (version < MIN_SUPPORTED_VERSION || version > VERSION) {
            throw new IOException("Unsupported AAARP settings backup version: " + version);
        }

        if (root.has("createdAtMillis")) {
            try {
                long createdAtMillis = (Long) SettingsBackupValueCodec.decode(
                        SettingsBackupValueCodec.TYPE_LONG,
                        requiredValue(
                                root,
                                "createdAtMillis",
                                "Settings backup creation time is invalid."
                        )
                );
                if (createdAtMillis < 0L) {
                    throw new IOException("Settings backup creation time is invalid.");
                }
            } catch (IllegalArgumentException e) {
                throw new IOException("Settings backup creation time is invalid.", e);
            }
        }

        Object rawPreferences = requiredValue(
                root,
                "preferences",
                "Settings backup does not contain preferences."
        );
        if (!(rawPreferences instanceof JSONObject)) {
            throw new IOException("Settings backup preferences are malformed.");
        }

        TreeMap<String, Object> decodedPreferences = new TreeMap<>();
        JSONObject preferences = (JSONObject) rawPreferences;
        JSONArray names = preferences.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.getString(i);
                Object rawEntry = preferences.get(key);
                if (!(rawEntry instanceof JSONObject)) {
                    throw new IOException("Setting " + key + " is malformed.");
                }
                Object decoded = decodePreferenceValue(key, (JSONObject) rawEntry);
                // Excluded keys are still validated so a malformed document can never reach
                // the destructive phase, but they are intentionally not restored.
                if (shouldBackupKey(key)) {
                    decodedPreferences.put(key, decoded);
                }
            }
        }

        AccessSnapshot access = validateAccessSnapshot(root);
        int importedPreferenceCount = decodedPreferences.size();
        // All migrations happen against the validated in-memory map. The monitor flag is
        // then included explicitly in the same destructive commit, so no service can observe
        // an imported legacy or running state between commits.
        AppPrefs.migrateRestoredPreferences(decodedPreferences);
        decodedPreferences.put(AppPrefs.MONITOR_ENABLED, false);
        return new ValidatedBackup(decodedPreferences, access, importedPreferenceCount);
    }

    private static void stopMonitorForRestore(Context context, SharedPreferences prefs)
            throws IOException {
        // Publish the stopped state synchronously before asking the live service to unwind.
        // Its apply loop treats this preference as the authority, so it cannot re-apply a route
        // while the restore-specific cleanup waits for already-started root work to finish.
        markMonitorStopped(prefs);
        RoutingMonitorService.stopSynchronouslyForSettingsRestore(context);
    }

    static void markMonitorStopped(SharedPreferences prefs) throws IOException {
        if (!prefs.edit().putBoolean(AppPrefs.MONITOR_ENABLED, false).commit()) {
            throw new IOException("Could not mark the routing monitor stopped before restore.");
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

    private static AccessSnapshot validateAccessSnapshot(JSONObject root)
            throws IOException, JSONException {
        if (!root.has("access")) {
            return null;
        }
        Object rawAccess = root.get("access");
        if (!(rawAccess instanceof JSONObject)) {
            throw new IOException("Settings backup access snapshot is malformed.");
        }
        JSONObject access = (JSONObject) rawAccess;
        boolean rootDiagnosticsEnabled = optionalStrictBoolean(
                access,
                "rootDiagnosticsEnabled",
                false
        );
        boolean batteryOptimizationExempt = optionalStrictBoolean(
                access,
                "batteryOptimizationExempt",
                false
        );

        TreeMap<String, Boolean> runtimePermissions = new TreeMap<>();
        if (access.has("runtimePermissions")) {
            Object rawPermissions = access.get("runtimePermissions");
            if (!(rawPermissions instanceof JSONObject)) {
                throw new IOException("Settings backup runtime permissions are malformed.");
            }
            JSONObject permissions = (JSONObject) rawPermissions;
            JSONArray names = permissions.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String permission = names.getString(i);
                    Object rawGranted = permissions.get(permission);
                    if (!(rawGranted instanceof Boolean)) {
                        throw new IOException("Runtime permission " + permission
                                + " does not contain a Boolean grant state.");
                    }
                    runtimePermissions.put(permission, (Boolean) rawGranted);
                }
            }
        }
        return new AccessSnapshot(
                rootDiagnosticsEnabled,
                batteryOptimizationExempt,
                runtimePermissions
        );
    }

    private static RootRestoreResult restoreAccessWithRootIfRequested(Context context,
                                                                      AccessSnapshot access) {
        if (access == null) {
            return RootRestoreResult.skipped("No root access snapshot in backup.");
        }
        if (!access.rootDiagnosticsEnabled) {
            return RootRestoreResult.skipped("Root diagnostics were off in the backup.");
        }

        Set<String> currentlyRestorablePermissions = new HashSet<>(
                restorableRuntimePermissions(context)
        );
        ArrayList<String> grantedPermissions = new ArrayList<>();
        for (Map.Entry<String, Boolean> permission : access.runtimePermissions.entrySet()) {
            if (Boolean.TRUE.equals(permission.getValue())
                    && isRuntimePermissionRelevant(permission.getKey())
                    && currentlyRestorablePermissions.contains(permission.getKey())) {
                grantedPermissions.add(permission.getKey());
            }
        }
        grantedPermissions = orderedPermissionsForRestore(grantedPermissions);

        boolean restoreBatteryExemption = access.batteryOptimizationExempt;
        if (grantedPermissions.isEmpty() && !restoreBatteryExemption) {
            return RootRestoreResult.skipped("No granted runtime permissions or battery exemption were in the backup.");
        }

        RootShell rootShell = new RootShell();
        if (!rootShell.isAvailable()) {
            return RootRestoreResult.unavailable();
        }

        RootShell.ShellResult result = rootShell.restoreAppAccess(
                context.getPackageName(),
                grantedPermissions,
                restoreBatteryExemption,
                userIdForUid(Process.myUid())
        );
        return RootRestoreResult.attempted(result, grantedPermissions.size(), restoreBatteryExemption);
    }

    static ArrayList<String> orderedPermissionsForRestore(Iterable<String> permissions) {
        ArrayList<String> ordered = new ArrayList<>();
        for (String permission : permissions) {
            if (permission != null && !ordered.contains(permission)) {
                ordered.add(permission);
            }
        }
        Collections.sort(ordered, (left, right) -> {
            int priorityComparison = Integer.compare(
                    permissionRestorePriority(left),
                    permissionRestorePriority(right)
            );
            return priorityComparison != 0 ? priorityComparison : left.compareTo(right);
        });
        return ordered;
    }

    private static int permissionRestorePriority(String permission) {
        if (Manifest.permission.ACCESS_COARSE_LOCATION.equals(permission)
                || Manifest.permission.ACCESS_FINE_LOCATION.equals(permission)) {
            return 0;
        }
        if (Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(permission)) {
            return 2;
        }
        return 1;
    }

    static int userIdForUid(int uid) {
        return uid < 0 ? 0 : uid / PER_USER_RANGE;
    }

    private static boolean shouldBackupKey(String key) {
        return !AppPrefs.MONITOR_ENABLED.equals(key)
                && !AppPrefs.isLegacyMediaRelayKey(key);
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
        SettingsBackupValueCodec.EncodedValue encoded = SettingsBackupValueCodec.encode(rawValue);
        JSONObject value = new JSONObject();
        value.put("type", encoded.type);
        if (SettingsBackupValueCodec.TYPE_STRING_SET.equals(encoded.type)) {
            JSONArray array = new JSONArray();
            for (Object item : (List<?>) encoded.value) {
                array.put(item);
            }
            value.put("value", array);
        } else {
            value.put("value", encoded.value);
        }
        return value;
    }

    private static Object decodePreferenceValue(String key, JSONObject value)
            throws IOException, JSONException {
        Object rawType = requiredValue(
                value,
                "type",
                "Setting " + key + " does not identify its type."
        );
        if (!(rawType instanceof String)) {
            throw new IOException("Setting " + key + " has a malformed type.");
        }
        Object rawValue = requiredValue(
                value,
                "value",
                "Setting " + key + " does not contain a value."
        );
        if (rawValue instanceof JSONArray) {
            JSONArray array = (JSONArray) rawValue;
            ArrayList<Object> items = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                items.add(array.get(i));
            }
            rawValue = items;
        }
        try {
            return SettingsBackupValueCodec.decode((String) rawType, rawValue);
        } catch (IllegalArgumentException e) {
            throw new IOException("Setting " + key + " is invalid: " + e.getMessage(), e);
        }
    }

    private static Object requiredValue(JSONObject object, String name, String missingMessage)
            throws IOException, JSONException {
        if (!object.has(name) || object.isNull(name)) {
            throw new IOException(missingMessage);
        }
        return object.get(name);
    }

    private static boolean optionalStrictBoolean(JSONObject object, String name, boolean fallback)
            throws IOException, JSONException {
        if (!object.has(name)) {
            return fallback;
        }
        Object value = object.get(name);
        if (!(value instanceof Boolean)) {
            throw new IOException("Settings backup field " + name + " must be Boolean.");
        }
        return (Boolean) value;
    }

    static void replacePreferencesWithRollback(SharedPreferences prefs,
                                               Map<String, Object> replacement)
            throws IOException {
        Map<String, Object> previous = snapshotPreferences(prefs);
        try {
            commitPreferences(prefs, replacement);
        } catch (IOException | RuntimeException restoreFailure) {
            IOException result = restoreFailure instanceof IOException
                    ? (IOException) restoreFailure
                    : new IOException("Android rejected the restored settings.", restoreFailure);
            try {
                commitPreferences(prefs, previous);
            } catch (IOException | RuntimeException rollbackFailure) {
                result.addSuppressed(rollbackFailure);
            }
            throw result;
        }
    }

    private static Map<String, Object> snapshotPreferences(SharedPreferences prefs)
            throws IOException {
        TreeMap<String, Object> snapshot = new TreeMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            try {
                snapshot.put(
                        entry.getKey(),
                        SettingsBackupValueCodec.copyPreferenceValue(entry.getValue())
                );
            } catch (IllegalArgumentException e) {
                throw new IOException("Existing setting " + entry.getKey()
                        + " cannot be safely preserved before restore.", e);
            }
        }
        return snapshot;
    }

    private static void commitPreferences(SharedPreferences prefs, Map<String, Object> values)
            throws IOException {
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            putPreferenceValue(editor, entry.getKey(), entry.getValue());
        }
        if (!editor.commit()) {
            throw new IOException("Android rejected the restored settings.");
        }
    }

    private static void putPreferenceValue(SharedPreferences.Editor editor,
                                           String key,
                                           Object value) throws IOException {
        if (value instanceof String) {
            editor.putString(key, (String) value);
            return;
        }
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
            return;
        }
        if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
            return;
        }
        if (value instanceof Long) {
            editor.putLong(key, (Long) value);
            return;
        }
        if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
            return;
        }
        if (value instanceof Set<?>) {
            Set<String> strings = new HashSet<>();
            for (Object item : (Set<?>) value) {
                if (!(item instanceof String)) {
                    throw new IOException("Setting " + key + " contains a non-string set value.");
                }
                strings.add((String) item);
            }
            editor.putStringSet(key, strings);
            return;
        }
        throw new IOException("Setting " + key + " has an unsupported value type.");
    }

    static String readUtf8(InputStream inputStream) throws IOException {
        return readUtf8(inputStream, MAX_BACKUP_CHARACTERS);
    }

    static String readUtf8(InputStream inputStream, int maximumCharacters) throws IOException {
        if (maximumCharacters < 1) {
            throw new IllegalArgumentException("maximumCharacters must be positive");
        }
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8)
        )) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (body.length() > maximumCharacters - count) {
                    throw new IOException("Settings backup is larger than the supported "
                            + maximumCharacters + " characters.");
                }
                body.append(buffer, 0, count);
            }
        }
        return body.toString();
    }

    private static final class ValidatedBackup {
        final Map<String, Object> preferences;
        final AccessSnapshot access;
        final int importedPreferenceCount;

        ValidatedBackup(Map<String, Object> preferences,
                        AccessSnapshot access,
                        int importedPreferenceCount) {
            this.preferences = preferences;
            this.access = access;
            this.importedPreferenceCount = importedPreferenceCount;
        }
    }

    private static final class AccessSnapshot {
        final boolean rootDiagnosticsEnabled;
        final boolean batteryOptimizationExempt;
        final Map<String, Boolean> runtimePermissions;

        AccessSnapshot(boolean rootDiagnosticsEnabled,
                       boolean batteryOptimizationExempt,
                       Map<String, Boolean> runtimePermissions) {
            this.rootDiagnosticsEnabled = rootDiagnosticsEnabled;
            this.batteryOptimizationExempt = batteryOptimizationExempt;
            this.runtimePermissions = runtimePermissions;
        }
    }

    static final class RestoreResult {
        final int preferenceCount;
        final RootRestoreResult rootRestoreResult;
        final boolean success;

        RestoreResult(int preferenceCount, RootRestoreResult rootRestoreResult) {
            this.preferenceCount = preferenceCount;
            this.rootRestoreResult = rootRestoreResult;
            this.success = rootRestoreResult == null || rootRestoreResult.success;
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
            return new RootRestoreResult(false, false, true, 0, false, message, "");
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

package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.content.SharedPreferences;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class SettingsBackupPreferencesTest {
    @Test
    public void backupReaderAcceptsDocumentAtConfiguredLimit() throws Exception {
        String document = "12345678";

        assertEquals(document, SettingsBackup.readUtf8(
                new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)),
                document.length()
        ));
    }

    @Test
    public void backupReaderRejectsDocumentBeyondConfiguredLimit() {
        try {
            SettingsBackup.readUtf8(
                    new ByteArrayInputStream("123456789".getBytes(StandardCharsets.UTF_8)),
                    8
            );
            fail("Expected an oversized settings document to be rejected.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("larger than"));
        }
    }

    @Test
    public void replacementWritesEverySupportedPreferenceTypeInOneCommit() throws Exception {
        FakeSharedPreferences prefs = new FakeSharedPreferences(mapOf("old", "value"));
        Map<String, Object> replacement = new HashMap<>();
        replacement.put("string", "value");
        replacement.put("boolean", true);
        replacement.put("integer", 17);
        replacement.put("long", 9_007_199_254_740_993L);
        replacement.put("float", 1.25f);
        replacement.put("set", new HashSet<>(Arrays.asList("one", "two")));

        SettingsBackup.replacePreferencesWithRollback(prefs, replacement);

        assertEquals(replacement, prefs.getAll());
        assertEquals(1, prefs.commitCount);
    }

    @Test
    public void failedCommitRestoresExactPreImportSnapshot() {
        Map<String, Object> original = new HashMap<>();
        original.put("profile", "CARLINK");
        original.put(AppPrefs.MONITOR_ENABLED, true);
        original.put("count", 8);
        original.put("set", new HashSet<>(Arrays.asList("a", "b")));
        FakeSharedPreferences prefs = new FakeSharedPreferences(original);
        try {
            SettingsBackup.markMonitorStopped(prefs);
        } catch (IOException unexpected) {
            throw new AssertionError(unexpected);
        }
        original.put(AppPrefs.MONITOR_ENABLED, false);
        prefs.commitCount = 0;
        prefs.failedCommitsRemaining = 1;

        try {
            SettingsBackup.replacePreferencesWithRollback(
                    prefs,
                    mapOf("profile", "replacement")
            );
            fail("Expected the failed restore commit to be reported.");
        } catch (IOException expected) {
            assertEquals("Android rejected the restored settings.", expected.getMessage());
        }

        assertEquals(original, prefs.getAll());
        assertEquals(2, prefs.commitCount);
    }

    @Test
    public void monitorIsSynchronouslyMarkedStoppedBeforeReplacement() throws Exception {
        FakeSharedPreferences prefs = new FakeSharedPreferences(
                mapOf(AppPrefs.MONITOR_ENABLED, true)
        );

        SettingsBackup.markMonitorStopped(prefs);

        assertEquals(false, prefs.getAll().get(AppPrefs.MONITOR_ENABLED));
        assertEquals(1, prefs.commitCount);
    }

    @Test
    public void foregroundLocationPermissionsAreOrderedBeforeBackgroundLocation() {
        java.util.List<String> ordered = SettingsBackup.orderedPermissionsForRestore(Arrays.asList(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
        ));

        assertEquals(Arrays.asList(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ), ordered);
    }

    @Test
    public void overallRestoreStatusRequiresRequestedRootAccessToVerify() {
        SettingsBackup.RestoreResult skipped = new SettingsBackup.RestoreResult(
                4,
                SettingsBackup.RootRestoreResult.skipped("Nothing requested.")
        );
        SettingsBackup.RestoreResult failed = new SettingsBackup.RestoreResult(
                4,
                SettingsBackup.RootRestoreResult.attempted(
                        RootShell.ShellResult.failure("verification failed"),
                        2,
                        true
                )
        );
        SettingsBackup.RestoreResult verified = new SettingsBackup.RestoreResult(
                4,
                SettingsBackup.RootRestoreResult.attempted(
                        new RootShell.ShellResult(true, 0, "verified"),
                        2,
                        true
                )
        );

        assertTrue(skipped.success);
        assertFalse(failed.success);
        assertTrue(verified.success);
    }

    @Test
    public void appUidMapsToItsAndroidUserInsteadOfAssumingOwnerUser() {
        assertEquals(0, SettingsBackup.userIdForUid(10_345));
        assertEquals(10, SettingsBackup.userIdForUid(1_012_345));
        assertEquals(11, SettingsBackup.userIdForUid(1_112_345));
    }

    @Test
    public void unsupportedReplacementCannotPartiallyClearExistingPreferences() {
        Map<String, Object> original = mapOf("keep", "unchanged");
        FakeSharedPreferences prefs = new FakeSharedPreferences(original);
        Map<String, Object> malformed = new HashMap<>();
        malformed.put("valid", "would-have-been-written");
        malformed.put("unsupported", 1.5d);

        try {
            SettingsBackup.replacePreferencesWithRollback(prefs, malformed);
            fail("Expected unsupported value to be rejected.");
        } catch (IOException expected) {
            // Expected.
        }

        assertEquals(original, prefs.getAll());
        assertEquals(1, prefs.commitCount);
    }

    @Test
    public void unsupportedExistingValueStopsBeforeAnyEditorCommit() {
        Map<String, Object> malformedExisting = new HashMap<>();
        malformedExisting.put("unsupported", 1.5d);
        FakeSharedPreferences prefs = new FakeSharedPreferences(malformedExisting);

        try {
            SettingsBackup.replacePreferencesWithRollback(prefs, mapOf("new", "value"));
            fail("Expected unsafe existing value to stop restore.");
        } catch (IOException expected) {
            // Expected.
        }

        assertEquals(malformedExisting, prefs.getAll());
        assertEquals(0, prefs.commitCount);
    }

    private static Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> result = new HashMap<>();
        result.put(key, value);
        return result;
    }

    private static final class FakeSharedPreferences implements SharedPreferences {
        private Map<String, Object> values;
        int failedCommitsRemaining;
        int commitCount;

        FakeSharedPreferences(Map<String, Object> initial) {
            values = copy(initial);
        }

        @Override
        public Map<String, ?> getAll() {
            return copy(values);
        }

        @Override
        public String getString(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : fallback;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Set<String> getStringSet(String key, Set<String> fallback) {
            Object value = values.get(key);
            return value instanceof Set<?> ? new HashSet<>((Set<String>) value) : fallback;
        }

        @Override
        public int getInt(String key, int fallback) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : fallback;
        }

        @Override
        public long getLong(String key, long fallback) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : fallback;
        }

        @Override
        public float getFloat(String key, float fallback) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : fallback;
        }

        @Override
        public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new FakeEditor(copy(values));
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }

        private final class FakeEditor implements Editor {
            private final Map<String, Object> pending;

            FakeEditor(Map<String, Object> pending) {
                this.pending = pending;
            }

            @Override
            public Editor putString(String key, String value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> value) {
                pending.put(key, new HashSet<>(value));
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor remove(String key) {
                pending.remove(key);
                return this;
            }

            @Override
            public Editor clear() {
                pending.clear();
                return this;
            }

            @Override
            public boolean commit() {
                commitCount++;
                values = copy(pending);
                if (failedCommitsRemaining > 0) {
                    failedCommitsRemaining--;
                    return false;
                }
                return true;
            }

            @Override
            public void apply() {
                commit();
            }
        }

        private static Map<String, Object> copy(Map<String, ?> source) {
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<String, ?> entry : source.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Set<?>) {
                    value = new HashSet<>((Set<?>) value);
                }
                result.put(entry.getKey(), value);
            }
            return result;
        }
    }
}

package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SettingsBackupValueCodecTest {
    @Test
    public void everySharedPreferencesValueTypeRoundTrips() {
        assertRoundTrip("music", SettingsBackupValueCodec.TYPE_STRING);
        assertRoundTrip(true, SettingsBackupValueCodec.TYPE_BOOLEAN);
        assertRoundTrip(Integer.MIN_VALUE, SettingsBackupValueCodec.TYPE_INTEGER);
        assertRoundTrip(Integer.MAX_VALUE, SettingsBackupValueCodec.TYPE_INTEGER);
        assertRoundTrip(Long.MIN_VALUE, SettingsBackupValueCodec.TYPE_LONG);
        assertRoundTrip(Long.MAX_VALUE, SettingsBackupValueCodec.TYPE_LONG);
        assertRoundTrip(-12.75f, SettingsBackupValueCodec.TYPE_FLOAT);
        assertRoundTrip(Float.MIN_VALUE, SettingsBackupValueCodec.TYPE_FLOAT);
        assertRoundTrip(Float.MAX_VALUE, SettingsBackupValueCodec.TYPE_FLOAT);

        Set<String> set = new HashSet<>(Arrays.asList("CARLINK", "Earbuds", ""));
        SettingsBackupValueCodec.EncodedValue encoded = SettingsBackupValueCodec.encode(set);
        assertEquals(SettingsBackupValueCodec.TYPE_STRING_SET, encoded.type);
        assertEquals(Arrays.asList("", "CARLINK", "Earbuds"), encoded.value);
        assertEquals(set, SettingsBackupValueCodec.decode(encoded.type, encoded.value));
    }

    @Test
    public void versionOneValueTypesRemainDecodable() {
        assertEquals("legacy", SettingsBackupValueCodec.decode("string", "legacy"));
        assertEquals(false, SettingsBackupValueCodec.decode("boolean", false));
        assertEquals(
                new HashSet<>(Arrays.asList("one", "two")),
                SettingsBackupValueCodec.decode("stringSet", Arrays.asList("one", "two"))
        );
    }

    @Test
    public void copiedStringSetDoesNotAliasExistingPreferences() {
        Set<String> original = new HashSet<>(Arrays.asList("one", "two"));
        Object copy = SettingsBackupValueCodec.copyPreferenceValue(original);
        assertEquals(original, copy);
        assertNotSame(original, copy);
    }

    @Test
    public void malformedOrUnsupportedValuesAreRejected() {
        assertRejected(() -> SettingsBackupValueCodec.encode(null));
        assertRejected(() -> SettingsBackupValueCodec.encode(1.5d));
        assertRejected(() -> SettingsBackupValueCodec.encode(Float.NaN));
        assertRejected(() -> SettingsBackupValueCodec.encode(Float.POSITIVE_INFINITY));

        Set<Object> mixedSet = new HashSet<>();
        mixedSet.add("valid");
        mixedSet.add(4);
        assertRejected(() -> SettingsBackupValueCodec.encode(mixedSet));

        assertRejected(() -> SettingsBackupValueCodec.decode("unknown", "value"));
        assertRejected(() -> SettingsBackupValueCodec.decode("string", true));
        assertRejected(() -> SettingsBackupValueCodec.decode("boolean", "true"));
        assertRejected(() -> SettingsBackupValueCodec.decode("integer", 2.5d));
        assertRejected(() -> SettingsBackupValueCodec.decode("integer", Long.MAX_VALUE));
        assertRejected(() -> SettingsBackupValueCodec.decode("long", "1"));
        assertRejected(() -> SettingsBackupValueCodec.decode("float", Double.MAX_VALUE));

        List<Object> mixedList = new ArrayList<>();
        mixedList.add("valid");
        mixedList.add(false);
        assertRejected(() -> SettingsBackupValueCodec.decode("stringSet", mixedList));
    }

    @Test
    public void integralJsonNumberShapesDecodeWithoutLoss() {
        assertEquals(42, SettingsBackupValueCodec.decode("integer", 42L));
        assertEquals(42, SettingsBackupValueCodec.decode("integer", 42.0d));
        assertEquals(9_007_199_254_740_993L,
                SettingsBackupValueCodec.decode("long", 9_007_199_254_740_993L));
    }

    private static void assertRoundTrip(Object original, String expectedType) {
        SettingsBackupValueCodec.EncodedValue encoded = SettingsBackupValueCodec.encode(original);
        assertEquals(expectedType, encoded.type);
        assertEquals(original, SettingsBackupValueCodec.decode(encoded.type, encoded.value));
    }

    private static void assertRejected(ThrowingRunnable action) {
        try {
            action.run();
            fail("Expected invalid backup value to be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }

    private interface ThrowingRunnable {
        void run();
    }
}

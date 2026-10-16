package dev.voxvargr.aaarp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict, Android-independent conversion for values supported by SharedPreferences. */
final class SettingsBackupValueCodec {
    static final String TYPE_STRING = "string";
    static final String TYPE_BOOLEAN = "boolean";
    static final String TYPE_STRING_SET = "stringSet";
    static final String TYPE_INTEGER = "integer";
    static final String TYPE_LONG = "long";
    static final String TYPE_FLOAT = "float";

    private SettingsBackupValueCodec() {
    }

    static EncodedValue encode(Object rawValue) {
        if (rawValue instanceof String) {
            return new EncodedValue(TYPE_STRING, rawValue);
        }
        if (rawValue instanceof Boolean) {
            return new EncodedValue(TYPE_BOOLEAN, rawValue);
        }
        if (rawValue instanceof Integer) {
            return new EncodedValue(TYPE_INTEGER, rawValue);
        }
        if (rawValue instanceof Long) {
            return new EncodedValue(TYPE_LONG, rawValue);
        }
        if (rawValue instanceof Float) {
            float value = (Float) rawValue;
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Float preference is not finite.");
            }
            return new EncodedValue(TYPE_FLOAT, rawValue);
        }
        if (rawValue instanceof Set<?>) {
            ArrayList<String> strings = new ArrayList<>();
            for (Object item : (Set<?>) rawValue) {
                if (!(item instanceof String)) {
                    throw new IllegalArgumentException("String-set preference contains a non-string value.");
                }
                strings.add((String) item);
            }
            Collections.sort(strings);
            return new EncodedValue(TYPE_STRING_SET, strings);
        }
        throw new IllegalArgumentException("Unsupported preference value type: "
                + (rawValue == null ? "null" : rawValue.getClass().getName()));
    }

    static Object decode(String type, Object rawValue) {
        if (TYPE_STRING.equals(type)) {
            if (!(rawValue instanceof String)) {
                throw typeMismatch(type);
            }
            return rawValue;
        }
        if (TYPE_BOOLEAN.equals(type)) {
            if (!(rawValue instanceof Boolean)) {
                throw typeMismatch(type);
            }
            return rawValue;
        }
        if (TYPE_STRING_SET.equals(type)) {
            if (!(rawValue instanceof List<?>)) {
                throw typeMismatch(type);
            }
            Set<String> set = new HashSet<>();
            for (Object item : (List<?>) rawValue) {
                if (!(item instanceof String)) {
                    throw new IllegalArgumentException("String-set preference contains a non-string value.");
                }
                set.add((String) item);
            }
            return set;
        }
        if (TYPE_INTEGER.equals(type)) {
            long value = exactLong(rawValue, type);
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Integer preference is out of range.");
            }
            return (int) value;
        }
        if (TYPE_LONG.equals(type)) {
            return exactLong(rawValue, type);
        }
        if (TYPE_FLOAT.equals(type)) {
            if (!(rawValue instanceof Number)) {
                throw typeMismatch(type);
            }
            double doubleValue = ((Number) rawValue).doubleValue();
            float value = ((Number) rawValue).floatValue();
            if (!Double.isFinite(doubleValue) || !Float.isFinite(value)) {
                throw new IllegalArgumentException("Float preference is not finite or is out of range.");
            }
            return value;
        }
        throw new IllegalArgumentException("Unsupported preference type: " + type);
    }

    static Object copyPreferenceValue(Object rawValue) {
        EncodedValue encoded = encode(rawValue);
        return decode(encoded.type, encoded.value);
    }

    private static long exactLong(Object rawValue, String type) {
        if (!(rawValue instanceof Number)) {
            throw typeMismatch(type);
        }
        Number number = (Number) rawValue;
        double doubleValue = number.doubleValue();
        long longValue = number.longValue();
        if (!Double.isFinite(doubleValue) || doubleValue != (double) longValue) {
            throw new IllegalArgumentException(type + " preference is not an exact whole number.");
        }
        return longValue;
    }

    private static IllegalArgumentException typeMismatch(String type) {
        return new IllegalArgumentException("Preference value does not match declared type " + type + ".");
    }

    static final class EncodedValue {
        final String type;
        final Object value;

        EncodedValue(String type, Object value) {
            this.type = type;
            this.value = value;
        }
    }
}

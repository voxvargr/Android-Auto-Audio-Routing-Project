package dev.voxvargr.aaarp;

import android.content.Context;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AndroidAutoConnection {
    private static final Pattern SSID_PATTERN = Pattern.compile("SSID\\s*(?:=|:)\\s*\"?([^,\\n\"]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern BSSID_PATTERN = Pattern.compile(
            "BSSID\\s*(?:=|:)\\s*([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})",
            Pattern.CASE_INSENSITIVE
    );

    private final String key;
    private final String label;
    private final boolean specific;

    AndroidAutoConnection(String key, String label, boolean specific) {
        this.key = clean(key, "default");
        this.label = clean(label, "Default");
        this.specific = specific;
    }

    static AndroidAutoConnection fallback() {
        return new AndroidAutoConnection("default", "Default", false);
    }

    static AndroidAutoConnection detect(Context context, RootShell rootShell) {
        RootShell.ShellResult result = rootShell.currentWifiIdentity();
        AndroidAutoConnection rootWifi = fromWifiText(result.output);
        if (rootWifi.specific()) {
            return rootWifi;
        }

        return fallback();
    }

    private static AndroidAutoConnection fromWifiText(String text) {
        String currentWifi = currentWifiDetails(text);
        if (!looksLikeWirelessAndroidAutoWifi(currentWifi)) {
            return fallback();
        }

        String ssid = firstMatch(SSID_PATTERN, currentWifi);
        String bssid = firstMatch(BSSID_PATTERN, currentWifi);
        if (!hasUsefulWifiValue(ssid) && !hasUsefulWifiValue(bssid)) {
            return fallback();
        }
        return fromParts(ssid, bssid);
    }

    private static AndroidAutoConnection fromParts(String ssid, String bssid) {
        String cleanSsid = cleanSsid(ssid);
        String cleanBssid = clean(bssid, "");
        String label = hasUsefulWifiValue(cleanSsid) ? cleanSsid : cleanBssid;
        String key = normalize(label + "|" + cleanBssid);
        return new AndroidAutoConnection("wifi_" + Integer.toHexString(key.hashCode()), label, true);
    }

    String key() {
        return key;
    }

    String label() {
        return label;
    }

    boolean specific() {
        return specific;
    }

    private static String firstMatch(Pattern pattern, String text) {
        if (text == null) {
            return "";
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String currentWifiDetails(String text) {
        if (text == null) {
            return "";
        }
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            if (line.indexOf("WifiInfo:") >= 0 || line.indexOf("mWifiInfo") >= 0) {
                return line;
            }
        }
        return "";
    }

    private static boolean looksLikeWirelessAndroidAutoWifi(String currentWifi) {
        String lower = clean(currentWifi, "").toLowerCase(Locale.US);
        if (lower.length() == 0) {
            return false;
        }
        if (lower.contains("ephemeral: true") || lower.contains("is_ephemeral=true")) {
            return true;
        }
        if (lower.contains("requesting package name:")
                && !lower.contains("requesting package name: <none>")) {
            return true;
        }
        return lower.contains("trusted: false")
                && (lower.contains("restricted: true") || lower.contains("net id: -1"));
    }

    private static boolean hasUsefulWifiValue(String value) {
        String clean = clean(value, "");
        return clean.length() > 0
                && !"<unknown ssid>".equalsIgnoreCase(clean)
                && !"02:00:00:00:00:00".equals(clean)
                && !"00:00:00:00:00:00".equals(clean);
    }

    private static String cleanSsid(String ssid) {
        String value = clean(ssid, "");
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String normalize(String value) {
        return clean(value, "")
                .toLowerCase(Locale.US)
                .replace(":", "")
                .replace(" ", "")
                .replace("-", "");
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.trim().length() == 0 || "null".equalsIgnoreCase(value.trim())) {
            return fallback;
        }
        return value.trim();
    }
}

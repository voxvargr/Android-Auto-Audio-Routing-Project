package dev.voxvargr.aaarp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

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
        AndroidAutoConnection publicWifi = detectPublicWifi(context);
        if (publicWifi.specific()) {
            return publicWifi;
        }

        RootShell.ShellResult result = rootShell.currentWifiIdentity();
        return fromWifiText(result.output);
    }

    private static AndroidAutoConnection detectPublicWifi(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return fallback();
            }

            WifiManager wifiManager = context.getSystemService(WifiManager.class);
            WifiInfo info = wifiManager == null ? null : wifiManager.getConnectionInfo();
            if (info == null) {
                return fallback();
            }

            String ssid = cleanSsid(info.getSSID());
            String bssid = clean(info.getBSSID(), "");
            if (!hasUsefulWifiValue(ssid) && !hasUsefulWifiValue(bssid)) {
                return fallback();
            }

            return fromParts(ssid, bssid);
        } catch (RuntimeException e) {
            return fallback();
        }
    }

    private static AndroidAutoConnection fromWifiText(String text) {
        String ssid = firstMatch(SSID_PATTERN, text);
        String bssid = firstMatch(BSSID_PATTERN, text);
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

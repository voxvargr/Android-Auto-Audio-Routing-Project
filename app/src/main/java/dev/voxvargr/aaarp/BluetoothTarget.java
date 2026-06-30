package dev.voxvargr.aaarp;

import java.util.Locale;

final class BluetoothTarget {
    private final String alias;
    private final String name;
    private final String address;
    private final String deviceClass;
    private final RouteDevice matchedRoute;

    BluetoothTarget(String alias, String name, String address, String deviceClass, RouteDevice matchedRoute) {
        this.alias = clean(alias, "Unnamed device");
        this.name = clean(name, "");
        this.address = clean(address, "");
        this.deviceClass = clean(deviceClass, "class unknown");
        this.matchedRoute = matchedRoute;
    }

    String key() {
        return normalize(address).length() == 0
                ? normalize(alias + "|" + name)
                : normalize(address);
    }

    String matchQuery() {
        StringBuilder query = new StringBuilder();
        appendPart(query, alias);
        appendPart(query, name);
        appendPart(query, address);
        return query.toString();
    }

    String displayLabel() {
        StringBuilder label = new StringBuilder(alias);
        if (name.length() > 0 && !normalize(name).equals(normalize(alias))) {
            label.append(" (").append(name).append(")");
        }
        if (address.length() > 0) {
            label.append(" - ").append(address);
        }
        if (matchedRoute != null) {
            label.append(" - route visible");
        }
        return label.toString();
    }

    String inventoryLabel() {
        StringBuilder label = new StringBuilder()
                .append(alias);
        if (name.length() > 0 && !normalize(name).equals(normalize(alias))) {
            label.append(" / ").append(name);
        }
        if (address.length() > 0) {
            label.append(" - ").append(address);
        }
        label.append(" - ").append(deviceClass);
        if (matchedRoute != null) {
            label.append("\n  route: ").append(matchedRoute.detailLabel());
        }
        return label.toString();
    }

    private static void appendPart(StringBuilder builder, String value) {
        if (value == null || value.trim().length() == 0) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('|');
        }
        builder.append(value.trim());
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.trim().length() == 0 || "no address".equals(value)) {
            return fallback;
        }
        return value.trim();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.US)
                .replace(":", "")
                .replace("-", "")
                .replace(" ", "")
                .trim();
    }
}

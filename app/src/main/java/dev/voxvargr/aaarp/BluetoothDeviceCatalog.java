package dev.voxvargr.aaarp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

final class BluetoothDeviceCatalog {
    private BluetoothDeviceCatalog() {
    }

    static String describe(Context context, List<RouteDevice> routeDevices) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        StringBuilder output = new StringBuilder();

        output.append("Bluetooth inventory\n");
        if (adapter == null) {
            return output.append("No Bluetooth adapter reported.").toString();
        }

        try {
            output.append("Adapter: ").append(adapter.isEnabled() ? "on" : "off").append('\n');
            output.append("Profiles: ")
                    .append(profileState(adapter, BluetoothProfile.HEADSET, "HFP/headset"))
                    .append(", ")
                    .append(profileState(adapter, BluetoothProfile.A2DP, "A2DP/media"));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                output.append(", ")
                        .append(profileState(adapter, BluetoothProfile.HEARING_AID, "hearing aid"));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                output.append(", ")
                        .append(profileState(adapter, BluetoothProfile.LE_AUDIO, "LE audio"));
            }
            output.append('\n');

            List<BluetoothTarget> targets = listTargets(context, routeDevices);
            if (targets.isEmpty()) {
                output.append("No paired Bluetooth devices reported.\n");
            } else {
                output.append("Paired devices:\n");
                for (BluetoothTarget target : targets) {
                    output.append("- ").append(target.inventoryLabel()).append('\n');
                }
            }

            output.append("Available AAARP routes:\n");
            for (RouteDevice routeDevice : routeDevices) {
                output.append("- ").append(routeDevice.detailLabel()).append('\n');
            }
            return output.toString().trim();
        } catch (SecurityException e) {
            return output.append("Bluetooth permission blocked inventory: ")
                    .append(e.getMessage())
                    .toString();
        }
    }

    static List<BluetoothTarget> listTargets(Context context, List<RouteDevice> routeDevices) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        List<BluetoothTarget> targets = new ArrayList<>();
        if (adapter == null) {
            return targets;
        }

        try {
            Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();
            if (bondedDevices == null || bondedDevices.isEmpty()) {
                return targets;
            }
            for (BluetoothDevice device : bondedDevices) {
                targets.add(new BluetoothTarget(
                        deviceAlias(device),
                        deviceName(device),
                        deviceAddress(device),
                        deviceClass(device),
                        findMatchingRoute(device, routeDevices)
                ));
            }
            Collections.sort(targets, Comparator.comparing(BluetoothTarget::displayLabel, String.CASE_INSENSITIVE_ORDER));
            return targets;
        } catch (SecurityException e) {
            return targets;
        }
    }

    private static RouteDevice findMatchingRoute(BluetoothDevice device, List<RouteDevice> routeDevices) {
        String deviceAddress = normalize(deviceAddress(device));
        String deviceName = normalize(deviceName(device));
        String deviceAlias = normalize(deviceAlias(device));
        for (RouteDevice routeDevice : routeDevices) {
            if (!routeDevice.isBluetooth()) {
                continue;
            }
            String routeAddress = normalize(routeDevice.address());
            String routeName = normalize(routeDevice.name());
            if (deviceAddress.length() > 0 && deviceAddress.equals(routeAddress)) {
                return routeDevice;
            }
            if (deviceName.length() > 0 && (routeName.contains(deviceName) || deviceName.contains(routeName))) {
                return routeDevice;
            }
            if (deviceAlias.length() > 0 && (routeName.contains(deviceAlias) || deviceAlias.contains(routeName))) {
                return routeDevice;
            }
        }
        return null;
    }

    private static String profileState(BluetoothAdapter adapter, int profile, String label) {
        return label + "=" + connectionState(adapter.getProfileConnectionState(profile));
    }

    private static String connectionState(int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:
                return "connected";
            case BluetoothProfile.STATE_CONNECTING:
                return "connecting";
            case BluetoothProfile.STATE_DISCONNECTING:
                return "disconnecting";
            case BluetoothProfile.STATE_DISCONNECTED:
            default:
                return "disconnected";
        }
    }

    private static String deviceName(BluetoothDevice device) {
        String name = device.getName();
        return name == null || name.length() == 0 ? "Unnamed device" : name;
    }

    private static String deviceAlias(BluetoothDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            String alias = device.getAlias();
            if (alias != null && alias.length() > 0) {
                return alias;
            }
        }
        return deviceName(device);
    }

    private static String deviceAddress(BluetoothDevice device) {
        String address = device.getAddress();
        return address == null || address.length() == 0 ? "no address" : address;
    }

    private static String deviceClass(BluetoothDevice device) {
        BluetoothClass bluetoothClass = device.getBluetoothClass();
        if (bluetoothClass == null) {
            return "class unknown";
        }
        return String.format(Locale.US, "class 0x%04X", bluetoothClass.getDeviceClass());
    }

    private static String normalize(String value) {
        if (value == null || "no address".equals(value)) {
            return "";
        }
        return value.toLowerCase(Locale.US)
                .replace(":", "")
                .replace("-", "")
                .replace(" ", "")
                .trim();
    }
}

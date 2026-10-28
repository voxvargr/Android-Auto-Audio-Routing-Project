package dev.voxvargr.aaarp;

import android.media.AudioDeviceInfo;

import java.util.Locale;

final class RouteDevice {
    private final int id;
    private final int type;
    private final String name;
    private final boolean source;
    private final boolean sink;
    private final boolean realDevice;

    RouteDevice(int id, int type, String name, boolean source, boolean sink, boolean realDevice) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.source = source;
        this.sink = sink;
        this.realDevice = realDevice;
    }

    static RouteDevice from(AudioDeviceInfo device) {
        CharSequence productName = device.getProductName();
        String label = productName == null || productName.length() == 0
                ? "Audio device " + device.getId()
                : productName.toString();
        return new RouteDevice(
                device.getId(),
                device.getType(),
                label,
                device.isSource(),
                device.isSink(),
                true
        );
    }

    static RouteDevice legacyBluetoothSco() {
        return new RouteDevice(-1, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Legacy Bluetooth SCO", true, true, false);
    }

    int id() {
        return id;
    }

    int type() {
        return type;
    }

    String key() {
        return String.format(Locale.US, "%d:%d:%s", type, id, name);
    }

    boolean isRealDevice() {
        return realDevice;
    }

    boolean isBluetooth() {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                || type == AudioDeviceInfo.TYPE_BLE_BROADCAST;
    }

    String displayLabel() {
        String direction;
        if (source && sink) {
            direction = "input/output";
        } else if (source) {
            direction = "input";
        } else if (sink) {
            direction = "output";
        } else {
            direction = "route";
        }
        return name + " - " + typeLabel() + " - " + direction;
    }

    String typeLabel() {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth SCO";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "Bluetooth A2DP";
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return "BLE headset";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return "BLE speaker";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Wired headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return "Wired headphones";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return "Earpiece";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "Speaker";
            default:
                return "Type " + type;
        }
    }
}

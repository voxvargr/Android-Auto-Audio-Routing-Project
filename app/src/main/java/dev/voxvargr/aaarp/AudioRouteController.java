package dev.voxvargr.aaarp;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

final class AudioRouteController {
    private final Context context;
    private final AudioManager audioManager;
    private final RootShell rootShell;

    AudioRouteController(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.rootShell = new RootShell();
    }

    List<RouteDevice> listRouteDevices() {
        List<RouteDevice> devices = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
                devices.add(RouteDevice.from(device));
            }
        } else {
            for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                RouteDevice routeDevice = RouteDevice.from(device);
                if (routeDevice.isBluetooth()) {
                    devices.add(routeDevice);
                }
            }
        }

        if (devices.isEmpty()) {
            devices.add(RouteDevice.legacyBluetoothSco());
        }
        return devices;
    }

    RoutingResult applyPreferredRoute(String selectedKey) {
        return applyPreferredRoute(selectedKey, null);
    }

    RoutingResult applyPreferredRoute(String selectedKey, String preferredBluetoothTarget) {
        return applyPreferredRoute(selectedKey, preferredBluetoothTarget, true);
    }

    RoutingResult maintainPreferredRoute(String selectedKey, String preferredBluetoothTarget) {
        return applyPreferredRoute(selectedKey, preferredBluetoothTarget, false);
    }

    private RoutingResult applyPreferredRoute(String selectedKey, String preferredBluetoothTarget, boolean forceApply) {
        List<RouteDevice> routeDevices = listRouteDevices();
        RouteDevice selected = findSelected(routeDevices, selectedKey, preferredBluetoothTarget);
        StringBuilder log = new StringBuilder();

        if (hasPreferredTarget(preferredBluetoothTarget)) {
            log.append("Preferred Bluetooth target: ").append(formatPreferredTarget(preferredBluetoothTarget)).append('\n');
            if (!selected.matchesTarget(preferredBluetoothTarget)) {
                log.append("No available Bluetooth route matched that saved device. ");
                log.append("Android is probably exposing only a generic Bluetooth SCO route right now.\n");
                appendBluetoothRoutes(log, routeDevices);
            }
        }
        log.append("Selected route: ").append(selected.detailLabel()).append('\n');
        log.append("Android Auto installed: ").append(AndroidAutoStatus.isInstalled(context) ? "yes" : "no").append('\n');

        if (!forceApply && isCurrentRoute(selected)) {
            log.append("Monitor check: selected route is already current; no reset needed.\n");
            return new RoutingResult(true, log.toString());
        }

        log.append("Public route layer: ");

        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && selected.isRealDevice()) {
                AudioDeviceInfo device = findCommunicationDevice(selected);
                if (device == null) {
                    log.append("device disappeared before routing.\n");
                    return new RoutingResult(false, log.toString());
                }
                boolean accepted = audioManager.setCommunicationDevice(device);
                log.append(accepted ? "accepted" : "rejected").append('\n');
                log.append("Current communication device: ").append(currentCommunicationDevice()).append('\n');
                return new RoutingResult(accepted, log.toString());
            }

            applyLegacyBluetoothSco(log);
            return new RoutingResult(true, log.toString());
        } catch (SecurityException e) {
            log.append("permission blocked: ").append(e.getMessage()).append('\n');
            return new RoutingResult(false, log.toString());
        } catch (RuntimeException e) {
            log.append("failed: ").append(e.getMessage()).append('\n');
            return new RoutingResult(false, log.toString());
        }
    }

    void clearRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice();
        } else {
            clearLegacyBluetoothSco();
        }
        audioManager.setMode(AudioManager.MODE_NORMAL);
    }

    String currentCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo device = audioManager.getCommunicationDevice();
            if (device == null) {
                return "none";
            }
            return RouteDevice.from(device).displayLabel();
        }
        return isBluetoothScoOn() ? "Bluetooth SCO" : "platform default";
    }

    RootShell.ShellResult rootDiagnostics() {
        return rootShell.diagnostics();
    }

    boolean isRootAvailable() {
        return rootShell.isAvailable();
    }

    boolean isAndroidAutoRunningWithRoot() {
        return AndroidAutoStatus.isRunningWithRoot(rootShell);
    }

    private RouteDevice findSelected(List<RouteDevice> devices, String selectedKey, String preferredBluetoothTarget) {
        RouteDevice targetMatch = findBluetoothTarget(devices, preferredBluetoothTarget);
        if (targetMatch != null) {
            return targetMatch;
        }

        if (selectedKey != null) {
            for (RouteDevice device : devices) {
                if (selectedKey.equals(device.key())) {
                    return device;
                }
            }
        }

        for (RouteDevice device : devices) {
            if (device.isBluetooth()) {
                return device;
            }
        }
        return devices.get(0);
    }

    private RouteDevice findBluetoothTarget(List<RouteDevice> devices, String preferredBluetoothTarget) {
        if (!hasPreferredTarget(preferredBluetoothTarget)) {
            return null;
        }
        for (RouteDevice device : devices) {
            if (device.isBluetooth() && device.matchesTarget(preferredBluetoothTarget)) {
                return device;
            }
        }
        return null;
    }

    private boolean hasPreferredTarget(String preferredBluetoothTarget) {
        return preferredBluetoothTarget != null && preferredBluetoothTarget.trim().length() > 0;
    }

    private String formatPreferredTarget(String preferredBluetoothTarget) {
        String[] parts = preferredBluetoothTarget.split("\\|");
        StringBuilder output = new StringBuilder();
        for (String part : parts) {
            String clean = part.trim();
            if (clean.length() == 0) {
                continue;
            }
            if (output.length() > 0) {
                output.append(" / ");
            }
            output.append(clean);
        }
        return output.length() == 0 ? preferredBluetoothTarget.trim() : output.toString();
    }

    private void appendBluetoothRoutes(StringBuilder log, List<RouteDevice> routeDevices) {
        log.append("Visible Bluetooth routes:\n");
        boolean found = false;
        for (RouteDevice routeDevice : routeDevices) {
            if (routeDevice.isBluetooth()) {
                log.append("- ").append(routeDevice.detailLabel()).append('\n');
                found = true;
            }
        }
        if (!found) {
            log.append("- none\n");
        }
    }

    private AudioDeviceInfo findCommunicationDevice(RouteDevice selected) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null;
        }
        for (AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
            RouteDevice routeDevice = RouteDevice.from(device);
            if (routeDevice.key().equals(selected.key())) {
                return device;
            }
            if (device.getId() == selected.id() && device.getType() == selected.type()) {
                return device;
            }
        }
        return null;
    }

    private boolean isCurrentRoute(RouteDevice selected) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo current = audioManager.getCommunicationDevice();
            if (current == null) {
                return false;
            }
            RouteDevice currentRoute = RouteDevice.from(current);
            return currentRoute.key().equals(selected.key())
                    || current.getId() == selected.id() && current.getType() == selected.type();
        }
        return selected.isBluetooth() && isBluetoothScoOn();
    }

    @SuppressWarnings("deprecation")
    private void applyLegacyBluetoothSco(StringBuilder log) {
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            log.append("legacy SCO not available off-call.\n");
            return;
        }
        audioManager.startBluetoothSco();
        audioManager.setBluetoothScoOn(true);
        log.append("legacy Bluetooth SCO requested.\n");
    }

    @SuppressWarnings("deprecation")
    private void clearLegacyBluetoothSco() {
        audioManager.setBluetoothScoOn(false);
        audioManager.stopBluetoothSco();
    }

    @SuppressWarnings("deprecation")
    private boolean isBluetoothScoOn() {
        return audioManager.isBluetoothScoOn();
    }

    static final class RoutingResult {
        final boolean success;
        final String log;

        RoutingResult(boolean success, String log) {
            this.success = success;
            this.log = log == null ? "" : log;
        }
    }
}

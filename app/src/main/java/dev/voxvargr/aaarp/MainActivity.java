package dev.voxvargr.aaarp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 4100;
    private static final int REQUEST_EXPORT_SETTINGS = 4101;
    private static final int REQUEST_IMPORT_SETTINGS = 4102;
    private static final String[] NOTIFICATION_ROUTE_LABELS = {
            "Leave notifications alone",
            "Phone speaker",
            "Earpiece",
            "Default Bluetooth target"
    };
    private static final String[] NOTIFICATION_ROUTE_VALUES = {
            AppPrefs.NOTIFICATION_ROUTE_OFF,
            AppPrefs.NOTIFICATION_ROUTE_SPEAKER,
            AppPrefs.NOTIFICATION_ROUTE_EARPIECE,
            AppPrefs.NOTIFICATION_ROUTE_BLUETOOTH
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AudioRouteController controller;
    private Spinner profileSpinner;
    private Spinner deviceSpinner;
    private Spinner bluetoothTargetSpinner;
    private Spinner notificationRouteSpinner;
    private ArrayAdapter<String> profileAdapter;
    private ArrayAdapter<String> deviceAdapter;
    private ArrayAdapter<String> bluetoothTargetAdapter;
    private ArrayAdapter<String> notificationRouteAdapter;
    private final List<ProfileSettings.ProfileEntry> profileEntries = new ArrayList<>();
    private final List<RouteDevice> routeDevices = new ArrayList<>();
    private final List<BluetoothTarget> bluetoothTargets = new ArrayList<>();
    private AndroidAutoConnection lastDetectedConnection = AndroidAutoConnection.fallback();
    private TextView statusView;
    private TextView currentRouteView;
    private TextView bluetoothInventoryView;
    private TextView logView;
    private EditText preferredTargetEditText;
    private CheckBox rootCheckBox;
    private CheckBox watchdogCheckBox;
    private CheckBox restoreAfterBootCheckBox;
    private CheckBox releaseAfterDisconnectCheckBox;
    private CheckBox autoStopCheckBox;
    private CheckBox resetBluetoothAfterDisconnectCheckBox;
    private CheckBox suppressDuckingCheckBox;
    private CheckBox suppressDuckingAlwaysCheckBox;
    private CheckBox muteNotificationsDuringPlaybackCheckBox;
    private CheckBox muteNotificationsDuringPlaybackAlwaysCheckBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controller = new AudioRouteController(this);
        setContentView(buildContentView());
        try {
            requestRuntimePermissions();
            refreshDevices();
        } catch (RuntimeException e) {
            updateStatus("Startup recovered.");
            setLog("Startup issue avoided: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            refreshDevices();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_SETTINGS && requestCode != REQUEST_IMPORT_SETTINGS) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            setLog("Settings backup/restore canceled.");
            return;
        }
        if (requestCode == REQUEST_EXPORT_SETTINGS) {
            writeSettingsBackup(data.getData());
        } else {
            readSettingsBackup(data.getData());
        }
    }

    private View buildContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(getColor(R.color.aaarp_bg));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(22), dp(20), dp(28));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("AAARP", 32, true);
        title.setTextColor(getColor(R.color.aaarp_text));
        content.addView(title);

        TextView subtitle = text("Android Auto Audio Routing Project", 16, false);
        subtitle.setTextColor(getColor(R.color.aaarp_muted));
        content.addView(subtitle);

        statusView = panelText();
        content.addView(statusView, blockParams());

        currentRouteView = panelText();
        content.addView(currentRouteView, blockParams());

        TextView profileLabel = text("Android Auto profile", 15, true);
        profileLabel.setTextColor(getColor(R.color.aaarp_text));
        content.addView(profileLabel, blockParams());

        profileSpinner = new Spinner(this);
        profileAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(profileAdapter);
        content.addView(profileSpinner, blockParams());

        LinearLayout profileRow = buttonRow();
        profileRow.addView(button("Detect AA", v -> detectAndroidAutoProfile()), weightParams());
        profileRow.addView(button("Load Profile", v -> loadSelectedProfile()), weightParams());
        profileRow.addView(button("Save Profile", v -> saveProfileForCurrentConnection()), weightParams());
        content.addView(profileRow, blockParams());

        deviceSpinner = new Spinner(this);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(deviceAdapter);
        content.addView(deviceSpinner, blockParams());

        TextView savedTargetLabel = text("Default Bluetooth audio target", 15, true);
        savedTargetLabel.setTextColor(getColor(R.color.aaarp_text));
        content.addView(savedTargetLabel, blockParams());

        bluetoothTargetSpinner = new Spinner(this);
        bluetoothTargetAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        bluetoothTargetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bluetoothTargetSpinner.setAdapter(bluetoothTargetAdapter);
        content.addView(bluetoothTargetSpinner, blockParams());

        preferredTargetEditText = new EditText(this);
        preferredTargetEditText.setSingleLine(true);
        preferredTargetEditText.setHint("Custom fallback, e.g. Shokz");
        preferredTargetEditText.setText(prefString(
                AppPrefs.CUSTOM_BLUETOOTH_QUERY,
                prefString(AppPrefs.PREFERRED_BLUETOOTH_QUERY, "")
        ));
        preferredTargetEditText.setTextColor(getColor(R.color.aaarp_text));
        preferredTargetEditText.setHintTextColor(getColor(R.color.aaarp_muted));
        content.addView(preferredTargetEditText, blockParams());

        LinearLayout rowOne = buttonRow();
        rowOne.addView(button("Refresh", v -> refreshDevices()), weightParams());
        rowOne.addView(button("Apply", v -> applySelectedRoute()), weightParams());
        rowOne.addView(button("Clear", v -> clearRoute()), weightParams());
        content.addView(rowOne, blockParams());

        LinearLayout rowTwo = buttonRow();
        rowTwo.addView(button("Start Monitor", v -> startRouteMonitor()), weightParams());
        rowTwo.addView(button("Stop Monitor", v -> stopRouteMonitor()), weightParams());
        content.addView(rowTwo, blockParams());

        rootCheckBox = new CheckBox(this);
        rootCheckBox.setText("Root diagnostics");
        rootCheckBox.setTextColor(getColor(R.color.aaarp_text));
        rootCheckBox.setChecked(prefBoolean(AppPrefs.USE_ROOT, false));
        rootCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                AppPrefs.get(this).edit().putBoolean(AppPrefs.USE_ROOT, isChecked).apply());
        content.addView(rootCheckBox, blockParams());

        watchdogCheckBox = new CheckBox(this);
        watchdogCheckBox.setText("Watch Android Auto");
        watchdogCheckBox.setTextColor(getColor(R.color.aaarp_text));
        watchdogCheckBox.setChecked(prefBoolean(AppPrefs.WATCHDOG_MODE, true));
        watchdogCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                AppPrefs.get(this).edit().putBoolean(AppPrefs.WATCHDOG_MODE, isChecked).apply());
        content.addView(watchdogCheckBox, blockParams());

        restoreAfterBootCheckBox = new CheckBox(this);
        restoreAfterBootCheckBox.setText("Restore monitor after reboot");
        restoreAfterBootCheckBox.setTextColor(getColor(R.color.aaarp_text));
        restoreAfterBootCheckBox.setChecked(prefBoolean(AppPrefs.RESTORE_MONITOR_AFTER_BOOT, true));
        restoreAfterBootCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                AppPrefs.get(this).edit().putBoolean(AppPrefs.RESTORE_MONITOR_AFTER_BOOT, isChecked).apply());
        content.addView(restoreAfterBootCheckBox, blockParams());

        releaseAfterDisconnectCheckBox = new CheckBox(this);
        releaseAfterDisconnectCheckBox.setText("Release route after Android Auto disconnects");
        releaseAfterDisconnectCheckBox.setTextColor(getColor(R.color.aaarp_text));
        releaseAfterDisconnectCheckBox.setChecked(prefBoolean(AppPrefs.RELEASE_ROUTE_AFTER_ANDROID_AUTO, true));
        releaseAfterDisconnectCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                AppPrefs.get(this).edit().putBoolean(AppPrefs.RELEASE_ROUTE_AFTER_ANDROID_AUTO, isChecked).apply());
        content.addView(releaseAfterDisconnectCheckBox, blockParams());

        autoStopCheckBox = new CheckBox(this);
        autoStopCheckBox.setText("Auto-stop after Android Auto disconnects");
        autoStopCheckBox.setTextColor(getColor(R.color.aaarp_text));
        autoStopCheckBox.setChecked(prefBoolean(AppPrefs.AUTO_STOP_AFTER_ANDROID_AUTO, false));
        autoStopCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                AppPrefs.get(this).edit().putBoolean(AppPrefs.AUTO_STOP_AFTER_ANDROID_AUTO, isChecked).apply());
        content.addView(autoStopCheckBox, blockParams());

        resetBluetoothAfterDisconnectCheckBox = new CheckBox(this);
        resetBluetoothAfterDisconnectCheckBox.setText("Reset Bluetooth after Android Auto disconnects");
        resetBluetoothAfterDisconnectCheckBox.setTextColor(getColor(R.color.aaarp_text));
        resetBluetoothAfterDisconnectCheckBox.setChecked(prefBoolean(AppPrefs.RESET_BLUETOOTH_AFTER_ANDROID_AUTO, false));
        resetBluetoothAfterDisconnectCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                AppPrefs.get(this).edit().putBoolean(AppPrefs.RESET_BLUETOOTH_AFTER_ANDROID_AUTO, isChecked).apply());
        content.addView(resetBluetoothAfterDisconnectCheckBox, blockParams());

        TextView notificationRouteLabel = text("Notification sound route during Android Auto", 15, true);
        notificationRouteLabel.setTextColor(getColor(R.color.aaarp_text));
        content.addView(notificationRouteLabel, blockParams());

        notificationRouteSpinner = new Spinner(this);
        notificationRouteAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        notificationRouteAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        notificationRouteSpinner.setAdapter(notificationRouteAdapter);
        for (String label : NOTIFICATION_ROUTE_LABELS) {
            notificationRouteAdapter.add(label);
        }
        notificationRouteAdapter.notifyDataSetChanged();
        restoreNotificationRouteMode();
        content.addView(notificationRouteSpinner, blockParams());

        suppressDuckingCheckBox = new CheckBox(this);
        suppressDuckingCheckBox.setText("Try to stop notification audio ducking");
        suppressDuckingCheckBox.setTextColor(getColor(R.color.aaarp_text));
        suppressDuckingCheckBox.setChecked(prefBoolean(AppPrefs.SUPPRESS_NOTIFICATION_DUCKING, false));
        suppressDuckingCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppPrefs.get(this).edit().putBoolean(AppPrefs.SUPPRESS_NOTIFICATION_DUCKING, isChecked).apply();
            updateDependentSoundTweakControls();
        });
        content.addView(suppressDuckingCheckBox, blockParams());

        suppressDuckingAlwaysCheckBox = new CheckBox(this);
        suppressDuckingAlwaysCheckBox.setText("Also stop notification ducking outside Android Auto");
        suppressDuckingAlwaysCheckBox.setTextColor(getColor(R.color.aaarp_text));
        suppressDuckingAlwaysCheckBox.setChecked(prefBoolean(AppPrefs.SUPPRESS_NOTIFICATION_DUCKING_ALWAYS, false));
        suppressDuckingAlwaysCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                AppPrefs.get(this).edit().putBoolean(AppPrefs.SUPPRESS_NOTIFICATION_DUCKING_ALWAYS, isChecked).apply());
        content.addView(suppressDuckingAlwaysCheckBox, blockParams());

        muteNotificationsDuringPlaybackCheckBox = new CheckBox(this);
        muteNotificationsDuringPlaybackCheckBox.setText("Mute notification sounds during media playback");
        muteNotificationsDuringPlaybackCheckBox.setTextColor(getColor(R.color.aaarp_text));
        muteNotificationsDuringPlaybackCheckBox.setChecked(prefBoolean(AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK, false));
        muteNotificationsDuringPlaybackCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppPrefs.get(this).edit().putBoolean(AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK, isChecked).apply();
            updateDependentSoundTweakControls();
        });
        content.addView(muteNotificationsDuringPlaybackCheckBox, blockParams());

        muteNotificationsDuringPlaybackAlwaysCheckBox = new CheckBox(this);
        muteNotificationsDuringPlaybackAlwaysCheckBox.setText("Also mute during playback outside Android Auto");
        muteNotificationsDuringPlaybackAlwaysCheckBox.setTextColor(getColor(R.color.aaarp_text));
        muteNotificationsDuringPlaybackAlwaysCheckBox.setChecked(prefBoolean(AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK_ALWAYS, false));
        muteNotificationsDuringPlaybackAlwaysCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                AppPrefs.get(this).edit().putBoolean(AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK_ALWAYS, isChecked).apply());
        content.addView(muteNotificationsDuringPlaybackAlwaysCheckBox, blockParams());
        updateDependentSoundTweakControls();

        LinearLayout rowThree = buttonRow();
        rowThree.addView(button("Root Check", v -> checkRootStatus()), weightParams());
        rowThree.addView(button("Diagnostics", v -> runRootDiagnostics()), weightParams());
        content.addView(rowThree, blockParams());

        LinearLayout rowFour = buttonRow();
        rowFour.addView(button("Save Diagnostics", v -> saveRootDiagnostics()), weightParams());
        rowFour.addView(button("Battery Exempt", v -> requestBatteryExemption()), weightParams());
        content.addView(rowFour, blockParams());

        LinearLayout rowFive = buttonRow();
        rowFive.addView(button("Backup Settings", v -> exportSettingsBackup()), weightParams());
        rowFive.addView(button("Restore Settings", v -> importSettingsBackup()), weightParams());
        content.addView(rowFive, blockParams());

        Button resetBluetoothButton = button("Reset Bluetooth", v -> resetBluetoothNow());
        content.addView(resetBluetoothButton, blockParams());

        bluetoothInventoryView = panelText();
        bluetoothInventoryView.setTypeface(Typeface.MONOSPACE);
        content.addView(bluetoothInventoryView, blockParams());

        logView = panelText();
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setText("Ready.");
        content.addView(logView, blockParams());

        return scrollView;
    }

    private void refreshDevices() {
        try {
            routeDevices.clear();
            routeDevices.addAll(controller.listRouteDevices());
            deviceAdapter.clear();
            for (RouteDevice device : routeDevices) {
                deviceAdapter.add(device.displayLabel());
            }
            deviceAdapter.notifyDataSetChanged();
            restoreSelectedDevice();
            refreshBluetoothTargets();
            refreshProfiles();
            updateBluetoothInventory();
            updateStatus("Devices refreshed.");
            updateCurrentRoute();
        } catch (SecurityException e) {
            updateStatus("Bluetooth permission needed.");
            setLog("Permission blocked device refresh: " + e.getMessage());
        } catch (RuntimeException e) {
            updateStatus("Refresh failed.");
            setLog("Device refresh failed: " + e.getMessage());
        }
    }

    private void applySelectedRoute() {
        String selectedKey = saveSelectedDevice();
        String preferredBluetoothTarget = savePreferredBluetoothTarget();
        saveNotificationRouteMode();
        setLog("Applying route...");
        executor.execute(() -> {
            AudioRouteController.RoutingResult result = controller.applyPreferredRoute(selectedKey, preferredBluetoothTarget);
            runOnUiThread(() -> {
                updateStatus(result.success ? "Route accepted." : "Route rejected.");
                setLog(result.log);
                updateCurrentRoute();
            });
        });
    }

    private void clearRoute() {
        setLog("Clearing route...");
        executor.execute(() -> {
            controller.clearRoute();
            runOnUiThread(() -> {
                stopRouteMonitor();
                updateStatus("Route cleared.");
                updateCurrentRoute();
                setLog("Cleared communication route.");
            });
        });
    }

    private void startRouteMonitor() {
        saveSelectedDevice();
        String preferredBluetoothTarget = savePreferredBluetoothTarget();
        saveNotificationRouteMode();
        Intent intent = new Intent(this, RoutingMonitorService.class).setAction(RoutingMonitorService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        AppPrefs.get(this).edit().putBoolean(AppPrefs.MONITOR_ENABLED, true).apply();
        updateStatus("Monitor running.");
        if (prefBoolean(AppPrefs.WATCHDOG_MODE, true)) {
            if (preferredBluetoothTarget.length() == 0) {
                setLog("Watchdog started. Select a default Bluetooth audio target before AAARP will route Android Auto.");
            } else {
                setLog("Watchdog started. AAARP will wait for Android Auto and the default Bluetooth target, then maintain the selected route.");
            }
        } else {
            setLog("Monitor started. AAARP will watch the selected route and only reset it if Android changes it.");
        }
    }

    private void stopRouteMonitor() {
        Intent intent = new Intent(this, RoutingMonitorService.class).setAction(RoutingMonitorService.ACTION_STOP);
        startService(intent);
        AppPrefs.get(this).edit().putBoolean(AppPrefs.MONITOR_ENABLED, false).apply();
        updateStatus("Monitor stopped.");
    }

    private void checkRootStatus() {
        setLog("Checking root...");
        executor.execute(() -> {
            boolean root = controller.isRootAvailable();
            boolean aaRunning = root && controller.isAndroidAutoRunningWithRoot();
            runOnUiThread(() -> {
                updateStatus(root ? "Root available." : "Root unavailable.");
                setLog("Root: " + (root ? "available" : "unavailable")
                        + "\nAndroid Auto running: " + (aaRunning ? "yes" : "no"));
            });
        });
    }

    private void runRootDiagnostics() {
        if (!rootCheckBox.isChecked()) {
            setLog("Enable Root diagnostics first.");
            return;
        }
        setLog("Collecting diagnostics...");
        executor.execute(() -> {
            RootShell.ShellResult result = controller.rootDiagnostics();
            runOnUiThread(() -> {
                updateStatus(result.success ? "Diagnostics complete." : "Diagnostics returned errors.");
                setLog(result.output.length() == 0 ? "No output." : result.output);
            });
        });
    }

    private void saveRootDiagnostics() {
        setLog("Collecting diagnostics for file...");
        executor.execute(() -> {
            RootShell.ShellResult result = controller.rootDiagnostics();
            String body = buildDiagnosticsReport(result);
            try {
                String location = DiagnosticsFileWriter.write(this, body);
                runOnUiThread(() -> {
                    updateStatus("Diagnostics saved.");
                    setLog("Saved diagnostics to " + location);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    updateStatus("Save failed.");
                    setLog("Could not save diagnostics: " + e.getMessage() + "\n\n" + body);
                });
            }
        });
    }

    private void detectAndroidAutoProfile() {
        setLog("Detecting Android Auto connection...");
        executor.execute(() -> {
            try {
                boolean androidAutoRunning = controller.isAndroidAutoRunningWithRoot();
                AndroidAutoConnection connection = controller.currentAndroidAutoConnection();
                if (!androidAutoRunning) {
                    String visibleWifi = connection.specific()
                            ? "\nVisible Wi-Fi identity: " + connection.label() + "\nIgnored until Android Auto is running."
                            : "";
                    runOnUiThread(() -> {
                        lastDetectedConnection = AndroidAutoConnection.fallback();
                        selectProfile(ProfileSettings.DEFAULT_PROFILE_ID);
                        updateStatus("Android Auto is not running.");
                        setLog("No active Android Auto connection detected."
                                + visibleWifi
                                + "\nProfile: Default");
                    });
                    return;
                }

                lastDetectedConnection = connection;
                String mappedProfileId = ProfileSettings.profileIdForConnection(this, connection);
                runOnUiThread(() -> {
                    selectProfile(mappedProfileId);
                    updateStatus(connection.specific()
                            ? "Android Auto profile detected."
                            : "Android Auto running; no specific profile detected.");
                    setLog("Android Auto process: " + (androidAutoRunning ? "detected" : "not confirmed")
                            + "\nDetected profile source: " + connection.label()
                            + "\nProfile: " + mappedProfileId);
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> {
                    selectProfile(ProfileSettings.DEFAULT_PROFILE_ID);
                    lastDetectedConnection = AndroidAutoConnection.fallback();
                    updateStatus("Android Auto detection failed.");
                    setLog("Could not detect Android Auto profile: " + e.getMessage()
                            + "\nProfile: Default");
                });
            }
        });
    }

    private void saveProfileForCurrentConnection() {
        saveSelectedDevice();
        savePreferredBluetoothTarget();
        saveNotificationRouteMode();
        setLog("Saving profile...");
        try {
            AndroidAutoConnection connection = lastDetectedConnection == null
                    ? AndroidAutoConnection.fallback()
                    : lastDetectedConnection;
            ProfileSettings.ProfileEntry selectedProfile = selectedProfileEntry();
            ProfileSettings.ProfileEntry entry;
            if (connection.specific()) {
                entry = ProfileSettings.saveCurrentSettingsForConnection(this, connection);
            } else {
                String profileId = selectedProfile == null ? ProfileSettings.DEFAULT_PROFILE_ID : selectedProfile.id;
                String label = selectedProfile == null ? "Default" : selectedProfile.displayLabel();
                entry = ProfileSettings.saveCurrentSettingsToProfile(this, profileId, label);
            }
            refreshProfiles();
            selectProfile(entry.id);
            lastDetectedConnection = AndroidAutoConnection.fallback();
            updateStatus("Profile saved.");
            setLog("Saved profile: " + entry.displayLabel()
                    + "\nConnection: " + connection.label()
                    + (connection.specific() ? "" : "\nThis is the default for new or unknown Android Auto connections."));
        } catch (RuntimeException e) {
            updateStatus("Profile save failed.");
            setLog("Could not save profile: " + e.getMessage());
        }
    }

    private void loadSelectedProfile() {
        ProfileSettings.ProfileEntry entry = selectedProfileEntry();
        if (entry == null) {
            setLog("No profile selected.");
            return;
        }
        ProfileSettings.loadProfileIntoCurrentSettings(this, entry.id);
        refreshDevices();
        restoreSettingsControls();
        selectProfile(entry.id);
        updateStatus("Profile loaded.");
        setLog("Loaded profile: " + entry.displayLabel());
    }

    private String buildDiagnosticsReport(RootShell.ShellResult result) {
        List<RouteDevice> snapshotRoutes = controller.listRouteDevices();
        StringBuilder report = new StringBuilder();
        report.append("AAARP diagnostics\n");
        report.append("Android Auto installed: ").append(AndroidAutoStatus.isInstalled(this) ? "yes" : "no").append('\n');
        report.append("Current communication route: ").append(controller.currentCommunicationDevice()).append('\n');
        report.append("Watch Android Auto: ")
                .append(prefBoolean(AppPrefs.WATCHDOG_MODE, true) ? "on" : "off").append('\n');
        report.append("Restore monitor after reboot: ")
                .append(prefBoolean(AppPrefs.RESTORE_MONITOR_AFTER_BOOT, true) ? "on" : "off").append('\n');
        report.append("Default Bluetooth target: ")
                .append(prefString(AppPrefs.PREFERRED_BLUETOOTH_QUERY, "none")).append('\n');
        report.append("Release route after Android Auto disconnects: ")
                .append(prefBoolean(AppPrefs.RELEASE_ROUTE_AFTER_ANDROID_AUTO, true) ? "on" : "off").append('\n');
        report.append("Auto-stop after Android Auto disconnects: ")
                .append(prefBoolean(AppPrefs.AUTO_STOP_AFTER_ANDROID_AUTO, false) ? "on" : "off").append('\n');
        report.append("Reset Bluetooth after Android Auto disconnects: ")
                .append(prefBoolean(AppPrefs.RESET_BLUETOOTH_AFTER_ANDROID_AUTO, false) ? "on" : "off").append('\n');
        report.append("Notification route during Android Auto: ")
                .append(prefString(AppPrefs.NOTIFICATION_ROUTE_MODE, AppPrefs.NOTIFICATION_ROUTE_OFF)).append('\n');
        report.append("Try to stop notification ducking: ")
                .append(prefBoolean(AppPrefs.SUPPRESS_NOTIFICATION_DUCKING, false) ? "on" : "off").append('\n');
        report.append("Stop notification ducking outside Android Auto: ")
                .append(prefBoolean(AppPrefs.SUPPRESS_NOTIFICATION_DUCKING_ALWAYS, false) ? "on" : "off").append('\n');
        report.append("Mute notification sounds during playback: ")
                .append(prefBoolean(AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK, false) ? "on" : "off").append('\n');
        report.append("Mute notification sounds outside Android Auto: ")
                .append(prefBoolean(AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK_ALWAYS, false) ? "on" : "off").append('\n');
        report.append("Auto log directory: ").append(AutoLogWriter.location(this)).append('\n');
        report.append('\n').append(BluetoothDeviceCatalog.describe(this, snapshotRoutes)).append('\n');
        report.append("\nRoot diagnostics exit code: ").append(result.exitCode).append('\n');
        report.append(result.output.length() == 0 ? "No root diagnostics output.\n" : result.output);
        return report.toString();
    }

    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            setLog("Battery optimization exemption is not needed on this Android version.");
            return;
        }

        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            setLog("AAARP is already ignoring battery optimizations.");
            return;
        }

        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (RuntimeException e) {
            Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(fallback);
        }
    }

    private void exportSettingsBackup() {
        saveCurrentUiSettings();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "AAARP-settings-backup.json");
        try {
            startActivityForResult(intent, REQUEST_EXPORT_SETTINGS);
        } catch (RuntimeException e) {
            updateStatus("Backup failed.");
            setLog("Could not open backup destination picker: " + e.getMessage());
        }
    }

    private void importSettingsBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        try {
            startActivityForResult(intent, REQUEST_IMPORT_SETTINGS);
        } catch (RuntimeException e) {
            updateStatus("Restore failed.");
            setLog("Could not open settings backup picker: " + e.getMessage());
        }
    }

    private void writeSettingsBackup(Uri uri) {
        setLog("Writing settings backup...");
        executor.execute(() -> {
            try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                if (outputStream == null) {
                    throw new IOException("Could not open backup destination.");
                }
                int count = SettingsBackup.write(this, outputStream);
                runOnUiThread(() -> {
                    updateStatus("Settings backed up.");
                    setLog("Backed up " + count + " settings/profile entries.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    updateStatus("Backup failed.");
                    setLog("Could not write settings backup: " + e.getMessage());
                });
            }
        });
    }

    private void readSettingsBackup(Uri uri) {
        setLog("Restoring settings backup...");
        executor.execute(() -> {
            try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
                if (inputStream == null) {
                    throw new IOException("Could not open settings backup.");
                }
                SettingsBackup.RestoreResult result = SettingsBackup.restore(this, inputStream);
                runOnUiThread(() -> {
                    refreshDevices();
                    restoreSettingsControls();
                    refreshProfiles();
                    updateStatus("Settings restored.");
                    setLog("Restored " + result.preferenceCount + " settings/profile entries."
                            + rootRestoreSummary(result.rootRestoreResult));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    updateStatus("Restore failed.");
                    setLog("Could not restore settings backup: " + e.getMessage());
                });
            }
        });
    }

    private String rootRestoreSummary(SettingsBackup.RootRestoreResult result) {
        if (result == null) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        summary.append("\nRoot restore: ").append(result.message);
        if (result.attempted && result.rootAvailable) {
            summary.append("\nRuntime permissions requested: ").append(result.runtimePermissionCount);
            summary.append("\nBattery exemption requested: ")
                    .append(result.batteryExemptionAttempted ? "yes" : "no");
        }
        if (result.output.length() > 0) {
            summary.append("\n\n").append(result.output);
        }
        return summary.toString();
    }

    private void resetBluetoothNow() {
        setLog("Resetting Bluetooth...");
        executor.execute(() -> {
            RootShell.ShellResult result = controller.resetBluetoothWithRoot();
            runOnUiThread(() -> {
                updateStatus(result.success ? "Bluetooth reset requested." : "Bluetooth reset failed.");
                setLog(result.output.length() == 0 ? "No root output." : result.output);
            });
        });
    }

    private void updateStatus(String message) {
        boolean monitorEnabled = prefBoolean(AppPrefs.MONITOR_ENABLED, false);
        boolean watchdogEnabled = prefBoolean(AppPrefs.WATCHDOG_MODE, true);
        boolean restoreAfterBoot = prefBoolean(AppPrefs.RESTORE_MONITOR_AFTER_BOOT, true);
        statusView.setText(message + "\nMonitor: " + (monitorEnabled ? "on" : "off")
                + "\nWatch Android Auto: " + (watchdogEnabled ? "on" : "off")
                + "\nRestore after reboot: " + (restoreAfterBoot ? "on" : "off")
                + "\nAndroid Auto package: " + (AndroidAutoStatus.isInstalled(this) ? "installed" : "not found"));
    }

    private void updateCurrentRoute() {
        currentRouteView.setText("Current communication route:\n" + controller.currentCommunicationDevice());
    }

    private String saveSelectedDevice() {
        int index = deviceSpinner.getSelectedItemPosition();
        if (index < 0 || index >= routeDevices.size()) {
            return null;
        }
        String key = routeDevices.get(index).key();
        AppPrefs.get(this).edit().putString(AppPrefs.SELECTED_DEVICE_KEY, key).apply();
        return key;
    }

    private String savePreferredBluetoothTarget() {
        String customTarget = preferredTargetEditText == null ? "" : preferredTargetEditText.getText().toString().trim();
        String savedTarget = selectedBluetoothTargetQuery();
        String target = savedTarget;
        if (customTarget.length() > 0) {
            target = target.length() == 0 ? customTarget : target + "|" + customTarget;
        }
        AppPrefs.get(this).edit().putString(AppPrefs.PREFERRED_BLUETOOTH_QUERY, target).apply();
        AppPrefs.get(this).edit().putString(AppPrefs.CUSTOM_BLUETOOTH_QUERY, customTarget).apply();
        return target;
    }

    private String selectedBluetoothTargetQuery() {
        int index = bluetoothTargetSpinner == null ? -1 : bluetoothTargetSpinner.getSelectedItemPosition();
        if (index <= 0 || index > bluetoothTargets.size()) {
            AppPrefs.get(this).edit().remove(AppPrefs.SELECTED_BLUETOOTH_TARGET_KEY).apply();
            return "";
        }
        BluetoothTarget target = bluetoothTargets.get(index - 1);
        AppPrefs.get(this).edit().putString(AppPrefs.SELECTED_BLUETOOTH_TARGET_KEY, target.key()).apply();
        return target.matchQuery();
    }

    private void saveNotificationRouteMode() {
        int index = notificationRouteSpinner == null ? 0 : notificationRouteSpinner.getSelectedItemPosition();
        if (index < 0 || index >= NOTIFICATION_ROUTE_VALUES.length) {
            index = 0;
        }
        AppPrefs.get(this).edit()
                .putString(AppPrefs.NOTIFICATION_ROUTE_MODE, NOTIFICATION_ROUTE_VALUES[index])
                .apply();
    }

    private void saveCurrentUiSettings() {
        saveSelectedDevice();
        savePreferredBluetoothTarget();
        saveNotificationRouteMode();
    }

    private void restoreNotificationRouteMode() {
        if (notificationRouteSpinner == null) {
            return;
        }
        String mode = prefString(AppPrefs.NOTIFICATION_ROUTE_MODE, AppPrefs.NOTIFICATION_ROUTE_OFF);
        for (int i = 0; i < NOTIFICATION_ROUTE_VALUES.length; i++) {
            if (NOTIFICATION_ROUTE_VALUES[i].equals(mode)) {
                notificationRouteSpinner.setSelection(i);
                return;
            }
        }
        notificationRouteSpinner.setSelection(0);
    }

    private void restoreSettingsControls() {
        rootCheckBox.setChecked(prefBoolean(AppPrefs.USE_ROOT, false));
        watchdogCheckBox.setChecked(prefBoolean(AppPrefs.WATCHDOG_MODE, true));
        restoreAfterBootCheckBox.setChecked(prefBoolean(AppPrefs.RESTORE_MONITOR_AFTER_BOOT, true));
        releaseAfterDisconnectCheckBox.setChecked(prefBoolean(AppPrefs.RELEASE_ROUTE_AFTER_ANDROID_AUTO, true));
        autoStopCheckBox.setChecked(prefBoolean(AppPrefs.AUTO_STOP_AFTER_ANDROID_AUTO, false));
        resetBluetoothAfterDisconnectCheckBox.setChecked(prefBoolean(AppPrefs.RESET_BLUETOOTH_AFTER_ANDROID_AUTO, false));
        suppressDuckingCheckBox.setChecked(prefBoolean(AppPrefs.SUPPRESS_NOTIFICATION_DUCKING, false));
        suppressDuckingAlwaysCheckBox.setChecked(prefBoolean(AppPrefs.SUPPRESS_NOTIFICATION_DUCKING_ALWAYS, false));
        muteNotificationsDuringPlaybackCheckBox.setChecked(prefBoolean(AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK, false));
        muteNotificationsDuringPlaybackAlwaysCheckBox.setChecked(prefBoolean(AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK_ALWAYS, false));
        updateDependentSoundTweakControls();
        preferredTargetEditText.setText(prefString(
                AppPrefs.CUSTOM_BLUETOOTH_QUERY,
                prefString(AppPrefs.PREFERRED_BLUETOOTH_QUERY, "")
        ));
        restoreNotificationRouteMode();
        restoreSelectedDevice();
        restoreSelectedBluetoothTarget();
    }

    private void updateDependentSoundTweakControls() {
        if (suppressDuckingAlwaysCheckBox != null && suppressDuckingCheckBox != null) {
            suppressDuckingAlwaysCheckBox.setEnabled(suppressDuckingCheckBox.isChecked());
        }
        if (muteNotificationsDuringPlaybackAlwaysCheckBox != null
                && muteNotificationsDuringPlaybackCheckBox != null) {
            muteNotificationsDuringPlaybackAlwaysCheckBox.setEnabled(
                    muteNotificationsDuringPlaybackCheckBox.isChecked()
            );
        }
    }

    private void refreshProfiles() {
        if (profileAdapter == null) {
            return;
        }
        String selectedId = ProfileSettings.activeProfileId(this);
        profileEntries.clear();
        profileEntries.addAll(ProfileSettings.listProfiles(this));
        profileAdapter.clear();
        for (ProfileSettings.ProfileEntry entry : profileEntries) {
            profileAdapter.add(entry.displayLabel());
        }
        profileAdapter.notifyDataSetChanged();
        selectProfile(selectedId);
    }

    private void selectProfile(String profileId) {
        if (profileSpinner == null || profileId == null) {
            return;
        }
        if (profileEntries.isEmpty()) {
            return;
        }
        for (int i = 0; i < profileEntries.size(); i++) {
            if (profileId.equals(profileEntries.get(i).id)) {
                profileSpinner.setSelection(i);
                return;
            }
        }
        profileSpinner.setSelection(0);
    }

    private ProfileSettings.ProfileEntry selectedProfileEntry() {
        int index = profileSpinner == null ? -1 : profileSpinner.getSelectedItemPosition();
        if (index < 0 || index >= profileEntries.size()) {
            return null;
        }
        return profileEntries.get(index);
    }

    private void updateBluetoothInventory() {
        if (bluetoothInventoryView == null) {
            return;
        }
        bluetoothInventoryView.setText(BluetoothDeviceCatalog.describe(this, routeDevices));
    }

    private void refreshBluetoothTargets() {
        bluetoothTargets.clear();
        bluetoothTargets.addAll(BluetoothDeviceCatalog.listTargets(this, routeDevices));
        bluetoothTargetAdapter.clear();
        bluetoothTargetAdapter.add("No saved Bluetooth target");
        for (BluetoothTarget target : bluetoothTargets) {
            bluetoothTargetAdapter.add(target.displayLabel());
        }
        bluetoothTargetAdapter.notifyDataSetChanged();
        restoreSelectedBluetoothTarget();
    }

    private void restoreSelectedDevice() {
        String selectedKey = prefString(AppPrefs.SELECTED_DEVICE_KEY, null);
        if (selectedKey == null) {
            return;
        }
        for (int i = 0; i < routeDevices.size(); i++) {
            if (selectedKey.equals(routeDevices.get(i).key())) {
                deviceSpinner.setSelection(i);
                return;
            }
        }
    }

    private void restoreSelectedBluetoothTarget() {
        String selectedKey = prefString(AppPrefs.SELECTED_BLUETOOTH_TARGET_KEY, null);
        if (selectedKey == null) {
            return;
        }
        for (int i = 0; i < bluetoothTargets.size(); i++) {
            if (selectedKey.equals(bluetoothTargets.get(i).key())) {
                bluetoothTargetSpinner.setSelection(i + 1);
                return;
            }
        }
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!permissions.isEmpty()) {
            try {
                requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
            } catch (RuntimeException e) {
                setLog("Permission request skipped: " + e.getMessage());
            }
        }
    }

    private String prefString(String key, String fallback) {
        try {
            return AppPrefs.get(this).getString(key, fallback);
        } catch (RuntimeException e) {
            AppPrefs.get(this).edit().remove(key).apply();
            return fallback;
        }
    }

    private boolean prefBoolean(String key, boolean fallback) {
        try {
            return AppPrefs.get(this).getBoolean(key, fallback);
        } catch (RuntimeException e) {
            AppPrefs.get(this).edit().remove(key).apply();
            return fallback;
        }
    }

    private void setLog(String log) {
        logView.setText(log == null || log.length() == 0 ? "No output." : log);
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setIncludeFontPadding(true);
        if (bold) {
            textView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return textView;
    }

    private TextView panelText() {
        TextView textView = text("", 15, false);
        textView.setTextColor(getColor(R.color.aaarp_text));
        textView.setBackgroundColor(getColor(R.color.aaarp_panel));
        textView.setPadding(dp(14), dp(12), dp(14), dp(12));
        return textView;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout buttonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private LinearLayout.LayoutParams blockParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(12), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

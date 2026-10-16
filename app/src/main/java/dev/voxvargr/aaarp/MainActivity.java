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
import android.view.WindowManager;
import android.widget.AdapterView;
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
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final String[] MEDIA_LEVELER_LABELS = {
            "Off (no leveling)",
            "Gain only (legacy boost - no song matching)",
            "Gentle leveling",
            "Strong leveling (recommended)",
            "Max leveling (most aggressive)"
    };
    private static final long RESTORE_UI_POLL_MS = 250L;
    private static final AtomicBoolean SETTINGS_RESTORE_IN_PROGRESS = new AtomicBoolean();
    private static final AtomicBoolean DIAGNOSTICS_SAVE_IN_PROGRESS = new AtomicBoolean();

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
    private boolean suppressProfileSelectionLoad;
    private boolean suppressMediaLevelerSave;
    private boolean suppressSettingsUiWrites;
    private boolean refreshAfterExternalRestore;
    private final Runnable restoreUiStatePoll = this::reconcileSettingsRestoreUiState;
    private TextView statusView;
    private TextView currentRouteView;
    private TextView bluetoothInventoryView;
    private TextView logView;
    private Button saveDiagnosticsButton;
    private EditText preferredTargetEditText;
    private CheckBox rootCheckBox;
    private CheckBox watchdogCheckBox;
    private CheckBox gpsWarmupDuringAndroidAutoCheckBox;
    private CheckBox restoreAfterBootCheckBox;
    private CheckBox releaseAfterDisconnectCheckBox;
    private CheckBox autoStopCheckBox;
    private CheckBox resetBluetoothAfterDisconnectCheckBox;
    private CheckBox pauseBluetoothScoDuringMediaCheckBox;
    private CheckBox pinMediaToBluetoothDuringAndroidAutoCheckBox;
    private CheckBox suppressDuckingCheckBox;
    private CheckBox suppressDuckingAlwaysCheckBox;
    private CheckBox muteNotificationsDuringPlaybackCheckBox;
    private CheckBox muteNotificationsDuringPlaybackAlwaysCheckBox;
    private CheckBox normalizeMediaDuringAndroidAutoCheckBox;
    private CheckBox normalizeMediaAlwaysCheckBox;
    private Spinner mediaLevelerSpinner;
    private ArrayAdapter<String> mediaLevelerAdapter;
    private TextView mediaLevelerLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean restoreWasRunning = isSettingsRestoreInProgress();
        refreshAfterExternalRestore = restoreWasRunning;
        if (!restoreWasRunning) {
            AppPrefs.removeLegacyMediaRelaySettings(this);
            AppPrefs.migrateLoudnessNormalizationSettings(this);
        }
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
    protected void onResume() {
        super.onResume();
        reconcileSettingsRestoreUiState();
    }

    @Override
    protected void onPause() {
        getWindow().getDecorView().removeCallbacks(restoreUiStatePoll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // A configuration change must not abandon a restore or a streamed history export.
        // Let that already-authorized task finish and release its process-wide guard.
        if (isSettingsRestoreInProgress() || DIAGNOSTICS_SAVE_IN_PROGRESS.get()) {
            executor.shutdown();
        } else {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            refreshDevices();
            if (gpsWarmupDuringAndroidAutoCheckBox != null
                    && gpsWarmupDuringAndroidAutoCheckBox.isChecked()) {
                updateGpsWarmupPermissionLog();
            }
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
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressProfileSelectionLoad || position < 0 || position >= profileEntries.size()) {
                    return;
                }
                ProfileSettings.ProfileEntry entry = profileEntries.get(position);
                if (entry.id.equals(ProfileSettings.activeProfileId(MainActivity.this))) {
                    return;
                }
                loadProfile(entry, false);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
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
        rootCheckBox.setText("Use root for diagnostics and volume shortcuts");
        rootCheckBox.setTextColor(getColor(R.color.aaarp_text));
        rootCheckBox.setChecked(prefBoolean(AppPrefs.USE_ROOT, false));
        rootCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isPreferenceWriteSuppressed()) {
                AppPrefs.get(this).edit().putBoolean(AppPrefs.USE_ROOT, isChecked).apply();
            }
        });
        content.addView(rootCheckBox, blockParams());

        watchdogCheckBox = new CheckBox(this);
        watchdogCheckBox.setText("Watch Android Auto");
        watchdogCheckBox.setTextColor(getColor(R.color.aaarp_text));
        watchdogCheckBox.setChecked(prefBoolean(AppPrefs.WATCHDOG_MODE, true));
        watchdogCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                saveBooleanSetting(AppPrefs.WATCHDOG_MODE, isChecked));
        content.addView(watchdogCheckBox, blockParams());

        gpsWarmupDuringAndroidAutoCheckBox = new CheckBox(this);
        gpsWarmupDuringAndroidAutoCheckBox.setText("Warm up GPS when Android Auto starts");
        gpsWarmupDuringAndroidAutoCheckBox.setTextColor(getColor(R.color.aaarp_text));
        gpsWarmupDuringAndroidAutoCheckBox.setChecked(
                prefBoolean(AppPrefs.GPS_WARMUP_DURING_ANDROID_AUTO, false)
        );
        gpsWarmupDuringAndroidAutoCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isPreferenceWriteSuppressed()) {
                return;
            }
            saveBooleanSetting(AppPrefs.GPS_WARMUP_DURING_ANDROID_AUTO, isChecked);
            if (isChecked) {
                requestGpsWarmupLocationAccess();
            }
        });
        content.addView(gpsWarmupDuringAndroidAutoCheckBox, blockParams());

        Button locationPermissionButton = button("Location Permission", v -> openLocationPermissionSettings());
        content.addView(locationPermissionButton, blockParams());

        restoreAfterBootCheckBox = new CheckBox(this);
        restoreAfterBootCheckBox.setText("Restore monitor after reboot");
        restoreAfterBootCheckBox.setTextColor(getColor(R.color.aaarp_text));
        restoreAfterBootCheckBox.setChecked(prefBoolean(AppPrefs.RESTORE_MONITOR_AFTER_BOOT, true));
        restoreAfterBootCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isPreferenceWriteSuppressed()) {
                AppPrefs.get(this).edit().putBoolean(AppPrefs.RESTORE_MONITOR_AFTER_BOOT, isChecked).apply();
            }
        });
        content.addView(restoreAfterBootCheckBox, blockParams());

        releaseAfterDisconnectCheckBox = new CheckBox(this);
        releaseAfterDisconnectCheckBox.setText("Release route after Android Auto disconnects");
        releaseAfterDisconnectCheckBox.setTextColor(getColor(R.color.aaarp_text));
        releaseAfterDisconnectCheckBox.setChecked(prefBoolean(AppPrefs.RELEASE_ROUTE_AFTER_ANDROID_AUTO, true));
        releaseAfterDisconnectCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                saveBooleanSetting(AppPrefs.RELEASE_ROUTE_AFTER_ANDROID_AUTO, isChecked));
        content.addView(releaseAfterDisconnectCheckBox, blockParams());

        autoStopCheckBox = new CheckBox(this);
        autoStopCheckBox.setText("Auto-stop after Android Auto disconnects");
        autoStopCheckBox.setTextColor(getColor(R.color.aaarp_text));
        autoStopCheckBox.setChecked(prefBoolean(AppPrefs.AUTO_STOP_AFTER_ANDROID_AUTO, false));
        autoStopCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                saveBooleanSetting(AppPrefs.AUTO_STOP_AFTER_ANDROID_AUTO, isChecked));
        content.addView(autoStopCheckBox, blockParams());

        resetBluetoothAfterDisconnectCheckBox = new CheckBox(this);
        resetBluetoothAfterDisconnectCheckBox.setText("Reset Bluetooth after Android Auto disconnects");
        resetBluetoothAfterDisconnectCheckBox.setTextColor(getColor(R.color.aaarp_text));
        resetBluetoothAfterDisconnectCheckBox.setChecked(prefBoolean(AppPrefs.RESET_BLUETOOTH_AFTER_ANDROID_AUTO, false));
        resetBluetoothAfterDisconnectCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                saveBooleanSetting(AppPrefs.RESET_BLUETOOTH_AFTER_ANDROID_AUTO, isChecked));
        content.addView(resetBluetoothAfterDisconnectCheckBox, blockParams());

        pauseBluetoothScoDuringMediaCheckBox = new CheckBox(this);
        pauseBluetoothScoDuringMediaCheckBox.setText("Avoid call-quality Bluetooth during media playback");
        pauseBluetoothScoDuringMediaCheckBox.setTextColor(getColor(R.color.aaarp_text));
        pauseBluetoothScoDuringMediaCheckBox.setChecked(prefBoolean(AppPrefs.PAUSE_BLUETOOTH_SCO_DURING_MEDIA, true));
        pauseBluetoothScoDuringMediaCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                saveBooleanSetting(AppPrefs.PAUSE_BLUETOOTH_SCO_DURING_MEDIA, isChecked));
        content.addView(pauseBluetoothScoDuringMediaCheckBox, blockParams());

        pinMediaToBluetoothDuringAndroidAutoCheckBox = new CheckBox(this);
        pinMediaToBluetoothDuringAndroidAutoCheckBox.setText("Keep media on Bluetooth during Android Auto");
        pinMediaToBluetoothDuringAndroidAutoCheckBox.setTextColor(getColor(R.color.aaarp_text));
        pinMediaToBluetoothDuringAndroidAutoCheckBox.setChecked(
                prefBoolean(AppPrefs.PIN_MEDIA_TO_BLUETOOTH_DURING_ANDROID_AUTO, false)
        );
        pinMediaToBluetoothDuringAndroidAutoCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                saveBooleanSetting(AppPrefs.PIN_MEDIA_TO_BLUETOOTH_DURING_ANDROID_AUTO, isChecked));
        content.addView(pinMediaToBluetoothDuringAndroidAutoCheckBox, blockParams());

        normalizeMediaDuringAndroidAutoCheckBox = new CheckBox(this);
        normalizeMediaDuringAndroidAutoCheckBox.setText("Keep songs at a consistent loudness during Android Auto");
        normalizeMediaDuringAndroidAutoCheckBox.setTextColor(getColor(R.color.aaarp_text));
        normalizeMediaDuringAndroidAutoCheckBox.setChecked(
                prefBoolean(AppPrefs.NORMALIZE_MEDIA_DURING_ANDROID_AUTO, false)
        );
        normalizeMediaDuringAndroidAutoCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveBooleanSetting(AppPrefs.NORMALIZE_MEDIA_DURING_ANDROID_AUTO, isChecked);
            updateDependentSoundTweakControls();
        });
        content.addView(normalizeMediaDuringAndroidAutoCheckBox, blockParams());

        TextView mediaLevelerExplanation = text(
                "AAARP uses an approximate stereo-linked RMS leveler, not measured per-track "
                        + "LUFS. It only turns louder audio windows down toward a quiet anchor; "
                        + "it never adds signal gain or changes your media-volume setting. "
                        + "Only you control that volume, and exact perceived loudness is not "
                        + "guaranteed.",
                13,
                false
        );
        mediaLevelerExplanation.setTextColor(getColor(R.color.aaarp_muted));
        content.addView(mediaLevelerExplanation, blockParams());

        normalizeMediaAlwaysCheckBox = new CheckBox(this);
        normalizeMediaAlwaysCheckBox.setText(
                "Keep leveling active outside Android Auto, including earbuds (recommended)"
        );
        normalizeMediaAlwaysCheckBox.setTextColor(getColor(R.color.aaarp_text));
        normalizeMediaAlwaysCheckBox.setChecked(prefBoolean(AppPrefs.NORMALIZE_MEDIA_ALWAYS, true));
        normalizeMediaAlwaysCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                saveBooleanSetting(AppPrefs.NORMALIZE_MEDIA_ALWAYS, isChecked));
        content.addView(normalizeMediaAlwaysCheckBox, blockParams());

        mediaLevelerLabel = text("Loudness leveling strength", 15, true);
        mediaLevelerLabel.setTextColor(getColor(R.color.aaarp_text));
        content.addView(mediaLevelerLabel, blockParams());

        mediaLevelerSpinner = new Spinner(this);
        mediaLevelerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        mediaLevelerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mediaLevelerSpinner.setAdapter(mediaLevelerAdapter);
        for (String label : MEDIA_LEVELER_LABELS) {
            mediaLevelerAdapter.add(label);
        }
        mediaLevelerAdapter.notifyDataSetChanged();
        restoreMediaLevelerMode();
        mediaLevelerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressMediaLevelerSave) {
                    return;
                }
                saveStringSetting(
                        AppPrefs.MEDIA_LEVELER_MODE,
                        MediaLevelerProfile.modeForLabelIndex(position)
                );
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        content.addView(mediaLevelerSpinner, blockParams());

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
            saveBooleanSetting(AppPrefs.SUPPRESS_NOTIFICATION_DUCKING, isChecked);
            updateDependentSoundTweakControls();
        });
        content.addView(suppressDuckingCheckBox, blockParams());

        suppressDuckingAlwaysCheckBox = new CheckBox(this);
        suppressDuckingAlwaysCheckBox.setText("Also stop notification ducking outside Android Auto");
        suppressDuckingAlwaysCheckBox.setTextColor(getColor(R.color.aaarp_text));
        suppressDuckingAlwaysCheckBox.setChecked(prefBoolean(AppPrefs.SUPPRESS_NOTIFICATION_DUCKING_ALWAYS, false));
        suppressDuckingAlwaysCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                saveBooleanSetting(AppPrefs.SUPPRESS_NOTIFICATION_DUCKING_ALWAYS, isChecked));
        content.addView(suppressDuckingAlwaysCheckBox, blockParams());

        muteNotificationsDuringPlaybackCheckBox = new CheckBox(this);
        muteNotificationsDuringPlaybackCheckBox.setText("Mute notification sounds during media playback");
        muteNotificationsDuringPlaybackCheckBox.setTextColor(getColor(R.color.aaarp_text));
        muteNotificationsDuringPlaybackCheckBox.setChecked(prefBoolean(AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK, false));
        muteNotificationsDuringPlaybackCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveBooleanSetting(AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK, isChecked);
            updateDependentSoundTweakControls();
        });
        content.addView(muteNotificationsDuringPlaybackCheckBox, blockParams());

        muteNotificationsDuringPlaybackAlwaysCheckBox = new CheckBox(this);
        muteNotificationsDuringPlaybackAlwaysCheckBox.setText("Also mute during playback outside Android Auto");
        muteNotificationsDuringPlaybackAlwaysCheckBox.setTextColor(getColor(R.color.aaarp_text));
        muteNotificationsDuringPlaybackAlwaysCheckBox.setChecked(prefBoolean(AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK_ALWAYS, false));
        muteNotificationsDuringPlaybackAlwaysCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                saveBooleanSetting(AppPrefs.MUTE_NOTIFICATIONS_DURING_PLAYBACK_ALWAYS, isChecked));
        content.addView(muteNotificationsDuringPlaybackAlwaysCheckBox, blockParams());
        updateDependentSoundTweakControls();

        LinearLayout rowThree = buttonRow();
        rowThree.addView(button("Root Check", v -> checkRootStatus()), weightParams());
        rowThree.addView(button("Diagnostics", v -> runRootDiagnostics()), weightParams());
        content.addView(rowThree, blockParams());

        LinearLayout rowFour = buttonRow();
        saveDiagnosticsButton = button("Save Diagnostics", v -> saveRootDiagnostics());
        rowFour.addView(saveDiagnosticsButton, weightParams());
        rowFour.addView(button("Battery Exempt", v -> requestBatteryExemption()), weightParams());
        content.addView(rowFour, blockParams());
        content.addView(text("AAARP keeps a rolling 48-hour history while the monitor is running, "
                + "including Bluetooth and audio changes. Save Diagnostics writes the retained "
                + "history and a fresh snapshot to one text file.", 14, false), blockParams());

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
        refreshDevices(true);
    }

    private void refreshDevices(boolean showStatus) {
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
            if (showStatus) {
                updateStatus("Devices refreshed.");
            }
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
        if (rejectActionDuringRestore()) {
            return;
        }
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
        if (rejectActionDuringRestore()) {
            return;
        }
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
        if (rejectActionDuringRestore()) {
            return;
        }
        saveSelectedDevice();
        String preferredBluetoothTarget = savePreferredBluetoothTarget();
        saveNotificationRouteMode();
        String gpsNote = gpsWarmupMonitorNote();
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
                setLog("Watchdog started. Select a default Bluetooth audio target before AAARP will route Android Auto."
                        + gpsNote);
            } else {
                setLog("Watchdog started. AAARP will wait for Android Auto and the default Bluetooth target, then maintain the selected route."
                        + gpsNote);
            }
        } else {
            setLog("Monitor started. AAARP will watch the selected route and only reset it if Android changes it."
                    + gpsNote);
        }
    }

    private void stopRouteMonitor() {
        if (rejectActionDuringRestore()) {
            return;
        }
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
            setLog("Enable root support first.");
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
        if (rejectActionDuringRestore()) {
            return;
        }
        if (!DIAGNOSTICS_SAVE_IN_PROGRESS.compareAndSet(false, true)) {
            setLog("A diagnostics file is already being saved.");
            return;
        }
        boolean useRoot = rootCheckBox.isChecked();
        saveDiagnosticsButton.setEnabled(false);
        setLog("Saving the retained 48-hour history and collecting a fresh snapshot...");
        AutoLogWriter.append(this, "diagnostics export requested monitorEnabled="
                + prefBoolean(AppPrefs.MONITOR_ENABLED, false));
        executor.execute(() -> {
            try {
                String body = collectDiagnosticsReport(useRoot);
                String location = DiagnosticsFileWriter.write(this, output -> {
                    output.write(body.getBytes(StandardCharsets.UTF_8));
                    output.write("\n\n===== ROLLING APP HISTORY =====\n".getBytes(StandardCharsets.UTF_8));
                    AutoLogWriter.writeHistory(this, output);
                });
                AutoLogWriter.append(this, "diagnostics export completed location=" + location);
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        updateStatus("Diagnostics and history saved.");
                        setLog("Saved the current snapshot and all retained history to " + location);
                    }
                });
            } catch (Exception e) {
                AutoLogWriter.append(this, "diagnostics export failed: " + e.getMessage());
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        updateStatus("Save failed.");
                        setLog("Could not save diagnostics: " + e.getMessage()
                                + "\nSaving does not clear the rolling history. Try Save Diagnostics again.");
                    }
                });
            } finally {
                DIAGNOSTICS_SAVE_IN_PROGRESS.set(false);
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        saveDiagnosticsButton.setEnabled(true);
                    }
                });
            }
        });
    }

    private String collectDiagnosticsReport(boolean useRoot) {
        try {
            RootShell.ShellResult result = useRoot
                    ? controller.rootDiagnostics()
                    : new RootShell.ShellResult(true, 0,
                            "Root diagnostics skipped: root support is disabled.\n");
            return buildDiagnosticsReport(result, useRoot);
        } catch (RuntimeException failure) {
            // A snapshot/permission failure must not prevent exporting the existing history.
            return "AAARP diagnostics\nSnapshot collection failed: " + failure.getMessage()
                    + "\nThe retained app history follows.\n";
        }
    }

    private void detectAndroidAutoProfile() {
        if (rejectActionDuringRestore()) {
            return;
        }
        setLog("Detecting Android Auto connection...");
        ProfileSettings.ProfileEntry selectedBeforeDetection = selectedProfileEntry();
        String selectedProfileId = selectedBeforeDetection == null
                ? ProfileSettings.activeProfileId(this)
                : selectedBeforeDetection.id;
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
                        selectProfile(selectedProfileId);
                        updateStatus("Android Auto is not running.");
                        setLog("No active Android Auto connection detected."
                                + visibleWifi
                                + "\nSelected profile: " + selectedProfileId);
                    });
                    return;
                }

                lastDetectedConnection = connection;
                String mappedProfileId = ProfileSettings.mappedProfileIdForConnection(this, connection);
                runOnUiThread(() -> {
                    String displayedProfileId;
                    if (mappedProfileId != null) {
                        displayedProfileId = mappedProfileId;
                        loadProfile(profileEntryForId(mappedProfileId), false);
                        updateStatus("Android Auto profile detected.");
                    } else if (connection.specific()) {
                        displayedProfileId = selectedProfileId;
                        loadProfile(profileEntryForId(selectedProfileId), false);
                        updateStatus("Android Auto connection detected. Choose a profile, then Save Profile.");
                    } else {
                        displayedProfileId = ProfileSettings.DEFAULT_PROFILE_ID;
                        loadProfile(profileEntryForId(ProfileSettings.DEFAULT_PROFILE_ID), false);
                        updateStatus("Android Auto running; no specific profile detected.");
                    }
                    setLog("Android Auto process: " + (androidAutoRunning ? "detected" : "not confirmed")
                            + "\nDetected profile source: " + connection.label()
                            + "\nConnection key: " + connection.key()
                            + (mappedProfileId == null
                            ? "\nProfile: unmapped\nSave target: " + displayedProfileId
                            : "\nProfile: " + displayedProfileId));
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> {
                    selectProfile(selectedProfileId);
                    lastDetectedConnection = AndroidAutoConnection.fallback();
                    updateStatus("Android Auto detection failed.");
                    setLog("Could not detect Android Auto profile: " + e.getMessage()
                            + "\nSelected profile: " + selectedProfileId);
                });
            }
        });
    }

    private void saveProfileForCurrentConnection() {
        if (rejectActionDuringRestore()) {
            return;
        }
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
                String profileId = selectedProfile == null
                        ? ProfileSettings.activeProfileId(this)
                        : selectedProfile.id;
                String label = selectedProfile == null
                        ? profileId
                        : selectedProfile.displayLabel();
                entry = ProfileSettings.saveCurrentSettingsForConnection(
                        this,
                        connection,
                        profileId,
                        label
                );
            } else {
                String profileId = selectedProfile == null ? ProfileSettings.DEFAULT_PROFILE_ID : selectedProfile.id;
                String label = selectedProfile == null ? "Default" : selectedProfile.displayLabel();
                entry = ProfileSettings.saveCurrentSettingsToProfile(this, profileId, label);
            }
            refreshProfiles();
            selectProfile(entry.id);
            restoreSettingsControls();
            lastDetectedConnection = AndroidAutoConnection.fallback();
            updateStatus("Profile saved.");
            setLog("Saved profile: " + entry.displayLabel()
                    + "\nConnection: " + connection.label()
                    + "\nConnection key: " + connection.key()
                    + (connection.specific() ? "" : "\nThis is the default for new or unknown Android Auto connections."));
        } catch (RuntimeException e) {
            updateStatus("Profile save failed.");
            setLog("Could not save profile: " + e.getMessage());
        }
    }

    private void loadSelectedProfile() {
        if (rejectActionDuringRestore()) {
            return;
        }
        ProfileSettings.ProfileEntry entry = selectedProfileEntry();
        if (entry == null) {
            setLog("No profile selected.");
            return;
        }
        loadProfile(entry, true);
    }

    private void loadProfile(ProfileSettings.ProfileEntry entry, boolean showLog) {
        if (entry == null || isSettingsRestoreInProgress()) {
            return;
        }
        ProfileSettings.loadProfileIntoCurrentSettings(this, entry.id);
        refreshDevices(false);
        restoreSettingsControls();
        selectProfile(entry.id);
        updateStatus("Profile loaded.");
        if (showLog) {
            setLog("Loaded profile: " + entry.displayLabel());
        }
    }

    private String buildDiagnosticsReport(RootShell.ShellResult result, boolean useRoot) {
        List<RouteDevice> snapshotRoutes = controller.listRouteDevices();
        AndroidAutoConnection connection = useRoot
                ? controller.currentAndroidAutoConnection()
                : AndroidAutoConnection.fallback();
        ProfileSettings.MonitorSettings settings = ProfileSettings.monitorSettings(this, connection);
        StringBuilder report = new StringBuilder();
        report.append("AAARP diagnostics\n");
        report.append("Current snapshot collected: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date()))
                .append('\n');
        report.append("Monitor enabled: ")
                .append(prefBoolean(AppPrefs.MONITOR_ENABLED, false) ? "yes" : "no").append('\n');
        report.append("Root support enabled: ").append(useRoot ? "yes" : "no").append('\n');
        report.append("Saved history: up to 48 hours recorded by AAARP; no history can be recovered "
                + "for periods when it was not recording.\n");
        report.append(BuildIdentity.describe(this));
        report.append("Android Auto installed: ").append(AndroidAutoStatus.isInstalled(this) ? "yes" : "no").append('\n');
        report.append("Current communication route: ").append(controller.currentCommunicationDevice()).append('\n');
        report.append("Available input routes:\n").append(controller.inputRouteSummary()).append('\n');
        report.append("Active profile: ").append(settings.profileId)
                .append(" - ").append(connection.label()).append('\n');
        report.append("Android Auto connection key: ").append(connection.key()).append('\n');
        report.append("Watch Android Auto: ")
                .append(settings.watchdogMode ? "on" : "off").append('\n');
        report.append("Warm up GPS when Android Auto starts: ")
                .append(settings.gpsWarmupDuringAndroidAuto ? "on" : "off").append('\n');
        report.append("GPS warm-up permission: ")
                .append(LocationWarmup.permissionSummary(this)).append('\n');
        report.append("Restore monitor after reboot: ")
                .append(prefBoolean(AppPrefs.RESTORE_MONITOR_AFTER_BOOT, true) ? "on" : "off").append('\n');
        report.append("Default Bluetooth target: ")
                .append(settings.preferredBluetoothTarget == null
                        || settings.preferredBluetoothTarget.length() == 0
                        ? "none"
                        : settings.preferredBluetoothTarget).append('\n');
        report.append("Release route after Android Auto disconnects: ")
                .append(settings.releaseAfterAndroidAuto ? "on" : "off").append('\n');
        report.append("Auto-stop after Android Auto disconnects: ")
                .append(settings.autoStopAfterAndroidAuto ? "on" : "off").append('\n');
        report.append("Reset Bluetooth after Android Auto disconnects: ")
                .append(settings.resetBluetoothAfterAndroidAuto ? "on" : "off").append('\n');
        report.append("Avoid call-quality Bluetooth during media playback: ")
                .append(settings.pauseBluetoothScoDuringMedia ? "on" : "off").append('\n');
        report.append("Keep media on Bluetooth during Android Auto: ")
                .append(settings.pinMediaToBluetoothDuringAndroidAuto ? "on" : "off").append('\n');
        report.append("Consistent song loudness during Android Auto: ")
                .append(settings.normalizeMediaDuringAndroidAuto ? "on" : "off").append('\n');
        report.append("Consistent song loudness outside Android Auto: ")
                .append(settings.normalizeMediaAlways ? "on" : "off").append('\n');
        report.append("Media volume ownership: user only (leveler never changes STREAM_MUSIC)\n");
        report.append("Loudness leveling mode: ")
                .append(settings.mediaLevelerMode).append('\n');
        report.append("Leveling tuning: ")
                .append(MediaLevelerProfile.forMode(settings.mediaLevelerMode).summary()).append('\n');
        report.append("Music volume: ").append(controller.musicVolumeSummary()).append('\n');
        report.append("Notification route during Android Auto: ")
                .append(settings.notificationRouteMode).append('\n');
        report.append("Try to stop notification ducking: ")
                .append(settings.suppressNotificationDucking ? "on" : "off").append('\n');
        report.append("Stop notification ducking outside Android Auto: ")
                .append(settings.suppressNotificationDuckingAlways ? "on" : "off").append('\n');
        report.append("Mute notification sounds during playback: ")
                .append(settings.muteNotificationsDuringPlayback ? "on" : "off").append('\n');
        report.append("Mute notification sounds outside Android Auto: ")
                .append(settings.muteNotificationsDuringPlaybackAlways ? "on" : "off").append('\n');
        report.append("Auto log directory: ").append(AutoLogWriter.location(this)).append('\n');
        report.append('\n').append(BluetoothDeviceCatalog.describe(this, snapshotRoutes)).append('\n');
        report.append("\nRoot diagnostics exit code: ").append(result.exitCode).append('\n');
        report.append(result.output.length() == 0 ? "No root diagnostics output.\n" : result.output);
        return report.toString();
    }

    private void requestBatteryExemption() {
        if (rejectActionDuringRestore()) {
            return;
        }
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
        if (rejectActionDuringRestore()) {
            return;
        }
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
        if (rejectActionDuringRestore()) {
            return;
        }
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
        if (rejectActionDuringRestore()) {
            return;
        }
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
        if (!beginSettingsRestore()) {
            setLog("A settings restore is already in progress.");
            return;
        }
        setLog("Restoring settings backup...");
        try {
            executor.execute(() -> {
                try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
                    if (inputStream == null) {
                        throw new IOException("Could not open settings backup.");
                    }
                    SettingsBackup.RestoreResult result = SettingsBackup.restore(this, inputStream);
                    runOnUiThread(() -> {
                        try {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            refreshDevices();
                            restoreSettingsControls();
                            refreshProfiles();
                            updateStatus(result.success
                                    ? "Settings restored. Monitor stopped."
                                    : "Settings restored; access needs attention. Monitor stopped.");
                            setLog("Restored " + result.preferenceCount + " settings/profile entries."
                                    + rootRestoreSummary(result.rootRestoreResult)
                                    + "\n\nThe monitor remains stopped. Review the restored settings and access "
                                    + "results, then tap Start Monitor when you are ready.");
                        } finally {
                            finishSettingsRestore();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        try {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            boolean monitorStopped = !prefBoolean(AppPrefs.MONITOR_ENABLED, false);
                            updateStatus(monitorStopped
                                    ? "Restore failed. Monitor stopped."
                                    : "Restore failed before the monitor changed.");
                            setLog("Could not restore settings backup: " + e.getMessage()
                                    + (monitorStopped
                                    ? "\n\nThe monitor remains stopped. Review the existing settings, then tap "
                                    + "Start Monitor when you are ready."
                                    : ""));
                        } finally {
                            finishSettingsRestore();
                        }
                    });
                }
            });
        } catch (RuntimeException e) {
            finishSettingsRestore();
            updateStatus("Restore failed before it started.");
            setLog("Could not start settings restore: " + e.getMessage());
        }
    }

    private String rootRestoreSummary(SettingsBackup.RootRestoreResult result) {
        if (result == null) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        summary.append("\nRoot restore: ").append(result.message);
        if (result.attempted && result.rootAvailable) {
            summary.append("\nRuntime permissions requested and checked: ")
                    .append(result.runtimePermissionCount);
            summary.append("\nBattery exemption requested: ")
                    .append(result.batteryExemptionAttempted ? "yes" : "no");
            summary.append("\nAccess verification: ").append(result.success ? "passed" : "failed");
        }
        if (result.output.length() > 0) {
            summary.append("\n\n").append(result.output);
        }
        return summary.toString();
    }

    private void resetBluetoothNow() {
        if (rejectActionDuringRestore()) {
            return;
        }
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
        boolean gpsWarmupEnabled = prefBoolean(AppPrefs.GPS_WARMUP_DURING_ANDROID_AUTO, false);
        boolean restoreAfterBoot = prefBoolean(AppPrefs.RESTORE_MONITOR_AFTER_BOOT, true);
        statusView.setText(message + "\nMonitor: " + (monitorEnabled ? "on" : "off")
                + "\nWatch Android Auto: " + (watchdogEnabled ? "on" : "off")
                + "\nGPS warm-up: " + (gpsWarmupEnabled ? "on" : "off")
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
        saveStringSetting(AppPrefs.SELECTED_DEVICE_KEY, key);
        return key;
    }

    private String savePreferredBluetoothTarget() {
        String customTarget = preferredTargetEditText == null ? "" : preferredTargetEditText.getText().toString().trim();
        String savedTarget = selectedBluetoothTargetQuery();
        String target = savedTarget;
        if (customTarget.length() > 0) {
            target = target.length() == 0 ? customTarget : target + "|" + customTarget;
        }
        saveStringSetting(AppPrefs.PREFERRED_BLUETOOTH_QUERY, target);
        saveStringSetting(AppPrefs.CUSTOM_BLUETOOTH_QUERY, customTarget);
        return target;
    }

    private String selectedBluetoothTargetQuery() {
        int index = bluetoothTargetSpinner == null ? -1 : bluetoothTargetSpinner.getSelectedItemPosition();
        if (index <= 0 || index > bluetoothTargets.size()) {
            removeStringSetting(AppPrefs.SELECTED_BLUETOOTH_TARGET_KEY);
            return "";
        }
        BluetoothTarget target = bluetoothTargets.get(index - 1);
        saveStringSetting(AppPrefs.SELECTED_BLUETOOTH_TARGET_KEY, target.key());
        return target.matchQuery();
    }

    private void saveNotificationRouteMode() {
        int index = notificationRouteSpinner == null ? 0 : notificationRouteSpinner.getSelectedItemPosition();
        if (index < 0 || index >= NOTIFICATION_ROUTE_VALUES.length) {
            index = 0;
        }
        saveStringSetting(AppPrefs.NOTIFICATION_ROUTE_MODE, NOTIFICATION_ROUTE_VALUES[index]);
    }

    private void saveStringSetting(String key, String value) {
        if (isPreferenceWriteSuppressed()) {
            return;
        }
        ProfileSettings.saveActiveString(this, key, value);
    }

    private void removeStringSetting(String key) {
        if (isPreferenceWriteSuppressed()) {
            return;
        }
        ProfileSettings.removeActiveString(this, key);
    }

    private void saveBooleanSetting(String key, boolean value) {
        if (isPreferenceWriteSuppressed()) {
            return;
        }
        ProfileSettings.saveActiveBoolean(this, key, value);
    }

    private void saveCurrentUiSettings() {
        saveSelectedDevice();
        savePreferredBluetoothTarget();
        saveNotificationRouteMode();
        saveMediaLevelerMode();
    }

    private void saveMediaLevelerMode() {
        int index = mediaLevelerSpinner == null ? -1 : mediaLevelerSpinner.getSelectedItemPosition();
        if (index < 0) {
            return;
        }
        saveStringSetting(AppPrefs.MEDIA_LEVELER_MODE, MediaLevelerProfile.modeForLabelIndex(index));
    }

    private void restoreMediaLevelerMode() {
        if (mediaLevelerSpinner == null) {
            return;
        }
        String mode = prefString(AppPrefs.MEDIA_LEVELER_MODE, MediaLevelerProfile.DEFAULT_MODE);
        suppressMediaLevelerSave = true;
        try {
            mediaLevelerSpinner.setSelection(MediaLevelerProfile.labelIndexForMode(mode));
        } finally {
            suppressMediaLevelerSave = false;
        }
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
        gpsWarmupDuringAndroidAutoCheckBox.setChecked(
                prefBoolean(AppPrefs.GPS_WARMUP_DURING_ANDROID_AUTO, false)
        );
        restoreAfterBootCheckBox.setChecked(prefBoolean(AppPrefs.RESTORE_MONITOR_AFTER_BOOT, true));
        releaseAfterDisconnectCheckBox.setChecked(prefBoolean(AppPrefs.RELEASE_ROUTE_AFTER_ANDROID_AUTO, true));
        autoStopCheckBox.setChecked(prefBoolean(AppPrefs.AUTO_STOP_AFTER_ANDROID_AUTO, false));
        resetBluetoothAfterDisconnectCheckBox.setChecked(prefBoolean(AppPrefs.RESET_BLUETOOTH_AFTER_ANDROID_AUTO, false));
        pauseBluetoothScoDuringMediaCheckBox.setChecked(prefBoolean(AppPrefs.PAUSE_BLUETOOTH_SCO_DURING_MEDIA, true));
        pinMediaToBluetoothDuringAndroidAutoCheckBox.setChecked(
                prefBoolean(AppPrefs.PIN_MEDIA_TO_BLUETOOTH_DURING_ANDROID_AUTO, false)
        );
        normalizeMediaDuringAndroidAutoCheckBox.setChecked(
                prefBoolean(AppPrefs.NORMALIZE_MEDIA_DURING_ANDROID_AUTO, false)
        );
        normalizeMediaAlwaysCheckBox.setChecked(prefBoolean(AppPrefs.NORMALIZE_MEDIA_ALWAYS, true));
        restoreMediaLevelerMode();
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
        if (normalizeMediaAlwaysCheckBox != null && normalizeMediaDuringAndroidAutoCheckBox != null) {
            normalizeMediaAlwaysCheckBox.setEnabled(normalizeMediaDuringAndroidAutoCheckBox.isChecked());
        }
        if (mediaLevelerSpinner != null && normalizeMediaDuringAndroidAutoCheckBox != null) {
            boolean levelerEnabled = normalizeMediaDuringAndroidAutoCheckBox.isChecked();
            mediaLevelerSpinner.setEnabled(levelerEnabled);
            if (mediaLevelerLabel != null) {
                mediaLevelerLabel.setEnabled(levelerEnabled);
            }
        }
    }

    private void refreshProfiles() {
        if (profileAdapter == null) {
            return;
        }
        String selectedId = ProfileSettings.activeProfileId(this);
        suppressProfileSelectionLoad = true;
        try {
            profileEntries.clear();
            profileEntries.addAll(ProfileSettings.listProfiles(this));
            profileAdapter.clear();
            for (ProfileSettings.ProfileEntry entry : profileEntries) {
                profileAdapter.add(entry.displayLabel());
            }
            profileAdapter.notifyDataSetChanged();
            selectProfile(selectedId);
        } finally {
            suppressProfileSelectionLoad = false;
        }
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
                setProfileSelection(i);
                return;
            }
        }
        setProfileSelection(0);
    }

    private void setProfileSelection(int index) {
        boolean previous = suppressProfileSelectionLoad;
        suppressProfileSelectionLoad = true;
        try {
            profileSpinner.setSelection(index);
        } finally {
            suppressProfileSelectionLoad = previous;
        }
    }

    private ProfileSettings.ProfileEntry selectedProfileEntry() {
        int index = profileSpinner == null ? -1 : profileSpinner.getSelectedItemPosition();
        if (index < 0 || index >= profileEntries.size()) {
            return null;
        }
        return profileEntries.get(index);
    }

    private ProfileSettings.ProfileEntry profileEntryForId(String profileId) {
        if (profileId != null) {
            for (ProfileSettings.ProfileEntry entry : profileEntries) {
                if (profileId.equals(entry.id)) {
                    return entry;
                }
            }
        }
        for (ProfileSettings.ProfileEntry entry : profileEntries) {
            if (ProfileSettings.DEFAULT_PROFILE_ID.equals(entry.id)) {
                return entry;
            }
        }
        return null;
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
            if (!routeDevices.isEmpty()) {
                deviceSpinner.setSelection(0);
            }
            return;
        }
        for (int i = 0; i < routeDevices.size(); i++) {
            if (selectedKey.equals(routeDevices.get(i).key())) {
                deviceSpinner.setSelection(i);
                return;
            }
        }
        if (!routeDevices.isEmpty()) {
            deviceSpinner.setSelection(0);
        }
    }

    private void restoreSelectedBluetoothTarget() {
        String selectedKey = prefString(AppPrefs.SELECTED_BLUETOOTH_TARGET_KEY, null);
        if (selectedKey == null) {
            bluetoothTargetSpinner.setSelection(0);
            return;
        }
        for (int i = 0; i < bluetoothTargets.size(); i++) {
            if (selectedKey.equals(bluetoothTargets.get(i).key())) {
                bluetoothTargetSpinner.setSelection(i + 1);
                return;
            }
        }
        bluetoothTargetSpinner.setSelection(0);
    }

    private void requestRuntimePermissions() {
        if (isSettingsRestoreInProgress()) {
            return;
        }
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
        if (prefBoolean(AppPrefs.GPS_WARMUP_DURING_ANDROID_AUTO, false)) {
            addGpsWarmupLocationPermissions(permissions);
        }

        if (!permissions.isEmpty()) {
            try {
                requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
            } catch (RuntimeException e) {
                setLog("Permission request skipped: " + e.getMessage());
            }
        }
    }

    private void requestGpsWarmupLocationAccess() {
        if (isSettingsRestoreInProgress()) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            setLog("GPS warm-up is enabled.");
            return;
        }
        List<String> permissions = new ArrayList<>();
        addGpsWarmupLocationPermissions(permissions);
        if (!permissions.isEmpty()) {
            try {
                requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
                setLog("Precise location permission is needed for GPS warm-up.");
            } catch (RuntimeException e) {
                setLog("Location permission request skipped: " + e.getMessage());
            }
            return;
        }
        updateGpsWarmupPermissionLog();
    }

    private void addGpsWarmupLocationPermissions(List<String> permissions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private void updateGpsWarmupPermissionLog() {
        if (!prefBoolean(AppPrefs.GPS_WARMUP_DURING_ANDROID_AUTO, false)) {
            return;
        }
        if (!LocationWarmup.hasPreciseLocationPermission(this)) {
            setLog("GPS warm-up is on, but Precise Location permission is missing.");
            return;
        }
        if (!LocationWarmup.hasBackgroundLocationPermission(this)) {
            setLog("GPS warm-up is on. For phone-in-pocket startup, open Location Permission and set Location to Allow all the time.");
            return;
        }
        setLog("GPS warm-up is ready. AAARP will briefly ask GPS for a fresh fix when Android Auto starts.");
    }

    private String gpsWarmupMonitorNote() {
        if (!prefBoolean(AppPrefs.GPS_WARMUP_DURING_ANDROID_AUTO, false)) {
            return "";
        }
        if (!LocationWarmup.hasPreciseLocationPermission(this)) {
            return "\nGPS warm-up is on, but Precise Location permission is missing.";
        }
        if (!LocationWarmup.hasBackgroundLocationPermission(this)) {
            return "\nGPS warm-up is on. For phone-in-pocket startup, set Location to Allow all the time.";
        }
        return "\nGPS warm-up is on.";
    }

    private void openLocationPermissionSettings() {
        if (rejectActionDuringRestore()) {
            return;
        }
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private String prefString(String key, String fallback) {
        try {
            return AppPrefs.get(this).getString(key, fallback);
        } catch (RuntimeException e) {
            if (!isPreferenceWriteSuppressed()) {
                AppPrefs.get(this).edit().remove(key).apply();
            }
            return fallback;
        }
    }

    private boolean prefBoolean(String key, boolean fallback) {
        try {
            return AppPrefs.get(this).getBoolean(key, fallback);
        } catch (RuntimeException e) {
            if (!isPreferenceWriteSuppressed()) {
                AppPrefs.get(this).edit().remove(key).apply();
            }
            return fallback;
        }
    }

    private boolean beginSettingsRestore() {
        if (!SETTINGS_RESTORE_IN_PROGRESS.compareAndSet(false, true)) {
            return false;
        }
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        } catch (RuntimeException e) {
            SETTINGS_RESTORE_IN_PROGRESS.set(false);
            throw e;
        }
        return true;
    }

    private void finishSettingsRestore() {
        SETTINGS_RESTORE_IN_PROGRESS.set(false);
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        } catch (RuntimeException ignored) {
            // A replacement Activity will reconcile the process-wide flag in onResume().
        }
    }

    private boolean rejectActionDuringRestore() {
        if (!isSettingsRestoreInProgress()) {
            return false;
        }
        setLog("Wait for the settings restore to finish before changing AAARP.");
        return true;
    }

    private static boolean isSettingsRestoreInProgress() {
        return SETTINGS_RESTORE_IN_PROGRESS.get();
    }

    private boolean isPreferenceWriteSuppressed() {
        return isSettingsRestoreInProgress() || suppressSettingsUiWrites;
    }

    private void reconcileSettingsRestoreUiState() {
        View decorView = getWindow().getDecorView();
        decorView.removeCallbacks(restoreUiStatePoll);
        if (isSettingsRestoreInProgress()) {
            refreshAfterExternalRestore = true;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            decorView.postDelayed(restoreUiStatePoll, RESTORE_UI_POLL_MS);
            return;
        }

        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        if (!refreshAfterExternalRestore || controller == null || statusView == null) {
            return;
        }
        refreshAfterExternalRestore = false;
        suppressSettingsUiWrites = true;
        try {
            AppPrefs.removeLegacyMediaRelaySettings(this);
            AppPrefs.migrateLoudnessNormalizationSettings(this);
            refreshDevices();
            restoreSettingsControls();
            refreshProfiles();
            updateStatus("Settings restore finished. Monitor stopped.");
            setLog("The settings restore finished while this screen was being recreated. "
                    + "Review the restored settings, then tap Start Monitor when you are ready.");
        } finally {
            suppressSettingsUiWrites = false;
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

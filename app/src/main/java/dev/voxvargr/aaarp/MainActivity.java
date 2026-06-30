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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 4100;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AudioRouteController controller;
    private Spinner deviceSpinner;
    private Spinner bluetoothTargetSpinner;
    private ArrayAdapter<String> deviceAdapter;
    private ArrayAdapter<String> bluetoothTargetAdapter;
    private final List<RouteDevice> routeDevices = new ArrayList<>();
    private final List<BluetoothTarget> bluetoothTargets = new ArrayList<>();
    private TextView statusView;
    private TextView currentRouteView;
    private TextView bluetoothInventoryView;
    private TextView logView;
    private EditText preferredTargetEditText;
    private CheckBox rootCheckBox;
    private CheckBox watchdogCheckBox;
    private CheckBox autoStopCheckBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controller = new AudioRouteController(this);
        setContentView(buildContentView());
        requestRuntimePermissions();
        refreshDevices();
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

        deviceSpinner = new Spinner(this);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(deviceAdapter);
        content.addView(deviceSpinner, blockParams());

        TextView savedTargetLabel = text("Saved Bluetooth target", 15, true);
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
        preferredTargetEditText.setText(AppPrefs.get(this).getString(
                AppPrefs.CUSTOM_BLUETOOTH_QUERY,
                AppPrefs.get(this).getString(AppPrefs.PREFERRED_BLUETOOTH_QUERY, "")
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
        rootCheckBox.setChecked(AppPrefs.get(this).getBoolean(AppPrefs.USE_ROOT, false));
        rootCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                AppPrefs.get(this).edit().putBoolean(AppPrefs.USE_ROOT, isChecked).apply());
        content.addView(rootCheckBox, blockParams());

        watchdogCheckBox = new CheckBox(this);
        watchdogCheckBox.setText("Watch Android Auto");
        watchdogCheckBox.setTextColor(getColor(R.color.aaarp_text));
        watchdogCheckBox.setChecked(AppPrefs.get(this).getBoolean(AppPrefs.WATCHDOG_MODE, true));
        watchdogCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                AppPrefs.get(this).edit().putBoolean(AppPrefs.WATCHDOG_MODE, isChecked).apply());
        content.addView(watchdogCheckBox, blockParams());

        autoStopCheckBox = new CheckBox(this);
        autoStopCheckBox.setText("Auto-stop after Android Auto disconnects");
        autoStopCheckBox.setTextColor(getColor(R.color.aaarp_text));
        autoStopCheckBox.setChecked(AppPrefs.get(this).getBoolean(AppPrefs.AUTO_STOP_AFTER_ANDROID_AUTO, false));
        autoStopCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                AppPrefs.get(this).edit().putBoolean(AppPrefs.AUTO_STOP_AFTER_ANDROID_AUTO, isChecked).apply());
        content.addView(autoStopCheckBox, blockParams());

        LinearLayout rowThree = buttonRow();
        rowThree.addView(button("Root Check", v -> checkRootStatus()), weightParams());
        rowThree.addView(button("Diagnostics", v -> runRootDiagnostics()), weightParams());
        content.addView(rowThree, blockParams());

        LinearLayout rowFour = buttonRow();
        rowFour.addView(button("Save Diagnostics", v -> saveRootDiagnostics()), weightParams());
        rowFour.addView(button("Battery Exempt", v -> requestBatteryExemption()), weightParams());
        content.addView(rowFour, blockParams());

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
        savePreferredBluetoothTarget();
        Intent intent = new Intent(this, RoutingMonitorService.class).setAction(RoutingMonitorService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        AppPrefs.get(this).edit().putBoolean(AppPrefs.MONITOR_ENABLED, true).apply();
        updateStatus("Monitor running.");
        if (AppPrefs.get(this).getBoolean(AppPrefs.WATCHDOG_MODE, true)) {
            setLog("Watchdog started. AAARP will wait for Android Auto, then maintain the selected route.");
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
            String body = result.output.length() == 0 ? "No diagnostics output." : result.output;
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

    private void updateStatus(String message) {
        boolean monitorEnabled = AppPrefs.get(this).getBoolean(AppPrefs.MONITOR_ENABLED, false);
        boolean watchdogEnabled = AppPrefs.get(this).getBoolean(AppPrefs.WATCHDOG_MODE, true);
        statusView.setText(message + "\nMonitor: " + (monitorEnabled ? "on" : "off")
                + "\nWatch Android Auto: " + (watchdogEnabled ? "on" : "off")
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
        String selectedKey = AppPrefs.get(this).getString(AppPrefs.SELECTED_DEVICE_KEY, null);
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
        String selectedKey = AppPrefs.get(this).getString(AppPrefs.SELECTED_BLUETOOTH_TARGET_KEY, null);
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
            requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
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

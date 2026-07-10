package dev.voxvargr.aaarp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public final class RoutingMonitorService extends Service {
    static final String ACTION_START = "dev.voxvargr.aaarp.START";
    static final String ACTION_STOP = "dev.voxvargr.aaarp.STOP";
    static final String ACTION_APPLY_ONCE = "dev.voxvargr.aaarp.APPLY_ONCE";
    static final String ACTION_CLEAR = "dev.voxvargr.aaarp.CLEAR";

    private static final String CHANNEL_ID = "aaarp-routing";
    private static final int NOTIFICATION_ID = 9401;
    private static final long ROUTE_CHECK_INTERVAL_MS = 2000L;
    private static final long ANDROID_AUTO_IDLE_CHECK_INTERVAL_MS = 5000L;
    private static final long AUTO_LOG_HEARTBEAT_MS = 30000L;
    private static final long AUTO_LOG_ROOT_SNAPSHOT_MS = 120000L;
    private static final int AUTO_STOP_MISSES = 3;

    private Handler handler;
    private AudioRouteController controller;
    private boolean androidAutoSeen;
    private int androidAutoMisses;
    private boolean routeReleasedAfterDisconnect;
    private boolean routeActiveForTarget;
    private boolean targetWasActiveThisSession;
    private boolean bluetoothResetAfterDisconnect;
    private String activeAudioTweakKey;
    private boolean audioTweakDuckingActive;
    private long lastAutoLogAt;
    private long lastRootSnapshotAt;
    private boolean rootSnapshotRunning;
    private String lastAutoLogSummary;
    private final Runnable applyLoop = new Runnable() {
        @Override
        public void run() {
            long nextDelay = applyFromPrefs();
            if (nextDelay > 0L) {
                handler.postDelayed(this, nextDelay);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        controller = new AudioRouteController(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopMonitor();
            return START_NOT_STICKY;
        }
        if (ACTION_CLEAR.equals(action)) {
            controller.clearRoute();
            stopMonitor();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        AppPrefs.get(this).edit().putBoolean(AppPrefs.MONITOR_ENABLED, true).apply();
        autoLog("monitor start action=" + action + " logs=" + AutoLogWriter.location(this));
        handler.removeCallbacks(applyLoop);
        handler.post(applyLoop);

        if (ACTION_APPLY_ONCE.equals(action)) {
            applyFromPrefs();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(applyLoop);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private long applyFromPrefs() {
        ProfileSettings.MonitorSettings settings = ProfileSettings.monitorSettings(this, null);
        AndroidAutoConnection activeConnection = AndroidAutoConnection.fallback();

        if (settings.watchdogMode) {
            boolean androidAutoRunning = controller.isAndroidAutoRunningWithRoot();
            if (!androidAutoRunning) {
                autoLogState("aa_idle", settings, AndroidAutoConnection.fallback(), false, false, null);
                if (androidAutoSeen) {
                    androidAutoMisses++;
                    if (androidAutoMisses >= AUTO_STOP_MISSES) {
                        autoLog("android auto disconnected; misses=" + androidAutoMisses);
                        clearAudioTweaksIfNeeded();
                        if (settings.releaseAfterAndroidAuto && !routeReleasedAfterDisconnect) {
                            controller.clearRoute();
                            routeReleasedAfterDisconnect = true;
                            routeActiveForTarget = false;
                            autoLog("route cleared after Android Auto disconnect");
                        }
                        resetBluetoothAfterDisconnectIfNeeded(settings.resetBluetoothAfterAndroidAuto);
                        if (settings.autoStopAfterAndroidAuto) {
                            stopMonitor();
                            return -1L;
                        }
                        androidAutoSeen = false;
                        androidAutoMisses = 0;
                    }
                }
                return ANDROID_AUTO_IDLE_CHECK_INTERVAL_MS;
            }

            AndroidAutoConnection connection = controller.currentAndroidAutoConnection();
            activeConnection = connection;
            settings = ProfileSettings.monitorSettings(this, connection);
            if (!androidAutoSeen) {
                targetWasActiveThisSession = false;
                bluetoothResetAfterDisconnect = false;
                autoLog("android auto connected profile=" + settings.profileId + " source=" + connection.label());
            }
            androidAutoSeen = true;
            androidAutoMisses = 0;
            routeReleasedAfterDisconnect = false;
            autoLogState("aa_active", settings, connection, true, null, null);
            maybeLogRootSnapshot("aa_active");
            applyAudioTweaksIfNeeded(settings);
        }

        if (settings.watchdogMode && !hasPreferredTarget(settings.preferredBluetoothTarget)) {
            releaseActiveTargetRouteIfNeeded(settings.releaseAfterAndroidAuto);
            autoLogState("missing_preferred_target", settings, activeConnection, true, false, null);
            return ANDROID_AUTO_IDLE_CHECK_INTERVAL_MS;
        }

        boolean targetConnected = controller.isPreferredBluetoothTargetConnected(settings.preferredBluetoothTarget);
        if (settings.watchdogMode && !targetConnected) {
            releaseActiveTargetRouteIfNeeded(settings.releaseAfterAndroidAuto);
            autoLogState("target_not_connected", settings, activeConnection, true, false, null);
            return ANDROID_AUTO_IDLE_CHECK_INTERVAL_MS;
        }

        AudioRouteController.RoutingResult result = controller.maintainPreferredRoute(
                settings.selectedDeviceKey,
                settings.preferredBluetoothTarget
        );
        routeActiveForTarget = result.success;
        if (result.success) {
            targetWasActiveThisSession = true;
        }
        autoLogState("route_check", settings, activeConnection, true, targetConnected, result.log);
        return ROUTE_CHECK_INTERVAL_MS;
    }

    private boolean hasPreferredTarget(String preferredBluetoothTarget) {
        return preferredBluetoothTarget != null && preferredBluetoothTarget.trim().length() > 0;
    }

    private void releaseActiveTargetRouteIfNeeded(boolean releaseAfterDisconnect) {
        if (releaseAfterDisconnect && routeActiveForTarget) {
            controller.clearRoute();
            routeActiveForTarget = false;
            autoLog("route released because preferred target is not active");
        }
    }

    private void applyAudioTweaksIfNeeded(ProfileSettings.MonitorSettings settings) {
        String tweakKey = settings.profileId
                + "|"
                + settings.notificationRouteMode
                + "|"
                + settings.suppressNotificationDucking
                + "|"
                + settings.selectedDeviceKey
                + "|"
                + settings.preferredBluetoothTarget;
        boolean hasTweaks = !AppPrefs.NOTIFICATION_ROUTE_OFF.equals(settings.notificationRouteMode)
                || settings.suppressNotificationDucking;
        if (!hasTweaks) {
            clearAudioTweaksIfNeeded();
            return;
        }
        if (tweakKey.equals(activeAudioTweakKey)) {
            return;
        }
        clearAudioTweaksIfNeeded();
        activeAudioTweakKey = tweakKey;
        audioTweakDuckingActive = settings.suppressNotificationDucking;
        autoLog("applying audio tweaks notificationRoute=" + settings.notificationRouteMode
                + " suppressDucking=" + settings.suppressNotificationDucking
                + " selected=" + settings.selectedDeviceKey
                + " target=" + settings.preferredBluetoothTarget);
        new Thread(() -> controller.applyAndroidAutoAudioTweaks(
                settings.notificationRouteMode,
                settings.selectedDeviceKey,
                settings.preferredBluetoothTarget,
                settings.suppressNotificationDucking
        ), "aaarp-audio-tweaks").start();
    }

    private void clearAudioTweaksIfNeeded() {
        if (activeAudioTweakKey == null && !audioTweakDuckingActive) {
            return;
        }
        boolean restoreDucking = audioTweakDuckingActive;
        activeAudioTweakKey = null;
        audioTweakDuckingActive = false;
        autoLog("clearing audio tweaks restoreDucking=" + restoreDucking);
        new Thread(() -> controller.clearAndroidAutoAudioTweaks(restoreDucking), "aaarp-audio-tweaks-clear").start();
    }

    private void resetBluetoothAfterDisconnectIfNeeded(boolean resetBluetoothAfterDisconnect) {
        if (!resetBluetoothAfterDisconnect || bluetoothResetAfterDisconnect || !targetWasActiveThisSession) {
            return;
        }
        bluetoothResetAfterDisconnect = true;
        autoLog("resetting Bluetooth after Android Auto disconnect");
        new Thread(() -> controller.resetBluetoothWithRoot(), "aaarp-bluetooth-reset").start();
    }

    private void stopMonitor() {
        autoLog("monitor stop requested");
        releaseRouteOnStopIfEnabled();
        AppPrefs.get(this).edit().putBoolean(AppPrefs.MONITOR_ENABLED, false).apply();
        handler.removeCallbacks(applyLoop);
        stopForegroundCompat();
        stopSelf();
    }

    private void releaseRouteOnStopIfEnabled() {
        clearAudioTweaksIfNeeded();
        SharedPreferences prefs = AppPrefs.get(this);
        if (prefs.getBoolean(AppPrefs.RELEASE_ROUTE_AFTER_ANDROID_AUTO, true)) {
            controller.clearRoute();
            routeActiveForTarget = false;
            autoLog("route cleared on monitor stop");
        }
    }

    private void autoLogState(String stage, ProfileSettings.MonitorSettings settings,
                              AndroidAutoConnection connection, Boolean androidAutoRunning,
                              Boolean targetConnected, String routeLog) {
        long now = System.currentTimeMillis();
        String summary = "stage=" + stage
                + " aa=" + value(androidAutoRunning)
                + " profile=" + settings.profileId
                + " source=" + (connection == null ? "unknown" : connection.label())
                + " targetConnected=" + value(targetConnected)
                + " routeActive=" + routeActiveForTarget
                + " currentRoute=" + controller.currentCommunicationDevice()
                + " notificationRoute=" + settings.notificationRouteMode
                + " suppressDucking=" + settings.suppressNotificationDucking
                + " preferredTarget=" + settings.preferredBluetoothTarget
                + " selected=" + settings.selectedDeviceKey;
        boolean changed = !summary.equals(lastAutoLogSummary);
        if (changed || now - lastAutoLogAt >= AUTO_LOG_HEARTBEAT_MS) {
            lastAutoLogSummary = summary;
            lastAutoLogAt = now;
            autoLog(summary);
            if (routeLog != null && routeLog.length() > 0) {
                autoLog("route_result " + routeLog);
            }
        }
    }

    private void maybeLogRootSnapshot(String reason) {
        long now = System.currentTimeMillis();
        if (rootSnapshotRunning || now - lastRootSnapshotAt < AUTO_LOG_ROOT_SNAPSHOT_MS) {
            return;
        }
        rootSnapshotRunning = true;
        lastRootSnapshotAt = now;
        new Thread(() -> {
            try {
                RootShell.ShellResult result = controller.autoLogSnapshot();
                autoLog("root_snapshot reason=" + reason + " exit=" + result.exitCode + " output=" + result.output);
            } finally {
                rootSnapshotRunning = false;
            }
        }, "aaarp-auto-log-snapshot").start();
    }

    private void autoLog(String message) {
        AutoLogWriter.append(this, message);
    }

    private String value(Boolean value) {
        return value == null ? "unknown" : (value ? "yes" : "no");
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForegroundLegacy();
        }
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundLegacy() {
        stopForeground(true);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, RoutingMonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = notificationBuilder();
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_launcher_foreground),
                "Stop",
                stopPendingIntent
        ).build();

        return builder
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.monitor_notification_title))
                .setContentText(getString(R.string.monitor_notification_text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(stopAction)
                .build();
    }

    private Notification.Builder notificationBuilder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID);
        }
        return legacyNotificationBuilder();
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder legacyNotificationBuilder() {
        return new Notification.Builder(this);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}

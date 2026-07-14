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
    private boolean audioTweakNotificationRouteActive;
    private boolean audioTweakMediaRouteActive;
    private boolean notificationPlaybackMuteActive;
    private boolean notificationPlaybackMuteOwned;
    private int notificationPlaybackRestoreVolume = -1;
    private long lastAutoLogAt;
    private long lastRootSnapshotAt;
    private long lastMediaPinPulseLogAt;
    private String lastMediaPinPulseSummary;
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
        clearAudioTweaksIfNeeded();
        clearNotificationPlaybackMuteIfNeeded();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private long applyFromPrefs() {
        ProfileSettings.MonitorSettings settings = ProfileSettings.monitorSettings(this, null);
        AndroidAutoConnection activeConnection = AndroidAutoConnection.fallback();
        boolean androidAutoActive = false;

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
                applyAudioTweaksIfNeeded(settings, false);
                updateNotificationPlaybackMute(settings, false);
                return idleDelayFor(settings, false);
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
            androidAutoActive = true;
            autoLogState("aa_active", settings, connection, true, null, null);
            maybeLogRootSnapshot("aa_active");
            applyAudioTweaksIfNeeded(settings, true);
            updateNotificationPlaybackMute(settings, true);
            reassertPinnedMediaIfNeeded(settings, true);
        } else {
            applyAudioTweaksIfNeeded(settings, false);
            updateNotificationPlaybackMute(settings, false);
        }

        if (settings.watchdogMode && !hasPreferredTarget(settings.preferredBluetoothTarget)) {
            releaseActiveTargetRouteIfNeeded(settings.releaseAfterAndroidAuto);
            autoLogState("missing_preferred_target", settings, activeConnection, true, false, null);
            return idleDelayFor(settings, androidAutoActive);
        }

        boolean targetConnected = controller.isPreferredBluetoothTargetConnected(settings.preferredBluetoothTarget);
        if (settings.watchdogMode && !targetConnected) {
            releaseActiveTargetRouteIfNeeded(settings.releaseAfterAndroidAuto);
            autoLogState("target_not_connected", settings, activeConnection, true, false, null);
            return idleDelayFor(settings, androidAutoActive);
        }

        if (shouldSkipGenericBluetoothScoFallback(settings)) {
            pauseBluetoothScoRouteForMediaIfNeeded();
            autoLogState("route_skipped_generic_sco", settings, activeConnection, true, targetConnected,
                    "Android only exposed a generic Bluetooth SCO route for the saved Bluetooth target.\n"
                            + "AAARP skipped that call-quality route because no phone-call audio mode is active.\n"
                            + "Selected route: " + controller.selectedRouteDetail(
                            settings.selectedDeviceKey,
                            settings.preferredBluetoothTarget
                    ) + '\n');
            return ROUTE_CHECK_INTERVAL_MS;
        }

        if (shouldPauseBluetoothScoForMedia(settings)) {
            pauseBluetoothScoRouteForMediaIfNeeded();
            autoLogState("route_paused_for_media", settings, activeConnection, true, targetConnected,
                    "Media playback is active and the selected communication route is Bluetooth SCO.\n"
                            + "AAARP left the normal media audio path alone.\n"
                            + "Selected route: " + controller.selectedRouteDetail(
                            settings.selectedDeviceKey,
                            settings.preferredBluetoothTarget
                    ) + '\n');
            return ROUTE_CHECK_INTERVAL_MS;
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

    private boolean shouldSkipGenericBluetoothScoFallback(ProfileSettings.MonitorSettings settings) {
        if (!settings.pauseBluetoothScoDuringMedia) {
            return false;
        }
        try {
            return controller.selectedRouteIsUnmatchedBluetoothScoFallback(
                    settings.selectedDeviceKey,
                    settings.preferredBluetoothTarget
            ) && !controller.isInCallAudioMode();
        } catch (RuntimeException e) {
            autoLog("Bluetooth SCO fallback guard failed: " + e.getMessage());
            return false;
        }
    }

    private boolean shouldPauseBluetoothScoForMedia(ProfileSettings.MonitorSettings settings) {
        if (!settings.pauseBluetoothScoDuringMedia) {
            return false;
        }
        try {
            return controller.isMediaPlaybackActive()
                    && controller.selectedRouteIsBluetoothSco(
                    settings.selectedDeviceKey,
                    settings.preferredBluetoothTarget
            );
        } catch (RuntimeException e) {
            autoLog("Bluetooth SCO media guard failed: " + e.getMessage());
            return false;
        }
    }

    private void pauseBluetoothScoRouteForMediaIfNeeded() {
        if (routeActiveForTarget || controller.isCurrentCommunicationRouteBluetoothSco()) {
            controller.clearRoute();
            routeActiveForTarget = false;
            autoLog("Bluetooth SCO route paused during media playback");
        }
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

    private void applyAudioTweaksIfNeeded(ProfileSettings.MonitorSettings settings, boolean androidAutoActive) {
        String notificationRouteMode = androidAutoActive
                ? settings.notificationRouteMode
                : AppPrefs.NOTIFICATION_ROUTE_OFF;
        boolean suppressDucking = settings.suppressNotificationDucking
                && (androidAutoActive || settings.suppressNotificationDuckingAlways);
        boolean pinMediaToBluetooth = androidAutoActive && settings.pinMediaToBluetoothDuringAndroidAuto;
        String tweakKey = settings.profileId
                + "|"
                + androidAutoActive
                + "|"
                + notificationRouteMode
                + "|"
                + suppressDucking
                + "|"
                + pinMediaToBluetooth
                + "|"
                + settings.selectedDeviceKey
                + "|"
                + settings.preferredBluetoothTarget;
        boolean hasTweaks = !AppPrefs.NOTIFICATION_ROUTE_OFF.equals(notificationRouteMode)
                || suppressDucking
                || pinMediaToBluetooth;
        if (!hasTweaks) {
            clearAudioTweaksIfNeeded();
            return;
        }
        if (tweakKey.equals(activeAudioTweakKey)) {
            return;
        }
        clearAudioTweaksIfNeeded();
        activeAudioTweakKey = tweakKey;
        audioTweakDuckingActive = suppressDucking;
        audioTweakNotificationRouteActive = !AppPrefs.NOTIFICATION_ROUTE_OFF.equals(notificationRouteMode);
        audioTweakMediaRouteActive = pinMediaToBluetooth;
        autoLog("applying audio tweaks notificationRoute=" + notificationRouteMode
                + " suppressDucking=" + suppressDucking
                + " pinMediaToBluetooth=" + pinMediaToBluetooth
                + " androidAutoActive=" + androidAutoActive
                + " selected=" + settings.selectedDeviceKey
                + " target=" + settings.preferredBluetoothTarget);
        new Thread(() -> controller.applyAndroidAutoAudioTweaks(
                notificationRouteMode,
                settings.selectedDeviceKey,
                settings.preferredBluetoothTarget,
                suppressDucking,
                pinMediaToBluetooth
        ), "aaarp-audio-tweaks").start();
    }

    private void clearAudioTweaksIfNeeded() {
        if (activeAudioTweakKey == null
                && !audioTweakDuckingActive
                && !audioTweakNotificationRouteActive
                && !audioTweakMediaRouteActive) {
            return;
        }
        boolean restoreDucking = audioTweakDuckingActive;
        boolean clearNotificationRoute = audioTweakNotificationRouteActive;
        boolean clearMediaRoute = audioTweakMediaRouteActive;
        activeAudioTweakKey = null;
        audioTweakDuckingActive = false;
        audioTweakNotificationRouteActive = false;
        audioTweakMediaRouteActive = false;
        autoLog("clearing audio tweaks restoreDucking=" + restoreDucking
                + " clearNotificationRoute=" + clearNotificationRoute
                + " clearMediaRoute=" + clearMediaRoute);
        new Thread(() -> controller.clearAndroidAutoAudioTweaks(
                restoreDucking,
                clearNotificationRoute,
                clearMediaRoute
        ), "aaarp-audio-tweaks-clear").start();
    }

    private void reassertPinnedMediaIfNeeded(ProfileSettings.MonitorSettings settings, boolean androidAutoActive) {
        if (!androidAutoActive || !settings.pinMediaToBluetoothDuringAndroidAuto) {
            return;
        }
        try {
            String result = controller.reassertBluetoothMediaPath(
                    settings.selectedDeviceKey,
                    settings.preferredBluetoothTarget
            );
            long now = System.currentTimeMillis();
            boolean changed = !result.equals(lastMediaPinPulseSummary);
            if (changed || now - lastMediaPinPulseLogAt >= AUTO_LOG_HEARTBEAT_MS) {
                lastMediaPinPulseSummary = result;
                lastMediaPinPulseLogAt = now;
                autoLog("media pin pulse " + result);
            }
        } catch (RuntimeException e) {
            long now = System.currentTimeMillis();
            String result = "failed: " + e.getMessage();
            if (!result.equals(lastMediaPinPulseSummary) || now - lastMediaPinPulseLogAt >= AUTO_LOG_HEARTBEAT_MS) {
                lastMediaPinPulseSummary = result;
                lastMediaPinPulseLogAt = now;
                autoLog("media pin pulse " + result);
            }
        }
    }

    private void updateNotificationPlaybackMute(ProfileSettings.MonitorSettings settings, boolean androidAutoActive) {
        if (!shouldMuteNotificationsDuringPlayback(settings, androidAutoActive)) {
            clearNotificationPlaybackMuteIfNeeded();
            return;
        }

        boolean playbackActive;
        try {
            playbackActive = controller.isMediaPlaybackActive();
        } catch (RuntimeException e) {
            autoLog("playback state check failed: " + e.getMessage());
            return;
        }

        if (playbackActive) {
            muteNotificationsForPlaybackIfNeeded(androidAutoActive);
        } else {
            clearNotificationPlaybackMuteIfNeeded();
        }
    }

    private boolean shouldMuteNotificationsDuringPlayback(ProfileSettings.MonitorSettings settings,
                                                          boolean androidAutoActive) {
        return settings.muteNotificationsDuringPlayback
                && (androidAutoActive || settings.muteNotificationsDuringPlaybackAlways);
    }

    private long idleDelayFor(ProfileSettings.MonitorSettings settings, boolean androidAutoActive) {
        return shouldMuteNotificationsDuringPlayback(settings, androidAutoActive)
                ? ROUTE_CHECK_INTERVAL_MS
                : ANDROID_AUTO_IDLE_CHECK_INTERVAL_MS;
    }

    private void muteNotificationsForPlaybackIfNeeded(boolean androidAutoActive) {
        try {
            int currentVolume = controller.notificationStreamVolume();
            if (!notificationPlaybackMuteActive) {
                notificationPlaybackMuteActive = true;
                if (currentVolume == 0) {
                    notificationPlaybackMuteOwned = false;
                    notificationPlaybackRestoreVolume = -1;
                    autoLog("notification stream already muted during playback; leaving ownership to another app"
                            + " androidAutoActive=" + androidAutoActive);
                    return;
                }
                notificationPlaybackMuteOwned = true;
                notificationPlaybackRestoreVolume = currentVolume;
                controller.setNotificationStreamVolume(0);
                autoLog("notification stream muted during playback restoreVolume="
                        + notificationPlaybackRestoreVolume
                        + " androidAutoActive=" + androidAutoActive);
                return;
            }
            if (notificationPlaybackMuteOwned && currentVolume != 0) {
                controller.setNotificationStreamVolume(0);
                autoLog("notification stream re-muted during playback currentVolume=" + currentVolume);
            }
        } catch (RuntimeException e) {
            autoLog("notification stream mute failed: " + e.getMessage());
        }
    }

    private void clearNotificationPlaybackMuteIfNeeded() {
        if (!notificationPlaybackMuteActive) {
            return;
        }
        int restoreVolume = notificationPlaybackRestoreVolume;
        boolean restoreOwned = notificationPlaybackMuteOwned;
        notificationPlaybackMuteActive = false;
        notificationPlaybackMuteOwned = false;
        notificationPlaybackRestoreVolume = -1;
        try {
            int currentVolume = controller.notificationStreamVolume();
            if (restoreOwned && restoreVolume >= 0 && currentVolume == 0) {
                controller.setNotificationStreamVolume(restoreVolume);
                autoLog("notification stream restored after playback restoreVolume=" + restoreVolume);
            } else {
                autoLog("notification stream mute ended without restore owned="
                        + restoreOwned
                        + " restoreVolume=" + restoreVolume
                        + " currentVolume=" + currentVolume);
            }
        } catch (RuntimeException e) {
            autoLog("notification stream restore failed: " + e.getMessage());
        }
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
        clearNotificationPlaybackMuteIfNeeded();
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
                + " pauseScoForMedia=" + settings.pauseBluetoothScoDuringMedia
                + " pinMediaToBluetooth=" + settings.pinMediaToBluetoothDuringAndroidAuto
                + " notificationRoute=" + settings.notificationRouteMode
                + " suppressDucking=" + settings.suppressNotificationDucking
                + " suppressDuckingAlways=" + settings.suppressNotificationDuckingAlways
                + " mutePlayback=" + settings.muteNotificationsDuringPlayback
                + " mutePlaybackAlways=" + settings.muteNotificationsDuringPlaybackAlways
                + " muteActive=" + notificationPlaybackMuteActive
                + " muteOwned=" + notificationPlaybackMuteOwned
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

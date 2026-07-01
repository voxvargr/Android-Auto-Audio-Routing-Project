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
    private static final int AUTO_STOP_MISSES = 3;

    private Handler handler;
    private AudioRouteController controller;
    private boolean androidAutoSeen;
    private int androidAutoMisses;
    private boolean routeReleasedAfterDisconnect;
    private boolean routeActiveForTarget;
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
        SharedPreferences prefs = AppPrefs.get(this);
        String selectedKey = prefs.getString(AppPrefs.SELECTED_DEVICE_KEY, null);
        String preferredBluetoothTarget = prefs.getString(AppPrefs.PREFERRED_BLUETOOTH_QUERY, null);
        boolean watchdogMode = prefs.getBoolean(AppPrefs.WATCHDOG_MODE, true);
        boolean autoStop = prefs.getBoolean(AppPrefs.AUTO_STOP_AFTER_ANDROID_AUTO, false);
        boolean releaseAfterDisconnect = prefs.getBoolean(AppPrefs.RELEASE_ROUTE_AFTER_ANDROID_AUTO, true);

        if (watchdogMode) {
            boolean androidAutoRunning = controller.isAndroidAutoRunningWithRoot();
            if (!androidAutoRunning) {
                if (androidAutoSeen) {
                    androidAutoMisses++;
                    if (androidAutoMisses >= AUTO_STOP_MISSES) {
                        if (releaseAfterDisconnect && !routeReleasedAfterDisconnect) {
                            controller.clearRoute();
                            routeReleasedAfterDisconnect = true;
                            routeActiveForTarget = false;
                        }
                    }
                    if (autoStop && androidAutoMisses >= AUTO_STOP_MISSES) {
                        stopMonitor();
                        return -1L;
                    }
                }
                return ANDROID_AUTO_IDLE_CHECK_INTERVAL_MS;
            }

            androidAutoSeen = true;
            androidAutoMisses = 0;
            routeReleasedAfterDisconnect = false;
        }

        if (watchdogMode && !hasPreferredTarget(preferredBluetoothTarget)) {
            releaseActiveTargetRouteIfNeeded(releaseAfterDisconnect);
            return ANDROID_AUTO_IDLE_CHECK_INTERVAL_MS;
        }

        if (watchdogMode && !controller.isPreferredBluetoothTargetConnected(preferredBluetoothTarget)) {
            releaseActiveTargetRouteIfNeeded(releaseAfterDisconnect);
            return ANDROID_AUTO_IDLE_CHECK_INTERVAL_MS;
        }

        AudioRouteController.RoutingResult result = controller.maintainPreferredRoute(selectedKey, preferredBluetoothTarget);
        routeActiveForTarget = result.success;
        return ROUTE_CHECK_INTERVAL_MS;
    }

    private boolean hasPreferredTarget(String preferredBluetoothTarget) {
        return preferredBluetoothTarget != null && preferredBluetoothTarget.trim().length() > 0;
    }

    private void releaseActiveTargetRouteIfNeeded(boolean releaseAfterDisconnect) {
        if (releaseAfterDisconnect && routeActiveForTarget) {
            controller.clearRoute();
            routeActiveForTarget = false;
        }
    }

    private void stopMonitor() {
        releaseRouteOnStopIfEnabled();
        AppPrefs.get(this).edit().putBoolean(AppPrefs.MONITOR_ENABLED, false).apply();
        handler.removeCallbacks(applyLoop);
        stopForegroundCompat();
        stopSelf();
    }

    private void releaseRouteOnStopIfEnabled() {
        SharedPreferences prefs = AppPrefs.get(this);
        if (prefs.getBoolean(AppPrefs.RELEASE_ROUTE_AFTER_ANDROID_AUTO, true)) {
            controller.clearRoute();
            routeActiveForTarget = false;
        }
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

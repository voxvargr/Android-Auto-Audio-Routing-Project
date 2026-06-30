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
    private static final long APPLY_INTERVAL_MS = 5000L;

    private Handler handler;
    private AudioRouteController controller;
    private final Runnable applyLoop = new Runnable() {
        @Override
        public void run() {
            applyFromPrefs();
            handler.postDelayed(this, APPLY_INTERVAL_MS);
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

    private void applyFromPrefs() {
        SharedPreferences prefs = AppPrefs.get(this);
        String selectedKey = prefs.getString(AppPrefs.SELECTED_DEVICE_KEY, null);
        String preferredBluetoothTarget = prefs.getString(AppPrefs.PREFERRED_BLUETOOTH_QUERY, null);
        controller.applyPreferredRoute(selectedKey, preferredBluetoothTarget);
    }

    private void stopMonitor() {
        AppPrefs.get(this).edit().putBoolean(AppPrefs.MONITOR_ENABLED, false).apply();
        handler.removeCallbacks(applyLoop);
        stopForegroundCompat();
        stopSelf();
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

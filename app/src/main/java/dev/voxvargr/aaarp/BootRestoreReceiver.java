package dev.voxvargr.aaarp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public final class BootRestoreReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        SharedPreferences prefs = AppPrefs.get(context);
        boolean restoreAfterBoot = prefs.getBoolean(AppPrefs.RESTORE_MONITOR_AFTER_BOOT, true);
        boolean monitorWasRunning = prefs.getBoolean(AppPrefs.MONITOR_ENABLED, false);
        if (!restoreAfterBoot || !monitorWasRunning) {
            return;
        }

        Intent serviceIntent = new Intent(context, RoutingMonitorService.class)
                .setAction(RoutingMonitorService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (RuntimeException ignored) {
            // Some vendor ROMs block boot autostart unless the user allows it in system settings.
        }
    }
}

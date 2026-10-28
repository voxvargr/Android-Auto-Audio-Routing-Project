package dev.voxvargr.aaarp;

import android.content.Context;
import android.content.pm.PackageManager;

final class AndroidAutoStatus {
    static final String PACKAGE_ANDROID_AUTO = "com.google.android.projection.gearhead";

    private AndroidAutoStatus() {
    }

    static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE_ANDROID_AUTO, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean isRunningWithRoot(RootShell rootShell) {
        RootShell.ShellResult result = rootShell.run("pidof " + PACKAGE_ANDROID_AUTO, 3000);
        return result.success && result.output.trim().length() > 0;
    }
}

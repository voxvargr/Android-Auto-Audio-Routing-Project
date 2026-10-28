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
        RootShell.ShellResult result = rootShell.run(
                "pidof " + PACKAGE_ANDROID_AUTO + " 2>/dev/null || "
                        + "ps -A 2>/dev/null | grep -F '" + PACKAGE_ANDROID_AUTO + "' || true",
                3000
        );
        String output = result.output.trim();
        return output.length() > 0
                && (output.contains(PACKAGE_ANDROID_AUTO) || output.matches("[0-9\\s]+"));
    }
}

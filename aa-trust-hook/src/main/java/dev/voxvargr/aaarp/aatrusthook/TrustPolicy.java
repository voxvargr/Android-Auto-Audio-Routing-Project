package dev.voxvargr.aaarp.aatrusthook;

final class TrustPolicy {
    static final String GEARHEAD_PACKAGE = "com.google.android.projection.gearhead";
    static final String VOLUME_UP_PACKAGE = "dev.voxvargr.aaarp.volumeup";
    static final String VOLUME_DOWN_PACKAGE = "dev.voxvargr.aaarp.volumedown";
    static final String VOLUME_SHORTCUT_SERVICE =
            "dev.voxvargr.aaarp.shortcut.VolumeShortcutCarAppService";
    static final String VOLUME_SHORTCUT_PROVIDER_AUTHORITY =
            "dev.voxvargr.aaarp.volumeshortcut";
    static final String VOLUME_UP_PROVIDER_METHOD = "volume_up";
    static final String VOLUME_DOWN_PROVIDER_METHOD = "volume_down";
    static final int PHONE_DOCK_FEED = 9;
    static final int RECENT_DOCK_FEED = 10;

    private TrustPolicy() {
    }

    static boolean isGearheadProcess(String processName) {
        return GEARHEAD_PACKAGE.equals(processName)
                || (processName != null && processName.startsWith(GEARHEAD_PACKAGE + ":"));
    }

    static boolean shouldSpoofInstallSource(String packageName) {
        return VOLUME_UP_PACKAGE.equals(packageName)
                || VOLUME_DOWN_PACKAGE.equals(packageName);
    }

    static String shortcutPackageForDockFeed(int dockFeed) {
        if (dockFeed == PHONE_DOCK_FEED) {
            return VOLUME_UP_PACKAGE;
        }
        if (dockFeed == RECENT_DOCK_FEED) {
            return VOLUME_DOWN_PACKAGE;
        }
        return null;
    }

    static String shortcutRoleForDockFeed(int dockFeed) {
        if (dockFeed == PHONE_DOCK_FEED) {
            return "PHONE";
        }
        if (dockFeed == RECENT_DOCK_FEED) {
            return "RECENT";
        }
        return null;
    }

    static String providerMethodForShortcutPackage(String packageName) {
        if (VOLUME_UP_PACKAGE.equals(packageName)) {
            return VOLUME_UP_PROVIDER_METHOD;
        }
        if (VOLUME_DOWN_PACKAGE.equals(packageName)) {
            return VOLUME_DOWN_PROVIDER_METHOD;
        }
        return null;
    }
}

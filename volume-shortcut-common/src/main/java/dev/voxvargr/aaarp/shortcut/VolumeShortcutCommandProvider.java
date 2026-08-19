package dev.voxvargr.aaarp.shortcut;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;

/** Receives one no-UI volume command from Android Auto's root-hooked dock callback. */
public final class VolumeShortcutCommandProvider extends ContentProvider {
    static final String METHOD_ADJUST_VOLUME = "adjust_volume";

    private static final String TAG = "AAARP-VolumeShortcut";
    private static final String GEARHEAD_PACKAGE =
            "com.google.android.projection.gearhead";
    private static final String GEARHEAD_PERMISSION =
            "com.google.android.projection.gearhead.permission.START_PROJECTED_ACTIVITY";
    private static final String META_ACTION = "dev.voxvargr.aaarp.shortcut.ACTION";
    private static final String ACTION_VOLUME_DOWN = "dev.voxvargr.aaarp.action.VOLUME_DOWN";
    private static final String ACTION_VOLUME_UP = "dev.voxvargr.aaarp.action.VOLUME_UP";
    private static final String RECEIVER_PACKAGE = "dev.voxvargr.aaarp";
    private static final String RECEIVER_CLASS =
            "dev.voxvargr.aaarp.AndroidAutoVolumeShortcutReceiver";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Shortcut provider has no context");
        }

        int callingUid = Binder.getCallingUid();
        if (context.checkCallingPermission(GEARHEAD_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Rejected direct command without Gearhead signature permission");
            throw new SecurityException("Caller lacks Android Auto permission");
        }
        String callingPackage = getCallingPackage();
        String[] uidPackages = context.getPackageManager().getPackagesForUid(callingUid);
        if (!isAuthorizedCaller(callingPackage, uidPackages)) {
            Log.w(TAG, "Rejected direct command from uid=" + callingUid);
            throw new SecurityException("Caller is not Android Auto");
        }
        if (!METHOD_ADJUST_VOLUME.equals(method)) {
            throw new IllegalArgumentException("Unsupported shortcut command");
        }
        if (arg != null || (extras != null && !extras.isEmpty())) {
            throw new IllegalArgumentException("Shortcut command does not accept arguments");
        }

        String action = configuredAction(context);
        if (!isSupportedAction(action)) {
            throw new IllegalStateException("Missing or invalid shortcut action metadata");
        }

        Intent request = new Intent(action)
                .setComponent(new ComponentName(RECEIVER_PACKAGE, RECEIVER_CLASS));
        long identity = Binder.clearCallingIdentity();
        try {
            // The helper APK is signed like AAARP, so this explicit broadcast passes the
            // receiver's signature permission without granting that permission to Gearhead.
            context.sendBroadcast(request);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        Bundle result = new Bundle();
        result.putBoolean("queued", true);
        return result;
    }

    static boolean isAuthorizedCaller(String callingPackage, String[] uidPackages) {
        return GEARHEAD_PACKAGE.equals(callingPackage)
                && uidPackages != null
                && uidPackages.length == 1
                && GEARHEAD_PACKAGE.equals(uidPackages[0]);
    }

    static boolean isSupportedAction(String action) {
        return ACTION_VOLUME_DOWN.equals(action) || ACTION_VOLUME_UP.equals(action);
    }

    private static String configuredAction(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(),
                    PackageManager.GET_META_DATA
            );
            Bundle metadata = info.metaData;
            return metadata == null ? null : metadata.getString(META_ACTION);
        } catch (PackageManager.NameNotFoundException error) {
            return null;
        }
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        throw new UnsupportedOperationException("No query surface");
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("No insert surface");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("No delete surface");
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs
    ) {
        throw new UnsupportedOperationException("No update surface");
    }
}

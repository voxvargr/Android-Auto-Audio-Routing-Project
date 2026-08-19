package dev.voxvargr.aaarp;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/**
 * Accepts the two fixed Android Auto dock commands without starting either icon helper app.
 *
 * <p>The provider is protected by Android Auto's signature permission and then verifies the
 * Binder caller again. It only enqueues AAARP's existing signed receiver; the root volume command
 * remains off Android Auto's UI thread.</p>
 */
public final class AndroidAutoVolumeShortcutProvider extends ContentProvider {
    static final String METHOD_VOLUME_DOWN = "volume_down";
    static final String METHOD_VOLUME_UP = "volume_up";

    private static final String GEARHEAD_PACKAGE =
            "com.google.android.projection.gearhead";
    private static final String GEARHEAD_PERMISSION =
            "com.google.android.projection.gearhead.permission.START_PROJECTED_ACTIVITY";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Volume shortcut provider has no context");
        }

        int callingUid = Binder.getCallingUid();
        if (context.checkCallingPermission(GEARHEAD_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Caller lacks Android Auto permission");
        }
        String callingPackage = getCallingPackage();
        String[] uidPackages = context.getPackageManager().getPackagesForUid(callingUid);
        if (!isAuthorizedCaller(callingPackage, uidPackages)) {
            throw new SecurityException("Caller is not Android Auto");
        }
        if (arg != null || (extras != null && !extras.isEmpty())) {
            throw new IllegalArgumentException("Volume shortcut does not accept arguments");
        }

        int direction = directionForMethod(method);
        if (direction == AudioManager.ADJUST_SAME) {
            throw new IllegalArgumentException("Unsupported volume shortcut method");
        }
        String action = direction == AudioManager.ADJUST_LOWER
                ? AndroidAutoVolumeShortcutReceiver.ACTION_VOLUME_DOWN
                : AndroidAutoVolumeShortcutReceiver.ACTION_VOLUME_UP;
        Intent request = new Intent(action)
                .setComponent(new ComponentName(
                        context,
                        AndroidAutoVolumeShortcutReceiver.class))
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND
                        | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);

        long identity = Binder.clearCallingIdentity();
        try {
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

    static int directionForMethod(String method) {
        if (METHOD_VOLUME_DOWN.equals(method)) {
            return AudioManager.ADJUST_LOWER;
        }
        if (METHOD_VOLUME_UP.equals(method)) {
            return AudioManager.ADJUST_RAISE;
        }
        return AudioManager.ADJUST_SAME;
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

package dev.voxvargr.aaarp.shortcut;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.model.Action;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;
import androidx.car.app.validation.HostValidator;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.util.concurrent.atomic.AtomicInteger;

/** One-shot projected-car surface used by the separate Volume - and Volume + helper APKs. */
public final class VolumeShortcutCarAppService extends CarAppService {
    private static final String TAG = "AAARP-VolumeShortcut";
    private static final String META_ACTION = "dev.voxvargr.aaarp.shortcut.ACTION";
    private static final String ACTION_VOLUME_DOWN = "dev.voxvargr.aaarp.action.VOLUME_DOWN";
    private static final String ACTION_VOLUME_UP = "dev.voxvargr.aaarp.action.VOLUME_UP";
    private static final String RECEIVER_PACKAGE = "dev.voxvargr.aaarp";
    private static final String RECEIVER_CLASS =
            "dev.voxvargr.aaarp.AndroidAutoVolumeShortcutReceiver";

    @NonNull
    @Override
    public HostValidator createHostValidator() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        }
        // These helpers are private debug prototypes, not release-distributed car apps.
        return new HostValidator.Builder(this).build();
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new ShortcutSession();
    }

    private final class ShortcutSession extends Session {
        private final AtomicInteger requestedLaunches = new AtomicInteger(1);
        private final AtomicInteger dispatchedLaunches = new AtomicInteger();

        @NonNull
        @Override
        public Screen onCreateScreen(@NonNull Intent intent) {
            return new ShortcutScreen(getCarContext(), this);
        }

        @Override
        public void onNewIntent(@NonNull Intent intent) {
            requestedLaunches.incrementAndGet();
            dispatchPendingLaunches();
        }

        private void dispatchPendingLaunches() {
            boolean sentAny = false;
            while (true) {
                int dispatched = dispatchedLaunches.get();
                int requested = requestedLaunches.get();
                if (dispatched >= requested) {
                    break;
                }
                if (dispatchedLaunches.compareAndSet(dispatched, dispatched + 1)) {
                    dispatchConfiguredAction();
                    sentAny = true;
                }
            }
            if (sentAny) {
                // Post until after the callback so the host can complete the launch cleanly.
                new Handler(Looper.getMainLooper()).post(getCarContext()::finishCarApp);
            }
        }
    }

    private final class ShortcutScreen extends Screen {
        private ShortcutScreen(CarContext carContext, ShortcutSession session) {
            super(carContext);
            getLifecycle().addObserver(new DefaultLifecycleObserver() {
                @Override
                public void onStart(@NonNull LifecycleOwner owner) {
                    session.dispatchPendingLaunches();
                }
            });
        }

        @NonNull
        @Override
        public Template onGetTemplate() {
            CharSequence label = getApplicationInfo().loadLabel(getPackageManager());
            return new MessageTemplate.Builder(label)
                    .setTitle(label)
                    .setHeaderAction(Action.APP_ICON)
                    .build();
        }

    }

    private void dispatchConfiguredAction() {
        String action = configuredAction();
        if (ACTION_VOLUME_DOWN.equals(action) || ACTION_VOLUME_UP.equals(action)) {
            Intent request = new Intent(action)
                    .setComponent(new ComponentName(RECEIVER_PACKAGE, RECEIVER_CLASS));
            sendBroadcast(request);
            Log.i(TAG, "Sent " + action);
        } else {
            Log.e(TAG, "Missing or invalid shortcut action metadata");
        }
    }

    private String configuredAction() {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(
                    getPackageName(),
                    PackageManager.GET_META_DATA
            );
            Bundle metadata = info.metaData;
            return metadata == null ? null : metadata.getString(META_ACTION);
        } catch (PackageManager.NameNotFoundException error) {
            return null;
        }
    }
}

package dev.voxvargr.aaarp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Executes one signed request from an installed Android Auto volume shortcut helper. */
public final class AndroidAutoVolumeShortcutReceiver extends BroadcastReceiver {
    static final String ACTION_VOLUME_DOWN = "dev.voxvargr.aaarp.action.VOLUME_DOWN";
    static final String ACTION_VOLUME_UP = "dev.voxvargr.aaarp.action.VOLUME_UP";

    // One running root command plus three queued commands remains below the receiver deadline
    // even when every command reaches RootShell's two-second timeout.
    private static final int MAX_PENDING_STEPS = 3;
    private static final ThreadPoolExecutor VOLUME_EXECUTOR = createExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        int direction = directionForAction(intent == null ? null : intent.getAction());
        if (direction == AudioManager.ADJUST_SAME) {
            AutoLogWriter.append(context, "aa_volume_shortcut rejected invalid_action");
            return;
        }

        Context appContext = context.getApplicationContext();
        PendingResult pendingResult = goAsync();
        try {
            VOLUME_EXECUTOR.execute(() -> {
                try {
                    stepVolume(appContext, direction);
                } finally {
                    pendingResult.finish();
                }
            });
        } catch (RuntimeException error) {
            AutoLogWriter.append(
                    appContext,
                    "aa_volume_shortcut rejected queue_full error="
                            + error.getClass().getSimpleName()
            );
            pendingResult.finish();
        }
    }

    static int directionForAction(String action) {
        if (ACTION_VOLUME_DOWN.equals(action)) {
            return AudioManager.ADJUST_LOWER;
        }
        if (ACTION_VOLUME_UP.equals(action)) {
            return AudioManager.ADJUST_RAISE;
        }
        return AudioManager.ADJUST_SAME;
    }

    private static void stepVolume(Context context, int direction) {
        AudioManager audioManager = context.getSystemService(AudioManager.class);
        if (audioManager == null) {
            AutoLogWriter.append(context, "aa_volume_shortcut failed audio_manager_unavailable");
            return;
        }

        RootShell rootShell = new RootShell();
        MusicVolumeStepper stepper = new MusicVolumeStepper(audioManager, requestedDirection -> {
            RootShell.ShellResult rootResult = rootShell.adjustMusicVolume(requestedDirection);
            if (!rootResult.success) {
                throw new RootVolumeAdjustmentException(rootResult.exitCode);
            }
        });
        boolean useRoot = AppPrefs.get(context).getBoolean(AppPrefs.USE_ROOT, false);
        MusicVolumeStepper.Result result = stepper.step(direction, useRoot);
        if (result.shouldProtectManualIntent()) {
            ManualMediaVolumeTracker.markSuccessfulStep();
        }
        AutoLogWriter.append(
                context,
                "aa_volume_shortcut action=" + actionName(direction)
                        + " " + result.logFields()
        );
    }

    private static String actionName(int direction) {
        return direction == AudioManager.ADJUST_LOWER ? "down" : "up";
    }

    private static ThreadPoolExecutor createExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                5L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_STEPS),
                runnable -> new Thread(runnable, "aaarp-volume-shortcut"),
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static final class RootVolumeAdjustmentException extends RuntimeException {
        private RootVolumeAdjustmentException(int exitCode) {
            super("Root volume command failed with exit code " + exitCode);
        }
    }
}

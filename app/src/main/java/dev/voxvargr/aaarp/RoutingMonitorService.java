package dev.voxvargr.aaarp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class RoutingMonitorService extends Service {
    static final String ACTION_START = "dev.voxvargr.aaarp.START";
    static final String ACTION_STOP = "dev.voxvargr.aaarp.STOP";
    static final String ACTION_APPLY_ONCE = "dev.voxvargr.aaarp.APPLY_ONCE";
    static final String ACTION_CLEAR = "dev.voxvargr.aaarp.CLEAR";

    private static final String CHANNEL_ID = "aaarp-routing";
    private static final int NOTIFICATION_ID = 9401;
    private static final long ROUTE_CHECK_INTERVAL_MS = 2000L;
    private static final long ANDROID_AUTO_IDLE_CHECK_INTERVAL_MS = 5000L;
    private static final long AUTO_LOG_HEARTBEAT_MS = 30000L;
    private static final long AUTO_LOG_ROOT_SNAPSHOT_MS = 120000L;
    private static final long NOTIFICATION_PLAYBACK_IDLE_GRACE_MS = 15000L;
    static final long BLUETOOTH_MEDIA_IDLE_GRACE_MS = 15000L;
    private static final long ASSISTANT_AUDIO_GRACE_MS = 12000L;
    private static final long MEDIA_LEVELER_RETRY_INITIAL_MS = 2000L;
    private static final long MEDIA_LEVELER_RETRY_MAX_MS = 60000L;
    static final long MEDIA_LEVELER_PLAYBACK_IDLE_GRACE_MS = 180000L;
    private static final int AUTO_STOP_MISSES = 3;
    private static final long RESTORE_MAIN_THREAD_TIMEOUT_MS = 5000L;
    private static final long RESTORE_AUDIO_TWEAK_TIMEOUT_MS = 20000L;
    private static final Object AUDIO_TWEAK_COMMAND_LOCK = new Object();
    private static int pendingAudioTweakCommands;
    private static final AtomicBoolean SETTINGS_RESTORE_ACTIVE = new AtomicBoolean();
    private static volatile RoutingMonitorService activeInstance;

    private Handler handler;
    private AudioRouteController controller;
    private LocationWarmup locationWarmup;
    private AudioManager audioManager;
    private AudioManager.AudioPlaybackCallback audioPlaybackCallback;
    private AudioDeviceCallback diagnosticAudioDeviceCallback;
    private AudioManager.OnModeChangedListener diagnosticModeListener;
    private AudioManager.OnCommunicationDeviceChangedListener diagnosticCommunicationListener;
    private BroadcastReceiver diagnosticScoReceiver;
    private BroadcastReceiver diagnosticBluetoothReceiver;
    private String lastPlaybackUsageSummary;
    private boolean assistantPlaybackActive;
    private long assistantAudioGuardUntilElapsed;
    private boolean mediaUsagePlaybackActive;
    private boolean protectedNonMediaPlaybackActive;
    private boolean ordinaryPromptPlaybackActive;
    private boolean interruptingNonMediaPlaybackActive;
    private boolean androidAutoSeen;
    private int androidAutoMisses;
    private ProfileSettings.MonitorSettings lastConfirmedAndroidAutoSettings;
    private AndroidAutoConnection lastConfirmedAndroidAutoConnection;
    private boolean foregroundLocationTypeActive;
    private boolean routeReleasedAfterDisconnect;
    private boolean routeActiveForTarget;
    private boolean targetWasActiveThisSession;
    private boolean bluetoothResetAfterDisconnect;
    private String activeAudioTweakKey;
    private boolean audioTweakDuckingActive;
    private boolean audioTweakNotificationRouteActive;
    private boolean audioTweakMediaRouteActive;
    private boolean audioTweakMediaRouteSuspendedForAssistant;
    private boolean notificationPlaybackMuteActive;
    private boolean notificationPlaybackMuteOwned;
    private int notificationPlaybackRestoreVolume = -1;
    private boolean mediaBoostActive;
    private String activeMediaBoostKey;
    private String requestedMediaBoostKey;
    private AudioRouteController.MediaLevelerStatus mediaLevelerStatus =
            AudioRouteController.MediaLevelerStatus.OFF;
    private long mediaLevelerRetryAtElapsed;
    private long mediaLevelerRetryDelayMs = MEDIA_LEVELER_RETRY_INITIAL_MS;
    private long lastMediaPlaybackActiveAt;
    private long lastBluetoothMediaPlaybackActiveAt;
    private long lastLevelerMediaPlaybackActiveAt;
    private long lastAutoLogAt;
    private long lastRootSnapshotAt;
    private long lastMediaPinPulseLogAt;
    private String lastMediaPinPulseSummary;
    private long lastMediaBoostLogAt;
    private String lastMediaBoostSummary;
    private long lastCallProtectionLogAt;
    private String lastCallProtectionSummary;
    private long lastAssistantProtectionLogAt;
    private String lastAssistantProtectionSummary;
    private volatile boolean rootSnapshotRunning;
    private String lastAutoLogSummary;
    private volatile long audioTweakCommandGeneration;
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
        activeInstance = this;
        handler = new Handler(Looper.getMainLooper());
        AppPrefs.migrateLegacyDynamicsProcessingSettings(this);
        controller = new AudioRouteController(this);
        locationWarmup = new LocationWarmup(this, handler, this::autoLog);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        startAssistantAudioGuard();
        startDiagnosticAudioCallbacks();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (SETTINGS_RESTORE_ACTIVE.get()) {
            AppPrefs.get(this).edit().putBoolean(AppPrefs.MONITOR_ENABLED, false).apply();
            handler.removeCallbacks(applyLoop);
            // The restore worker will synchronously unwind this exact instance, including
            // controller-owned legacy SCO state that a replacement controller cannot identify.
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopMonitor();
            return START_NOT_STICKY;
        }
        if (ACTION_CLEAR.equals(action)) {
            controller.clearRoute();
            stopMonitor();
            return START_NOT_STICKY;
        }

        startForegroundCompat(false);
        AppPrefs.get(this).edit().putBoolean(AppPrefs.MONITOR_ENABLED, true).apply();
        autoLog("monitor start action=" + action + " logs=" + AutoLogWriter.location(this));
        logDiagnosticAudioEvent("monitor_started");
        handler.removeCallbacks(applyLoop);
        handler.post(applyLoop);

        if (ACTION_APPLY_ONCE.equals(action)) {
            applyFromPrefs();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        autoLog("monitor lifecycle destroyed uptimeMs=" + SystemClock.elapsedRealtime());
        handler.removeCallbacks(applyLoop);
        stopLocationWarmup("service destroyed");
        stopAssistantAudioGuard();
        stopDiagnosticAudioCallbacks();
        clearAudioTweaksIfNeeded();
        clearNotificationPlaybackMuteIfNeeded();
        clearMediaBoostIfNeeded();
        if (activeInstance == this) {
            activeInstance = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static boolean beginSettingsRestoreGate() {
        return SETTINGS_RESTORE_ACTIVE.compareAndSet(false, true);
    }

    static void finishSettingsRestoreGate() {
        SETTINGS_RESTORE_ACTIVE.set(false);
    }

    /**
     * Stops a live monitor and synchronously removes every audio change it owns before a
     * settings document replaces the preferences those cleanup decisions depend on. This is
     * called from the restore worker, never the main thread.
     */
    static void stopSynchronouslyForSettingsRestore(Context context) throws IOException {
        Context appContext = context.getApplicationContext();
        AtomicReference<RestoreCleanupPlan> planReference = new AtomicReference<>();
        IOException cleanupFailure = null;
        RestoreCleanupPlan plan = null;
        try {
            try {
                runOnMainThreadAndWait(() -> {
                    RoutingMonitorService service = activeInstance;
                    if (service != null) {
                        planReference.set(service.prepareForSettingsRestoreOnMainThread());
                    }
                }, "prepare routing monitor for settings restore");
                plan = planReference.get();
            } catch (IOException e) {
                cleanupFailure = e;
            }

            if (plan == null) {
                // onDestroy() normally queues root cleanup just before clearing activeInstance.
                // Wait for that retired instance's work as well, so a restore cannot outrun it.
                try {
                    awaitPendingAudioTweakCommands();
                } catch (IOException e) {
                    cleanupFailure = appendFailure(cleanupFailure, e);
                }
                AudioRouteController fallbackController = new AudioRouteController(appContext);
                if (fallbackController.isCallOrCommunicationActive()) {
                    cleanupFailure = appendFailure(cleanupFailure, new IOException(
                            "Cannot clear AAARP's route while a call or communication session is active."
                    ));
                } else {
                    try {
                        fallbackController.clearRoute();
                    } catch (RuntimeException e) {
                        cleanupFailure = appendFailure(cleanupFailure, new IOException(
                                "Could not clear AAARP's communication route before restore.",
                                e
                        ));
                    }
                }
            } else {
                if (plan.localFailure != null) {
                    cleanupFailure = new IOException(plan.localFailure);
                }
                RootShell.ShellResult rootCleanup = plan.clearRootAudioTweaks();
                if (rootCleanup != null && !rootCleanup.success) {
                    IOException rootFailure = new IOException(
                            "Could not verify removal of AAARP's root audio tweaks before restore."
                                    + (rootCleanup.output.length() == 0
                                    ? ""
                                    : " " + rootCleanup.output.trim())
                    );
                    cleanupFailure = appendFailure(cleanupFailure, rootFailure);
                }
            }
        } finally {
            if (plan != null) {
                try {
                    runOnMainThreadAndWait(
                            plan.service::finishSettingsRestoreShutdownOnMainThread,
                            "finish routing monitor shutdown for settings restore"
                    );
                } catch (IOException e) {
                    cleanupFailure = appendFailure(cleanupFailure, e);
                }
            }
            try {
                appContext.stopService(new Intent(appContext, RoutingMonitorService.class));
            } catch (RuntimeException e) {
                cleanupFailure = appendFailure(cleanupFailure, new IOException(
                        "Could not stop the routing monitor before restore.",
                        e
                ));
            }
        }

        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    private RestoreCleanupPlan prepareForSettingsRestoreOnMainThread() {
        handler.removeCallbacks(applyLoop);
        stopLocationWarmup("settings restore");
        stopAssistantAudioGuard();

        boolean restoreDucking = audioTweakDuckingActive;
        boolean clearNotificationRoute = audioTweakNotificationRouteActive;
        boolean clearMediaRoute = audioTweakMediaRouteActive
                || audioTweakMediaRouteSuspendedForAssistant;
        long cleanupGeneration = nextAudioTweakCommandGeneration();
        activeAudioTweakKey = null;
        audioTweakDuckingActive = false;
        audioTweakNotificationRouteActive = false;
        audioTweakMediaRouteActive = false;
        audioTweakMediaRouteSuspendedForAssistant = false;

        String localFailure = clearNotificationPlaybackMuteIfNeeded();
        String mediaCleanup = clearMediaBoostForSettingsRestore();
        if (mediaCleanup != null) {
            localFailure = appendFailureMessage(localFailure, mediaCleanup);
        }

        try {
            if (controller.isCallOrRingingActive()) {
                localFailure = appendFailureMessage(
                        localFailure,
                        "Cannot clear AAARP's route while a call or ringtone is active."
                );
            } else if (controller.clearOwnedRouteIfCurrentBluetoothSco()) {
                routeActiveForTarget = false;
                autoLog("owned Bluetooth route cleared for settings restore");
            } else if (isCallOrCommunicationActive()) {
                localFailure = appendFailureMessage(
                        localFailure,
                        "Cannot clear AAARP's route while another communication session is active."
                );
            } else {
                controller.clearRoute();
                routeActiveForTarget = false;
                autoLog("route cleared for settings restore");
            }
        } catch (RuntimeException e) {
            localFailure = appendFailureMessage(
                    localFailure,
                    "Could not clear AAARP's communication route before restore: "
                            + e.getMessage()
            );
        }
        return new RestoreCleanupPlan(
                this,
                cleanupGeneration,
                restoreDucking,
                clearNotificationRoute,
                clearMediaRoute,
                localFailure
        );
    }

    private void finishSettingsRestoreShutdownOnMainThread() {
        handler.removeCallbacks(applyLoop);
        stopForegroundCompat();
        if (activeInstance == this) {
            activeInstance = null;
        }
        stopSelf();
    }

    private static void runOnMainThreadAndWait(Runnable action, String description)
            throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IOException("Settings restore cleanup must run off the main thread.");
        }
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        boolean posted = mainHandler.post(() -> {
            try {
                action.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                finished.countDown();
            }
        });
        if (!posted) {
            throw new IOException("Could not " + description + ".");
        }
        try {
            if (!finished.await(RESTORE_MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new IOException("Timed out while trying to " + description + ".");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while trying to " + description + ".", e);
        }
        Throwable throwable = failure.get();
        if (throwable != null) {
            throw new IOException("Could not " + description + ".", throwable);
        }
    }

    private static IOException appendFailure(IOException current, IOException additional) {
        if (current == null) {
            return additional;
        }
        current.addSuppressed(additional);
        return current;
    }

    private static String appendFailureMessage(String current, String additional) {
        if (additional == null || additional.length() == 0) {
            return current;
        }
        return current == null || current.length() == 0
                ? additional
                : current + " " + additional;
    }

    private static void awaitPendingAudioTweakCommands() throws IOException {
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(RESTORE_AUDIO_TWEAK_TIMEOUT_MS);
        long deadlineNanos = System.nanoTime() + remainingNanos;
        synchronized (AUDIO_TWEAK_COMMAND_LOCK) {
            while (pendingAudioTweakCommands > 0) {
                if (remainingNanos <= 0L) {
                    throw new IOException(
                            "Timed out while waiting for AAARP's root audio cleanup."
                    );
                }
                long waitMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                try {
                    AUDIO_TWEAK_COMMAND_LOCK.wait(waitMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "Interrupted while waiting for AAARP's root audio cleanup.",
                            e
                    );
                }
                remainingNanos = deadlineNanos - System.nanoTime();
            }
        }
    }

    private static final class RestoreCleanupPlan {
        final RoutingMonitorService service;
        final long commandGeneration;
        final boolean restoreDucking;
        final boolean clearNotificationRoute;
        final boolean clearMediaRoute;
        final String localFailure;

        RestoreCleanupPlan(RoutingMonitorService service,
                           long commandGeneration,
                           boolean restoreDucking,
                           boolean clearNotificationRoute,
                           boolean clearMediaRoute,
                           String localFailure) {
            this.service = service;
            this.commandGeneration = commandGeneration;
            this.restoreDucking = restoreDucking;
            this.clearNotificationRoute = clearNotificationRoute;
            this.clearMediaRoute = clearMediaRoute;
            this.localFailure = localFailure;
        }

        RootShell.ShellResult clearRootAudioTweaks() {
            if (!restoreDucking && !clearNotificationRoute && !clearMediaRoute) {
                return null;
            }
            synchronized (AUDIO_TWEAK_COMMAND_LOCK) {
                // The prepare step invalidates older queued commands. A command already inside
                // this lock completes first, after which this cleanup is the final root change.
                if (commandGeneration != service.audioTweakCommandGeneration) {
                    return RootShell.ShellResult.failure(
                            "Audio-tweak state changed while settings restore was stopping the monitor."
                    );
                }
                return service.controller.clearAndroidAutoAudioTweaks(
                        restoreDucking,
                        clearNotificationRoute,
                        clearMediaRoute
                );
            }
        }
    }

    private long applyFromPrefs() {
        if (!AppPrefs.get(this).getBoolean(AppPrefs.MONITOR_ENABLED, false)) {
            stopForDisabledMonitorPreference();
            return -1L;
        }
        ProfileSettings.MonitorSettings settings = ProfileSettings.monitorSettings(this, null);
        AndroidAutoConnection activeConnection = AndroidAutoConnection.fallback();
        boolean androidAutoActive = false;
        // Keep history through idle time, calls and Assistant protection too. Root work
        // stays on its existing worker and two-minute cadence, outside the routing path.
        maybeLogRootSnapshot("monitor_running");

        if (isCallOrCommunicationActive()) {
            handleCallOrCommunicationProtection(settings);
            return ROUTE_CHECK_INTERVAL_MS;
        }

        if (isAssistantAudioGuardActive()) {
            handleAssistantAudioProtection(settings);
            return ROUTE_CHECK_INTERVAL_MS;
        }

        if (settings.watchdogMode) {
            boolean androidAutoRunning = controller.isAndroidAutoRunningWithRoot();
            if (!androidAutoRunning) {
                if (androidAutoSeen) {
                    androidAutoMisses++;
                    ProfileSettings.MonitorSettings confirmedSettings =
                            lastConfirmedAndroidAutoSettings == null
                                    ? settings
                                    : lastConfirmedAndroidAutoSettings;
                    AndroidAutoConnection confirmedConnection =
                            lastConfirmedAndroidAutoConnection == null
                                    ? AndroidAutoConnection.fallback()
                                    : lastConfirmedAndroidAutoConnection;
                    if (shouldHoldConfirmedAndroidAutoState(androidAutoMisses)) {
                        autoLogState(
                                "aa_probe_miss_hold_" + androidAutoMisses,
                                confirmedSettings,
                                confirmedConnection,
                                null,
                                null,
                                "Holding the last confirmed Android Auto settings and audio effect."
                        );
                        return ROUTE_CHECK_INTERVAL_MS;
                    }

                    autoLog("android auto disconnected; misses=" + androidAutoMisses);
                    stopLocationWarmup("android auto disconnected");
                    clearAudioTweaksIfNeeded();
                    if (confirmedSettings.releaseAfterAndroidAuto && !routeReleasedAfterDisconnect) {
                        controller.clearRoute();
                        routeReleasedAfterDisconnect = true;
                        routeActiveForTarget = false;
                        autoLog("route cleared after Android Auto disconnect");
                    }
                    resetBluetoothAfterDisconnectIfNeeded(
                            confirmedSettings.resetBluetoothAfterAndroidAuto
                    );

                    androidAutoSeen = false;
                    androidAutoMisses = 0;
                    lastConfirmedAndroidAutoSettings = null;
                    lastConfirmedAndroidAutoConnection = null;

                    // Only after the third consecutive miss do we switch from the last
                    // confirmed AA profile to the default outside-AA settings.
                    settings = ProfileSettings.monitorSettings(this, null);
                    applyAudioTweaksIfNeeded(settings, false);
                    updateNotificationPlaybackMute(settings, false);
                    updateMediaBoost(settings, false);
                    boolean keepMonitorForOutsideLeveling = isOutsideMediaLevelingEnabled(
                            settings.normalizeMediaDuringAndroidAuto,
                            settings.normalizeMediaAlways,
                            settings.mediaLevelerMode
                    );
                    if (confirmedSettings.autoStopAfterAndroidAuto
                            && !keepMonitorForOutsideLeveling) {
                        stopMonitor();
                        return -1L;
                    }
                    if (confirmedSettings.autoStopAfterAndroidAuto) {
                        autoLog("auto-stop bypassed because outside-AA media leveling is enabled");
                    }
                    autoLogState(
                            "aa_idle_confirmed",
                            settings,
                            AndroidAutoConnection.fallback(),
                            false,
                            false,
                            null
                    );
                    return idleDelayFor(settings, false);
                }

                autoLogState("aa_idle", settings, AndroidAutoConnection.fallback(), false, false, null);
                applyAudioTweaksIfNeeded(settings, false);
                updateNotificationPlaybackMute(settings, false);
                updateMediaBoost(settings, false);
                return idleDelayFor(settings, false);
            }

            AndroidAutoConnection connection = controller.currentAndroidAutoConnection();
            activeConnection = connection;
            settings = ProfileSettings.monitorSettings(this, connection);
            if (!androidAutoSeen) {
                targetWasActiveThisSession = false;
                bluetoothResetAfterDisconnect = false;
                autoLog("android auto connected profile=" + settings.profileId
                        + " source=" + connection.label()
                        + " key=" + connection.key());
                startLocationWarmupIfNeeded(settings, connection);
            }
            androidAutoSeen = true;
            androidAutoMisses = 0;
            lastConfirmedAndroidAutoSettings = settings;
            lastConfirmedAndroidAutoConnection = connection;
            routeReleasedAfterDisconnect = false;
            androidAutoActive = true;
            autoLogState("aa_active", settings, connection, true, null, null);
            applyAudioTweaksIfNeeded(settings, true);
            updateNotificationPlaybackMute(settings, true);
            updateMediaBoost(settings, true);
            reassertPinnedMediaIfNeeded(settings, true);
        } else {
            applyAudioTweaksIfNeeded(settings, false);
            updateNotificationPlaybackMute(settings, false);
            updateMediaBoost(settings, false);
        }

        if (settings.watchdogMode && !hasPreferredTarget(settings.preferredBluetoothTarget)) {
            releaseActiveTargetRouteIfNeeded(settings.releaseAfterAndroidAuto);
            autoLogState("missing_preferred_target", settings, activeConnection, true, false, null);
            return idleDelayFor(settings, androidAutoActive);
        }

        boolean targetConnected = controller.isPreferredBluetoothTargetConnected(settings.preferredBluetoothTarget);
        if (settings.watchdogMode && !targetConnected) {
            releaseActiveTargetRouteIfNeeded(settings.releaseAfterAndroidAuto);
            autoLogState("target_not_connected", settings, activeConnection, true, false, null);
            return idleDelayFor(settings, androidAutoActive);
        }

        if (shouldSkipGenericBluetoothScoFallback(settings)) {
            pauseBluetoothScoRouteForMediaIfNeeded();
            autoLogState("route_skipped_generic_sco", settings, activeConnection, true, targetConnected,
                    "Android only exposed a generic Bluetooth SCO route for the saved Bluetooth target.\n"
                            + "AAARP skipped that call-quality route because no phone-call audio mode is active.\n"
                            + "Selected route: " + controller.selectedRouteDetail(
                            settings.selectedDeviceKey,
                            settings.preferredBluetoothTarget
                    ) + '\n');
            return ROUTE_CHECK_INTERVAL_MS;
        }

        if (shouldPauseBluetoothScoForMedia(settings)) {
            pauseBluetoothScoRouteForMediaIfNeeded();
            autoLogState("route_paused_for_media", settings, activeConnection, true, targetConnected,
                    "Media playback is active or recently paused and the selected communication route is Bluetooth SCO.\n"
                            + "AAARP left the normal media audio path alone.\n"
                            + "Selected route: " + controller.selectedRouteDetail(
                            settings.selectedDeviceKey,
                            settings.preferredBluetoothTarget
                    ) + '\n');
            return ROUTE_CHECK_INTERVAL_MS;
        }

        AudioRouteController.RoutingResult result = controller.maintainPreferredRoute(
                settings.selectedDeviceKey,
                settings.preferredBluetoothTarget
        );
        routeActiveForTarget = result.success;
        if (result.success) {
            targetWasActiveThisSession = true;
        }
        autoLogState("route_check", settings, activeConnection, true, targetConnected, result.log);
        return ROUTE_CHECK_INTERVAL_MS;
    }

    private void stopForDisabledMonitorPreference() {
        autoLog("monitor preference is disabled; stopping before applying settings");
        handler.removeCallbacks(applyLoop);
        stopLocationWarmup("monitor preference disabled");
        clearAudioTweaksIfNeeded();
        clearNotificationPlaybackMuteIfNeeded();
        clearMediaBoostIfNeeded();
        stopForegroundCompat();
        stopSelf();
    }

    private boolean shouldSkipGenericBluetoothScoFallback(ProfileSettings.MonitorSettings settings) {
        if (!settings.pauseBluetoothScoDuringMedia) {
            return false;
        }
        try {
            return controller.selectedRouteIsUnmatchedBluetoothScoFallback(
                    settings.selectedDeviceKey,
                    settings.preferredBluetoothTarget
            ) && !isCallOrCommunicationActive();
        } catch (RuntimeException e) {
            autoLog("Bluetooth SCO fallback guard failed: " + e.getMessage());
            return false;
        }
    }

    private boolean shouldPauseBluetoothScoForMedia(ProfileSettings.MonitorSettings settings) {
        if (!settings.pauseBluetoothScoDuringMedia) {
            return false;
        }
        try {
            boolean mediaActive = mediaUsagePlaybackActive || controller.isMediaPlaybackActive();
            long now = SystemClock.elapsedRealtime();
            if (mediaActive) {
                lastBluetoothMediaPlaybackActiveAt = now;
            }
            return shouldPauseBluetoothScoForMedia(
                    true,
                    controller.selectedRouteIsBluetoothSco(
                            settings.selectedDeviceKey,
                            settings.preferredBluetoothTarget
                    ),
                    isCallOrCommunicationActive(),
                    mediaActive,
                    lastBluetoothMediaPlaybackActiveAt,
                    now
            );
        } catch (RuntimeException e) {
            autoLog("Bluetooth SCO media guard failed: " + e.getMessage());
            return false;
        }
    }

    static boolean shouldPauseBluetoothScoForMedia(boolean enabled, boolean selectedRouteIsSco,
                                                   boolean communicationActive, boolean mediaActive,
                                                   long lastMediaActiveAt, long nowElapsed) {
        if (!enabled || !selectedRouteIsSco || communicationActive) {
            return false;
        }
        return mediaActive || (lastMediaActiveAt > 0L
                && nowElapsed >= lastMediaActiveAt
                && nowElapsed - lastMediaActiveAt <= BLUETOOTH_MEDIA_IDLE_GRACE_MS);
    }

    private void pauseBluetoothScoRouteForMediaIfNeeded() {
        if (isCallOrCommunicationActive()) {
            autoLog("Bluetooth SCO pause skipped during " + controller.audioModeSummary());
            return;
        }
        // Call protection may reset routeActiveForTarget while our route request remains.
        // The controller checks ownership before clearing, including after a call ends.
        if (controller.clearOwnedRouteIfCurrentBluetoothSco()) {
            routeActiveForTarget = false;
            autoLog("Bluetooth SCO route paused during media playback");
        }
    }

    private boolean hasPreferredTarget(String preferredBluetoothTarget) {
        return preferredBluetoothTarget != null && preferredBluetoothTarget.trim().length() > 0;
    }

    private void releaseActiveTargetRouteIfNeeded(boolean releaseAfterDisconnect) {
        if (isCallOrCommunicationActive()) {
            autoLog("route release skipped during " + controller.audioModeSummary());
            return;
        }
        if (releaseAfterDisconnect && routeActiveForTarget) {
            controller.clearRoute();
            routeActiveForTarget = false;
            autoLog("route released because preferred target is not active");
        }
    }

    private void applyAudioTweaksIfNeeded(ProfileSettings.MonitorSettings settings, boolean androidAutoActive) {
        if (isCallOrCommunicationActive()) {
            logCallProtection("audio tweaks skipped", settings);
            return;
        }
        String notificationRouteMode = androidAutoActive
                ? settings.notificationRouteMode
                : AppPrefs.NOTIFICATION_ROUTE_OFF;
        boolean suppressDucking = settings.suppressNotificationDucking
                && (androidAutoActive || settings.suppressNotificationDuckingAlways);
        boolean pinMediaToBluetooth = androidAutoActive && settings.pinMediaToBluetoothDuringAndroidAuto;
        String tweakKey = settings.profileId
                + "|"
                + androidAutoActive
                + "|"
                + notificationRouteMode
                + "|"
                + suppressDucking
                + "|"
                + pinMediaToBluetooth
                + "|"
                + settings.selectedDeviceKey
                + "|"
                + settings.preferredBluetoothTarget;
        boolean hasTweaks = !AppPrefs.NOTIFICATION_ROUTE_OFF.equals(notificationRouteMode)
                || suppressDucking
                || pinMediaToBluetooth;
        if (!hasTweaks) {
            clearAudioTweaksIfNeeded();
            return;
        }
        if (tweakKey.equals(activeAudioTweakKey)) {
            resumePinnedMediaAfterAssistantIfNeeded(
                    pinMediaToBluetooth,
                    settings.selectedDeviceKey,
                    settings.preferredBluetoothTarget
            );
            return;
        }
        clearAudioTweaksIfNeeded();
        activeAudioTweakKey = tweakKey;
        audioTweakDuckingActive = suppressDucking;
        audioTweakNotificationRouteActive = !AppPrefs.NOTIFICATION_ROUTE_OFF.equals(notificationRouteMode);
        audioTweakMediaRouteActive = pinMediaToBluetooth;
        autoLog("applying audio tweaks notificationRoute=" + notificationRouteMode
                + " suppressDucking=" + suppressDucking
                + " pinMediaToBluetooth=" + pinMediaToBluetooth
                + " androidAutoActive=" + androidAutoActive
                + " selected=" + settings.selectedDeviceKey
                + " target=" + settings.preferredBluetoothTarget);
        long commandGeneration = nextAudioTweakCommandGeneration();
        runAudioTweakCommandAsync(commandGeneration, "aaarp-audio-tweaks", () ->
                controller.applyAndroidAutoAudioTweaks(
                        notificationRouteMode,
                        settings.selectedDeviceKey,
                        settings.preferredBluetoothTarget,
                        suppressDucking,
                        pinMediaToBluetooth
                ));
    }

    private void clearAudioTweaksIfNeeded() {
        if (activeAudioTweakKey == null
                && !audioTweakDuckingActive
                && !audioTweakNotificationRouteActive
                && !audioTweakMediaRouteActive
                && !audioTweakMediaRouteSuspendedForAssistant) {
            return;
        }
        boolean restoreDucking = audioTweakDuckingActive;
        boolean clearNotificationRoute = audioTweakNotificationRouteActive;
        boolean clearMediaRoute = audioTweakMediaRouteActive
                || audioTweakMediaRouteSuspendedForAssistant;
        activeAudioTweakKey = null;
        audioTweakDuckingActive = false;
        audioTweakNotificationRouteActive = false;
        audioTweakMediaRouteActive = false;
        audioTweakMediaRouteSuspendedForAssistant = false;
        long commandGeneration = nextAudioTweakCommandGeneration();
        autoLog("clearing audio tweaks restoreDucking=" + restoreDucking
                + " clearNotificationRoute=" + clearNotificationRoute
                + " clearMediaRoute=" + clearMediaRoute);
        runAudioTweakCommandAsync(commandGeneration, "aaarp-audio-tweaks-clear", () ->
                controller.clearAndroidAutoAudioTweaks(
                        restoreDucking,
                        clearNotificationRoute,
                        clearMediaRoute
                ));
    }

    private long nextAudioTweakCommandGeneration() {
        return ++audioTweakCommandGeneration;
    }

    private void runAudioTweakCommandAsync(long commandGeneration,
                                           String threadName,
                                           Runnable command) {
        synchronized (AUDIO_TWEAK_COMMAND_LOCK) {
            pendingAudioTweakCommands++;
        }
        Thread worker = new Thread(() -> {
            try {
                synchronized (AUDIO_TWEAK_COMMAND_LOCK) {
                    if (commandGeneration != audioTweakCommandGeneration) {
                        autoLog("skipped stale audio-tweak command generation=" + commandGeneration);
                        return;
                    }
                    try {
                        command.run();
                    } catch (RuntimeException e) {
                        autoLog("audio-tweak command failed generation=" + commandGeneration
                                + " error=" + e.getMessage());
                    }
                }
            } finally {
                synchronized (AUDIO_TWEAK_COMMAND_LOCK) {
                    pendingAudioTweakCommands--;
                    AUDIO_TWEAK_COMMAND_LOCK.notifyAll();
                }
            }
        }, threadName);
        try {
            worker.start();
        } catch (RuntimeException e) {
            synchronized (AUDIO_TWEAK_COMMAND_LOCK) {
                pendingAudioTweakCommands--;
                AUDIO_TWEAK_COMMAND_LOCK.notifyAll();
            }
            autoLog("could not start audio-tweak command generation=" + commandGeneration
                    + " error=" + e.getMessage());
        }
    }

    private void reassertPinnedMediaIfNeeded(ProfileSettings.MonitorSettings settings, boolean androidAutoActive) {
        if (isCallOrCommunicationActive()) {
            logCallProtection("media pin skipped", settings);
            return;
        }
        if (!androidAutoActive || !settings.pinMediaToBluetoothDuringAndroidAuto) {
            return;
        }
        try {
            String result = controller.reassertBluetoothMediaPath(
                    settings.selectedDeviceKey,
                    settings.preferredBluetoothTarget
            );
            long now = SystemClock.elapsedRealtime();
            boolean changed = !result.equals(lastMediaPinPulseSummary);
            if (changed || now - lastMediaPinPulseLogAt >= AUTO_LOG_HEARTBEAT_MS) {
                lastMediaPinPulseSummary = result;
                lastMediaPinPulseLogAt = now;
                autoLog("media pin pulse " + result);
            }
        } catch (RuntimeException e) {
            long now = SystemClock.elapsedRealtime();
            String result = "failed: " + e.getMessage();
            if (!result.equals(lastMediaPinPulseSummary) || now - lastMediaPinPulseLogAt >= AUTO_LOG_HEARTBEAT_MS) {
                lastMediaPinPulseSummary = result;
                lastMediaPinPulseLogAt = now;
                autoLog("media pin pulse " + result);
            }
        }
    }

    private void updateNotificationPlaybackMute(ProfileSettings.MonitorSettings settings, boolean androidAutoActive) {
        if (isCallOrCommunicationActive()) {
            clearNotificationPlaybackMuteIfNeeded();
            logCallProtection("notification playback mute skipped", settings);
            return;
        }
        if (!shouldMuteNotificationsDuringPlayback(settings, androidAutoActive)) {
            clearNotificationPlaybackMuteIfNeeded();
            return;
        }

        boolean playbackActive;
        try {
            playbackActive = controller.isMediaPlaybackActive();
        } catch (RuntimeException e) {
            autoLog("playback state check failed: " + e.getMessage());
            return;
        }

        if (playbackActive) {
            lastMediaPlaybackActiveAt = SystemClock.elapsedRealtime();
            muteNotificationsForPlaybackIfNeeded(androidAutoActive);
        } else if (notificationPlaybackMuteActive
                && recentlyHadMediaPlayback(NOTIFICATION_PLAYBACK_IDLE_GRACE_MS)) {
            autoLog("notification stream mute held during brief playback gap androidAutoActive="
                    + androidAutoActive);
        } else {
            clearNotificationPlaybackMuteIfNeeded();
        }
    }

    private boolean shouldMuteNotificationsDuringPlayback(ProfileSettings.MonitorSettings settings,
                                                          boolean androidAutoActive) {
        return settings.muteNotificationsDuringPlayback
                && (androidAutoActive || settings.muteNotificationsDuringPlaybackAlways);
    }

    private void updateMediaBoost(ProfileSettings.MonitorSettings settings, boolean androidAutoActive) {
        boolean boostMedia = shouldBoostMedia(settings, androidAutoActive);
        if (isCallOrCommunicationActive()) {
            clearMediaBoostForCallProtection();
            logCallProtection("media boost skipped", settings);
            return;
        }
        if (!boostMedia) {
            clearMediaBoostIfNeeded();
            return;
        }

        long nowElapsed = SystemClock.elapsedRealtime();
        boolean streamMusicActive = false;
        try {
            streamMusicActive = controller.isMediaPlaybackActive();
        } catch (RuntimeException e) {
            autoLog("media leveler playback-state check failed: " + e.getMessage());
        }
        boolean mediaPlaybackActive = isMediaPlaybackConfirmed(
                mediaUsagePlaybackActive,
                streamMusicActive,
                protectedNonMediaPlaybackActive
        );
        String boostKey = settings.profileId + "|" + settings.mediaLevelerMode;
        boolean holdAcrossTrackGap = !mediaPlaybackActive
                && boostKey.equals(activeMediaBoostKey)
                && shouldHoldMediaLevelerDuringPlaybackGap(
                mediaBoostActive,
                lastLevelerMediaPlaybackActiveAt,
                nowElapsed
        );
        boolean preserveOrdinaryPromptGap = shouldPreserveLevelerForOrdinaryPromptGap(
                MediaLevelerProfile.forMode(settings.mediaLevelerMode).dynamicsEnabled,
                ordinaryPromptPlaybackActive,
                interruptingNonMediaPlaybackActive,
                mediaPlaybackActive,
                holdAcrossTrackGap
        );
        if (shouldSuspendLevelerForProtectedNonMedia(
                ordinaryPromptPlaybackActive,
                interruptingNonMediaPlaybackActive,
                mediaPlaybackActive
        ) && !preserveOrdinaryPromptGap) {
            clearMediaBoostForProtection("non-media playback outside active song-gap hold");
            logMediaBoost("media leveler held off for non-media-only playback"
                    + " streamMusicOwner=user aa=" + androidAutoActive);
            return;
        }
        if (preserveOrdinaryPromptGap) {
            // Releasing and recreating a session-zero effect between songs can expose the next
            // song's loud onset. Keep the already-attached effect; the unavoidable tradeoff is
            // that this standalone prompt also traverses the global processor.
            logMediaBoost("media leveler kept during ordinary prompt inside song-gap hold"
                    + " limitation=global_mix_prompt_also_processed"
                    + " streamMusicOwner=user aa=" + androidAutoActive);
        } else if (ordinaryPromptPlaybackActive && mediaPlaybackActive) {
            // Session zero cannot exclude the prompt without also changing the song. Keeping
            // the stable song path avoids an audible disable/re-enable cycle for every prompt.
            logMediaBoost("media leveler kept during simultaneous media and ordinary prompt playback"
                    + " limitation=global_mix_prompt_also_processed"
                    + " streamMusicOwner=user aa=" + androidAutoActive);
        }

        if (mediaPlaybackActive) {
            lastLevelerMediaPlaybackActiveAt = nowElapsed;
        } else {
            if (holdAcrossTrackGap) {
                AudioRouteController.MediaLevelerActivation inspected =
                        controller.inspectMediaLeveler();
                mediaLevelerStatus = inspected.status;
                if (inspected.processingActive()) {
                    logMediaBoost("media leveler held across playback gap " + inspected.summary()
                            + " idleForMs=" + (nowElapsed - lastLevelerMediaPlaybackActiveAt)
                            + " streamMusicOwner=user aa=" + androidAutoActive);
                    return;
                }
            }
            clearMediaBoostIfNeeded();
            return;
        }

        boolean requestedProfileChanged = !boostKey.equals(requestedMediaBoostKey);
        if (requestedProfileChanged) {
            requestedMediaBoostKey = boostKey;
            mediaLevelerRetryAtElapsed = 0L;
            mediaLevelerRetryDelayMs = MEDIA_LEVELER_RETRY_INITIAL_MS;
        }

        if (mediaBoostActive && boostKey.equals(activeMediaBoostKey)) {
            AudioRouteController.MediaLevelerActivation inspected = controller.inspectMediaLeveler();
            mediaLevelerStatus = inspected.status;
            if (inspected.processingActive()) {
                logMediaBoost("media leveler attached " + inspected.summary()
                        + " streamMusicOwner=user"
                        + " aa=" + androidAutoActive);
                return;
            }
            mediaBoostActive = false;
            activeMediaBoostKey = null;
            mediaLevelerRetryAtElapsed = 0L;
            logMediaBoost("media leveler lost " + inspected.summary()
                    + " streamMusicOwner=user aa=" + androidAutoActive);
        }

        if (!requestedProfileChanged && nowElapsed < mediaLevelerRetryAtElapsed) {
            logMediaBoost("media leveler retry pending status="
                    + mediaLevelerStatus.name().toLowerCase(java.util.Locale.US)
                    + " retryInMs=" + (mediaLevelerRetryAtElapsed - nowElapsed)
                    + " streamMusicOwner=user"
                    + " aa=" + androidAutoActive);
            return;
        }

        try {
            AudioRouteController.MediaLevelerActivation activation =
                    controller.enableMediaBoost(settings.mediaLevelerMode);
            mediaLevelerStatus = activation.status;
            mediaBoostActive = activation.processingActive();
            if (mediaBoostActive) {
                activeMediaBoostKey = boostKey;
                mediaLevelerRetryAtElapsed = 0L;
                mediaLevelerRetryDelayMs = MEDIA_LEVELER_RETRY_INITIAL_MS;
            } else {
                activeMediaBoostKey = null;
                scheduleMediaLevelerRetry(nowElapsed);
            }
            logMediaBoost("media leveler activation " + activation.summary()
                    + " streamMusicOwner=user"
                    + " legacyVolumeFloorIgnored=" + settings.mediaVolumeFloorFallback
                    + " aa=" + androidAutoActive);
        } catch (RuntimeException e) {
            mediaBoostActive = false;
            activeMediaBoostKey = null;
            mediaLevelerStatus = AudioRouteController.MediaLevelerStatus.FAILED;
            scheduleMediaLevelerRetry(nowElapsed);
            autoLog("media leveler activation failed status=failed error=" + e.getMessage()
                    + " retryInMs=" + Math.max(0L, mediaLevelerRetryAtElapsed - nowElapsed)
                    + " streamMusicOwner=user");
        }
    }

    private boolean shouldBoostMedia(ProfileSettings.MonitorSettings settings, boolean androidAutoActive) {
        return settings.normalizeMediaDuringAndroidAuto
                && (androidAutoActive || settings.normalizeMediaAlways)
                && MediaLevelerProfile.forMode(settings.mediaLevelerMode).anyProcessingEnabled();
    }

    static boolean isOutsideMediaLevelingEnabled(boolean normalizeMedia,
                                                  boolean normalizeMediaAlways,
                                                  String levelerMode) {
        return normalizeMedia
                && normalizeMediaAlways
                && MediaLevelerProfile.forMode(levelerMode).anyProcessingEnabled();
    }

    static boolean shouldHoldConfirmedAndroidAutoState(int consecutiveMisses) {
        return consecutiveMisses > 0 && consecutiveMisses < AUTO_STOP_MISSES;
    }

    static boolean shouldHoldMediaLevelerDuringPlaybackGap(boolean effectActive,
                                                            long lastPlaybackActiveAt,
                                                            long nowElapsed) {
        return effectActive
                && lastPlaybackActiveAt > 0L
                && nowElapsed >= lastPlaybackActiveAt
                && nowElapsed - lastPlaybackActiveAt <= MEDIA_LEVELER_PLAYBACK_IDLE_GRACE_MS;
    }

    static boolean isMediaPlaybackConfirmed(boolean mediaUsageActive,
                                             boolean streamMusicActive,
                                             boolean protectedNonMediaActive) {
        // isMusicActive() is the pre-API-26/unknown-usage fallback, but it must not turn an
        // explicitly classified alarm or prompt into "music" merely because that sound uses
        // the music stream. A real USAGE_MEDIA/GAME player wins when both usages are present.
        return mediaUsageActive || (streamMusicActive && !protectedNonMediaActive);
    }

    static boolean shouldSuspendLevelerForProtectedNonMedia(boolean ordinaryPromptActive,
                                                             boolean interruptingNonMediaActive,
                                                             boolean mediaPlaybackActive) {
        // Alarms, ringtones, accessibility, and voice/communication audio always win over the
        // global effect. Ordinary prompts may share it only while real media is also playing;
        // the separate song-gap rule handles an already-attached downward-only leveler.
        return interruptingNonMediaActive
                || (ordinaryPromptActive && !mediaPlaybackActive);
    }

    static boolean shouldPreserveLevelerForOrdinaryPromptGap(boolean downwardOnlyLevelerActive,
                                                              boolean ordinaryPromptActive,
                                                              boolean interruptingNonMediaActive,
                                                              boolean mediaPlaybackActive,
                                                              boolean holdAcrossTrackGap) {
        return downwardOnlyLevelerActive
                && ordinaryPromptActive
                && !interruptingNonMediaActive
                && !mediaPlaybackActive
                && holdAcrossTrackGap;
    }

    private void scheduleMediaLevelerRetry(long nowElapsed) {
        mediaLevelerRetryAtElapsed = nowElapsed + mediaLevelerRetryDelayMs;
        mediaLevelerRetryDelayMs = nextMediaLevelerRetryDelayMs(mediaLevelerRetryDelayMs);
    }

    static long nextMediaLevelerRetryDelayMs(long currentDelayMs) {
        long safeCurrent = Math.max(MEDIA_LEVELER_RETRY_INITIAL_MS, currentDelayMs);
        if (safeCurrent >= MEDIA_LEVELER_RETRY_MAX_MS) {
            return MEDIA_LEVELER_RETRY_MAX_MS;
        }
        return Math.min(MEDIA_LEVELER_RETRY_MAX_MS, safeCurrent * 2L);
    }

    private void clearMediaBoostIfNeeded() {
        boolean hadTrackedState = mediaBoostActive
                || activeMediaBoostKey != null
                || requestedMediaBoostKey != null
                || mediaLevelerStatus != AudioRouteController.MediaLevelerStatus.OFF;
        if (!hadTrackedState) {
            return;
        }
        mediaBoostActive = false;
        activeMediaBoostKey = null;
        requestedMediaBoostKey = null;
        mediaLevelerStatus = AudioRouteController.MediaLevelerStatus.OFF;
        mediaLevelerRetryAtElapsed = 0L;
        mediaLevelerRetryDelayMs = MEDIA_LEVELER_RETRY_INITIAL_MS;

        String effect = controller.disableMediaBoost();
        autoLog("media leveler cleared " + effect + " streamMusicOwner=user");
    }

    private String clearMediaBoostForSettingsRestore() {
        mediaBoostActive = false;
        activeMediaBoostKey = null;
        requestedMediaBoostKey = null;
        mediaLevelerStatus = AudioRouteController.MediaLevelerStatus.OFF;
        mediaLevelerRetryAtElapsed = 0L;
        mediaLevelerRetryDelayMs = MEDIA_LEVELER_RETRY_INITIAL_MS;

        String effect = controller.disableMediaBoost();
        autoLog("media leveler cleared for settings restore " + effect
                + " streamMusicOwner=user");
        if (effect.contains("clear_failed") || effect.contains("release_failed")) {
            return "Could not fully release AAARP's media leveler before restore: " + effect;
        }
        return null;
    }

    private void clearMediaBoostForCallProtection() {
        clearMediaBoostForProtection("call");
    }

    private boolean clearMediaBoostForProtection(String reason) {
        boolean hadTrackedState = mediaBoostActive
                || activeMediaBoostKey != null
                || requestedMediaBoostKey != null
                || mediaLevelerStatus != AudioRouteController.MediaLevelerStatus.OFF;
        mediaBoostActive = false;
        activeMediaBoostKey = null;
        requestedMediaBoostKey = null;
        mediaLevelerStatus = AudioRouteController.MediaLevelerStatus.OFF;
        mediaLevelerRetryAtElapsed = 0L;
        mediaLevelerRetryDelayMs = MEDIA_LEVELER_RETRY_INITIAL_MS;

        String effect = controller.disableMediaBoost();
        if (hadTrackedState || !effect.contains("already")) {
            autoLog("media leveler suspended for " + reason + " protection " + effect
                    + " streamMusicOwner=user");
        }
        return hadTrackedState;
    }

    private void logMediaBoost(String summary) {
        long now = SystemClock.elapsedRealtime();
        boolean changed = !summary.equals(lastMediaBoostSummary);
        if (changed || now - lastMediaBoostLogAt >= AUTO_LOG_HEARTBEAT_MS) {
            lastMediaBoostSummary = summary;
            lastMediaBoostLogAt = now;
            autoLog(summary);
        }
    }

    private long idleDelayFor(ProfileSettings.MonitorSettings settings, boolean androidAutoActive) {
        return shouldMuteNotificationsDuringPlayback(settings, androidAutoActive)
                || shouldBoostMedia(settings, androidAutoActive)
                ? ROUTE_CHECK_INTERVAL_MS
                : ANDROID_AUTO_IDLE_CHECK_INTERVAL_MS;
    }

    private void muteNotificationsForPlaybackIfNeeded(boolean androidAutoActive) {
        try {
            int currentVolume = controller.notificationStreamVolume();
            if (!notificationPlaybackMuteActive) {
                notificationPlaybackMuteActive = true;
                if (currentVolume == 0) {
                    notificationPlaybackMuteOwned = false;
                    notificationPlaybackRestoreVolume = -1;
                    autoLog("notification stream already muted during playback; leaving ownership to another app"
                            + " androidAutoActive=" + androidAutoActive);
                    return;
                }
                notificationPlaybackMuteOwned = true;
                notificationPlaybackRestoreVolume = currentVolume;
                controller.setNotificationStreamVolume(0);
                autoLog("notification stream muted during playback restoreVolume="
                        + notificationPlaybackRestoreVolume
                        + " androidAutoActive=" + androidAutoActive);
                return;
            }
            if (notificationPlaybackMuteOwned && currentVolume != 0) {
                controller.setNotificationStreamVolume(0);
                autoLog("notification stream re-muted during playback currentVolume=" + currentVolume);
            }
        } catch (RuntimeException e) {
            autoLog("notification stream mute failed: " + e.getMessage());
        }
    }

    private String clearNotificationPlaybackMuteIfNeeded() {
        if (!notificationPlaybackMuteActive) {
            return null;
        }
        int restoreVolume = notificationPlaybackRestoreVolume;
        boolean restoreOwned = notificationPlaybackMuteOwned;
        notificationPlaybackMuteActive = false;
        notificationPlaybackMuteOwned = false;
        notificationPlaybackRestoreVolume = -1;
        try {
            int currentVolume = controller.notificationStreamVolume();
            if (restoreOwned && restoreVolume >= 0 && currentVolume == 0) {
                controller.setNotificationStreamVolume(restoreVolume);
                autoLog("notification stream restored after playback restoreVolume=" + restoreVolume);
            } else {
                autoLog("notification stream mute ended without restore owned="
                        + restoreOwned
                        + " restoreVolume=" + restoreVolume
                        + " currentVolume=" + currentVolume);
            }
            return null;
        } catch (RuntimeException e) {
            // Preserve ownership so onDestroy() can retry instead of forgetting a stream change
            // that Android rejected during the synchronous restore preparation.
            notificationPlaybackMuteActive = true;
            notificationPlaybackMuteOwned = restoreOwned;
            notificationPlaybackRestoreVolume = restoreVolume;
            autoLog("notification stream restore failed: " + e.getMessage());
            return "Could not restore AAARP's notification-volume change before restore: "
                    + e.getMessage();
        }
    }

    private void resetBluetoothAfterDisconnectIfNeeded(boolean resetBluetoothAfterDisconnect) {
        if (!resetBluetoothAfterDisconnect || bluetoothResetAfterDisconnect || !targetWasActiveThisSession) {
            return;
        }
        bluetoothResetAfterDisconnect = true;
        autoLog("resetting Bluetooth after Android Auto disconnect");
        new Thread(() -> controller.resetBluetoothWithRoot(), "aaarp-bluetooth-reset").start();
    }

    private void stopMonitor() {
        autoLog("monitor stop requested");
        stopLocationWarmup("monitor stopping");
        releaseRouteOnStopIfEnabled();
        AppPrefs.get(this).edit().putBoolean(AppPrefs.MONITOR_ENABLED, false).apply();
        handler.removeCallbacks(applyLoop);
        stopForegroundCompat();
        stopSelf();
    }

    private void releaseRouteOnStopIfEnabled() {
        clearAudioTweaksIfNeeded();
        clearNotificationPlaybackMuteIfNeeded();
        clearMediaBoostIfNeeded();
        SharedPreferences prefs = AppPrefs.get(this);
        if (prefs.getBoolean(AppPrefs.RELEASE_ROUTE_AFTER_ANDROID_AUTO, true)) {
            if (isCallOrCommunicationActive()) {
                autoLog("route clear on monitor stop skipped during " + controller.audioModeSummary());
                return;
            }
            controller.clearRoute();
            routeActiveForTarget = false;
            autoLog("route cleared on monitor stop");
        }
    }

    private void autoLogState(String stage, ProfileSettings.MonitorSettings settings,
                              AndroidAutoConnection connection, Boolean androidAutoRunning,
                              Boolean targetConnected, String routeLog) {
        long now = SystemClock.elapsedRealtime();
        String summary = "stage=" + stage
                + " aa=" + value(androidAutoRunning)
                + " profile=" + settings.profileId
                + " source=" + (connection == null ? "unknown" : connection.label())
                + " sourceKey=" + (connection == null ? "unknown" : connection.key())
                + " targetConnected=" + value(targetConnected)
                + " routeActive=" + routeActiveForTarget
                + " currentRoute=" + controller.currentCommunicationDevice()
                + " audioMode=" + controller.audioModeSummary()
                + " pauseScoForMedia=" + settings.pauseBluetoothScoDuringMedia
                + " gpsWarmup=" + settings.gpsWarmupDuringAndroidAuto
                + " pinMediaToBluetooth=" + settings.pinMediaToBluetoothDuringAndroidAuto
                + " normalizeMedia=" + settings.normalizeMediaDuringAndroidAuto
                + " normalizeAlways=" + settings.normalizeMediaAlways
                + " legacyVolumeFloorIgnored=" + settings.mediaVolumeFloorFallback
                + " leveler=" + settings.mediaLevelerMode
                + " notificationRoute=" + settings.notificationRouteMode
                + " suppressDucking=" + settings.suppressNotificationDucking
                + " suppressDuckingAlways=" + settings.suppressNotificationDuckingAlways
                + " mutePlayback=" + settings.muteNotificationsDuringPlayback
                + " mutePlaybackAlways=" + settings.muteNotificationsDuringPlaybackAlways
                + " muteActive=" + notificationPlaybackMuteActive
                + " muteOwned=" + notificationPlaybackMuteOwned
                + " mediaLevelerStatus=" + mediaLevelerStatus.name().toLowerCase(java.util.Locale.US)
                + " streamMusicOwner=user"
                + " inputRoutes=" + controller.compactInputRouteSummary()
                + " preferredTarget=" + settings.preferredBluetoothTarget
                + " selected=" + settings.selectedDeviceKey;
        boolean changed = !summary.equals(lastAutoLogSummary);
        if (changed || now - lastAutoLogAt >= AUTO_LOG_HEARTBEAT_MS) {
            lastAutoLogSummary = summary;
            lastAutoLogAt = now;
            autoLog(summary);
            if (routeLog != null && routeLog.length() > 0) {
                autoLog("route_result " + routeLog);
            }
        }
    }

    private void maybeLogRootSnapshot(String reason) {
        long now = SystemClock.elapsedRealtime();
        if (!isDiagnosticCaptureEnabled()
                || !AppPrefs.get(this).getBoolean(AppPrefs.USE_ROOT, false)
                || rootSnapshotRunning
                || (lastRootSnapshotAt > 0L
                && now - lastRootSnapshotAt < AUTO_LOG_ROOT_SNAPSHOT_MS)) {
            return;
        }
        rootSnapshotRunning = true;
        lastRootSnapshotAt = now;
        new Thread(() -> {
            try {
                if (!isDiagnosticCaptureEnabled()
                        || !AppPrefs.get(this).getBoolean(AppPrefs.USE_ROOT, false)) {
                    return;
                }
                RootShell.ShellResult result = controller.autoLogSnapshot();
                autoLog("root_snapshot reason=" + reason + " exit=" + result.exitCode + " output=" + result.output);
            } catch (RuntimeException e) {
                autoLog("root_snapshot reason=" + reason + " failed=" + e.getMessage());
            } finally {
                rootSnapshotRunning = false;
            }
        }, "aaarp-auto-log-snapshot").start();
    }

    private void autoLog(String message) {
        AutoLogWriter.append(this, message);
    }

    private boolean isDiagnosticCaptureEnabled() {
        return activeInstance == this
                && !SETTINGS_RESTORE_ACTIVE.get()
                && AppPrefs.get(this).getBoolean(AppPrefs.MONITOR_ENABLED, false);
    }

    private void logDiagnosticAudioEvent(String event) {
        if (!isDiagnosticCaptureEnabled() || audioManager == null) {
            return;
        }
        try {
            // These public API reads are immediate. Never wait for a root dump on an
            // audio callback: a short SCO transition may already be over by then.
            autoLog("audio_event " + event
                    + " audioMode=" + controller.audioModeSummary()
                    + " currentRoute=" + controller.currentCommunicationDevice()
                    + " musicActive=" + audioManager.isMusicActive()
                    + " mediaUsage=" + mediaUsagePlaybackActive
                    + " assistantPlayback=" + assistantPlaybackActive
                    + " routeOwned=" + routeActiveForTarget);
        } catch (RuntimeException e) {
            autoLog("audio_event " + event + " state_unavailable=" + e.getMessage());
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void logPlaybackUsageTransition(List<AudioPlaybackConfiguration> configs) {
        if (!isDiagnosticCaptureEnabled()) {
            return;
        }
        Set<Integer> usages = new TreeSet<>();
        if (configs != null) {
            for (AudioPlaybackConfiguration config : configs) {
                if (config != null && config.getAudioAttributes() != null) {
                    usages.add(config.getAudioAttributes().getUsage());
                }
            }
        }
        String summary = "playback_usages=" + usages;
        if (!summary.equals(lastPlaybackUsageSummary)) {
            lastPlaybackUsageSummary = summary;
            logDiagnosticAudioEvent(summary);
        }
    }

    private void startDiagnosticAudioCallbacks() {
        if (audioManager == null) {
            return;
        }
        diagnosticAudioDeviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                logDiagnosticAudioDevices("devices_added", addedDevices);
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                logDiagnosticAudioDevices("devices_removed", removedDevices);
            }
        };
        try {
            audioManager.registerAudioDeviceCallback(diagnosticAudioDeviceCallback, handler);
        } catch (RuntimeException e) {
            diagnosticAudioDeviceCallback = null;
            autoLog("audio history device callback registration failed: " + e.getMessage());
        }

        diagnosticScoReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED.equals(intent.getAction())) {
                    logDiagnosticAudioEvent("sco_state="
                            + intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,
                            AudioManager.SCO_AUDIO_STATE_ERROR)
                            + " previous="
                            + intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE,
                            AudioManager.SCO_AUDIO_STATE_ERROR));
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(diagnosticScoReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(diagnosticScoReceiver, filter);
            }
        } catch (RuntimeException e) {
            diagnosticScoReceiver = null;
            autoLog("audio history SCO receiver registration failed: " + e.getMessage());
        }

        startDiagnosticBluetoothReceiver();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startDiagnosticCommunicationCallbacks();
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private void startDiagnosticCommunicationCallbacks() {
        diagnosticModeListener = mode -> logDiagnosticAudioEvent("mode_changed=" + mode);
        try {
            audioManager.addOnModeChangedListener(getMainExecutor(), diagnosticModeListener);
        } catch (RuntimeException e) {
            diagnosticModeListener = null;
            autoLog("audio history mode callback registration failed: " + e.getMessage());
        }
        diagnosticCommunicationListener = device -> logDiagnosticAudioEvent(
                "communication_device=" + diagnosticDeviceLabel(device));
        try {
            audioManager.addOnCommunicationDeviceChangedListener(
                    getMainExecutor(), diagnosticCommunicationListener);
        } catch (RuntimeException e) {
            diagnosticCommunicationListener = null;
            autoLog("audio history communication callback registration failed: " + e.getMessage());
        }
    }

    private void logDiagnosticAudioDevices(String event, AudioDeviceInfo[] devices) {
        if (!isDiagnosticCaptureEnabled()) {
            return;
        }
        StringBuilder detail = new StringBuilder(event).append('=');
        if (devices != null) {
            for (AudioDeviceInfo device : devices) {
                if (device != null) {
                    detail.append('[').append(diagnosticDeviceLabel(device)).append(']');
                }
            }
        }
        logDiagnosticAudioEvent(detail.toString());
    }

    private String diagnosticDeviceLabel(AudioDeviceInfo device) {
        if (device == null) {
            return "none";
        }
        try {
            return RouteDevice.from(device).detailLabel();
        } catch (RuntimeException e) {
            return "unavailable: " + e.getMessage();
        }
    }

    private void startDiagnosticBluetoothReceiver() {
        diagnosticBluetoothReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!isDiagnosticCaptureEnabled()) {
                    return;
                }
                String action = intent.getAction();
                boolean aclEvent = BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                        || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action);
                boolean profileEvent = BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                        || BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                        || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED.equals(action));
                if (!aclEvent && !profileEvent) {
                    return;
                }
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                if (profileEvent && state != BluetoothProfile.STATE_CONNECTED
                        && state != BluetoothProfile.STATE_DISCONNECTED) {
                    return;
                }
                BluetoothDevice device;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                } else {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                }
                String event = "bluetooth_connection action=" + action
                        + " state=" + (aclEvent
                        ? (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action) ? "connected" : "disconnected")
                        : state)
                        + " previous=" + intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
                        + " device=" + (device == null ? "unknown" : device.toString());
                logDiagnosticAudioEvent(event);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            filter.addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        }
        try {
            // Bluetooth can broadcast from a privileged process with a different UID.
            // These platform actions are protected against broadcasts from ordinary apps.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(diagnosticBluetoothReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(diagnosticBluetoothReceiver, filter);
            }
        } catch (RuntimeException e) {
            diagnosticBluetoothReceiver = null;
            autoLog("audio history Bluetooth receiver registration failed: " + e.getMessage());
        }
    }

    private void stopDiagnosticAudioCallbacks() {
        if (audioManager != null && diagnosticAudioDeviceCallback != null) {
            try {
                audioManager.unregisterAudioDeviceCallback(diagnosticAudioDeviceCallback);
            } catch (RuntimeException e) {
                autoLog("audio history device callback unregister failed: " + e.getMessage());
            }
        }
        diagnosticAudioDeviceCallback = null;
        if (diagnosticScoReceiver != null) {
            try {
                unregisterReceiver(diagnosticScoReceiver);
            } catch (RuntimeException e) {
                autoLog("audio history SCO receiver unregister failed: " + e.getMessage());
            }
        }
        diagnosticScoReceiver = null;
        if (diagnosticBluetoothReceiver != null) {
            try {
                unregisterReceiver(diagnosticBluetoothReceiver);
            } catch (RuntimeException e) {
                autoLog("audio history Bluetooth receiver unregister failed: " + e.getMessage());
            }
        }
        diagnosticBluetoothReceiver = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && audioManager != null) {
            stopDiagnosticCommunicationCallbacks();
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private void stopDiagnosticCommunicationCallbacks() {
        if (diagnosticModeListener != null) {
            try {
                audioManager.removeOnModeChangedListener(diagnosticModeListener);
            } catch (RuntimeException e) {
                autoLog("audio history mode callback unregister failed: " + e.getMessage());
            }
        }
        diagnosticModeListener = null;
        if (diagnosticCommunicationListener != null) {
            try {
                audioManager.removeOnCommunicationDeviceChangedListener(diagnosticCommunicationListener);
            } catch (RuntimeException e) {
                autoLog("audio history communication callback unregister failed: " + e.getMessage());
            }
        }
        diagnosticCommunicationListener = null;
    }

    private String value(Boolean value) {
        return value == null ? "unknown" : (value ? "yes" : "no");
    }

    private boolean recentlyHadMediaPlayback(long idleGraceMs) {
        return lastMediaPlaybackActiveAt > 0L
                && SystemClock.elapsedRealtime() - lastMediaPlaybackActiveAt <= idleGraceMs;
    }

    private void startAssistantAudioGuard() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || audioManager == null) {
            return;
        }

        audioPlaybackCallback = new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                updateAssistantAudioGuard(configs);
                updateLevelerPlaybackUsageGuard(configs);
                pauseOwnedBluetoothScoOnMediaStart();
                logPlaybackUsageTransition(configs);
                wakeApplyLoopForPlaybackChange();
            }
        };
        try {
            audioManager.registerAudioPlaybackCallback(audioPlaybackCallback, handler);
        } catch (RuntimeException e) {
            audioPlaybackCallback = null;
            autoLog("assistant audio guard registration failed: " + e.getMessage());
            return;
        }

        try {
            List<AudioPlaybackConfiguration> activeConfigs =
                    audioManager.getActivePlaybackConfigurations();
            updateAssistantAudioGuard(activeConfigs);
            updateLevelerPlaybackUsageGuard(activeConfigs);
            logPlaybackUsageTransition(activeConfigs);
        } catch (RuntimeException e) {
            autoLog("playback usage guard initial state failed: " + e.getMessage());
        }
    }

    private void stopAssistantAudioGuard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && audioManager != null
                && audioPlaybackCallback != null) {
            try {
                audioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback);
            } catch (RuntimeException e) {
                autoLog("assistant audio guard unregister failed: " + e.getMessage());
            }
        }
        audioPlaybackCallback = null;
        assistantPlaybackActive = false;
        assistantAudioGuardUntilElapsed = 0L;
        mediaUsagePlaybackActive = false;
        protectedNonMediaPlaybackActive = false;
        ordinaryPromptPlaybackActive = false;
        interruptingNonMediaPlaybackActive = false;
    }

    private void wakeApplyLoopForPlaybackChange() {
        if (handler == null) {
            return;
        }
        // The callback is registered on this main-thread Handler. Removing before posting
        // collapses a burst of player-state callbacks into one immediate monitor pass.
        handler.removeCallbacks(applyLoop);
        handler.post(applyLoop);
    }

    private void pauseOwnedBluetoothScoOnMediaStart() {
        if (!mediaUsagePlaybackActive || SETTINGS_RESTORE_ACTIVE.get()
                || isAssistantAudioGuardActive()) {
            return;
        }
        SharedPreferences prefs = AppPrefs.get(this);
        ProfileSettings.MonitorSettings settings = ProfileSettings.monitorSettings(
                this, androidAutoSeen ? lastConfirmedAndroidAutoConnection : null);
        if (!prefs.getBoolean(AppPrefs.MONITOR_ENABLED, false)
                || !settings.pauseBluetoothScoDuringMedia) {
            return;
        }
        try {
            // Release our SCO request before the full monitor pass does any root probes.
            // The existing cleanup checks ownership and yields to call/communication modes.
            pauseBluetoothScoRouteForMediaIfNeeded();
        } catch (RuntimeException e) {
            autoLog("playback callback Bluetooth SCO pause failed: " + e.getMessage());
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void updateLevelerPlaybackUsageGuard(List<AudioPlaybackConfiguration> configs) {
        boolean mediaActive = false;
        boolean ordinaryPromptActive = false;
        boolean interruptingNonMediaActive = false;
        if (configs != null) {
            for (AudioPlaybackConfiguration config : configs) {
                // AudioManager documents this callback payload as the result of
                // getActivePlaybackConfigurations(). Player state itself is a hidden
                // @SystemApi, so ordinary apps must rely on that public active-list contract.
                if (config == null || config.getAudioAttributes() == null) {
                    continue;
                }
                int usage = config.getAudioAttributes().getUsage();
                if (isLevelerMediaUsage(usage)) {
                    mediaActive = true;
                } else if (isLevelerOrdinaryPromptUsage(usage)) {
                    ordinaryPromptActive = true;
                } else if (isLevelerProtectedNonMediaUsage(usage)) {
                    interruptingNonMediaActive = true;
                }
            }
        }

        long now = SystemClock.elapsedRealtime();
        if (mediaActive || mediaUsagePlaybackActive) {
            // Record the stop callback too: a long song may have had no recent callbacks.
            lastBluetoothMediaPlaybackActiveAt = now;
        }
        mediaUsagePlaybackActive = mediaActive;
        if (mediaActive) {
            lastLevelerMediaPlaybackActiveAt = now;
        }
        ordinaryPromptPlaybackActive = ordinaryPromptActive;
        interruptingNonMediaPlaybackActive = interruptingNonMediaActive;
        protectedNonMediaPlaybackActive = ordinaryPromptActive || interruptingNonMediaActive;
    }

    static boolean isLevelerMediaUsage(int usage) {
        return usage == AudioAttributes.USAGE_MEDIA
                || usage == AudioAttributes.USAGE_GAME;
    }

    static boolean isLevelerProtectedNonMediaUsage(int usage) {
        switch (usage) {
            case AudioAttributes.USAGE_ALARM:
            case AudioAttributes.USAGE_NOTIFICATION_RINGTONE:
            case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST:
            case AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY:
            case AudioAttributes.USAGE_VOICE_COMMUNICATION:
            case AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING:
                return true;
            default:
                return isLevelerOrdinaryPromptUsage(usage);
        }
    }

    static boolean isLevelerOrdinaryPromptUsage(int usage) {
        switch (usage) {
            case AudioAttributes.USAGE_NOTIFICATION:
            case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT:
            case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED:
            case AudioAttributes.USAGE_NOTIFICATION_EVENT:
            case AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
            case AudioAttributes.USAGE_ASSISTANCE_SONIFICATION:
                return true;
            default:
                return false;
        }
    }

    private void updateAssistantAudioGuard(List<AudioPlaybackConfiguration> configs) {
        int assistantUsage = activeAssistantUsage(configs);
        long now = SystemClock.elapsedRealtime();
        if (assistantUsage >= 0) {
            if (!assistantPlaybackActive) {
                autoLog("assistant audio guard active usage=" + assistantUsage);
            }
            assistantPlaybackActive = true;
            assistantAudioGuardUntilElapsed = now + ASSISTANT_AUDIO_GRACE_MS;
            return;
        }

        if (assistantPlaybackActive) {
            assistantPlaybackActive = false;
            assistantAudioGuardUntilElapsed = now + ASSISTANT_AUDIO_GRACE_MS;
            autoLog("assistant playback ended; routing guard held for "
                    + ASSISTANT_AUDIO_GRACE_MS + "ms");
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private int activeAssistantUsage(List<AudioPlaybackConfiguration> configs) {
        if (configs == null) {
            return -1;
        }
        for (AudioPlaybackConfiguration config : configs) {
            if (config == null) {
                continue;
            }
            AudioAttributes attributes = config.getAudioAttributes();
            if (attributes == null) {
                continue;
            }
            int usage = attributes.getUsage();
            // Navigation prompts are expected to mix or duck over music and must not tear
            // down the persistent song leveler. Only the full Assistant voice path gets the
            // explicit processing suspension.
            if (usage == AudioAttributes.USAGE_ASSISTANT) {
                return usage;
            }
        }
        return -1;
    }

    private boolean isAssistantAudioGuardActive() {
        return assistantPlaybackActive
                || SystemClock.elapsedRealtime() < assistantAudioGuardUntilElapsed;
    }

    private void handleAssistantAudioProtection(ProfileSettings.MonitorSettings settings) {
        boolean suspendedMediaPin = suspendPinnedMediaForAssistantIfNeeded();
        boolean suspendedLeveler = clearMediaBoostForProtection("assistant");
        logAssistantProtection(settings, suspendedMediaPin, suspendedLeveler);
    }

    private boolean suspendPinnedMediaForAssistantIfNeeded() {
        if (!audioTweakMediaRouteActive) {
            return false;
        }
        audioTweakMediaRouteActive = false;
        audioTweakMediaRouteSuspendedForAssistant = true;
        long commandGeneration = nextAudioTweakCommandGeneration();
        runAudioTweakCommandAsync(commandGeneration, "aaarp-assistant-media-pin-clear", () ->
                controller.clearAndroidAutoAudioTweaks(false, false, true));
        autoLog("assistant audio guard suspended AAARP media pin");
        return true;
    }

    private void resumePinnedMediaAfterAssistantIfNeeded(boolean pinMediaToBluetooth,
                                                          String selectedDeviceKey,
                                                          String preferredBluetoothTarget) {
        if (!pinMediaToBluetooth || !audioTweakMediaRouteSuspendedForAssistant) {
            return;
        }
        audioTweakMediaRouteSuspendedForAssistant = false;
        audioTweakMediaRouteActive = true;
        long commandGeneration = nextAudioTweakCommandGeneration();
        runAudioTweakCommandAsync(commandGeneration, "aaarp-assistant-media-pin-restore", () ->
                controller.applyAndroidAutoAudioTweaks(
                        AppPrefs.NOTIFICATION_ROUTE_OFF,
                        selectedDeviceKey,
                        preferredBluetoothTarget,
                        false,
                        true
                ));
        autoLog("assistant audio guard ended; restoring AAARP media pin");
    }

    private void logAssistantProtection(ProfileSettings.MonitorSettings settings,
                                          boolean suspendedMediaPin,
                                          boolean suspendedLeveler) {
        long now = SystemClock.elapsedRealtime();
        String summary = "assistant audio guard yielded"
                + " assistantPlayback=" + assistantPlaybackActive
                + " suspendedMediaPin=" + suspendedMediaPin
                + " suspendedLeveler=" + suspendedLeveler
                + " routeActive=" + routeActiveForTarget
                + " currentRoute=" + controller.currentCommunicationDevice()
                + " profile=" + settings.profileId
                + " target=" + settings.preferredBluetoothTarget;
        boolean changed = !summary.equals(lastAssistantProtectionSummary);
        if (changed || now - lastAssistantProtectionLogAt >= AUTO_LOG_HEARTBEAT_MS) {
            lastAssistantProtectionSummary = summary;
            lastAssistantProtectionLogAt = now;
            autoLog(summary);
        }
    }

    private boolean isCallOrCommunicationActive() {
        try {
            return controller.isCallOrCommunicationActive();
        } catch (RuntimeException e) {
            autoLog("audio mode check failed; leaving routes alone: " + e.getMessage());
            return true;
        }
    }

    private void handleCallOrCommunicationProtection(ProfileSettings.MonitorSettings settings) {
        routeActiveForTarget = false;
        clearNotificationPlaybackMuteIfNeeded();
        clearMediaBoostForCallProtection();
        logCallProtection("monitor protected", settings);
    }

    private void logCallProtection(String action, ProfileSettings.MonitorSettings settings) {
        long now = SystemClock.elapsedRealtime();
        String summary = action
                + " mode=" + controller.audioModeSummary()
                + " profile=" + settings.profileId
                + " routeActive=" + routeActiveForTarget
                + " currentRoute=" + controller.currentCommunicationDevice()
                + " mediaLevelerStatus=" + mediaLevelerStatus.name().toLowerCase(java.util.Locale.US)
                + " streamMusicOwner=user"
                + " muteActive=" + notificationPlaybackMuteActive
                + " target=" + settings.preferredBluetoothTarget;
        boolean changed = !summary.equals(lastCallProtectionSummary);
        if (changed || now - lastCallProtectionLogAt >= AUTO_LOG_HEARTBEAT_MS) {
            lastCallProtectionSummary = summary;
            lastCallProtectionLogAt = now;
            autoLog(summary);
        }
    }

    private void startLocationWarmupIfNeeded(ProfileSettings.MonitorSettings settings,
                                             AndroidAutoConnection connection) {
        if (!settings.gpsWarmupDuringAndroidAuto) {
            return;
        }
        if (locationWarmup == null) {
            autoLog("gps warmup skipped: helper unavailable");
            return;
        }
        if (!LocationWarmup.hasPreciseLocationPermission(this)) {
            autoLog("gps warmup skipped: precise location permission is not granted");
            return;
        }
        if (!setForegroundLocationTypeActive(true)) {
            autoLog("gps warmup skipped: Android rejected the foreground location service type");
            return;
        }
        boolean started = locationWarmup.start(
                "android auto connected source=" + connection.label(),
                () -> setForegroundLocationTypeActive(false)
        );
        if (!started) {
            setForegroundLocationTypeActive(false);
        }
    }

    private void stopLocationWarmup(String reason) {
        if (locationWarmup != null) {
            locationWarmup.stop(reason);
        }
        setForegroundLocationTypeActive(false);
    }

    private boolean setForegroundLocationTypeActive(boolean active) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            foregroundLocationTypeActive = active;
            return true;
        }
        if (foregroundLocationTypeActive == active) {
            return true;
        }

        boolean previous = foregroundLocationTypeActive;
        foregroundLocationTypeActive = active;
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), foregroundServiceTypes(active));
            return true;
        } catch (RuntimeException e) {
            foregroundLocationTypeActive = previous;
            autoLog("foreground location service type update failed active="
                    + active
                    + " error="
                    + e.getMessage());
            return false;
        }
    }

    private void startForegroundCompat(boolean includeLocation) {
        foregroundLocationTypeActive = includeLocation;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), foregroundServiceTypes(includeLocation));
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }
    }

    private int foregroundServiceTypes(boolean includeLocation) {
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (includeLocation) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        return types;
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

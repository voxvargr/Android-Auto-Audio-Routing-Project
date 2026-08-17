package dev.voxvargr.aaarp;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import androidx.media.MediaBrowserServiceCompat;
import androidx.media.MediaSessionManager;
import androidx.media.utils.MediaConstants;

import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Non-playing Android Auto surface for one-step phone volume and the opt-in media relay. */
public final class AndroidAutoVolumeService extends MediaBrowserServiceCompat {
    private static final String ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead";
    private static final String GOOGLE_PLAY_SERVICES_PACKAGE = "com.google.android.gms";
    private static final int MAX_PENDING_VOLUME_STEPS = 8;

    private MediaSessionCompat mediaSession;
    private MusicVolumeStepper volumeStepper;
    private AudioManager audioManager;
    private RootShell rootShell;
    private ExternalMediaSessionRepository relayRepository;
    private boolean relayListenerRegistered;
    private volatile ExternalMediaSessionRepository.Snapshot relaySnapshot;
    private volatile boolean relayArmed;
    private volatile long armedRelayGeneration = -1L;
    private SharedPreferences preferences;
    private Handler mainHandler;
    private volatile AndroidAutoControlBar controlBar;
    private ExecutorService volumeExecutor;
    private volatile boolean destroyed;
    private final Set<PendingBrowseResult> pendingBrowseResults =
            Collections.synchronizedSet(new HashSet<>());

    private final ExternalMediaSessionRepository.Listener relayListener =
            this::onExternalMediaSnapshotChanged;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (sharedPreferences, key) -> {
                if (!ProfileSettings.isMediaRelayConfigurationKey(key)) {
                    return;
                }
                updateRelayRepositorySubscription();
                if (relayArmed && !relayEnabledForCurrentProfile()) {
                    deactivateRelay("profile_disabled");
                } else if (!relayArmed) {
                    publishBrowseReadyOrInactiveSession();
                }
                notifyChildrenChanged(AndroidAutoVolumeContent.ROOT_ID);
            };
    private final CurrentAndroidAutoProfile.Listener profileListener =
            profileId -> runOnMain(this::onAndroidAutoProfileChanged);

    @Override
    public void onCreate() {
        super.onCreate();
        destroyed = false;
        mainHandler = new Handler(Looper.getMainLooper());
        Handler controlBarHandler = mainHandler;
        controlBar = new AndroidAutoControlBar(
                new AndroidAutoControlBar.Scheduler() {
                    @Override
                    public void postDelayed(Runnable action, long delayMs) {
                        controlBarHandler.postDelayed(action, delayMs);
                    }

                    @Override
                    public void removeCallbacks(Runnable action) {
                        controlBarHandler.removeCallbacks(action);
                    }
                },
                this::onControlBarModeChanged
        );
        volumeExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_VOLUME_STEPS),
                runnable -> new Thread(runnable, "aaarp-aa-volume"),
                new ThreadPoolExecutor.AbortPolicy()
        );
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        rootShell = new RootShell();
        volumeStepper = new MusicVolumeStepper(audioManager, direction -> {
            RootShell.ShellResult result = rootShell.adjustMusicVolume(direction);
            if (!result.success) {
                AutoLogWriter.append(
                        this,
                        "aa_volume root_command_failed exit=" + result.exitCode
                                + " detail=" + safeLogValue(result.output)
                );
                throw new RootVolumeAdjustmentException();
            }
        });
        preferences = AppPrefs.get(this);
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);

        mediaSession = new MediaSessionCompat(
                this,
                getString(R.string.android_auto_volume_service_label)
        );
        mediaSession.setMediaButtonReceiver(null);
        mediaSession.setFlags(0);
        mediaSession.setCallback(buildMediaSessionCallback());
        publishInactiveSession();
        mediaSession.setActive(false);
        setSessionToken(mediaSession.getSessionToken());

        relayRepository = ExternalMediaSessionRepository.getInstance(this);
        relaySnapshot = relayRepository.snapshot();
        CurrentAndroidAutoProfile.addListener(profileListener);
        updateRelayRepositorySubscription();
        publishBrowseReadyOrInactiveSession();
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        if (!isAllowedClient(clientPackageName, clientUid)) {
            AutoLogWriter.append(
                    this,
                    "aa_volume browser_rejected package=" + safeLogValue(clientPackageName)
                            + " uid=" + clientUid
            );
            return null;
        }

        int actionLimit = customBrowseActionLimit(rootHints);
        AutoLogWriter.append(
                this,
                "aa_volume browser_connected package=" + safeLogValue(clientPackageName)
                        + " uid=" + clientUid
                        + " custom_action_limit=" + actionLimit
                        + " relay_profile=" + safeLogValue(currentRelayProfileId())
                        + " relay_enabled=" + relayEnabledForCurrentProfile()
        );
        Bundle rootExtras = new Bundle();
        if (AndroidAutoVolumeContent.supportsVolumeActions(actionLimit)) {
            rootExtras.putParcelableArrayList(
                    MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ROOT_LIST,
                    buildCustomBrowseActions()
            );
        }
        return new BrowserRoot(AndroidAutoVolumeContent.ROOT_ID, rootExtras);
    }

    @Override
    public void onLoadChildren(
            String parentId,
            Result<List<MediaBrowserCompat.MediaItem>> result
    ) {
        if (AndroidAutoVolumeContent.ROOT_ID.equals(parentId)) {
            AndroidAutoVolumeContent.BrowseLayout layout = currentBrowseLayout();
            ExternalMediaSessionRepository.Snapshot snapshot = relaySnapshot();
            AutoLogWriter.append(
                    this,
                    "aa_volume browse_layout layout=" + layout.name()
                            + " profile=" + safeLogValue(currentRelayProfileId())
                            + " relay_enabled=" + relayEnabledForCurrentProfile()
                            + " notification_access=" + snapshot.notificationAccessConnected()
                            + " external_session=" + snapshot.hasSession()
                            + " armed=" + relayArmed
            );
            if (!relayArmed) {
                publishBrowseReadyOrInactiveSession();
            }
            MediaBrowserCompat.MediaItem item = layout
                    == AndroidAutoVolumeContent.BrowseLayout.RELAY_ITEM
                    ? buildRelayItem()
                    : buildInfoItem(layout);
            result.sendResult(Collections.singletonList(item));
            return;
        }
        result.sendResult(Collections.emptyList());
    }

    @Override
    public void onLoadItem(String itemId, Result<MediaBrowserCompat.MediaItem> result) {
        if (AndroidAutoVolumeContent.INFO_ID.equals(itemId)) {
            result.sendResult(buildInfoItem(currentBrowseLayout()));
            return;
        }
        if (AndroidAutoVolumeContent.RELAY_ID.equals(itemId)
                && currentBrowseLayout() == AndroidAutoVolumeContent.BrowseLayout.RELAY_ITEM) {
            if (!relayArmed) {
                publishBrowseReadyOrInactiveSession();
            }
            result.sendResult(buildRelayItem());
            return;
        }
        result.sendResult(null);
    }

    @Override
    public void onCustomAction(String action, Bundle extras, Result<Bundle> result) {
        if (!volumeActionsSupported()) {
            Bundle error = resultMessage(getString(R.string.android_auto_volume_info_unsupported));
            AutoLogWriter.append(
                    this,
                    "aa_volume rejected action=" + safeLogValue(action) + " reason=host_unsupported"
            );
            result.sendError(error);
            return;
        }

        String mediaItemId = extras == null
                ? null
                : extras.getString(MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_MEDIA_ITEM_ID);
        if (mediaItemId != null && !AndroidAutoVolumeContent.isExpectedMediaItemId(mediaItemId)) {
            Bundle error = resultMessage(
                    getString(R.string.android_auto_volume_unrecognized_action)
            );
            AutoLogWriter.append(
                    this,
                    "aa_volume rejected action=" + safeLogValue(action)
                            + " reason=unexpected_media_id"
            );
            result.sendError(error);
            return;
        }

        PendingBrowseResult pendingResult = new PendingBrowseResult(result);
        pendingBrowseResults.add(pendingResult);
        result.detach();
        if (!submitVolumeStep(
                action,
                "browse",
                -1L,
                -1L,
                pendingResult::complete,
                pendingResult::fail
        )) {
            AutoLogWriter.append(this, "aa_volume rejected action=" + safeLogValue(action)
                    + " reason=worker_unavailable");
            pendingResult.fail();
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        cancelPendingBrowseResults();
        ExecutorService executor = volumeExecutor;
        volumeExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        CurrentAndroidAutoProfile.removeListener(profileListener);
        if (preferences != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
            preferences = null;
        }
        if (relayRepository != null && relayListenerRegistered) {
            relayRepository.removeListener(relayListener);
            relayListenerRegistered = false;
            relaySnapshot = relayRepository.snapshot();
        }
        relayRepository = null;
        deactivateRelay("service_destroyed");
        AndroidAutoControlBar bar = controlBar;
        controlBar = null;
        if (bar != null) {
            bar.close();
        }
        if (mediaSession != null) {
            mediaSession.setCallback(null);
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        mainHandler = null;
        super.onDestroy();
    }

    private MediaSessionCompat.Callback buildMediaSessionCallback() {
        return new MediaSessionCompat.Callback() {
            @Override
            public void onPlayFromMediaId(String mediaId, Bundle extras) {
                if (!AndroidAutoVolumeContent.RELAY_ID.equals(mediaId)) {
                    logRejectedRelayCommand("play_from_media_id", "unexpected_media_id");
                    return;
                }
                armRelay("play_from_media_id");
            }

            @Override
            public void onPlay() {
                // Projected Android Auto 17.3 opens our playable row but sends generic Play
                // instead of PlayFromMediaId. Treat that explicit command as the arm request,
                // then forward the same user intent exactly once to the mirrored player.
                if (!relayArmed && !armRelay("play_fallback")) {
                    return;
                }
                if (forwardRelayCommand(AndroidAutoVolumeContent.RelayCommand.PLAY, "play")) {
                    resetControlBar("play");
                }
            }

            @Override
            public void onPause() {
                if (forwardRelayCommand(AndroidAutoVolumeContent.RelayCommand.PAUSE, "pause")) {
                    resetControlBar("pause");
                }
            }

            @Override
            public void onStop() {
                if (forwardRelayCommand(AndroidAutoVolumeContent.RelayCommand.STOP, "stop")) {
                    deactivateRelay("stop_command");
                }
            }

            @Override
            public void onSkipToNext() {
                if (forwardRelayCommand(
                        AndroidAutoVolumeContent.RelayCommand.NEXT,
                        "next",
                        AndroidAutoVolumeContent.ControlBarMode.TRACK
                )) {
                    touchControlBar(AndroidAutoVolumeContent.ControlBarMode.TRACK);
                }
            }

            @Override
            public void onSkipToPrevious() {
                if (forwardRelayCommand(
                        AndroidAutoVolumeContent.RelayCommand.PREVIOUS,
                        "previous",
                        AndroidAutoVolumeContent.ControlBarMode.TRACK
                )) {
                    touchControlBar(AndroidAutoVolumeContent.ControlBarMode.TRACK);
                }
            }

            @Override
            public void onSeekTo(long position) {
                if (position < 0L) {
                    logRejectedRelayCommand("seek", "invalid_position");
                    return;
                }
                if (forwardRelayCommand(
                        AndroidAutoVolumeContent.RelayCommand.SEEK,
                        "seek",
                        position,
                        null
                )) {
                    resetControlBar("seek");
                }
            }

            @Override
            public void onCustomAction(String action, Bundle extras) {
                if (!relayCommandAllowed("custom_action")) {
                    return;
                }
                AndroidAutoVolumeContent.ControlBarMode requestedMode =
                        AndroidAutoVolumeContent.modeForNavigationAction(action);
                if (requestedMode != null) {
                    AndroidAutoControlBar bar = controlBar;
                    if (bar == null
                            || bar.mode() != AndroidAutoVolumeContent.ControlBarMode.HUB) {
                        logRejectedRelayCommand("custom_action", "stale_control_layout");
                        return;
                    }
                    if (requestedMode == AndroidAutoVolumeContent.ControlBarMode.TRACK
                            && !AndroidAutoVolumeContent.supportsTrackControls(
                            relaySnapshot().supportedActions())) {
                        logRejectedRelayCommand("custom_action", "track_actions_unsupported");
                        return;
                    }
                    bar.select(requestedMode, "action_" + safeLogValue(action));
                    return;
                }
                int direction = AndroidAutoVolumeContent.directionForAction(action);
                if (direction == AudioManager.ADJUST_SAME) {
                    logRejectedRelayCommand("custom_action", "unknown_action");
                    return;
                }
                AndroidAutoControlBar bar = controlBar;
                if (bar == null
                        || bar.mode() != AndroidAutoVolumeContent.ControlBarMode.VOLUME) {
                    logRejectedRelayCommand("custom_action", "stale_control_layout");
                    return;
                }
                bar.touch(AndroidAutoVolumeContent.ControlBarMode.VOLUME);
                long requestGeneration = armedRelayGeneration;
                long requestControlRevision = bar.revision();
                if (!submitVolumeStep(
                        action,
                        "relay",
                        requestGeneration,
                        requestControlRevision,
                        adjustment -> {
                            if (relayVolumeRequestStillAllowed(
                                    requestGeneration,
                                    requestControlRevision)) {
                                publishRelaySnapshot(relaySnapshot());
                            }
                        },
                        () -> logRejectedRelayCommand("custom_action", "worker_failed")
                )) {
                    logRejectedRelayCommand("custom_action", "worker_unavailable");
                }
            }

            @Override
            public void onPlayFromSearch(String query, Bundle extras) {
                // The private relay mirrors an already-selected player and never starts search results.
                AutoLogWriter.append(
                        AndroidAutoVolumeService.this,
                        "aa_relay ignored voice_play_search"
                );
            }
        };
    }

    private boolean armRelay(String origin) {
        MediaSessionManager.RemoteUserInfo controller = currentControllerInfo();
        if (controller == null
                || !isAllowedClient(controller.getPackageName(), controller.getUid())) {
            logRejectedRelayCommand(origin, "untrusted_controller");
            return false;
        }
        ExternalMediaSessionRepository.Snapshot snapshot = relaySnapshot();
        if (AndroidAutoVolumeContent.isDuplicateValidArm(
                relayArmed,
                armedRelayGeneration,
                snapshot.generation(),
                relayEnabledForCurrentProfile(),
                snapshot.notificationAccessConnected(),
                snapshot.hasSession(),
                snapshot.playbackState()
        )) {
            AutoLogWriter.append(
                    this,
                    "aa_relay arm_ignored reason=already_armed"
                            + " generation=" + snapshot.generation()
                            + " origin=" + safeLogValue(origin)
                            + " controller=" + safeLogValue(controller.getPackageName())
                            + "/" + controller.getUid()
            );
            return true;
        }
        if (!relayEnabledForCurrentProfile()) {
            logRejectedRelayCommand(origin, "profile_disabled");
            deactivateRelay("profile_disabled");
            return false;
        }
        if (!relayPlatformSupported()) {
            logRejectedRelayCommand(origin, "platform_identity_unavailable");
            deactivateRelay("platform_identity_unavailable");
            return false;
        }
        if (!snapshot.notificationAccessConnected()) {
            logRejectedRelayCommand(origin, "notification_access_unavailable");
            deactivateRelay("notification_access_unavailable");
            return false;
        }
        if (!snapshot.hasSession()
                || !AndroidAutoVolumeContent.shouldRemainArmed(
                true,
                true,
                true,
                snapshot.playbackState())) {
            logRejectedRelayCommand(origin, "no_eligible_external_session");
            deactivateRelay("no_eligible_external_session");
            return false;
        }

        resetControlBar("relay_armed");
        relayArmed = true;
        armedRelayGeneration = snapshot.generation();
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        publishRelaySnapshot(snapshot);
        mediaSession.setActive(true);
        notifyChildrenChanged(AndroidAutoVolumeContent.ROOT_ID);
        AutoLogWriter.append(
                this,
                "aa_relay armed profile=" + safeLogValue(currentRelayProfileId())
                        + " source_package=" + safeLogValue(snapshot.packageName())
                        + " generation=" + snapshot.generation()
                        + " origin=" + safeLogValue(origin)
                        + " controller=" + safeLogValue(controller.getPackageName())
                        + "/" + controller.getUid()
        );
        return true;
    }

    private void deactivateRelay(String reason) {
        boolean wasArmed = relayArmed;
        relayArmed = false;
        armedRelayGeneration = -1L;
        resetControlBar("relay_deactivated");
        if (mediaSession != null) {
            mediaSession.setActive(false);
            publishBrowseReadyOrInactiveSession();
        }
        if (wasArmed) {
            AutoLogWriter.append(
                    this,
                    "aa_relay deactivated reason=" + safeLogValue(reason)
            );
        }
    }

    private void onControlBarModeChanged(
            AndroidAutoVolumeContent.ControlBarMode mode,
            long revision,
            String reason
    ) {
        AutoLogWriter.append(
                this,
                "aa_relay controls mode=" + mode.name()
                        + " revision=" + revision
                        + " reason=" + safeLogValue(reason)
        );
        if (relayArmed) {
            publishRelaySnapshot(relaySnapshot());
        }
    }

    private void resetControlBar(String reason) {
        AndroidAutoControlBar bar = controlBar;
        if (bar != null) {
            bar.reset(reason);
        }
    }

    private void touchControlBar(AndroidAutoVolumeContent.ControlBarMode mode) {
        AndroidAutoControlBar bar = controlBar;
        if (bar != null) {
            bar.touch(mode);
        }
    }

    private void onExternalMediaSnapshotChanged(
            ExternalMediaSessionRepository.Snapshot snapshot
    ) {
        relaySnapshot = snapshot;
        if (relayArmed) {
            if (snapshot.generation() != armedRelayGeneration) {
                deactivateRelay("source_changed");
                notifyChildrenChanged(AndroidAutoVolumeContent.ROOT_ID);
                return;
            }
            boolean remainArmed = AndroidAutoVolumeContent.shouldRemainArmed(
                    relayEnabledForCurrentProfile(),
                    snapshot.notificationAccessConnected(),
                    snapshot.hasSession(),
                    snapshot.playbackState()
            );
            if (remainArmed) {
                publishRelaySnapshot(snapshot);
            } else {
                deactivateRelay("relay_gate_lost");
            }
        } else {
            publishBrowseReadyOrInactiveSession();
        }
        notifyChildrenChanged(AndroidAutoVolumeContent.ROOT_ID);
    }

    private void onAndroidAutoProfileChanged() {
        updateRelayRepositorySubscription();
        if (relayArmed && !relayEnabledForCurrentProfile()) {
            deactivateRelay("profile_changed");
        } else if (!relayArmed) {
            publishBrowseReadyOrInactiveSession();
        }
        notifyChildrenChanged(AndroidAutoVolumeContent.ROOT_ID);
    }

    private void updateRelayRepositorySubscription() {
        ExternalMediaSessionRepository repository = relayRepository;
        if (repository == null) {
            return;
        }
        boolean shouldObserve = relayEnabledForCurrentProfile();
        if (shouldObserve && !relayListenerRegistered) {
            relayListenerRegistered = true;
            repository.addListener(relayListener);
        } else if (!shouldObserve && relayListenerRegistered) {
            relayListenerRegistered = false;
            repository.removeListener(relayListener);
            relaySnapshot = repository.snapshot();
        }
    }

    private void publishRelaySnapshot(ExternalMediaSessionRepository.Snapshot snapshot) {
        if (mediaSession == null || snapshot == null || !snapshot.hasSession()) {
            return;
        }
        AndroidAutoControlBar bar = controlBar;
        AndroidAutoVolumeContent.ControlBarMode mode = bar == null
                ? AndroidAutoVolumeContent.ControlBarMode.HUB
                : bar.mode();
        if (mode == AndroidAutoVolumeContent.ControlBarMode.TRACK
                && !AndroidAutoVolumeContent.supportsTrackControls(
                snapshot.supportedActions())) {
            resetControlBar("track_actions_unavailable");
            return;
        }

        String title = nonEmpty(
                snapshot.title(),
                getString(R.string.android_auto_relay_default_title)
        );
        MediaMetadataCompat.Builder metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, AndroidAutoVolumeContent.RELAY_ID)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, snapshot.artist())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, snapshot.artist())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, snapshot.album());
        if (snapshot.durationMs() >= 0L) {
            metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, snapshot.durationMs());
        }
        Bitmap artwork = snapshot.artwork();
        if (artwork != null && !artwork.isRecycled()) {
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork);
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, artwork);
        }
        mediaSession.setMetadata(metadata.build());

        AndroidAutoVolumeContent.ControlUiSpec controlUi =
                AndroidAutoVolumeContent.controlUiSpec(
                        mode,
                        snapshot.supportedActions(),
                        snapshot.playbackState()
                );
        PlaybackStateCompat.Builder state = new PlaybackStateCompat.Builder()
                .setActions(controlUi.standardActions())
                .setState(
                        compatPlaybackState(snapshot.playbackState()),
                        snapshot.positionMs() < 0L
                                ? PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
                                : snapshot.positionMs(),
                        snapshot.playbackSpeed(),
                        snapshot.positionUpdateTimeMs() > 0L
                                ? snapshot.positionUpdateTimeMs()
                                : SystemClock.elapsedRealtime()
                );
        for (String action : controlUi.customActionIds()) {
            state.addCustomAction(playbackControlAction(action));
        }
        mediaSession.setPlaybackState(state.build());
    }

    private PlaybackStateCompat.CustomAction playbackControlAction(String action) {
        int labelResource;
        int iconResource;
        if (AndroidAutoVolumeContent.ACTION_SHOW_VOLUME_CONTROLS.equals(action)) {
            labelResource = R.string.android_auto_show_volume_controls;
            iconResource = R.drawable.ic_android_auto_volume_controls;
        } else if (AndroidAutoVolumeContent.ACTION_SHOW_TRACK_CONTROLS.equals(action)) {
            labelResource = R.string.android_auto_show_track_controls;
            iconResource = R.drawable.ic_android_auto_track_controls;
        } else if (AndroidAutoVolumeContent.ACTION_VOLUME_DOWN.equals(action)) {
            labelResource = R.string.android_auto_volume_down;
            iconResource = R.drawable.ic_android_auto_volume_down;
        } else if (AndroidAutoVolumeContent.ACTION_VOLUME_UP.equals(action)) {
            labelResource = R.string.android_auto_volume_up;
            iconResource = R.drawable.ic_android_auto_volume_up;
        } else {
            throw new IllegalArgumentException("Unknown playback control action");
        }
        Bundle extras = new Bundle();
        int semanticIcon = AndroidAutoVolumeContent.semanticIconForPlaybackAction(action);
        if (semanticIcon != 0) {
            extras.putInt(
                    AndroidAutoVolumeContent.COMMAND_BUTTON_ICON_COMPAT_EXTRA,
                    semanticIcon
            );
        }
        return new PlaybackStateCompat.CustomAction.Builder(
                action,
                getString(labelResource),
                iconResource
        ).setExtras(extras).build();
    }

    private void publishInactiveSession() {
        if (mediaSession == null) {
            return;
        }
        mediaSession.setFlags(0);
        mediaSession.setMetadata(null);
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(0L)
                .setState(
                        PlaybackStateCompat.STATE_NONE,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                        0f
                )
                .build());
    }

    private void publishBrowseReadyOrInactiveSession() {
        if (mediaSession == null || relayArmed) {
            return;
        }
        ExternalMediaSessionRepository.Snapshot snapshot = relaySnapshot();
        boolean ready = relayPlatformSupported()
                && relayEnabledForCurrentProfile()
                && snapshot.notificationAccessConnected()
                && snapshot.hasSession()
                && AndroidAutoVolumeContent.shouldRemainArmed(
                true,
                true,
                true,
                snapshot.playbackState()
        );
        if (!ready) {
            publishInactiveSession();
            mediaSession.setActive(false);
            return;
        }

        String title = nonEmpty(
                snapshot.title(),
                getString(R.string.android_auto_relay_default_title)
        );
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, AndroidAutoVolumeContent.RELAY_ID)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, snapshot.artist())
                .build());
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(AndroidAutoVolumeContent.preArmTransportActions())
                .setState(
                        PlaybackStateCompat.STATE_STOPPED,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                        0f
                )
                .build());
        mediaSession.setActive(false);
    }

    private boolean forwardRelayCommand(
            AndroidAutoVolumeContent.RelayCommand command,
            String logName
    ) {
        return forwardRelayCommand(command, logName, 0L, null);
    }

    private boolean forwardRelayCommand(
            AndroidAutoVolumeContent.RelayCommand command,
            String logName,
            AndroidAutoVolumeContent.ControlBarMode requiredMode
    ) {
        return forwardRelayCommand(command, logName, 0L, requiredMode);
    }

    private boolean forwardRelayCommand(
            AndroidAutoVolumeContent.RelayCommand command,
            String logName,
            long positionMs,
            AndroidAutoVolumeContent.ControlBarMode requiredMode
    ) {
        if (!relayCommandAllowed(logName)) {
            return false;
        }
        if (requiredMode != null) {
            AndroidAutoControlBar bar = controlBar;
            if (bar == null || bar.mode() != requiredMode) {
                logRejectedRelayCommand(logName, "stale_control_layout");
                return false;
            }
        }
        ExternalMediaSessionRepository.Snapshot snapshot = relaySnapshot();
        if (!AndroidAutoVolumeContent.supportsRelayCommand(
                snapshot.supportedActions(),
                command)) {
            logRejectedRelayCommand(logName, "external_action_unsupported");
            return false;
        }

        boolean forwarded;
        switch (command) {
            case PLAY:
                forwarded = relayRepository.play();
                break;
            case PAUSE:
                forwarded = relayRepository.pause();
                break;
            case STOP:
                forwarded = relayRepository.stop();
                break;
            case NEXT:
                forwarded = relayRepository.skipToNext();
                break;
            case PREVIOUS:
                forwarded = relayRepository.skipToPrevious();
                break;
            case SEEK:
                forwarded = relayRepository.seekTo(positionMs);
                break;
            default:
                forwarded = false;
        }
        AutoLogWriter.append(
                this,
                "aa_relay command=" + safeLogValue(logName)
                        + " forwarded=" + forwarded
                        + " source_package=" + safeLogValue(snapshot.packageName())
        );
        return forwarded;
    }

    private boolean relayCommandAllowed(String commandName) {
        if (!isAllowedCurrentController()) {
            logRejectedRelayCommand(commandName, "untrusted_controller");
            return false;
        }
        ExternalMediaSessionRepository.Snapshot snapshot = relaySnapshot();
        if (!relayArmed
                || snapshot.generation() != armedRelayGeneration
                || !AndroidAutoVolumeContent.shouldRemainArmed(
                relayEnabledForCurrentProfile(),
                snapshot.notificationAccessConnected(),
                snapshot.hasSession(),
                snapshot.playbackState())) {
            logRejectedRelayCommand(commandName, "relay_not_armed");
            deactivateRelay("relay_gate_lost");
            return false;
        }
        return true;
    }

    private void logRejectedRelayCommand(String command, String reason) {
        MediaSessionManager.RemoteUserInfo controller = currentControllerInfo();
        AutoLogWriter.append(
                this,
                "aa_relay rejected command=" + safeLogValue(command)
                        + " reason=" + safeLogValue(reason)
                        + " controller=" + (controller == null
                        ? "unknown"
                        : safeLogValue(controller.getPackageName()) + "/" + controller.getUid())
        );
    }

    private boolean isAllowedCurrentController() {
        MediaSessionManager.RemoteUserInfo controller = currentControllerInfo();
        if (controller == null) {
            return false;
        }
        return isAllowedClient(controller.getPackageName(), controller.getUid());
    }

    private MediaSessionManager.RemoteUserInfo currentControllerInfo() {
        if (mediaSession == null) {
            return null;
        }
        try {
            return mediaSession.getCurrentControllerInfo();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean isAllowedClient(String packageName, int uid) {
        if (packageName == null || !packageBelongsToUid(packageName, uid)) {
            return false;
        }
        return uid == Process.SYSTEM_UID
                || packageName.equals(getPackageName())
                || ANDROID_AUTO_PACKAGE.equals(packageName)
                || GOOGLE_PLAY_SERVICES_PACKAGE.equals(packageName);
    }

    private boolean packageBelongsToUid(String packageName, int uid) {
        String[] uidPackages = getPackageManager().getPackagesForUid(uid);
        if (uidPackages == null) {
            return false;
        }
        for (String uidPackage : uidPackages) {
            if (packageName.equals(uidPackage)) {
                return true;
            }
        }
        return false;
    }

    private ArrayList<Bundle> buildCustomBrowseActions() {
        ArrayList<Bundle> actions = new ArrayList<>(2);
        actions.add(customBrowseAction(
                AndroidAutoVolumeContent.ACTION_VOLUME_DOWN,
                R.string.android_auto_volume_down,
                R.drawable.ic_android_auto_volume_down
        ));
        actions.add(customBrowseAction(
                AndroidAutoVolumeContent.ACTION_VOLUME_UP,
                R.string.android_auto_volume_up,
                R.drawable.ic_android_auto_volume_up
        ));
        return actions;
    }

    private Bundle customBrowseAction(String id, int labelResource, int iconResource) {
        Bundle action = new Bundle();
        action.putString(MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ID, id);
        action.putString(
                MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_LABEL,
                getString(labelResource)
        );
        action.putString(
                MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ICON_URI,
                resourceUri(iconResource).toString()
        );
        return action;
    }

    private MediaBrowserCompat.MediaItem buildInfoItem(
            AndroidAutoVolumeContent.BrowseLayout layout
    ) {
        boolean actionsSupported = layout
                == AndroidAutoVolumeContent.BrowseLayout.CUSTOM_BROWSE_ACTIONS;
        Bundle descriptionExtras = new Bundle();
        if (actionsSupported) {
            descriptionExtras.putStringArrayList(
                    MediaConstants.DESCRIPTION_EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ID_LIST,
                    new ArrayList<>(AndroidAutoVolumeContent.actionIdsForLimit(2))
            );
        }

        int titleResource = 0;
        int subtitleResource;
        ExternalMediaSessionRepository.Snapshot snapshot = relaySnapshot();
        if (actionsSupported) {
            subtitleResource = R.string.android_auto_volume_info_supported;
        } else if (!relayEnabledForCurrentProfile()) {
            titleResource = R.string.android_auto_relay_disabled_title;
            subtitleResource = R.string.android_auto_relay_disabled_subtitle;
        } else if (!snapshot.notificationAccessConnected()) {
            titleResource = R.string.android_auto_relay_access_required_title;
            subtitleResource = R.string.android_auto_relay_access_required_subtitle;
        } else if (!snapshot.hasSession()) {
            titleResource = R.string.android_auto_relay_no_media_title;
            subtitleResource = R.string.android_auto_relay_no_media_subtitle;
        } else {
            subtitleResource = R.string.android_auto_volume_info_unsupported;
        }

        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(AndroidAutoVolumeContent.INFO_ID)
                .setTitle(titleResource == 0 ? currentVolumeText() : getString(titleResource))
                .setSubtitle(getString(subtitleResource))
                .setIconUri(resourceUri(R.drawable.ic_android_auto_attribution))
                .setExtras(descriptionExtras)
                .build();
        return new MediaBrowserCompat.MediaItem(
                description,
                AndroidAutoVolumeContent.INFO_ITEM_FLAGS
        );
    }

    private MediaBrowserCompat.MediaItem buildRelayItem() {
        ExternalMediaSessionRepository.Snapshot snapshot = relaySnapshot();
        MediaDescriptionCompat.Builder description = new MediaDescriptionCompat.Builder()
                .setMediaId(AndroidAutoVolumeContent.RELAY_ID)
                .setTitle(getString(R.string.android_auto_relay_item_title))
                .setSubtitle(nonEmpty(
                        snapshot.title(),
                        getString(R.string.android_auto_relay_item_subtitle)
                ));
        Bitmap artwork = snapshot.artwork();
        if (artwork != null && !artwork.isRecycled()) {
            description.setIconBitmap(artwork);
        } else {
            description.setIconUri(resourceUri(R.drawable.ic_android_auto_attribution));
        }
        return new MediaBrowserCompat.MediaItem(
                description.build(),
                AndroidAutoVolumeContent.RELAY_ITEM_FLAGS
        );
    }

    private AndroidAutoVolumeContent.BrowseLayout currentBrowseLayout() {
        ExternalMediaSessionRepository.Snapshot snapshot = relaySnapshot();
        return AndroidAutoVolumeContent.browseLayout(
                customBrowseActionLimit(currentRootHints()),
                relayPlatformSupported() && relayEnabledForCurrentProfile(),
                snapshot.notificationAccessConnected(),
                snapshot.hasSession()
        );
    }

    private boolean volumeActionsSupported() {
        return AndroidAutoVolumeContent.supportsVolumeActions(
                customBrowseActionLimit(currentRootHints())
        );
    }

    private Bundle currentRootHints() {
        try {
            return getBrowserRootHints();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private static int customBrowseActionLimit(Bundle rootHints) {
        return rootHints == null
                ? 0
                : rootHints.getInt(
                        MediaConstants.BROWSER_ROOT_HINTS_KEY_CUSTOM_BROWSER_ACTION_LIMIT,
                        0
                );
    }

    private ExternalMediaSessionRepository.Snapshot relaySnapshot() {
        ExternalMediaSessionRepository.Snapshot snapshot = relaySnapshot;
        return snapshot == null
                ? ExternalMediaSessionRepository.getInstance(this).snapshot()
                : snapshot;
    }

    private String currentRelayProfileId() {
        return CurrentAndroidAutoProfile.detectedOrDefaultProfileId();
    }

    private boolean relayEnabledForCurrentProfile() {
        return ProfileSettings.mediaRelayEnabledForProfile(this, currentRelayProfileId());
    }

    private static boolean relayPlatformSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    private MusicVolumeStepper.Result stepVolume(
            String action,
            String source,
            boolean useRoot
    ) {
        int direction = AndroidAutoVolumeContent.directionForAction(action);
        MusicVolumeStepper.Result adjustment = volumeStepper.step(direction, useRoot);
        if (adjustment.shouldProtectManualIntent()) {
            ManualMediaVolumeTracker.markSuccessfulStep();
        }
        AutoLogWriter.append(
                this,
                "aa_volume source=android_auto_" + safeLogValue(source)
                        + " action=" + AndroidAutoVolumeContent.actionName(direction)
                        + " " + adjustment.logFields()
        );
        return adjustment;
    }

    private boolean submitVolumeStep(
            String action,
            String source,
            long requiredRelayGeneration,
            long requiredControlRevision,
            VolumeStepResultHandler resultHandler,
            Runnable failureHandler
    ) {
        ExecutorService executor = volumeExecutor;
        if (destroyed || executor == null) {
            return false;
        }
        boolean useRoot = rootVolumeAdjustmentEnabled();
        try {
            executor.execute(() -> {
                try {
                    if (destroyed) {
                        return;
                    }
                    if (requiredRelayGeneration >= 0L
                            && !relayVolumeRequestStillAllowed(
                            requiredRelayGeneration,
                            requiredControlRevision)) {
                        AutoLogWriter.append(
                                this,
                                "aa_volume rejected action=" + safeLogValue(action)
                                        + " reason=stale_relay_request"
                        );
                        return;
                    }
                    MusicVolumeStepper.Result adjustment = stepVolume(action, source, useRoot);
                    runOnMain(() -> resultHandler.onResult(adjustment));
                } catch (RuntimeException error) {
                    AutoLogWriter.append(
                            this,
                            "aa_volume worker_failed action=" + safeLogValue(action)
                                    + " error=" + error.getClass().getSimpleName()
                    );
                    runOnMain(failureHandler);
                }
            });
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private boolean relayVolumeRequestStillAllowed(
            long requestGeneration,
            long requiredControlRevision
    ) {
        ExternalMediaSessionRepository.Snapshot snapshot = relaySnapshot();
        AndroidAutoControlBar bar = controlBar;
        return !destroyed
                && relayArmed
                && armedRelayGeneration == requestGeneration
                && snapshot.generation() == requestGeneration
                && bar != null
                && bar.isCurrent(
                AndroidAutoVolumeContent.ControlBarMode.VOLUME,
                requiredControlRevision)
                && AndroidAutoVolumeContent.shouldRemainArmed(
                relayEnabledForCurrentProfile(),
                snapshot.notificationAccessConnected(),
                snapshot.hasSession(),
                snapshot.playbackState()
        );
    }

    private boolean rootVolumeAdjustmentEnabled() {
        try {
            return preferences != null && preferences.getBoolean(AppPrefs.USE_ROOT, false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static final class RootVolumeAdjustmentException extends RuntimeException {
    }

    private interface VolumeStepResultHandler {
        void onResult(MusicVolumeStepper.Result result);
    }

    private final class PendingBrowseResult {
        private final Result<Bundle> result;
        private final AtomicBoolean completed = new AtomicBoolean();

        private PendingBrowseResult(Result<Bundle> result) {
            this.result = result;
        }

        private void complete(MusicVolumeStepper.Result adjustment) {
            if (!finishOnce()) {
                return;
            }
            boolean terminalCallStarted = false;
            try {
                Bundle response = resultMessage(messageFor(adjustment));
                if (adjustment.shouldProtectManualIntent()) {
                    notifyChildrenChanged(AndroidAutoVolumeContent.ROOT_ID);
                }
                terminalCallStarted = true;
                if (adjustment.outcome() == MusicVolumeStepper.Outcome.ERROR
                        || adjustment.outcome() == MusicVolumeStepper.Outcome.INVALID_ACTION) {
                    result.sendError(response);
                } else {
                    result.sendResult(response);
                }
            } catch (RuntimeException error) {
                AutoLogWriter.append(
                        AndroidAutoVolumeService.this,
                        "aa_volume browse_result_failed error="
                                + error.getClass().getSimpleName()
                );
                if (!terminalCallStarted) {
                    try {
                        result.sendError(resultMessage(
                                getString(R.string.android_auto_volume_error)
                        ));
                    } catch (RuntimeException ignored) {
                        // The browser connection may have closed while the detached work ran.
                    }
                }
            }
        }

        private void fail() {
            if (!finishOnce()) {
                return;
            }
            try {
                result.sendError(resultMessage(getString(R.string.android_auto_volume_error)));
            } catch (RuntimeException error) {
                AutoLogWriter.append(
                        AndroidAutoVolumeService.this,
                        "aa_volume browse_result_failed error="
                                + error.getClass().getSimpleName()
                );
            }
        }

        private boolean finishOnce() {
            if (!completed.compareAndSet(false, true)) {
                return false;
            }
            pendingBrowseResults.remove(this);
            return true;
        }
    }

    private void cancelPendingBrowseResults() {
        List<PendingBrowseResult> pending;
        synchronized (pendingBrowseResults) {
            pending = new ArrayList<>(pendingBrowseResults);
        }
        for (PendingBrowseResult result : pending) {
            result.fail();
        }
    }

    private Bundle resultMessage(String message) {
        Bundle result = new Bundle();
        result.putString(MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_RESULT_MESSAGE, message);
        return result;
    }

    private String messageFor(MusicVolumeStepper.Result adjustment) {
        switch (adjustment.outcome()) {
            case CHANGED:
                return getString(
                        R.string.android_auto_volume_changed,
                        adjustment.after(),
                        adjustment.max()
                );
            case AT_MINIMUM:
                return getString(R.string.android_auto_volume_at_minimum);
            case AT_MAXIMUM:
                return getString(R.string.android_auto_volume_at_maximum);
            case FIXED_VOLUME:
                return getString(R.string.android_auto_volume_fixed);
            case NO_EFFECT:
                return getString(Build.VERSION.SDK_INT >= 37
                        ? R.string.android_auto_volume_no_effect_android17
                        : R.string.android_auto_volume_no_effect);
            case INVALID_ACTION:
                return getString(R.string.android_auto_volume_unrecognized_action);
            case ERROR:
            default:
                return getString(R.string.android_auto_volume_error);
        }
    }

    private String currentVolumeText() {
        try {
            return getString(
                    R.string.android_auto_volume_changed,
                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            );
        } catch (RuntimeException ignored) {
            return getString(R.string.android_auto_volume_info_title);
        }
    }

    private Uri resourceUri(int resourceId) {
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(getPackageName())
                .appendPath(getResources().getResourceTypeName(resourceId))
                .appendPath(getResources().getResourceEntryName(resourceId))
                .build();
    }

    private static int compatPlaybackState(int state) {
        switch (state) {
            case PlaybackStateCompat.STATE_NONE:
            case PlaybackStateCompat.STATE_STOPPED:
            case PlaybackStateCompat.STATE_PAUSED:
            case PlaybackStateCompat.STATE_PLAYING:
            case PlaybackStateCompat.STATE_FAST_FORWARDING:
            case PlaybackStateCompat.STATE_REWINDING:
            case PlaybackStateCompat.STATE_BUFFERING:
            case PlaybackStateCompat.STATE_ERROR:
            case PlaybackStateCompat.STATE_CONNECTING:
            case PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackStateCompat.STATE_SKIPPING_TO_NEXT:
            case PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM:
                return state;
            default:
                return PlaybackStateCompat.STATE_NONE;
        }
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.length() == 0 ? fallback : value;
    }

    private static String safeLogValue(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private void runOnMain(Runnable action) {
        Handler handler = mainHandler;
        if (destroyed || handler == null) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            handler.post(() -> {
                if (!destroyed) {
                    action.run();
                }
            });
        }
    }
}

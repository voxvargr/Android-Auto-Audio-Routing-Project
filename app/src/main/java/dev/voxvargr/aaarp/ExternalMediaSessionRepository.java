package dev.voxvargr.aaarp;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local, notification-access-backed view of the current external media session.
 *
 * <p>The repository reads only platform media-session state. It does not receive, inspect, log, or
 * retain notification contents.</p>
 */
public final class ExternalMediaSessionRepository {
    public static final long UNKNOWN_TIME_MS = -1L;

    private static final int MAX_TEXT_LENGTH = 512;
    private static final int MAX_ARTWORK_EDGE_PX = 256;
    private static final long STANDARD_ACTION_MASK = PlaybackState.ACTION_PLAY
            | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE
            | PlaybackState.ACTION_STOP
            | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS
            | PlaybackState.ACTION_SEEK_TO;

    private static volatile ExternalMediaSessionRepository instance;

    private final Context appContext;
    private final MediaSessionManager mediaSessionManager;
    private final Handler mainHandler;
    private final AtomicLong nextConnectionId = new AtomicLong();
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();

    private volatile Snapshot currentSnapshot = Snapshot.withoutSession(false, 0L);
    private volatile SelectedTarget selectedTarget;

    // Main-thread-only lifecycle and selection state.
    private long currentConnectionId;
    private long selectionGeneration;
    private ComponentName credentialComponent;
    private boolean activeSessionsListenerRegistered;
    private boolean systemObservationActive;
    private MediaSessionManager.OnActiveSessionsChangedListener activeSessionsListener;
    private Object mediaKeyEventListener;
    private MediaSession.Token stickyToken;
    private MediaSession.Token destroyedToken;

    public static ExternalMediaSessionRepository getInstance(Context context) {
        Objects.requireNonNull(context, "context");
        ExternalMediaSessionRepository repository = instance;
        if (repository != null) {
            return repository;
        }
        synchronized (ExternalMediaSessionRepository.class) {
            repository = instance;
            if (repository == null) {
                Context applicationContext = context.getApplicationContext();
                repository = new ExternalMediaSessionRepository(
                        applicationContext == null ? context : applicationContext
                );
                instance = repository;
            }
            return repository;
        }
    }

    private ExternalMediaSessionRepository(Context context) {
        appContext = context;
        mediaSessionManager = (MediaSessionManager) context.getSystemService(
                Context.MEDIA_SESSION_SERVICE
        );
        mainHandler = new Handler(Looper.getMainLooper());
    }

    /** Registers a listener and delivers the current immutable snapshot on the main thread. */
    public void addListener(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        runOnMain(() -> {
            boolean added = listeners.add(listener);
            if (!added) {
                notifyListener(listener, currentSnapshot);
                return;
            }
            if (listeners.size() == 1 && startSystemObservationIfNeeded()) {
                // refreshFromSystemOnMain() published the first demanded snapshot.
                return;
            }
            notifyListener(listener, currentSnapshot);
        });
    }

    public void removeListener(Listener listener) {
        if (listener == null) {
            return;
        }
        runOnMain(() -> {
            if (listeners.remove(listener) && listeners.isEmpty()) {
                stopSystemObservationWithoutDemand();
            }
        });
    }

    public boolean isNotificationAccessConnected() {
        return currentSnapshot.notificationAccessConnected();
    }

    public Snapshot snapshot() {
        return currentSnapshot;
    }

    public boolean play() {
        return forward(
                PlaybackState.ACTION_PLAY,
                PlaybackState.ACTION_PLAY_PAUSE,
                TransportOperation.PLAY,
                0L
        );
    }

    public boolean pause() {
        return forward(
                PlaybackState.ACTION_PAUSE,
                PlaybackState.ACTION_PLAY_PAUSE,
                TransportOperation.PAUSE,
                0L
        );
    }

    public boolean stop() {
        return forward(
                PlaybackState.ACTION_STOP,
                0L,
                TransportOperation.STOP,
                0L
        );
    }

    public boolean skipToNext() {
        return forward(
                PlaybackState.ACTION_SKIP_TO_NEXT,
                0L,
                TransportOperation.NEXT,
                0L
        );
    }

    public boolean skipToPrevious() {
        return forward(
                PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                0L,
                TransportOperation.PREVIOUS,
                0L
        );
    }

    public boolean seekTo(long positionMs) {
        if (positionMs < 0L) {
            return false;
        }
        return forward(
                PlaybackState.ACTION_SEEK_TO,
                0L,
                TransportOperation.SEEK,
                positionMs
        );
    }

    long notificationListenerConnected(ComponentName componentName) {
        Objects.requireNonNull(componentName, "componentName");
        long connectionId = nextConnectionId.incrementAndGet();
        runOnMain(() -> connectOnMain(connectionId, componentName));
        return connectionId;
    }

    void notificationListenerDisconnected(long connectionId) {
        runOnMain(() -> disconnectOnMain(connectionId));
    }

    private void connectOnMain(long connectionId, ComponentName componentName) {
        unregisterSystemListeners();
        clearSelectedTarget(true);

        currentConnectionId = connectionId;
        credentialComponent = componentName;
        publishSnapshot(Snapshot.withoutSession(true, selectionGeneration));

        startSystemObservationIfNeeded();
    }

    /** Starts media observation only while a real Android Auto consumer is present. */
    private boolean startSystemObservationIfNeeded() {
        if (systemObservationActive
                || listeners.isEmpty()
                || currentConnectionId == 0L
                || credentialComponent == null
                || mediaSessionManager == null) {
            return false;
        }

        systemObservationActive = true;

        try {
            long connectionId = currentConnectionId;
            MediaSessionManager.OnActiveSessionsChangedListener connectionListener = controllers -> {
                List<MediaController> copiedControllers = copyControllers(controllers);
                runOnMain(() -> {
                    if (!systemObservationActive || connectionId != currentConnectionId) {
                        return;
                    }
                    selectFromControllers(copiedControllers, currentMediaKeyToken());
                });
            };
            activeSessionsListener = connectionListener;
            mediaSessionManager.addOnActiveSessionsChangedListener(
                    connectionListener,
                    credentialComponent,
                    mainHandler
            );
            activeSessionsListenerRegistered = true;
        } catch (RuntimeException ignored) {
            activeSessionsListenerRegistered = false;
            activeSessionsListener = null;
        }

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                mediaKeyEventListener = Api33.registerMediaKeyEventSessionListener(
                        mediaSessionManager,
                        mainHandler,
                        this::refreshFromSystem
                );
            } catch (RuntimeException ignored) {
                mediaKeyEventListener = null;
            }
        }
        refreshFromSystemOnMain();
        AutoLogWriter.append(
                appContext,
                "aa_relay observation_started consumers=" + listeners.size()
        );
        return true;
    }

    private void stopSystemObservationWithoutDemand() {
        if (!systemObservationActive && selectedTarget == null) {
            return;
        }
        unregisterSystemListeners();
        destroyedToken = null;
        clearSelectedTarget(true);
        publishSnapshot(Snapshot.withoutSession(currentConnectionId != 0L, selectionGeneration));
        AutoLogWriter.append(appContext, "aa_relay observation_stopped reason=no_consumers");
    }

    private void disconnectOnMain(long connectionId) {
        if (connectionId == 0L || connectionId != currentConnectionId) {
            return;
        }
        unregisterSystemListeners();
        currentConnectionId = 0L;
        credentialComponent = null;
        destroyedToken = null;
        clearSelectedTarget(true);
        publishSnapshot(Snapshot.withoutSession(false, selectionGeneration));
    }

    private void unregisterSystemListeners() {
        systemObservationActive = false;
        if (mediaSessionManager != null && activeSessionsListenerRegistered) {
            try {
                mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener);
            } catch (RuntimeException ignored) {
                // The system may already have discarded the listener with its credential.
            }
        }
        activeSessionsListenerRegistered = false;
        activeSessionsListener = null;

        if (mediaSessionManager != null
                && mediaKeyEventListener != null
                && Build.VERSION.SDK_INT >= 33) {
            try {
                Api33.removeMediaKeyEventSessionListener(
                        mediaSessionManager,
                        mediaKeyEventListener
                );
            } catch (RuntimeException ignored) {
                // The system may already have discarded the listener with its credential.
            }
        }
        mediaKeyEventListener = null;
    }

    private void refreshFromSystem() {
        runOnMain(this::refreshFromSystemOnMain);
    }

    private void refreshFromSystemOnMain() {
        if (!systemObservationActive
                || currentConnectionId == 0L
                || credentialComponent == null
                || mediaSessionManager == null) {
            return;
        }

        List<MediaController> controllers;
        try {
            controllers = copyControllers(
                    mediaSessionManager.getActiveSessions(credentialComponent)
            );
        } catch (RuntimeException ignored) {
            controllers = Collections.emptyList();
        }
        selectFromControllers(controllers, currentMediaKeyToken());
    }

    private MediaSession.Token currentMediaKeyToken() {
        if (mediaSessionManager == null || Build.VERSION.SDK_INT < 33) {
            return null;
        }
        try {
            return Api33.getMediaKeyEventSession(mediaSessionManager);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void selectFromControllers(
            List<MediaController> controllers,
            MediaSession.Token mediaKeyToken
    ) {
        if (!systemObservationActive || currentConnectionId == 0L) {
            return;
        }

        updateDestroyedTokenPresence(controllers);
        List<ControllerCandidate> controllerCandidates = new ArrayList<>(controllers.size());
        List<ExternalMediaSessionSelector.Candidate> policyCandidates = new ArrayList<>(
                controllers.size()
        );
        for (MediaController controller : controllers) {
            ControllerCandidate candidate = buildCandidate(controller, mediaKeyToken);
            controllerCandidates.add(candidate);
            policyCandidates.add(candidate.policyCandidate);
        }

        int selectedIndex = ExternalMediaSessionSelector.select(policyCandidates);
        if (selectedIndex == ExternalMediaSessionSelector.NONE) {
            clearSelectedTarget(false);
            publishSnapshot(Snapshot.withoutSession(true, selectionGeneration));
            return;
        }

        selectTarget(controllerCandidates.get(selectedIndex));
    }

    private ControllerCandidate buildCandidate(
            MediaController controller,
            MediaSession.Token mediaKeyToken
    ) {
        if (controller == null) {
            return ControllerCandidate.invalid();
        }

        String packageName = safePackageName(controller);
        MediaSession.Token token = safeToken(controller);
        PlaybackState playbackState = safePlaybackState(controller);
        MediaMetadata metadata = safeMetadata(controller);
        boolean external = !packageName.isEmpty()
                && !appContext.getPackageName().equals(packageName)
                && token != null
                && !tokensEqual(token, destroyedToken);
        boolean sticky = external && tokensEqual(token, stickyToken);
        boolean mediaKeyTarget = external && tokensEqual(token, mediaKeyToken);
        int activity = activityFor(playbackState, metadata);
        return new ControllerCandidate(
                controller,
                token,
                packageName,
                metadata,
                playbackState,
                new ExternalMediaSessionSelector.Candidate(
                        external,
                        sticky,
                        mediaKeyTarget,
                        activity
                )
        );
    }

    private void selectTarget(ControllerCandidate candidate) {
        SelectedTarget current = selectedTarget;
        if (current != null && tokensEqual(current.token, candidate.token)) {
            SelectedTarget updated = current.withSupportedActions(
                    standardActions(candidate.playbackState)
            );
            selectedTarget = updated;
            publishSnapshot(buildSnapshot(
                    updated,
                    candidate.metadata,
                    candidate.playbackState
            ));
            return;
        }

        clearSelectedTarget(false);
        long generation = selectionGeneration;
        MediaController.Callback callback = callbackFor(candidate.token, generation);
        try {
            candidate.controller.registerCallback(callback, mainHandler);
        } catch (RuntimeException ignored) {
            publishSnapshot(Snapshot.withoutSession(true, selectionGeneration));
            return;
        }

        SelectedTarget selected = new SelectedTarget(
                candidate.controller,
                candidate.token,
                candidate.packageName,
                callback,
                generation,
                standardActions(candidate.playbackState)
        );
        selectedTarget = selected;
        stickyToken = candidate.token;
        publishSnapshot(buildSnapshot(selected, candidate.metadata, candidate.playbackState));
    }

    private MediaController.Callback callbackFor(
            MediaSession.Token token,
            long callbackGeneration
    ) {
        return new MediaController.Callback() {
            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                runOnMain(() -> refreshSelectionFromCallback(token, callbackGeneration));
            }

            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                runOnMain(() -> refreshSelectionFromCallback(token, callbackGeneration));
            }

            @Override
            public void onSessionDestroyed() {
                runOnMain(() -> handleSelectedSessionDestroyed(token, callbackGeneration));
            }
        };
    }

    private void refreshSelectionFromCallback(
            MediaSession.Token callbackToken,
            long callbackGeneration
    ) {
        SelectedTarget current = selectedTarget;
        if (!isCurrentCallback(current, callbackToken, callbackGeneration)) {
            return;
        }
        refreshFromSystemOnMain();
    }

    private void handleSelectedSessionDestroyed(
            MediaSession.Token callbackToken,
            long callbackGeneration
    ) {
        SelectedTarget current = selectedTarget;
        if (!isCurrentCallback(current, callbackToken, callbackGeneration)) {
            return;
        }
        destroyedToken = callbackToken;
        clearSelectedTarget(false);
        publishSnapshot(Snapshot.withoutSession(true, selectionGeneration));
        refreshFromSystemOnMain();
    }

    private boolean isCurrentCallback(
            SelectedTarget current,
            MediaSession.Token callbackToken,
            long callbackGeneration
    ) {
        return current != null
                && ExternalMediaSessionSelector.isCurrentGeneration(
                        callbackGeneration,
                        current.generation
                )
                && tokensEqual(callbackToken, current.token);
    }

    private void clearSelectedTarget(boolean clearSticky) {
        SelectedTarget current = selectedTarget;
        selectedTarget = null;
        selectionGeneration++;
        if (current != null) {
            try {
                current.controller.unregisterCallback(current.callback);
            } catch (RuntimeException ignored) {
                // A destroyed or remote session may already have dropped the callback.
            }
        }
        if (clearSticky) {
            stickyToken = null;
        }
    }

    private void updateDestroyedTokenPresence(List<MediaController> controllers) {
        if (destroyedToken == null) {
            return;
        }
        for (MediaController controller : controllers) {
            if (tokensEqual(safeToken(controller), destroyedToken)) {
                return;
            }
        }
        destroyedToken = null;
    }

    private boolean forward(
            long requiredAction,
            long alternativeAction,
            TransportOperation operation,
            long positionMs
    ) {
        SelectedTarget target = selectedTarget;
        if (target == null || !supports(target.supportedActions, requiredAction, alternativeAction)) {
            return false;
        }
        SelectedTarget latest = selectedTarget;
        if (latest == null
                || !ExternalMediaSessionSelector.isCurrentGeneration(
                        target.generation,
                        latest.generation
                )
                || !tokensEqual(target.token, latest.token)) {
            return false;
        }

        try {
            MediaController.TransportControls controls = target.controller.getTransportControls();
            switch (operation) {
                case PLAY:
                    controls.play();
                    break;
                case PAUSE:
                    controls.pause();
                    break;
                case STOP:
                    controls.stop();
                    break;
                case NEXT:
                    controls.skipToNext();
                    break;
                case PREVIOUS:
                    controls.skipToPrevious();
                    break;
                case SEEK:
                    controls.seekTo(positionMs);
                    break;
                default:
                    return false;
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Snapshot buildSnapshot(
            SelectedTarget target,
            MediaMetadata metadata,
            PlaybackState playbackState
    ) {
        return new Snapshot(
                target.generation,
                true,
                true,
                target.packageName,
                metadataText(
                        metadata,
                        MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                        MediaMetadata.METADATA_KEY_TITLE
                ),
                metadataText(
                        metadata,
                        MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                        MediaMetadata.METADATA_KEY_ARTIST,
                        MediaMetadata.METADATA_KEY_ALBUM_ARTIST
                ),
                metadataText(metadata, MediaMetadata.METADATA_KEY_ALBUM),
                metadataDuration(metadata),
                metadataArtwork(metadata),
                playbackState == null ? PlaybackState.STATE_NONE : playbackState.getState(),
                playbackPosition(playbackState),
                playbackPositionUpdateTime(playbackState),
                playbackSpeed(playbackState),
                standardActions(playbackState)
        );
    }

    private void publishSnapshot(Snapshot snapshot) {
        currentSnapshot = snapshot;
        for (Listener listener : listeners) {
            notifyListener(listener, snapshot);
        }
    }

    private static void notifyListener(Listener listener, Snapshot snapshot) {
        try {
            listener.onExternalMediaSnapshotChanged(snapshot);
        } catch (RuntimeException ignored) {
            // A failing UI listener must not prevent discovery or other listeners.
        }
    }

    private void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == mainHandler.getLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }

    private static List<MediaController> copyControllers(List<MediaController> controllers) {
        return controllers == null
                ? Collections.emptyList()
                : new ArrayList<>(controllers);
    }

    private static String safePackageName(MediaController controller) {
        try {
            return sanitizeText(controller.getPackageName());
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static MediaSession.Token safeToken(MediaController controller) {
        if (controller == null) {
            return null;
        }
        try {
            return controller.getSessionToken();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static PlaybackState safePlaybackState(MediaController controller) {
        try {
            return controller.getPlaybackState();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static MediaMetadata safeMetadata(MediaController controller) {
        try {
            return controller.getMetadata();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static int activityFor(PlaybackState playbackState, MediaMetadata metadata) {
        if (playbackState == null) {
            return ExternalMediaSessionSelector.ACTIVITY_OTHER;
        }
        int state = playbackState.getState();
        if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
            return ExternalMediaSessionSelector.ACTIVITY_PLAYING_OR_BUFFERING;
        }
        if (state == PlaybackState.STATE_CONNECTING
                || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING
                || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
                || state == PlaybackState.STATE_SKIPPING_TO_NEXT
                || state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM) {
            return ExternalMediaSessionSelector.ACTIVITY_TRANSIENT;
        }
        if (state == PlaybackState.STATE_PAUSED && hasKnownMetadata(metadata)) {
            return ExternalMediaSessionSelector.ACTIVITY_PAUSED_WITH_METADATA;
        }
        return ExternalMediaSessionSelector.ACTIVITY_OTHER;
    }

    private static boolean hasKnownMetadata(MediaMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        return !metadataText(
                metadata,
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM
        ).isEmpty() || hasKnownDuration(metadata) || hasKnownArtwork(metadata);
    }

    private static boolean hasKnownDuration(MediaMetadata metadata) {
        try {
            return metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION)
                    && metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) >= 0L;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean hasKnownArtwork(MediaMetadata metadata) {
        String[] keys = {
                MediaMetadata.METADATA_KEY_DISPLAY_ICON,
                MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_ALBUM_ART
        };
        for (String key : keys) {
            try {
                Bitmap bitmap = metadata.getBitmap(key);
                if (bitmap != null && !bitmap.isRecycled()) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Try the next explicitly allowed bitmap key.
            }
        }
        return false;
    }

    private static String metadataText(MediaMetadata metadata, String... keys) {
        if (metadata == null) {
            return "";
        }
        for (String key : keys) {
            try {
                String value = sanitizeText(metadata.getText(key));
                if (!value.isEmpty()) {
                    return value;
                }
            } catch (RuntimeException ignored) {
                // Try the next explicitly allowed metadata key.
            }
        }
        return "";
    }

    private static String sanitizeText(CharSequence text) {
        if (text == null) {
            return "";
        }
        String value = text.toString();
        if (value.length() <= MAX_TEXT_LENGTH) {
            return value;
        }
        int end = MAX_TEXT_LENGTH;
        if (Character.isHighSurrogate(value.charAt(end - 1))
                && end < value.length()
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static long metadataDuration(MediaMetadata metadata) {
        if (metadata == null) {
            return UNKNOWN_TIME_MS;
        }
        try {
            if (!metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION)) {
                return UNKNOWN_TIME_MS;
            }
            long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            return duration < 0L ? UNKNOWN_TIME_MS : duration;
        } catch (RuntimeException ignored) {
            return UNKNOWN_TIME_MS;
        }
    }

    private static Bitmap metadataArtwork(MediaMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        String[] keys = {
                MediaMetadata.METADATA_KEY_DISPLAY_ICON,
                MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_ALBUM_ART
        };
        for (String key : keys) {
            try {
                Bitmap artwork = sanitizeArtwork(metadata.getBitmap(key));
                if (artwork != null) {
                    return artwork;
                }
            } catch (RuntimeException ignored) {
                // Try the next explicitly allowed bitmap key.
            }
        }
        return null;
    }

    private static Bitmap sanitizeArtwork(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return null;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }

        Bitmap scaled = source;
        int largestEdge = Math.max(width, height);
        if (largestEdge > MAX_ARTWORK_EDGE_PX) {
            float scale = (float) MAX_ARTWORK_EDGE_PX / (float) largestEdge;
            int scaledWidth = Math.max(1, Math.round(width * scale));
            int scaledHeight = Math.max(1, Math.round(height * scale));
            scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true);
        }

        Bitmap copy = scaled.copy(Bitmap.Config.ARGB_8888, false);
        if (scaled != source && scaled != copy) {
            scaled.recycle();
        }
        return copy;
    }

    private static long playbackPosition(PlaybackState playbackState) {
        if (playbackState == null || playbackState.getPosition() < 0L) {
            return UNKNOWN_TIME_MS;
        }
        return playbackState.getPosition();
    }

    private static long playbackPositionUpdateTime(PlaybackState playbackState) {
        if (playbackState == null || playbackState.getLastPositionUpdateTime() <= 0L) {
            return UNKNOWN_TIME_MS;
        }
        return playbackState.getLastPositionUpdateTime();
    }

    private static float playbackSpeed(PlaybackState playbackState) {
        if (playbackState == null) {
            return 0f;
        }
        float speed = playbackState.getPlaybackSpeed();
        return Float.isNaN(speed) || Float.isInfinite(speed) ? 0f : speed;
    }

    private static long standardActions(PlaybackState playbackState) {
        return playbackState == null ? 0L : playbackState.getActions() & STANDARD_ACTION_MASK;
    }

    private static boolean supports(long actions, long primary, long alternative) {
        return (actions & primary) != 0L || (alternative != 0L && (actions & alternative) != 0L);
    }

    private static boolean tokensEqual(MediaSession.Token first, MediaSession.Token second) {
        return first == second || (first != null && first.equals(second));
    }

    public interface Listener {
        void onExternalMediaSnapshotChanged(Snapshot snapshot);
    }

    /** Immutable, sanitized media state safe to mirror into AAARP's own media session. */
    public static final class Snapshot {
        private final long generation;
        private final boolean notificationAccessConnected;
        private final boolean hasSession;
        private final String packageName;
        private final String title;
        private final String artist;
        private final String album;
        private final long durationMs;
        private final Bitmap artwork;
        private final int playbackState;
        private final long positionMs;
        private final long positionUpdateTimeMs;
        private final float playbackSpeed;
        private final long supportedActions;

        private Snapshot(
                long generation,
                boolean notificationAccessConnected,
                boolean hasSession,
                String packageName,
                String title,
                String artist,
                String album,
                long durationMs,
                Bitmap artwork,
                int playbackState,
                long positionMs,
                long positionUpdateTimeMs,
                float playbackSpeed,
                long supportedActions
        ) {
            this.generation = generation;
            this.notificationAccessConnected = notificationAccessConnected;
            this.hasSession = hasSession;
            this.packageName = packageName;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.durationMs = durationMs;
            this.artwork = artwork;
            this.playbackState = playbackState;
            this.positionMs = positionMs;
            this.positionUpdateTimeMs = positionUpdateTimeMs;
            this.playbackSpeed = playbackSpeed;
            this.supportedActions = supportedActions;
        }

        private static Snapshot withoutSession(boolean accessConnected, long generation) {
            return new Snapshot(
                    generation,
                    accessConnected,
                    false,
                    "",
                    "",
                    "",
                    "",
                    UNKNOWN_TIME_MS,
                    null,
                    PlaybackState.STATE_NONE,
                    UNKNOWN_TIME_MS,
                    UNKNOWN_TIME_MS,
                    0f,
                    0L
            );
        }

        public long generation() {
            return generation;
        }

        public boolean notificationAccessConnected() {
            return notificationAccessConnected;
        }

        public boolean hasSession() {
            return hasSession;
        }

        public String packageName() {
            return packageName;
        }

        public String title() {
            return title;
        }

        public String artist() {
            return artist;
        }

        public String album() {
            return album;
        }

        public long durationMs() {
            return durationMs;
        }

        public Bitmap artwork() {
            return artwork;
        }

        public int playbackState() {
            return playbackState;
        }

        public long positionMs() {
            return positionMs;
        }

        public long positionUpdateTimeMs() {
            return positionUpdateTimeMs;
        }

        public float playbackSpeed() {
            return playbackSpeed;
        }

        public long supportedActions() {
            return supportedActions;
        }
    }

    private enum TransportOperation {
        PLAY,
        PAUSE,
        STOP,
        NEXT,
        PREVIOUS,
        SEEK
    }

    private static final class ControllerCandidate {
        final MediaController controller;
        final MediaSession.Token token;
        final String packageName;
        final MediaMetadata metadata;
        final PlaybackState playbackState;
        final ExternalMediaSessionSelector.Candidate policyCandidate;

        private ControllerCandidate(
                MediaController controller,
                MediaSession.Token token,
                String packageName,
                MediaMetadata metadata,
                PlaybackState playbackState,
                ExternalMediaSessionSelector.Candidate policyCandidate
        ) {
            this.controller = controller;
            this.token = token;
            this.packageName = packageName;
            this.metadata = metadata;
            this.playbackState = playbackState;
            this.policyCandidate = policyCandidate;
        }

        static ControllerCandidate invalid() {
            return new ControllerCandidate(
                    null,
                    null,
                    "",
                    null,
                    null,
                    new ExternalMediaSessionSelector.Candidate(
                            false,
                            false,
                            false,
                            ExternalMediaSessionSelector.ACTIVITY_OTHER
                    )
            );
        }
    }

    private static final class SelectedTarget {
        final MediaController controller;
        final MediaSession.Token token;
        final String packageName;
        final MediaController.Callback callback;
        final long generation;
        final long supportedActions;

        private SelectedTarget(
                MediaController controller,
                MediaSession.Token token,
                String packageName,
                MediaController.Callback callback,
                long generation,
                long supportedActions
        ) {
            this.controller = controller;
            this.token = token;
            this.packageName = packageName;
            this.callback = callback;
            this.generation = generation;
            this.supportedActions = supportedActions;
        }

        SelectedTarget withSupportedActions(long actions) {
            return new SelectedTarget(
                    controller,
                    token,
                    packageName,
                    callback,
                    generation,
                    actions
            );
        }
    }

    /**
     * Keeps media-key-session symbols out of class verification on older devices. Calls into this
     * helper are intentionally gated at API 33 even though the underlying platform API began at 31.
     */
    @TargetApi(33)
    private static final class Api33 {
        private Api33() {
        }

        static Object registerMediaKeyEventSessionListener(
                MediaSessionManager manager,
                Handler handler,
                Runnable onChanged
        ) {
            MediaSessionManager.OnMediaKeyEventSessionChangedListener listener =
                    (packageName, token) -> onChanged.run();
            Executor executor = command -> handler.post(command);
            manager.addOnMediaKeyEventSessionChangedListener(executor, listener);
            return listener;
        }

        static void removeMediaKeyEventSessionListener(
                MediaSessionManager manager,
                Object listener
        ) {
            manager.removeOnMediaKeyEventSessionChangedListener(
                    (MediaSessionManager.OnMediaKeyEventSessionChangedListener) listener
            );
        }

        static MediaSession.Token getMediaKeyEventSession(MediaSessionManager manager) {
            return manager.getMediaKeyEventSession();
        }
    }
}

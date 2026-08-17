package dev.voxvargr.aaarp;

import android.media.AudioManager;

import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class AndroidAutoVolumeContent {
    static final String ROOT_ID = "aaarp.phone_volume.root";
    static final String INFO_ID = "aaarp.phone_volume.info";
    static final String RELAY_ID = "aaarp.phone_volume.relay";
    static final String ACTION_SHOW_VOLUME_CONTROLS =
            "dev.voxvargr.aaarp.action.SHOW_VOLUME_CONTROLS";
    static final String ACTION_SHOW_TRACK_CONTROLS =
            "dev.voxvargr.aaarp.action.SHOW_TRACK_CONTROLS";
    static final String ACTION_VOLUME_DOWN = "dev.voxvargr.aaarp.action.VOLUME_DOWN";
    static final String ACTION_VOLUME_UP = "dev.voxvargr.aaarp.action.VOLUME_UP";
    // Media3 CommandButton semantic icon constants. Keeping these local avoids pulling in
    // media3-session solely for metadata consumed by modern Android Auto hosts.
    static final String COMMAND_BUTTON_ICON_COMPAT_EXTRA =
            "androidx.media3.session.EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT";
    static final int COMMAND_BUTTON_ICON_VOLUME_DOWN = 57421;
    static final int COMMAND_BUTTON_ICON_VOLUME_UP = 57424;
    static final int INFO_ITEM_FLAGS = MediaBrowserCompat.MediaItem.FLAG_BROWSABLE;
    static final int RELAY_ITEM_FLAGS = MediaBrowserCompat.MediaItem.FLAG_PLAYABLE;

    private static final long RELAY_TRANSPORT_ACTIONS = PlaybackStateCompat.ACTION_PLAY
            | PlaybackStateCompat.ACTION_PAUSE
            | PlaybackStateCompat.ACTION_PLAY_PAUSE
            | PlaybackStateCompat.ACTION_STOP
            | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            | PlaybackStateCompat.ACTION_SEEK_TO;

    private static final List<String> VOLUME_ACTION_IDS = Collections.unmodifiableList(
            Arrays.asList(ACTION_VOLUME_DOWN, ACTION_VOLUME_UP)
    );
    private static final List<String> HUB_ACTION_IDS = Collections.unmodifiableList(
            Arrays.asList(ACTION_SHOW_VOLUME_CONTROLS, ACTION_SHOW_TRACK_CONTROLS)
    );

    private AndroidAutoVolumeContent() {
    }

    static boolean supportsVolumeActions(int customBrowseActionLimit) {
        return customBrowseActionLimit >= 2;
    }

    static BrowseLayout browseLayout(
            int customBrowseActionLimit,
            boolean relayEnabled,
            boolean notificationAccessConnected,
            boolean externalSessionAvailable
    ) {
        if (supportsVolumeActions(customBrowseActionLimit)) {
            return BrowseLayout.CUSTOM_BROWSE_ACTIONS;
        }
        if (relayEnabled
                && notificationAccessConnected
                && externalSessionAvailable) {
            return BrowseLayout.RELAY_ITEM;
        }
        return BrowseLayout.INFO_ONLY;
    }

    static List<String> actionIdsForLimit(int customBrowseActionLimit) {
        return supportsVolumeActions(customBrowseActionLimit)
                ? VOLUME_ACTION_IDS
                : Collections.emptyList();
    }

    static List<String> playbackVolumeActionIds() {
        return VOLUME_ACTION_IDS;
    }

    static int semanticIconForVolumeAction(String action) {
        if (ACTION_VOLUME_DOWN.equals(action)) {
            return COMMAND_BUTTON_ICON_VOLUME_DOWN;
        }
        if (ACTION_VOLUME_UP.equals(action)) {
            return COMMAND_BUTTON_ICON_VOLUME_UP;
        }
        return 0;
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

    static boolean isExpectedMediaItemId(String mediaItemId) {
        return ROOT_ID.equals(mediaItemId)
                || INFO_ID.equals(mediaItemId)
                || RELAY_ID.equals(mediaItemId);
    }

    static long sanitizedTransportActions(long externalActions) {
        return externalActions & RELAY_TRANSPORT_ACTIONS;
    }

    static ControlUiSpec controlUiSpec(
            ControlBarMode mode,
            long externalActions,
            int playbackState
    ) {
        ControlBarMode resolvedMode = mode == null ? ControlBarMode.HUB : mode;
        long actions = sanitizedTransportActions(externalActions);
        if ((actions & PlaybackStateCompat.ACTION_PLAY_PAUSE) != 0L) {
            if (playbackState == PlaybackStateCompat.STATE_PLAYING
                    || playbackState == PlaybackStateCompat.STATE_BUFFERING
                    || playbackState == PlaybackStateCompat.STATE_CONNECTING) {
                actions |= PlaybackStateCompat.ACTION_PAUSE;
            } else {
                actions |= PlaybackStateCompat.ACTION_PLAY;
            }
        }
        long skipActions = actions & (PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT);
        actions &= ~(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT);
        List<String> customActions;
        switch (resolvedMode) {
            case VOLUME:
                customActions = VOLUME_ACTION_IDS;
                break;
            case TRACK:
                actions |= skipActions;
                customActions = Collections.emptyList();
                break;
            case HUB:
            default:
                customActions = supportsTrackControls(externalActions)
                        ? HUB_ACTION_IDS
                        : Collections.singletonList(ACTION_SHOW_VOLUME_CONTROLS);
                break;
        }
        return new ControlUiSpec(
                actions | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID,
                customActions
        );
    }

    static long playbackUiTransportActions(
            long externalActions,
            int playbackState,
            ControlBarMode mode
    ) {
        return controlUiSpec(mode, externalActions, playbackState).standardActions();
    }

    static List<String> playbackCustomActionIds(
            ControlBarMode mode,
            long externalActions,
            int playbackState
    ) {
        return controlUiSpec(mode, externalActions, playbackState).customActionIds();
    }

    static boolean supportsTrackControls(long externalActions) {
        return (externalActions & (PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT)) != 0L;
    }

    static ControlBarMode modeForNavigationAction(String action) {
        if (ACTION_SHOW_VOLUME_CONTROLS.equals(action)) {
            return ControlBarMode.VOLUME;
        }
        if (ACTION_SHOW_TRACK_CONTROLS.equals(action)) {
            return ControlBarMode.TRACK;
        }
        return null;
    }

    static boolean isVolumeAction(String action) {
        return ACTION_VOLUME_DOWN.equals(action) || ACTION_VOLUME_UP.equals(action);
    }

    static int semanticIconForPlaybackAction(String action) {
        return semanticIconForVolumeAction(action);
    }

    static long preArmTransportActions() {
        // Some projected Android Auto hosts turn a playable browse-row selection into a
        // generic Play command instead of PlayFromMediaId. Advertise both so either
        // explicit user action can arm the relay without activating it during discovery.
        return PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID;
    }

    static boolean supportsRelayCommand(long externalActions, RelayCommand command) {
        if (command == null) {
            return false;
        }
        switch (command) {
            case PLAY:
                return (externalActions
                        & (PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE)) != 0L;
            case PAUSE:
                return (externalActions
                        & (PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE)) != 0L;
            case STOP:
                return (externalActions & PlaybackStateCompat.ACTION_STOP) != 0L;
            case NEXT:
                return (externalActions & PlaybackStateCompat.ACTION_SKIP_TO_NEXT) != 0L;
            case PREVIOUS:
                return (externalActions & PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS) != 0L;
            case SEEK:
                return (externalActions & PlaybackStateCompat.ACTION_SEEK_TO) != 0L;
            default:
                return false;
        }
    }

    static boolean shouldRemainArmed(
            boolean relayEnabled,
            boolean notificationAccessConnected,
            boolean externalSessionAvailable,
            int externalPlaybackState
    ) {
        if (!relayEnabled || !notificationAccessConnected || !externalSessionAvailable) {
            return false;
        }
        switch (externalPlaybackState) {
            case PlaybackStateCompat.STATE_PAUSED:
            case PlaybackStateCompat.STATE_PLAYING:
            case PlaybackStateCompat.STATE_FAST_FORWARDING:
            case PlaybackStateCompat.STATE_REWINDING:
            case PlaybackStateCompat.STATE_BUFFERING:
            case PlaybackStateCompat.STATE_CONNECTING:
            case PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackStateCompat.STATE_SKIPPING_TO_NEXT:
            case PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM:
                return true;
            default:
                return false;
        }
    }

    static boolean isDuplicateValidArm(
            boolean relayArmed,
            long armedGeneration,
            long snapshotGeneration,
            boolean relayEnabled,
            boolean notificationAccessConnected,
            boolean externalSessionAvailable,
            int externalPlaybackState
    ) {
        return relayArmed
                && armedGeneration == snapshotGeneration
                && shouldRemainArmed(
                relayEnabled,
                notificationAccessConnected,
                externalSessionAvailable,
                externalPlaybackState
        );
    }

    static String actionName(int direction) {
        if (direction == AudioManager.ADJUST_LOWER) {
            return "down";
        }
        if (direction == AudioManager.ADJUST_RAISE) {
            return "up";
        }
        return "unknown";
    }

    enum BrowseLayout {
        CUSTOM_BROWSE_ACTIONS,
        RELAY_ITEM,
        INFO_ONLY
    }

    enum RelayCommand {
        PLAY,
        PAUSE,
        STOP,
        NEXT,
        PREVIOUS,
        SEEK
    }

    enum ControlBarMode {
        HUB,
        VOLUME,
        TRACK
    }

    static final class ControlUiSpec {
        private final long standardActions;
        private final List<String> customActionIds;

        private ControlUiSpec(long standardActions, List<String> customActionIds) {
            this.standardActions = standardActions;
            this.customActionIds = customActionIds;
        }

        @PlaybackStateCompat.Actions
        long standardActions() {
            return standardActions;
        }

        List<String> customActionIds() {
            return customActionIds;
        }
    }
}

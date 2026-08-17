package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import java.util.Arrays;

import org.junit.Test;

public final class AndroidAutoRelayPolicyTest {
    @Test
    public void customBrowseActionsTakePriorityWhenHostHasTwoSlots() {
        assertEquals(
                AndroidAutoVolumeContent.BrowseLayout.CUSTOM_BROWSE_ACTIONS,
                AndroidAutoVolumeContent.browseLayout(2, true, true, true)
        );
    }

    @Test
    public void relayItemRequiresUnsupportedBrowseActionsAndEveryRelayGate() {
        assertEquals(
                AndroidAutoVolumeContent.BrowseLayout.RELAY_ITEM,
                AndroidAutoVolumeContent.browseLayout(0, true, true, true)
        );
        assertEquals(
                AndroidAutoVolumeContent.BrowseLayout.RELAY_ITEM,
                AndroidAutoVolumeContent.browseLayout(1, true, true, true)
        );
        assertEquals(
                AndroidAutoVolumeContent.BrowseLayout.INFO_ONLY,
                AndroidAutoVolumeContent.browseLayout(0, false, true, true)
        );
        assertEquals(
                AndroidAutoVolumeContent.BrowseLayout.INFO_ONLY,
                AndroidAutoVolumeContent.browseLayout(0, true, false, true)
        );
        assertEquals(
                AndroidAutoVolumeContent.BrowseLayout.INFO_ONLY,
                AndroidAutoVolumeContent.browseLayout(0, true, true, false)
        );
    }

    @Test
    public void relayItemIsExplicitlyPlayable() {
        assertEquals(
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
                AndroidAutoVolumeContent.RELAY_ITEM_FLAGS
        );
    }

    @Test
    public void playbackVolumeActionsRemainDownThenUp() {
        assertEquals(
                Arrays.asList(
                        AndroidAutoVolumeContent.ACTION_VOLUME_DOWN,
                        AndroidAutoVolumeContent.ACTION_VOLUME_UP
                ),
                AndroidAutoVolumeContent.playbackVolumeActionIds()
        );
    }

    @Test
    public void playbackVolumeActionsUseStandardSemanticIcons() {
        assertEquals(
                AndroidAutoVolumeContent.COMMAND_BUTTON_ICON_VOLUME_DOWN,
                AndroidAutoVolumeContent.semanticIconForVolumeAction(
                        AndroidAutoVolumeContent.ACTION_VOLUME_DOWN
                )
        );
        assertEquals(
                AndroidAutoVolumeContent.COMMAND_BUTTON_ICON_VOLUME_UP,
                AndroidAutoVolumeContent.semanticIconForVolumeAction(
                        AndroidAutoVolumeContent.ACTION_VOLUME_UP
                )
        );
        assertEquals(0, AndroidAutoVolumeContent.semanticIconForVolumeAction("unknown"));
        assertEquals(0, AndroidAutoVolumeContent.semanticIconForPlaybackAction(
                AndroidAutoVolumeContent.ACTION_SHOW_VOLUME_CONTROLS
        ));
        assertEquals(0, AndroidAutoVolumeContent.semanticIconForPlaybackAction(
                AndroidAutoVolumeContent.ACTION_SHOW_TRACK_CONTROLS
        ));
    }

    @Test
    public void transportCommandsRequireTheirAdvertisedExternalAction() {
        long actions = PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SEEK_TO;

        assertTrue(AndroidAutoVolumeContent.supportsRelayCommand(
                actions,
                AndroidAutoVolumeContent.RelayCommand.PLAY
        ));
        assertTrue(AndroidAutoVolumeContent.supportsRelayCommand(
                actions,
                AndroidAutoVolumeContent.RelayCommand.PAUSE
        ));
        assertTrue(AndroidAutoVolumeContent.supportsRelayCommand(
                actions,
                AndroidAutoVolumeContent.RelayCommand.NEXT
        ));
        assertTrue(AndroidAutoVolumeContent.supportsRelayCommand(
                actions,
                AndroidAutoVolumeContent.RelayCommand.SEEK
        ));
        assertFalse(AndroidAutoVolumeContent.supportsRelayCommand(
                actions,
                AndroidAutoVolumeContent.RelayCommand.STOP
        ));
        assertFalse(AndroidAutoVolumeContent.supportsRelayCommand(
                actions,
                AndroidAutoVolumeContent.RelayCommand.PREVIOUS
        ));
        assertFalse(AndroidAutoVolumeContent.supportsRelayCommand(actions, null));
    }

    @Test
    public void mirroredActionsDropUnsupportedAndNonTransportBits() {
        long external = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_SET_RATING;

        assertEquals(
                PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE,
                AndroidAutoVolumeContent.sanitizedTransportActions(external)
        );
    }

    @Test
    public void hubUsesVolumeAndTrackMenusInTheTwoSideSlots() {
        long external = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT;

        assertEquals(
                PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID,
                AndroidAutoVolumeContent.playbackUiTransportActions(
                        external,
                        PlaybackStateCompat.STATE_PAUSED,
                        AndroidAutoVolumeContent.ControlBarMode.HUB
                )
        );
        assertEquals(
                Arrays.asList(
                        AndroidAutoVolumeContent.ACTION_SHOW_VOLUME_CONTROLS,
                        AndroidAutoVolumeContent.ACTION_SHOW_TRACK_CONTROLS
                ),
                AndroidAutoVolumeContent.playbackCustomActionIds(
                        AndroidAutoVolumeContent.ControlBarMode.HUB,
                        external,
                        PlaybackStateCompat.STATE_PAUSED
                )
        );
    }

    @Test
    public void hubOmitsTrackMenuWhenTheSourceCannotSkip() {
        assertEquals(
                Arrays.asList(AndroidAutoVolumeContent.ACTION_SHOW_VOLUME_CONTROLS),
                AndroidAutoVolumeContent.playbackCustomActionIds(
                        AndroidAutoVolumeContent.ControlBarMode.HUB,
                        PlaybackStateCompat.ACTION_PLAY,
                        PlaybackStateCompat.STATE_PAUSED
                )
        );
    }

    @Test
    public void volumeModeKeepsTheProvenDownThenUpLayout() {
        long external = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT;

        assertEquals(
                PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID,
                AndroidAutoVolumeContent.playbackUiTransportActions(
                        external,
                        PlaybackStateCompat.STATE_PAUSED,
                        AndroidAutoVolumeContent.ControlBarMode.VOLUME
                )
        );
        assertEquals(
                Arrays.asList(
                        AndroidAutoVolumeContent.ACTION_VOLUME_DOWN,
                        AndroidAutoVolumeContent.ACTION_VOLUME_UP
                ),
                AndroidAutoVolumeContent.playbackCustomActionIds(
                        AndroidAutoVolumeContent.ControlBarMode.VOLUME,
                        external,
                        PlaybackStateCompat.STATE_PAUSED
                )
        );
    }

    @Test
    public void trackModeUsesOnlyTheSourceSupportedStandardSkips() {
        long both = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        assertEquals(
                both | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID,
                AndroidAutoVolumeContent.playbackUiTransportActions(
                        both,
                        PlaybackStateCompat.STATE_PAUSED,
                        AndroidAutoVolumeContent.ControlBarMode.TRACK
                )
        );
        assertTrue(AndroidAutoVolumeContent.playbackCustomActionIds(
                AndroidAutoVolumeContent.ControlBarMode.TRACK,
                both,
                PlaybackStateCompat.STATE_PAUSED
        ).isEmpty());

        long nextOnly = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        assertEquals(
                nextOnly | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID,
                AndroidAutoVolumeContent.playbackUiTransportActions(
                        nextOnly,
                        PlaybackStateCompat.STATE_PAUSED,
                        AndroidAutoVolumeContent.ControlBarMode.TRACK
                )
        );
    }

    @Test
    public void combinedPlayPauseIsExpandedForTheCurrentCarUiState() {
        long combined = PlaybackStateCompat.ACTION_PLAY_PAUSE;

        assertEquals(
                PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID,
                AndroidAutoVolumeContent.playbackUiTransportActions(
                        combined,
                        PlaybackStateCompat.STATE_PLAYING,
                        AndroidAutoVolumeContent.ControlBarMode.HUB
                )
        );
        assertEquals(
                PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID,
                AndroidAutoVolumeContent.playbackUiTransportActions(
                        combined,
                        PlaybackStateCompat.STATE_PAUSED,
                        AndroidAutoVolumeContent.ControlBarMode.HUB
                )
        );
    }

    @Test
    public void armedRelayStopsOnAnyGateLossOrStoppedState() {
        assertTrue(AndroidAutoVolumeContent.shouldRemainArmed(
                true,
                true,
                true,
                PlaybackStateCompat.STATE_PLAYING
        ));
        assertTrue(AndroidAutoVolumeContent.shouldRemainArmed(
                true,
                true,
                true,
                PlaybackStateCompat.STATE_PAUSED
        ));
        assertFalse(AndroidAutoVolumeContent.shouldRemainArmed(
                false,
                true,
                true,
                PlaybackStateCompat.STATE_PLAYING
        ));
        assertFalse(AndroidAutoVolumeContent.shouldRemainArmed(
                true,
                false,
                true,
                PlaybackStateCompat.STATE_PLAYING
        ));
        assertFalse(AndroidAutoVolumeContent.shouldRemainArmed(
                true,
                true,
                false,
                PlaybackStateCompat.STATE_PLAYING
        ));
        assertFalse(AndroidAutoVolumeContent.shouldRemainArmed(
                true,
                true,
                true,
                PlaybackStateCompat.STATE_NONE
        ));
        assertFalse(AndroidAutoVolumeContent.shouldRemainArmed(
                true,
                true,
                true,
                PlaybackStateCompat.STATE_STOPPED
        ));
        assertFalse(AndroidAutoVolumeContent.shouldRemainArmed(
                true,
                true,
                true,
                PlaybackStateCompat.STATE_ERROR
        ));
    }

    @Test
    public void duplicateArmIsIgnoredOnlyForSameStillValidSource() {
        assertTrue(AndroidAutoVolumeContent.isDuplicateValidArm(
                true,
                7L,
                7L,
                true,
                true,
                true,
                PlaybackStateCompat.STATE_PLAYING
        ));
        assertFalse(AndroidAutoVolumeContent.isDuplicateValidArm(
                true,
                7L,
                8L,
                true,
                true,
                true,
                PlaybackStateCompat.STATE_PLAYING
        ));
        assertFalse(AndroidAutoVolumeContent.isDuplicateValidArm(
                true,
                7L,
                7L,
                false,
                true,
                true,
                PlaybackStateCompat.STATE_PLAYING
        ));
        assertFalse(AndroidAutoVolumeContent.isDuplicateValidArm(
                false,
                7L,
                7L,
                true,
                true,
                true,
                PlaybackStateCompat.STATE_PLAYING
        ));
    }
}

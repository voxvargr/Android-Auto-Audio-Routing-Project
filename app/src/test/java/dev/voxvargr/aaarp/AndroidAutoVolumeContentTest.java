package dev.voxvargr.aaarp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.AudioManager;

import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public final class AndroidAutoVolumeContentTest {
    @Test
    public void volumeActionsRequireTwoHostSlots() {
        assertFalse(AndroidAutoVolumeContent.supportsVolumeActions(-1));
        assertFalse(AndroidAutoVolumeContent.supportsVolumeActions(0));
        assertFalse(AndroidAutoVolumeContent.supportsVolumeActions(1));
        assertTrue(AndroidAutoVolumeContent.supportsVolumeActions(2));
        assertTrue(AndroidAutoVolumeContent.supportsVolumeActions(4));
    }

    @Test
    public void actionListsArePerCapabilityAndRemainOrdered() {
        assertEquals(
                Arrays.asList(
                        AndroidAutoVolumeContent.ACTION_VOLUME_DOWN,
                        AndroidAutoVolumeContent.ACTION_VOLUME_UP
                ),
                AndroidAutoVolumeContent.actionIdsForLimit(2)
        );
        assertEquals(Collections.emptyList(), AndroidAutoVolumeContent.actionIdsForLimit(0));
        assertEquals(
                Arrays.asList(
                        AndroidAutoVolumeContent.ACTION_VOLUME_DOWN,
                        AndroidAutoVolumeContent.ACTION_VOLUME_UP
                ),
                AndroidAutoVolumeContent.actionIdsForLimit(2)
        );
    }

    @Test
    public void infoItemIsBrowsableAndNeverPlayable() {
        assertTrue((AndroidAutoVolumeContent.INFO_ITEM_FLAGS
                & MediaBrowserCompat.MediaItem.FLAG_BROWSABLE) != 0);
        assertEquals(
                0,
                AndroidAutoVolumeContent.INFO_ITEM_FLAGS
                        & MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        );
    }

    @Test
    public void actionsMapOnlyToRaiseAndLower() {
        assertEquals(
                AudioManager.ADJUST_LOWER,
                AndroidAutoVolumeContent.directionForAction(
                        AndroidAutoVolumeContent.ACTION_VOLUME_DOWN
                )
        );
        assertEquals(
                AudioManager.ADJUST_RAISE,
                AndroidAutoVolumeContent.directionForAction(
                        AndroidAutoVolumeContent.ACTION_VOLUME_UP
                )
        );
        assertEquals(
                AudioManager.ADJUST_SAME,
                AndroidAutoVolumeContent.directionForAction("unknown")
        );
    }

    @Test
    public void preArmSessionAdvertisesBothAndroidAutoPlayEntryPoints() {
        long actions = AndroidAutoVolumeContent.preArmTransportActions();

        assertTrue((actions & PlaybackStateCompat.ACTION_PLAY) != 0L);
        assertTrue((actions & PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID) != 0L);
        assertEquals(
                PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID,
                actions
        );
    }

    @Test
    public void onlyAdvertisedRootAndInfoIdsAreAccepted() {
        assertTrue(AndroidAutoVolumeContent.isExpectedMediaItemId(
                AndroidAutoVolumeContent.ROOT_ID
        ));
        assertTrue(AndroidAutoVolumeContent.isExpectedMediaItemId(
                AndroidAutoVolumeContent.INFO_ID
        ));
        assertFalse(AndroidAutoVolumeContent.isExpectedMediaItemId("other"));
        assertFalse(AndroidAutoVolumeContent.isExpectedMediaItemId(null));
    }
}

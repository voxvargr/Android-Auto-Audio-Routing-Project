package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;

import android.media.AudioManager;

import org.junit.Test;

public final class AndroidAutoVolumeShortcutReceiverTest {
    @Test
    public void downActionMapsToOneLowerStep() {
        assertEquals(
                AudioManager.ADJUST_LOWER,
                AndroidAutoVolumeShortcutReceiver.directionForAction(
                        AndroidAutoVolumeShortcutReceiver.ACTION_VOLUME_DOWN
                )
        );
    }

    @Test
    public void upActionMapsToOneRaiseStep() {
        assertEquals(
                AudioManager.ADJUST_RAISE,
                AndroidAutoVolumeShortcutReceiver.directionForAction(
                        AndroidAutoVolumeShortcutReceiver.ACTION_VOLUME_UP
                )
        );
    }

    @Test
    public void unknownActionIsRejected() {
        assertEquals(
                AudioManager.ADJUST_SAME,
                AndroidAutoVolumeShortcutReceiver.directionForAction("unexpected")
        );
        assertEquals(
                AudioManager.ADJUST_SAME,
                AndroidAutoVolumeShortcutReceiver.directionForAction(null)
        );
    }
}

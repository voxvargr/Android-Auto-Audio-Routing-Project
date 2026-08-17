package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class AndroidAutoControlBarTest {
    @Test
    public void submenuInteractionKeepsOneResetAndTimeoutReturnsToHub() {
        FakeScheduler scheduler = new FakeScheduler();
        List<AndroidAutoVolumeContent.ControlBarMode> changes = new ArrayList<>();
        AndroidAutoControlBar bar = new AndroidAutoControlBar(
                scheduler,
                (mode, revision, reason) -> changes.add(mode),
                100L
        );

        assertTrue(bar.select(AndroidAutoVolumeContent.ControlBarMode.VOLUME, "volume_menu"));
        long revision = bar.revision();
        assertEquals(AndroidAutoVolumeContent.ControlBarMode.VOLUME, bar.mode());
        assertEquals(1, scheduler.posts);
        assertTrue(bar.isCurrent(AndroidAutoVolumeContent.ControlBarMode.VOLUME, revision));

        bar.touch(AndroidAutoVolumeContent.ControlBarMode.VOLUME);
        assertEquals(2, scheduler.posts);
        assertEquals(1, scheduler.pendingCount());
        assertEquals(revision, bar.revision());

        scheduler.runPending();
        assertEquals(AndroidAutoVolumeContent.ControlBarMode.HUB, bar.mode());
        assertFalse(bar.isCurrent(AndroidAutoVolumeContent.ControlBarMode.VOLUME, revision));
        assertEquals(0, scheduler.pendingCount());
        assertEquals(2, changes.size());
        assertEquals(AndroidAutoVolumeContent.ControlBarMode.VOLUME, changes.get(0));
        assertEquals(AndroidAutoVolumeContent.ControlBarMode.HUB, changes.get(1));
    }

    @Test
    public void idempotentSelectionOnlyRefreshesTheTimeout() {
        FakeScheduler scheduler = new FakeScheduler();
        List<Long> revisions = new ArrayList<>();
        AndroidAutoControlBar bar = new AndroidAutoControlBar(
                scheduler,
                (mode, revision, reason) -> revisions.add(revision),
                100L
        );

        assertTrue(bar.select(AndroidAutoVolumeContent.ControlBarMode.TRACK, "track_menu"));
        long revision = bar.revision();
        assertFalse(bar.select(AndroidAutoVolumeContent.ControlBarMode.TRACK, "track_again"));

        assertEquals(revision, bar.revision());
        assertEquals(1, revisions.size());
        assertEquals(1, scheduler.pendingCount());
        assertEquals(2, scheduler.posts);
    }

    @Test
    public void changingModesInvalidatesQueuedWorkAndCloseCancelsReset() {
        FakeScheduler scheduler = new FakeScheduler();
        AndroidAutoControlBar bar = new AndroidAutoControlBar(
                scheduler,
                (mode, revision, reason) -> { },
                100L
        );

        bar.select(AndroidAutoVolumeContent.ControlBarMode.VOLUME, "volume_menu");
        long volumeRevision = bar.revision();
        bar.select(AndroidAutoVolumeContent.ControlBarMode.TRACK, "track_menu");
        assertFalse(bar.isCurrent(
                AndroidAutoVolumeContent.ControlBarMode.VOLUME,
                volumeRevision
        ));
        assertEquals(1, scheduler.pendingCount());

        bar.close();
        assertEquals(AndroidAutoVolumeContent.ControlBarMode.HUB, bar.mode());
        assertEquals(0, scheduler.pendingCount());
        assertFalse(bar.select(AndroidAutoVolumeContent.ControlBarMode.VOLUME, "after_close"));
        assertNull(scheduler.pending);
    }

    private static final class FakeScheduler implements AndroidAutoControlBar.Scheduler {
        private Runnable pending;
        private int posts;

        @Override
        public void postDelayed(Runnable action, long delayMs) {
            pending = action;
            posts++;
        }

        @Override
        public void removeCallbacks(Runnable action) {
            if (pending == action) {
                pending = null;
            }
        }

        private int pendingCount() {
            return pending == null ? 0 : 1;
        }

        private void runPending() {
            Runnable action = pending;
            pending = null;
            if (action != null) {
                action.run();
            }
        }
    }
}

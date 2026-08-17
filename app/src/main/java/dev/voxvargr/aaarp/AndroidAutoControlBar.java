package dev.voxvargr.aaarp;

/** Process-local state for the two side buttons around Android Auto's Play/Pause control. */
final class AndroidAutoControlBar {
    static final long INACTIVITY_TIMEOUT_MS = 10_000L;

    interface Scheduler {
        void postDelayed(Runnable action, long delayMs);

        void removeCallbacks(Runnable action);
    }

    interface Listener {
        void onModeChanged(
                AndroidAutoVolumeContent.ControlBarMode mode,
                long revision,
                String reason
        );
    }

    private final Scheduler scheduler;
    private final Listener listener;
    private final long timeoutMs;
    private final Runnable timeoutAction = () -> reset("inactivity_timeout");

    private volatile AndroidAutoVolumeContent.ControlBarMode mode =
            AndroidAutoVolumeContent.ControlBarMode.HUB;
    private volatile long revision;
    private volatile boolean closed;

    AndroidAutoControlBar(Scheduler scheduler, Listener listener) {
        this(scheduler, listener, INACTIVITY_TIMEOUT_MS);
    }

    AndroidAutoControlBar(Scheduler scheduler, Listener listener, long timeoutMs) {
        if (scheduler == null || listener == null || timeoutMs <= 0L) {
            throw new IllegalArgumentException("scheduler, listener, and timeout are required");
        }
        this.scheduler = scheduler;
        this.listener = listener;
        this.timeoutMs = timeoutMs;
    }

    AndroidAutoVolumeContent.ControlBarMode mode() {
        return mode;
    }

    long revision() {
        return revision;
    }

    boolean select(AndroidAutoVolumeContent.ControlBarMode requestedMode, String reason) {
        if (closed || requestedMode == null) {
            return false;
        }
        if (mode == requestedMode) {
            rescheduleIfNeeded();
            return false;
        }
        scheduler.removeCallbacks(timeoutAction);
        mode = requestedMode;
        revision++;
        rescheduleIfNeeded();
        listener.onModeChanged(mode, revision, reason);
        return true;
    }

    void touch(AndroidAutoVolumeContent.ControlBarMode expectedMode) {
        if (closed || expectedMode == AndroidAutoVolumeContent.ControlBarMode.HUB) {
            return;
        }
        if (mode == expectedMode) {
            rescheduleIfNeeded();
        }
    }

    boolean isCurrent(AndroidAutoVolumeContent.ControlBarMode expectedMode, long expectedRevision) {
        return !closed && mode == expectedMode && revision == expectedRevision;
    }

    void reset(String reason) {
        select(AndroidAutoVolumeContent.ControlBarMode.HUB, reason);
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        scheduler.removeCallbacks(timeoutAction);
        mode = AndroidAutoVolumeContent.ControlBarMode.HUB;
        revision++;
    }

    private void rescheduleIfNeeded() {
        scheduler.removeCallbacks(timeoutAction);
        if (mode != AndroidAutoVolumeContent.ControlBarMode.HUB) {
            scheduler.postDelayed(timeoutAction, timeoutMs);
        }
    }
}

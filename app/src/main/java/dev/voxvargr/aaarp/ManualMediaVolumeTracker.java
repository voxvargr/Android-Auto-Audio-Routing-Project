package dev.voxvargr.aaarp;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local handoff from an explicit user volume step to the routing monitor.
 *
 * <p>The generation deliberately is not persisted. A newly created monitor snapshots the current
 * value and therefore reacts only to manual changes made during that service instance.</p>
 */
final class ManualMediaVolumeTracker {
    private static final AtomicLong GENERATION = new AtomicLong();

    private ManualMediaVolumeTracker() {
    }

    static long generation() {
        return GENERATION.get();
    }

    static long markSuccessfulStep() {
        return GENERATION.incrementAndGet();
    }

    static boolean changedSince(long observedGeneration, long currentGeneration) {
        return observedGeneration != currentGeneration;
    }
}

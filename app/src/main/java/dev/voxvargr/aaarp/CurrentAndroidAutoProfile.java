package dev.voxvargr.aaarp;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-local handoff from the routing monitor's detected connection to Android Auto surfaces.
 * A null value means no connection-specific profile is currently known.
 */
final class CurrentAndroidAutoProfile {
    private static final AtomicReference<String> DETECTED_PROFILE_ID = new AtomicReference<>();
    private static final CopyOnWriteArraySet<Listener> LISTENERS =
            new CopyOnWriteArraySet<>();

    private CurrentAndroidAutoProfile() {
    }

    static void connected(String profileId) {
        String resolved = profileId == null || profileId.length() == 0
                ? ProfileSettings.DEFAULT_PROFILE_ID
                : profileId;
        String previous = DETECTED_PROFILE_ID.getAndSet(resolved);
        if (Objects.equals(previous, resolved)) {
            return;
        }
        notifyListeners(resolved);
    }

    static void disconnected() {
        String previous = DETECTED_PROFILE_ID.getAndSet(null);
        if (previous != null) {
            notifyListeners(null);
        }
    }

    static String detectedProfileId() {
        return DETECTED_PROFILE_ID.get();
    }

    static String detectedOrDefaultProfileId() {
        String detected = detectedProfileId();
        return detected == null ? ProfileSettings.DEFAULT_PROFILE_ID : detected;
    }

    static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    static void removeListener(Listener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    private static void notifyListeners(String profileId) {
        for (Listener listener : LISTENERS) {
            try {
                listener.onAndroidAutoProfileChanged(profileId);
            } catch (RuntimeException ignored) {
                // One process-local observer must not block the routing monitor.
            }
        }
    }

    interface Listener {
        void onAndroidAutoProfileChanged(String profileId);
    }
}

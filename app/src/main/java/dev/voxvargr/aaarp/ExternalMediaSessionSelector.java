package dev.voxvargr.aaarp;

import java.util.List;

/** Pure selection policy shared by the Android media-session repository and JVM tests. */
final class ExternalMediaSessionSelector {
    static final int NONE = -1;
    static final int ACTIVITY_OTHER = 0;
    static final int ACTIVITY_PAUSED_WITH_METADATA = 1;
    static final int ACTIVITY_PLAYING_OR_BUFFERING = 2;
    static final int ACTIVITY_TRANSIENT = 3;

    private ExternalMediaSessionSelector() {
    }

    static int select(List<Candidate> candidates) {
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            if (candidate.external
                    && candidate.mediaKeyTarget
                    && (candidate.activity == ACTIVITY_PLAYING_OR_BUFFERING
                    || candidate.activity == ACTIVITY_PAUSED_WITH_METADATA
                    || candidate.activity == ACTIVITY_TRANSIENT)) {
                return index;
            }
        }

        int stickyActive = firstMatching(
                candidates,
                true,
                ACTIVITY_PLAYING_OR_BUFFERING
        );
        if (stickyActive != NONE) {
            return stickyActive;
        }

        int firstActive = firstMatching(
                candidates,
                false,
                ACTIVITY_PLAYING_OR_BUFFERING
        );
        if (firstActive != NONE) {
            return firstActive;
        }

        int stickyTransient = firstMatching(candidates, true, ACTIVITY_TRANSIENT);
        if (stickyTransient != NONE) {
            return stickyTransient;
        }

        int stickyPaused = firstMatching(
                candidates,
                true,
                ACTIVITY_PAUSED_WITH_METADATA
        );
        if (stickyPaused != NONE) {
            return stickyPaused;
        }

        return firstMatching(candidates, false, ACTIVITY_PAUSED_WITH_METADATA);
    }

    static boolean isCurrentGeneration(long callbackGeneration, long currentGeneration) {
        return callbackGeneration == currentGeneration;
    }

    private static int firstMatching(
            List<Candidate> candidates,
            boolean requireSticky,
            int requiredActivity
    ) {
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            if (!candidate.external || candidate.activity != requiredActivity) {
                continue;
            }
            if (!requireSticky || candidate.sticky) {
                return index;
            }
        }
        return NONE;
    }

    static final class Candidate {
        final boolean external;
        final boolean sticky;
        final boolean mediaKeyTarget;
        final int activity;

        Candidate(
                boolean external,
                boolean sticky,
                boolean mediaKeyTarget,
                int activity
        ) {
            this.external = external;
            this.sticky = sticky;
            this.mediaKeyTarget = mediaKeyTarget;
            this.activity = activity;
        }
    }
}

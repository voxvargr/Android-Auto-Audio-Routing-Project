package dev.voxvargr.aaarp;

import android.media.AudioManager;
import android.os.Build;

import java.util.Objects;

/** Performs one user-requested adjustment of the phone media-volume index. */
final class MusicVolumeStepper {
    static final int UNKNOWN_VOLUME = -1;

    private final AudioGateway audio;
    private final RootAdjustmentGateway rootAdjustment;

    MusicVolumeStepper(AudioManager audioManager) {
        this(new AndroidAudioGateway(audioManager), null);
    }

    MusicVolumeStepper(AudioManager audioManager, RootAdjustmentGateway rootAdjustment) {
        this(new AndroidAudioGateway(audioManager), rootAdjustment);
    }

    MusicVolumeStepper(AudioGateway audio) {
        this(audio, null);
    }

    MusicVolumeStepper(AudioGateway audio, RootAdjustmentGateway rootAdjustment) {
        this.audio = Objects.requireNonNull(audio, "audio");
        this.rootAdjustment = rootAdjustment;
    }

    Result step(int direction) {
        return step(direction, false);
    }

    Result step(int direction, boolean useRoot) {
        AdjustmentPath adjustmentPath = useRoot ? AdjustmentPath.ROOT : AdjustmentPath.PUBLIC;
        if (direction != AudioManager.ADJUST_LOWER
                && direction != AudioManager.ADJUST_RAISE) {
            return new Result(
                    direction,
                    UNKNOWN_VOLUME,
                    UNKNOWN_VOLUME,
                    UNKNOWN_VOLUME,
                    UNKNOWN_VOLUME,
                    Outcome.INVALID_ACTION,
                    adjustmentPath
            );
        }

        int min = UNKNOWN_VOLUME;
        int max = UNKNOWN_VOLUME;
        int before = UNKNOWN_VOLUME;
        int after = UNKNOWN_VOLUME;
        boolean adjustmentDispatched = false;
        try {
            min = audio.sdkInt() >= Build.VERSION_CODES.P
                    ? audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
                    : 0;
            max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            before = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            after = before;

            if (audio.isVolumeFixed()) {
                return new Result(
                        direction,
                        before,
                        after,
                        min,
                        max,
                        Outcome.FIXED_VOLUME,
                        adjustmentPath
                );
            }
            if (direction == AudioManager.ADJUST_LOWER && before <= min) {
                return new Result(
                        direction,
                        before,
                        after,
                        min,
                        max,
                        Outcome.AT_MINIMUM,
                        adjustmentPath
                );
            }
            if (direction == AudioManager.ADJUST_RAISE && before >= max) {
                return new Result(
                        direction,
                        before,
                        after,
                        min,
                        max,
                        Outcome.AT_MAXIMUM,
                        adjustmentPath
                );
            }

            after = UNKNOWN_VOLUME;
            if (useRoot) {
                if (rootAdjustment == null) {
                    throw new IllegalStateException("Root music-volume adjustment is unavailable");
                }
                // Once the root command starts, a timeout or non-zero exit cannot prove that
                // AudioService did not already apply it. Treat the attempt as dispatched and
                // use the stream readback below (or in the catch path) as the source of truth.
                adjustmentDispatched = true;
                rootAdjustment.adjustMusicVolume(direction);
            } else {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
                adjustmentDispatched = true;
            }
            after = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean movedInRequestedDirection = direction == AudioManager.ADJUST_LOWER
                    ? after < before
                    : after > before;
            Outcome outcome = movedInRequestedDirection ? Outcome.CHANGED : Outcome.NO_EFFECT;
            return new Result(
                    direction,
                    before,
                    after,
                    min,
                    max,
                    outcome,
                    adjustmentPath,
                    true,
                    ""
            );
        } catch (RuntimeException error) {
            if (useRoot && adjustmentDispatched && before != UNKNOWN_VOLUME) {
                try {
                    after = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                    boolean movedInRequestedDirection = direction == AudioManager.ADJUST_LOWER
                            ? after < before
                            : after > before;
                    if (movedInRequestedDirection) {
                        return new Result(
                                direction,
                                before,
                                after,
                                min,
                                max,
                                Outcome.CHANGED,
                                adjustmentPath,
                                true,
                                error.getClass().getSimpleName()
                        );
                    }
                } catch (RuntimeException readbackError) {
                    after = UNKNOWN_VOLUME;
                    return new Result(
                            direction,
                            before,
                            after,
                            min,
                            max,
                            Outcome.ERROR,
                            adjustmentPath,
                            true,
                            error.getClass().getSimpleName()
                                    + "+" + readbackError.getClass().getSimpleName()
                    );
                }
            }
            return new Result(
                    direction,
                    before,
                    after,
                    min,
                    max,
                    Outcome.ERROR,
                    adjustmentPath,
                    adjustmentDispatched,
                    error.getClass().getSimpleName()
            );
        }
    }

    enum Outcome {
        CHANGED,
        AT_MINIMUM,
        AT_MAXIMUM,
        FIXED_VOLUME,
        NO_EFFECT,
        INVALID_ACTION,
        ERROR
    }

    enum AdjustmentPath {
        PUBLIC("public"),
        ROOT("root");

        private final String logName;

        AdjustmentPath(String logName) {
            this.logName = logName;
        }
    }

    interface AudioGateway {
        int sdkInt();

        int getStreamMinVolume(int streamType);

        int getStreamMaxVolume(int streamType);

        int getStreamVolume(int streamType);

        boolean isVolumeFixed();

        void adjustStreamVolume(int streamType, int direction, int flags);
    }

    interface RootAdjustmentGateway {
        void adjustMusicVolume(int direction);
    }

    static final class Result {
        private final int direction;
        private final int before;
        private final int after;
        private final int min;
        private final int max;
        private final Outcome outcome;
        private final AdjustmentPath adjustmentPath;
        private final boolean adjustmentDispatched;
        private final String errorType;

        private Result(
                int direction,
                int before,
                int after,
                int min,
                int max,
                Outcome outcome,
                AdjustmentPath adjustmentPath
        ) {
            this(direction, before, after, min, max, outcome, adjustmentPath, false, "");
        }

        private Result(
                int direction,
                int before,
                int after,
                int min,
                int max,
                Outcome outcome,
                AdjustmentPath adjustmentPath,
                boolean adjustmentDispatched,
                String errorType
        ) {
            this.direction = direction;
            this.before = before;
            this.after = after;
            this.min = min;
            this.max = max;
            this.outcome = outcome;
            this.adjustmentPath = adjustmentPath;
            this.adjustmentDispatched = adjustmentDispatched;
            this.errorType = errorType;
        }

        int direction() {
            return direction;
        }

        int before() {
            return before;
        }

        int after() {
            return after;
        }

        int min() {
            return min;
        }

        int max() {
            return max;
        }

        Outcome outcome() {
            return outcome;
        }

        AdjustmentPath adjustmentPath() {
            return adjustmentPath;
        }

        boolean changed() {
            return outcome == Outcome.CHANGED;
        }

        boolean shouldProtectManualIntent() {
            return changed() || (outcome == Outcome.ERROR && adjustmentDispatched);
        }

        String logFields() {
            String fields = "direction=" + direction
                    + " before=" + before
                    + " after=" + after
                    + " min=" + min
                    + " max=" + max
                    + " outcome=" + outcome.name()
                    + " path=" + adjustmentPath.logName
                    + " dispatched=" + adjustmentDispatched;
            return errorType.length() == 0 ? fields : fields + " error=" + errorType;
        }
    }

    private static final class AndroidAudioGateway implements AudioGateway {
        private final AudioManager audioManager;

        private AndroidAudioGateway(AudioManager audioManager) {
            this.audioManager = Objects.requireNonNull(audioManager, "audioManager");
        }

        @Override
        public int sdkInt() {
            return Build.VERSION.SDK_INT;
        }

        @Override
        public int getStreamMinVolume(int streamType) {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? audioManager.getStreamMinVolume(streamType)
                    : 0;
        }

        @Override
        public int getStreamMaxVolume(int streamType) {
            return audioManager.getStreamMaxVolume(streamType);
        }

        @Override
        public int getStreamVolume(int streamType) {
            return audioManager.getStreamVolume(streamType);
        }

        @Override
        public boolean isVolumeFixed() {
            return audioManager.isVolumeFixed();
        }

        @Override
        public void adjustStreamVolume(int streamType, int direction, int flags) {
            audioManager.adjustStreamVolume(streamType, direction, flags);
        }
    }
}

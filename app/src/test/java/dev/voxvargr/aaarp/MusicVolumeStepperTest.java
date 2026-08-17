package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.media.AudioManager;

import org.junit.Test;

public final class MusicVolumeStepperTest {
    @Test
    public void lower_changed_usesOneMusicAdjustmentWithNoFlags() {
        FakeAudioGateway audio = new FakeAudioGateway(27, 3, 15, 8, 7);

        MusicVolumeStepper.Result result = new MusicVolumeStepper(audio)
                .step(AudioManager.ADJUST_LOWER);

        assertEquals(MusicVolumeStepper.Outcome.CHANGED, result.outcome());
        assertEquals(AudioManager.ADJUST_LOWER, result.direction());
        assertEquals(8, result.before());
        assertEquals(7, result.after());
        assertEquals(0, result.min());
        assertEquals(15, result.max());
        assertTrue(result.changed());
        assertEquals(MusicVolumeStepper.AdjustmentPath.PUBLIC, result.adjustmentPath());
        assertEquals(0, audio.minReadCount);
        assertSingleAdjustment(audio, AudioManager.ADJUST_LOWER);
    }

    @Test
    public void raise_changed_readsPlatformMinimumOnApi28AndAdjustsExactlyOnce() {
        FakeAudioGateway audio = new FakeAudioGateway(28, 2, 15, 8, 9);

        MusicVolumeStepper.Result result = new MusicVolumeStepper(audio)
                .step(AudioManager.ADJUST_RAISE);

        assertEquals(MusicVolumeStepper.Outcome.CHANGED, result.outcome());
        assertEquals(2, result.min());
        assertEquals(1, audio.minReadCount);
        assertSingleAdjustment(audio, AudioManager.ADJUST_RAISE);
    }

    @Test
    public void lower_atMinimum_doesNotAdjust() {
        FakeAudioGateway audio = new FakeAudioGateway(36, 2, 15, 2, 2);

        MusicVolumeStepper.Result result = new MusicVolumeStepper(audio)
                .step(AudioManager.ADJUST_LOWER);

        assertEquals(MusicVolumeStepper.Outcome.AT_MINIMUM, result.outcome());
        assertEquals(2, result.before());
        assertEquals(2, result.after());
        assertFalse(result.changed());
        assertEquals(0, audio.adjustCallCount);
    }

    @Test
    public void raise_atMaximum_doesNotAdjust() {
        FakeAudioGateway audio = new FakeAudioGateway(36, 0, 15, 15, 15);

        MusicVolumeStepper.Result result = new MusicVolumeStepper(audio)
                .step(AudioManager.ADJUST_RAISE);

        assertEquals(MusicVolumeStepper.Outcome.AT_MAXIMUM, result.outcome());
        assertEquals(0, audio.adjustCallCount);
    }

    @Test
    public void fixedVolume_doesNotAdjustEvenAtBoundary() {
        FakeAudioGateway audio = new FakeAudioGateway(36, 0, 15, 15, 15);
        audio.fixed = true;

        MusicVolumeStepper.Result result = new MusicVolumeStepper(audio)
                .step(AudioManager.ADJUST_RAISE);

        assertEquals(MusicVolumeStepper.Outcome.FIXED_VOLUME, result.outcome());
        assertEquals(0, audio.adjustCallCount);
    }

    @Test
    public void unchangedReadback_isNoEffectAfterExactlyOneAdjustment() {
        FakeAudioGateway audio = new FakeAudioGateway(36, 0, 15, 8, 8);

        MusicVolumeStepper.Result result = new MusicVolumeStepper(audio)
                .step(AudioManager.ADJUST_RAISE);

        assertEquals(MusicVolumeStepper.Outcome.NO_EFFECT, result.outcome());
        assertFalse(result.changed());
        assertSingleAdjustment(audio, AudioManager.ADJUST_RAISE);
    }

    @Test
    public void unsupportedDirection_isInvalidWithoutReadingOrAdjustingAudio() {
        FakeAudioGateway audio = new FakeAudioGateway(36, 0, 15, 8, 8);

        MusicVolumeStepper.Result result = new MusicVolumeStepper(audio)
                .step(AudioManager.ADJUST_SAME);

        assertEquals(MusicVolumeStepper.Outcome.INVALID_ACTION, result.outcome());
        assertEquals(AudioManager.ADJUST_SAME, result.direction());
        assertEquals(MusicVolumeStepper.UNKNOWN_VOLUME, result.before());
        assertEquals(MusicVolumeStepper.UNKNOWN_VOLUME, result.after());
        assertEquals(MusicVolumeStepper.UNKNOWN_VOLUME, result.min());
        assertEquals(MusicVolumeStepper.UNKNOWN_VOLUME, result.max());
        assertEquals(0, audio.totalReadCount());
        assertEquals(0, audio.adjustCallCount);
    }

    @Test
    public void gatewayFailure_isErrorWithDeterministicKnownFields() {
        FakeAudioGateway audio = new FakeAudioGateway(36, 0, 15, 8, 9);
        audio.throwOnAdjust = true;

        MusicVolumeStepper.Result result = new MusicVolumeStepper(audio)
                .step(AudioManager.ADJUST_RAISE);

        assertEquals(MusicVolumeStepper.Outcome.ERROR, result.outcome());
        assertEquals(8, result.before());
        assertEquals(MusicVolumeStepper.UNKNOWN_VOLUME, result.after());
        assertEquals(
                "direction=1 before=8 after=-1 min=0 max=15 outcome=ERROR"
                        + " path=public dispatched=false"
                        + " error=IllegalStateException",
                result.logFields()
        );
        assertSingleAdjustment(audio, AudioManager.ADJUST_RAISE);
        assertFalse(result.shouldProtectManualIntent());
    }

    @Test
    public void readbackFailure_isErrorAndProtectsPossiblyAppliedManualStep() {
        FakeAudioGateway audio = new FakeAudioGateway(36, 0, 15, 8, 9);
        audio.throwOnReadback = true;

        MusicVolumeStepper.Result result = new MusicVolumeStepper(audio)
                .step(AudioManager.ADJUST_RAISE);

        assertEquals(MusicVolumeStepper.Outcome.ERROR, result.outcome());
        assertEquals(MusicVolumeStepper.UNKNOWN_VOLUME, result.after());
        assertTrue(result.shouldProtectManualIntent());
        assertSingleAdjustment(audio, AudioManager.ADJUST_RAISE);
    }

    @Test
    public void oppositeConcurrentMovement_isNotClaimedAsRequestedChange() {
        FakeAudioGateway audio = new FakeAudioGateway(36, 0, 15, 8, 9);

        MusicVolumeStepper.Result result = new MusicVolumeStepper(audio)
                .step(AudioManager.ADJUST_LOWER);

        assertEquals(MusicVolumeStepper.Outcome.NO_EFFECT, result.outcome());
        assertFalse(result.changed());
        assertFalse(result.shouldProtectManualIntent());
        assertSingleAdjustment(audio, AudioManager.ADJUST_LOWER);
    }

    @Test
    public void rootRaise_changed_usesOneRootCommandAndNoPublicAdjustment() {
        FakeAudioGateway audio = new FakeAudioGateway(36, 0, 15, 8, 9);
        FakeRootAdjustmentGateway root = new FakeRootAdjustmentGateway();

        MusicVolumeStepper.Result result = new MusicVolumeStepper(audio, root)
                .step(AudioManager.ADJUST_RAISE, true);

        assertEquals(MusicVolumeStepper.Outcome.CHANGED, result.outcome());
        assertEquals(MusicVolumeStepper.AdjustmentPath.ROOT, result.adjustmentPath());
        assertEquals(8, result.before());
        assertEquals(9, result.after());
        assertEquals(0, audio.adjustCallCount);
        assertSingleRootAdjustment(root, AudioManager.ADJUST_RAISE);
        assertTrue(result.logFields().contains("path=root dispatched=true"));
    }

    @Test
    public void rootFailure_doesNotFallBackAndProtectsUncertainManualIntent() {
        FakeAudioGateway audio = new FakeAudioGateway(36, 0, 15, 8, 8);
        FakeRootAdjustmentGateway root = new FakeRootAdjustmentGateway();
        root.throwOnAdjust = true;

        MusicVolumeStepper.Result result = new MusicVolumeStepper(audio, root)
                .step(AudioManager.ADJUST_LOWER, true);

        assertEquals(MusicVolumeStepper.Outcome.ERROR, result.outcome());
        assertEquals(MusicVolumeStepper.AdjustmentPath.ROOT, result.adjustmentPath());
        assertEquals(0, audio.adjustCallCount);
        assertSingleRootAdjustment(root, AudioManager.ADJUST_LOWER);
        assertTrue(result.shouldProtectManualIntent());
        assertTrue(result.logFields().contains("path=root dispatched=true"));
    }

    @Test
    public void rootFailureAfterMutation_readbackStillReportsChanged() {
        FakeAudioGateway audio = new FakeAudioGateway(36, 0, 15, 8, 7);
        FakeRootAdjustmentGateway root = new FakeRootAdjustmentGateway();
        root.throwOnAdjust = true;

        MusicVolumeStepper.Result result = new MusicVolumeStepper(audio, root)
                .step(AudioManager.ADJUST_LOWER, true);

        assertEquals(MusicVolumeStepper.Outcome.CHANGED, result.outcome());
        assertEquals(8, result.before());
        assertEquals(7, result.after());
        assertEquals(0, audio.adjustCallCount);
        assertSingleRootAdjustment(root, AudioManager.ADJUST_LOWER);
        assertTrue(result.shouldProtectManualIntent());
        assertTrue(result.logFields().contains("path=root dispatched=true"));
    }

    @Test
    public void rootReadbackFailure_preservesPossiblyAppliedManualIntent() {
        FakeAudioGateway audio = new FakeAudioGateway(36, 0, 15, 8, 9);
        audio.throwOnReadback = true;
        FakeRootAdjustmentGateway root = new FakeRootAdjustmentGateway();

        MusicVolumeStepper.Result result = new MusicVolumeStepper(audio, root)
                .step(AudioManager.ADJUST_RAISE, true);

        assertEquals(MusicVolumeStepper.Outcome.ERROR, result.outcome());
        assertEquals(0, audio.adjustCallCount);
        assertSingleRootAdjustment(root, AudioManager.ADJUST_RAISE);
        assertTrue(result.shouldProtectManualIntent());
        assertTrue(result.logFields().contains("path=root dispatched=true"));
    }

    @Test
    public void rootBoundary_doesNotDispatchEitherAdjustmentPath() {
        FakeAudioGateway audio = new FakeAudioGateway(36, 0, 15, 15, 15);
        FakeRootAdjustmentGateway root = new FakeRootAdjustmentGateway();

        MusicVolumeStepper.Result result = new MusicVolumeStepper(audio, root)
                .step(AudioManager.ADJUST_RAISE, true);

        assertEquals(MusicVolumeStepper.Outcome.AT_MAXIMUM, result.outcome());
        assertEquals(MusicVolumeStepper.AdjustmentPath.ROOT, result.adjustmentPath());
        assertEquals(0, audio.adjustCallCount);
        assertEquals(0, root.adjustCallCount);
    }

    @Test
    public void rootShellCommand_isFixedToMusicStreamAndValidatedDirection() {
        assertEquals(
                "cmd media_session volume --stream 3 --adj lower",
                RootShell.musicVolumeAdjustmentCommand(AudioManager.ADJUST_LOWER)
        );
        assertEquals(
                "cmd media_session volume --stream 3 --adj raise",
                RootShell.musicVolumeAdjustmentCommand(AudioManager.ADJUST_RAISE)
        );
        assertNull(RootShell.musicVolumeAdjustmentCommand(AudioManager.ADJUST_SAME));
    }

    private static void assertSingleAdjustment(FakeAudioGateway audio, int expectedDirection) {
        assertEquals(1, audio.adjustCallCount);
        assertEquals(AudioManager.STREAM_MUSIC, audio.adjustStream);
        assertEquals(expectedDirection, audio.adjustDirection);
        assertEquals(0, audio.adjustFlags);
    }

    private static void assertSingleRootAdjustment(
            FakeRootAdjustmentGateway root,
            int expectedDirection
    ) {
        assertEquals(1, root.adjustCallCount);
        assertEquals(expectedDirection, root.adjustDirection);
    }

    private static final class FakeRootAdjustmentGateway
            implements MusicVolumeStepper.RootAdjustmentGateway {
        private int adjustCallCount;
        private int adjustDirection = Integer.MIN_VALUE;
        private boolean throwOnAdjust;

        @Override
        public void adjustMusicVolume(int direction) {
            adjustCallCount++;
            adjustDirection = direction;
            if (throwOnAdjust) {
                throw new IllegalStateException("simulated root failure");
            }
        }
    }

    private static final class FakeAudioGateway implements MusicVolumeStepper.AudioGateway {
        private final int sdkInt;
        private final int min;
        private final int max;
        private final int before;
        private final int after;

        private boolean fixed;
        private boolean throwOnAdjust;
        private boolean throwOnReadback;
        private int minReadCount;
        private int maxReadCount;
        private int volumeReadCount;
        private int fixedReadCount;
        private int adjustCallCount;
        private int adjustStream = Integer.MIN_VALUE;
        private int adjustDirection = Integer.MIN_VALUE;
        private int adjustFlags = Integer.MIN_VALUE;

        private FakeAudioGateway(int sdkInt, int min, int max, int before, int after) {
            this.sdkInt = sdkInt;
            this.min = min;
            this.max = max;
            this.before = before;
            this.after = after;
        }

        @Override
        public int sdkInt() {
            return sdkInt;
        }

        @Override
        public int getStreamMinVolume(int streamType) {
            assertEquals(AudioManager.STREAM_MUSIC, streamType);
            minReadCount++;
            return min;
        }

        @Override
        public int getStreamMaxVolume(int streamType) {
            assertEquals(AudioManager.STREAM_MUSIC, streamType);
            maxReadCount++;
            return max;
        }

        @Override
        public int getStreamVolume(int streamType) {
            assertEquals(AudioManager.STREAM_MUSIC, streamType);
            volumeReadCount++;
            if (volumeReadCount > 1 && throwOnReadback) {
                throw new IllegalStateException("simulated readback failure");
            }
            return volumeReadCount == 1 ? before : after;
        }

        @Override
        public boolean isVolumeFixed() {
            fixedReadCount++;
            return fixed;
        }

        @Override
        public void adjustStreamVolume(int streamType, int direction, int flags) {
            adjustCallCount++;
            adjustStream = streamType;
            adjustDirection = direction;
            adjustFlags = flags;
            if (throwOnAdjust) {
                throw new IllegalStateException("simulated audio failure");
            }
        }

        private int totalReadCount() {
            return minReadCount + maxReadCount + volumeReadCount + fixedReadCount;
        }
    }
}

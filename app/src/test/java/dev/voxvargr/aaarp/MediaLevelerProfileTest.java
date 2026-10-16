package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaLevelerProfileTest {
    private static final String[] LEVELING_MODES = {
            MediaLevelerProfile.MODE_GENTLE,
            MediaLevelerProfile.MODE_STRONG,
            MediaLevelerProfile.MODE_MAX,
    };

    @Test
    public void unknownAndMissingModesFallBackToStrong() {
        assertEquals(MediaLevelerProfile.MODE_STRONG, MediaLevelerProfile.normalizeMode(null));
        assertEquals(MediaLevelerProfile.MODE_STRONG, MediaLevelerProfile.normalizeMode(""));
        assertEquals(MediaLevelerProfile.MODE_STRONG,
                MediaLevelerProfile.normalizeMode("nonsense"));
    }

    @Test
    public void modeParsingIgnoresCaseAndPadding() {
        assertEquals(MediaLevelerProfile.MODE_GENTLE,
                MediaLevelerProfile.normalizeMode("  GENTLE "));
        assertEquals(MediaLevelerProfile.MODE_OFF, MediaLevelerProfile.normalizeMode("Off"));
    }

    @Test
    public void offModeDisablesAllProcessing() {
        MediaLevelerProfile profile = MediaLevelerProfile.forMode(MediaLevelerProfile.MODE_OFF);
        assertFalse(profile.anyProcessingEnabled());
        assertFalse(profile.dynamicsEnabled);
        assertEquals(0, profile.loudnessGainMb);
        assertTrue(profile.summary().contains("streamMusic=user_owned"));
    }

    @Test
    public void gainOnlyModeIsExplicitLegacyBoostNotNormalization() {
        MediaLevelerProfile profile =
                MediaLevelerProfile.forMode(MediaLevelerProfile.MODE_GAIN_ONLY);
        assertTrue(profile.anyProcessingEnabled());
        assertFalse(profile.dynamicsEnabled);
        assertEquals(1200, profile.loudnessGainMb);
        assertEquals(profile.netGainDbAt(-40f), profile.netGainDbAt(-8f), 0.001f);
        assertEquals(0f, profile.levelingRangeReductionDb(-28f), 0.001f);
    }

    @Test
    public void realLevelingModesUseDynamicsWithoutStackedLoudnessEnhancerGain() {
        for (String mode : LEVELING_MODES) {
            MediaLevelerProfile profile = MediaLevelerProfile.forMode(mode);
            assertTrue(mode + " should request DynamicsProcessing", profile.dynamicsEnabled);
            assertEquals(mode + " must not stack LoudnessEnhancer", 0, profile.loudnessGainMb);
        }
    }

    @Test
    public void dynamicsPolicyUsesOnlyOneStereoLinkedLimiter() {
        assertEquals(2, MediaLevelerProfile.DYNAMICS_CHANNEL_COUNT);
        assertFalse(MediaLevelerProfile.DYNAMICS_MBC_IN_USE);
        assertEquals(0, MediaLevelerProfile.DYNAMICS_MBC_BAND_COUNT);
        assertTrue(MediaLevelerProfile.DYNAMICS_LIMITER_IN_USE);
        assertEquals(0, MediaLevelerProfile.LIMITER_LINK_GROUP);

        for (String mode : LEVELING_MODES) {
            assertEquals(MediaLevelerProfile.LIMITER_LINK_GROUP,
                    MediaLevelerProfile.forMode(mode).limiterLinkGroup);
        }
    }

    @Test
    public void presetsMatchReviewedLimiterOnlyCurves() {
        assertLimiterCurve(MediaLevelerProfile.MODE_GENTLE, -24f, 4f, 1f, 140f);
        assertLimiterCurve(MediaLevelerProfile.MODE_STRONG, -28f, 24f, 1f, 100f);
        assertLimiterCurve(MediaLevelerProfile.MODE_MAX, -34f, 50f, 1f, 100f);
    }

    private static void assertLimiterCurve(String mode, float thresholdDb, float ratio,
                                           float attackMs, float releaseMs) {
        MediaLevelerProfile profile = MediaLevelerProfile.forMode(mode);
        assertEquals(thresholdDb, profile.anchorLevelDbFs, 0.001f);
        assertEquals(thresholdDb, profile.limiterThresholdDb, 0.001f);
        assertEquals(ratio, profile.limiterRatio, 0.001f);
        assertEquals(attackMs, profile.limiterAttackMs, 0.001f);
        assertEquals(releaseMs, profile.limiterReleaseMs, 0.001f);
        assertEquals(0f, profile.inputGainDb, 0.001f);
        assertEquals(0f, profile.limiterPostGainDb, 0.001f);
    }

    @Test
    public void downwardOnlyCurveNeverAddsSignalGainButDoesNotProvePcmPeaks() {
        // This checks only the steady-state scalar curve. It is not a PCM, crest-factor,
        // true-peak, vendor-effect, or listening test.
        for (String mode : LEVELING_MODES) {
            MediaLevelerProfile profile = MediaLevelerProfile.forMode(mode);
            for (float input = -100f; input <= 0f; input += 0.5f) {
                assertTrue(mode + " added signal gain at " + input,
                        profile.netGainDbAt(input) <= 0.001f);
                assertTrue(mode + " produced an output above its input at " + input,
                        profile.outputLevelDbAt(input) <= input + 0.001f);
            }
            assertEquals(profile.limiterThresholdDb,
                    profile.outputLevelDbAt(profile.limiterThresholdDb), 0.001f);
            assertEquals(profile.limiterThresholdDb - 12f,
                    profile.outputLevelDbAt(profile.limiterThresholdDb - 12f), 0.001f);
        }
    }

    @Test
    public void strongerModesCloseTheRepresentativeGapMoreClosely() {
        float quietSong = -28f;
        MediaLevelerProfile gentle = MediaLevelerProfile.forMode(MediaLevelerProfile.MODE_GENTLE);
        MediaLevelerProfile strong = MediaLevelerProfile.forMode(MediaLevelerProfile.MODE_STRONG);
        MediaLevelerProfile max = MediaLevelerProfile.forMode(MediaLevelerProfile.MODE_MAX);

        float gentleGap = gentle.loudReferenceOutputDbFs() - gentle.outputLevelDbAt(quietSong);
        float strongGap = strong.loudReferenceOutputDbFs() - strong.outputLevelDbAt(quietSong);
        float maxGap = max.loudReferenceOutputDbFs() - max.outputLevelDbAt(quietSong);
        assertTrue(strongGap < gentleGap);
        assertTrue(maxGap < strongGap);
        assertEquals(8f, gentleGap, 0.01f);
        assertTrue("strong scalar curve should leave no more than 1 dB",
                strongGap <= 1.0f);
        assertTrue("max scalar curve should leave no more than 0.5 dB",
                maxGap <= 0.5f);
    }

    @Test
    public void everyLevelingModeClosesAtLeastHalfOfRepresentativeGap() {
        for (String mode : LEVELING_MODES) {
            MediaLevelerProfile profile = MediaLevelerProfile.forMode(mode);
            assertTrue(mode + " should close at least half of a 20 dB scalar gap",
                    profile.levelingRangeReductionDb(-28f) >= 10f);
        }
    }

    @Test
    public void responseRemainsMonotonicWithoutLevelInversion() {
        for (String mode : LEVELING_MODES) {
            MediaLevelerProfile profile = MediaLevelerProfile.forMode(mode);
            float previousOutput = profile.outputLevelDbAt(-100f);
            for (float input = -99.5f; input <= 0f; input += 0.5f) {
                float output = profile.outputLevelDbAt(input);
                assertTrue(mode + " inverted levels near " + input, output >= previousOutput);
                previousOutput = output;
            }
        }
    }

    @Test
    public void scalarReleaseModelApproachesSteadyStateBySevenHundredMilliseconds() {
        for (String mode : LEVELING_MODES) {
            MediaLevelerProfile profile = MediaLevelerProfile.forMode(mode);
            float steadyGap = Math.abs(
                    profile.loudReferenceOutputDbFs() - profile.outputLevelDbAt(-28f)
            );
            assertEquals(20f, profile.modeledLoudToQuietGapDbAt(-28f, 0f), 0.01f);
            assertTrue(mode + " scalar release estimate should settle near steady state",
                    profile.modeledLoudToQuietGapDbAt(-28f, 700f) <= steadyGap + 0.11f);
            assertEquals(steadyGap,
                    profile.modeledLoudToQuietGapDbAt(-28f, 5000f), 0.01f);
        }
    }

    @Test
    public void everyLegacyNormalizationSettingMigratesToRealLeveling() {
        assertEquals(MediaLevelerProfile.MODE_STRONG,
                MediaLevelerProfile.migratedModeFromLegacyFlag(true));
        assertEquals(MediaLevelerProfile.MODE_STRONG,
                MediaLevelerProfile.migratedModeFromLegacyFlag(false));
    }

    @Test
    public void spinnerIndexesRoundTripToModes() {
        assertEquals(MediaLevelerProfile.MODE_VALUES.length, MediaLevelerProfile.MODE_LABELS.length);
        for (int i = 0; i < MediaLevelerProfile.MODE_VALUES.length; i++) {
            String mode = MediaLevelerProfile.modeForLabelIndex(i);
            assertEquals(MediaLevelerProfile.MODE_VALUES[i], mode);
            assertEquals(i, MediaLevelerProfile.labelIndexForMode(mode));
        }
        assertEquals(MediaLevelerProfile.DEFAULT_MODE, MediaLevelerProfile.modeForLabelIndex(-1));
        assertEquals(MediaLevelerProfile.DEFAULT_MODE, MediaLevelerProfile.modeForLabelIndex(99));
    }

    @Test
    public void summaryStatesLinkedDownwardProcessorAndUserVolumeOwnership() {
        String summary = MediaLevelerProfile.forMode(MediaLevelerProfile.MODE_STRONG).summary();
        assertTrue(summary.contains("leveler=strong"));
        assertTrue(summary.contains("processor=stereo_linked_limiter"));
        assertTrue(summary.contains("detector=window_rms"));
        assertTrue(summary.contains("direction=downward_only"));
        assertTrue(summary.contains("inputGainDb=0.0"));
        assertTrue(summary.contains("postGainDb=0.0"));
        assertTrue(summary.contains("linkGroup=0"));
        assertTrue(summary.contains("mbc=off"));
        assertTrue(summary.contains("streamMusic=user_owned"));
        assertFalse(summary.contains("makeup"));
        assertFalse(summary.contains("ceiling"));
    }
}

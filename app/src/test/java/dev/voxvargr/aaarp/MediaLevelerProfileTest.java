package dev.voxvargr.aaarp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaLevelerProfileTest {
    @Test
    public void unknownAndMissingModesFallBackToStrong() {
        assertEquals(MediaLevelerProfile.MODE_STRONG, MediaLevelerProfile.normalizeMode(null));
        assertEquals(MediaLevelerProfile.MODE_STRONG, MediaLevelerProfile.normalizeMode(""));
        assertEquals(MediaLevelerProfile.MODE_STRONG, MediaLevelerProfile.normalizeMode("nonsense"));
    }

    @Test
    public void modeParsingIgnoresCaseAndPadding() {
        assertEquals(MediaLevelerProfile.MODE_GENTLE, MediaLevelerProfile.normalizeMode("  GENTLE "));
        assertEquals(MediaLevelerProfile.MODE_OFF, MediaLevelerProfile.normalizeMode("Off"));
    }

    @Test
    public void offModeDisablesAllProcessing() {
        MediaLevelerProfile profile = MediaLevelerProfile.forMode(MediaLevelerProfile.MODE_OFF);
        assertFalse(profile.anyProcessingEnabled());
        assertFalse(profile.dynamicsEnabled);
        assertEquals(0, profile.loudnessGainMb);
        assertEquals("leveler=off", profile.summary());
    }

    @Test
    public void gainOnlyModeReproducesLegacyFlatBoost() {
        MediaLevelerProfile profile = MediaLevelerProfile.forMode(MediaLevelerProfile.MODE_GAIN_ONLY);
        assertTrue(profile.anyProcessingEnabled());
        assertFalse(profile.dynamicsEnabled);
        assertEquals(1200, profile.loudnessGainMb);
    }

    @Test
    public void levelingModesUseSlowReleaseSoTheyRideSongsNotDrumHits() {
        for (String mode : new String[]{
                MediaLevelerProfile.MODE_GENTLE,
                MediaLevelerProfile.MODE_STRONG,
                MediaLevelerProfile.MODE_MAX}) {
            MediaLevelerProfile profile = MediaLevelerProfile.forMode(mode);
            assertTrue(mode + " should compress", profile.dynamicsEnabled);
            assertTrue(mode + " release should be slow", profile.mbcReleaseMs >= 400f);
            assertTrue(mode + " attack should protect transients", profile.mbcAttackMs >= 20f);
        }
    }

    @Test
    public void levelingThresholdsSitLowEnoughToCatchQuietTracks() {
        // The old -32 dB threshold never caught quiet material, which was the core bug.
        for (String mode : new String[]{
                MediaLevelerProfile.MODE_GENTLE,
                MediaLevelerProfile.MODE_STRONG,
                MediaLevelerProfile.MODE_MAX}) {
            MediaLevelerProfile profile = MediaLevelerProfile.forMode(mode);
            assertTrue(mode + " threshold must be below -32 dB", profile.mbcThresholdDb < -32f);
        }
    }

    @Test
    public void strongerModesLevelHarderThanGentlerOnes() {
        MediaLevelerProfile gentle = MediaLevelerProfile.forMode(MediaLevelerProfile.MODE_GENTLE);
        MediaLevelerProfile strong = MediaLevelerProfile.forMode(MediaLevelerProfile.MODE_STRONG);
        MediaLevelerProfile max = MediaLevelerProfile.forMode(MediaLevelerProfile.MODE_MAX);

        assertTrue(strong.mbcRatio > gentle.mbcRatio);
        assertTrue(max.mbcRatio > strong.mbcRatio);

        assertTrue(strong.mbcThresholdDb < gentle.mbcThresholdDb);
        assertTrue(max.mbcThresholdDb < strong.mbcThresholdDb);

        assertTrue(strong.mbcPostGainDb > gentle.mbcPostGainDb);
        assertTrue(max.mbcPostGainDb > strong.mbcPostGainDb);

        assertTrue(strong.approximateQuietTrackGainDb() > gentle.approximateQuietTrackGainDb());
        assertTrue(max.approximateQuietTrackGainDb() > strong.approximateQuietTrackGainDb());
    }

    @Test
    public void limiterKeepsHeadroomBelowFullScaleSoMakeupGainCannotClip() {
        for (String mode : new String[]{
                MediaLevelerProfile.MODE_GENTLE,
                MediaLevelerProfile.MODE_STRONG,
                MediaLevelerProfile.MODE_MAX}) {
            MediaLevelerProfile profile = MediaLevelerProfile.forMode(mode);
            assertTrue(mode + " needs a ceiling below 0 dBFS", profile.limiterThresholdDb < 0f);
            assertTrue(mode + " limiter must be brickwall-ish", profile.limiterRatio >= 10f);
            assertTrue(mode + " limiter must catch peaks fast", profile.limiterAttackMs <= 2f);
        }
    }

    @Test
    public void levelingNeverAttenuatesQuietMaterial() {
        for (String mode : MediaLevelerProfile.MODE_VALUES) {
            MediaLevelerProfile profile = MediaLevelerProfile.forMode(mode);
            assertTrue(mode + " must not turn quiet tracks down",
                    profile.approximateQuietTrackGainDb() >= 0f);
        }
    }

    @Test
    public void legacyCheckboxMapsOntoEquivalentModes() {
        assertEquals(MediaLevelerProfile.MODE_STRONG,
                MediaLevelerProfile.migratedModeFromLegacyFlag(true));
        assertEquals(MediaLevelerProfile.MODE_GAIN_ONLY,
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
    public void summaryReportsTheTuningThatMatters() {
        String summary = MediaLevelerProfile.forMode(MediaLevelerProfile.MODE_STRONG).summary();
        assertTrue(summary.contains("leveler=strong"));
        assertTrue(summary.contains("thresholdDb="));
        assertTrue(summary.contains("releaseMs="));
        assertTrue(summary.contains("makeupDb="));
    }
}

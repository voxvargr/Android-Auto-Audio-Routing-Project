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
    public void thresholdsSitAboveQuietMaterialSoOnlyLoudTracksAreCompressed() {
        // Quiet tracks must pass under the threshold untouched and collect full makeup
        // gain; the compressor exists to pull LOUD tracks down toward them.
        for (String mode : new String[]{
                MediaLevelerProfile.MODE_GENTLE,
                MediaLevelerProfile.MODE_STRONG,
                MediaLevelerProfile.MODE_MAX}) {
            MediaLevelerProfile profile = MediaLevelerProfile.forMode(mode);
            assertTrue(mode + " threshold must sit above quiet material",
                    profile.mbcThresholdDb > -32f);
            assertTrue(mode + " threshold must sit below the loudest material",
                    profile.mbcThresholdDb < MediaLevelerProfile.LOUDEST_RMS_DBFS);
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
    public void nothingIsEverAttenuatedAcrossTheRealisticLevelRange() {
        // A downward compressor's net gain decreases as input level rises, so checking
        // quiet material alone is not enough: sweep from near-silence up to the loudest
        // RMS real music reaches. (Sustained RMS above that anchor is not physically
        // reachable by music, and transient peaks above it are the limiter's job.)
        for (String mode : MediaLevelerProfile.MODE_VALUES) {
            MediaLevelerProfile profile = MediaLevelerProfile.forMode(mode);
            for (float level = -60f; level <= MediaLevelerProfile.LOUDEST_RMS_DBFS; level += 0.5f) {
                float net = profile.netGainDbAt(level);
                assertTrue(
                        mode + " attenuates by " + net + " dB at " + level + " dBFS",
                        net >= -0.05f
                );
            }
        }
    }

    @Test
    public void loudestMaterialLandsNearUnitySoHeadroomIsNotWasted() {
        for (String mode : new String[]{
                MediaLevelerProfile.MODE_GENTLE,
                MediaLevelerProfile.MODE_STRONG,
                MediaLevelerProfile.MODE_MAX}) {
            MediaLevelerProfile profile = MediaLevelerProfile.forMode(mode);
            float loudest = profile.loudestTrackGainDb();
            assertTrue(mode + " must not turn loud tracks down", loudest >= -0.05f);
            assertTrue(mode + " wastes headroom on loud tracks (" + loudest + " dB)",
                    loudest <= 3.0f);
        }
    }

    @Test
    public void quietTracksAreLiftedMuchMoreThanLoudOnes() {
        for (String mode : new String[]{
                MediaLevelerProfile.MODE_GENTLE,
                MediaLevelerProfile.MODE_STRONG,
                MediaLevelerProfile.MODE_MAX}) {
            MediaLevelerProfile profile = MediaLevelerProfile.forMode(mode);
            assertTrue(mode + " should lift quiet material far more than loud material",
                    profile.netGainDbAt(-30f) > profile.netGainDbAt(-6f) + 3f);
        }
    }

    @Test
    public void levelingMeaningfullyClosesTheGapBetweenLoudAndQuietTracks() {
        float quietTrack = -22f;
        MediaLevelerProfile gentle = MediaLevelerProfile.forMode(MediaLevelerProfile.MODE_GENTLE);
        MediaLevelerProfile strong = MediaLevelerProfile.forMode(MediaLevelerProfile.MODE_STRONG);
        MediaLevelerProfile max = MediaLevelerProfile.forMode(MediaLevelerProfile.MODE_MAX);

        assertTrue("gentle should close at least 5 dB of the gap",
                gentle.levelingRangeReductionDb(quietTrack) >= 5f);
        assertTrue(strong.levelingRangeReductionDb(quietTrack)
                > gentle.levelingRangeReductionDb(quietTrack));
        assertTrue(max.levelingRangeReductionDb(quietTrack)
                > strong.levelingRangeReductionDb(quietTrack));
        assertTrue("max should nearly eliminate a 17 dB gap",
                max.levelingRangeReductionDb(quietTrack) >= 13f);
    }

    @Test
    public void gainOnlyModeCannotLevelBecauseItLiftsEverythingEqually() {
        MediaLevelerProfile profile = MediaLevelerProfile.forMode(MediaLevelerProfile.MODE_GAIN_ONLY);
        assertEquals(0f, profile.levelingRangeReductionDb(-22f), 0.001f);
        assertEquals(profile.netGainDbAt(-40f), profile.netGainDbAt(-5f), 0.001f);
    }

    @Test
    public void makeupGainIsDerivedFromThresholdAndRatio() {
        // (loudest - threshold) * (1 - 1/ratio)
        assertEquals(14.25f, MediaLevelerProfile.derivedMakeupGainDb(-24f, 4f), 0.01f);
        assertEquals(0f, MediaLevelerProfile.derivedMakeupGainDb(-24f, 1f), 0.001f);
        for (String mode : new String[]{
                MediaLevelerProfile.MODE_GENTLE,
                MediaLevelerProfile.MODE_STRONG,
                MediaLevelerProfile.MODE_MAX}) {
            MediaLevelerProfile profile = MediaLevelerProfile.forMode(mode);
            assertEquals(
                    MediaLevelerProfile.derivedMakeupGainDb(profile.mbcThresholdDb, profile.mbcRatio),
                    profile.mbcPostGainDb,
                    0.01f
            );
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
    public void fallbackGainIsLargerBecauseThereIsNoMakeupGainWithoutCompression() {
        for (String mode : new String[]{
                MediaLevelerProfile.MODE_GENTLE,
                MediaLevelerProfile.MODE_STRONG,
                MediaLevelerProfile.MODE_MAX}) {
            MediaLevelerProfile profile = MediaLevelerProfile.forMode(mode);
            assertTrue(mode + " fallback must exceed the compressed-path gain",
                    profile.fallbackLoudnessGainMb > profile.loudnessGainMb);
            assertTrue(mode + " fallback must stay within LoudnessEnhancer sanity limits",
                    profile.fallbackLoudnessGainMb <= 1500);
        }
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

package dev.voxvargr.aaarp;

/**
 * Tuning presets for AAARP's cross-app, downward-only RMS leveler.
 *
 * <p>The Android media-volume index remains entirely user-owned. Gentle, Strong, and Max never
 * add signal gain: a stereo-linked limiter reduces louder windows toward a quiet anchor. A
 * window passes unchanged when neither linked channel drives the detector above that anchor.
 * The listener can then set the downstream {@code STREAM_MUSIC} volume once for the result.
 *
 * <p>{@link android.media.audiofx.DynamicsProcessing} does not expose integrated LUFS, track
 * boundaries, true-peak detection, or per-player audio sessions here. These presets therefore
 * cannot promise identical perceived loudness or prove peak safety. They are a real-time,
 * window-RMS approximation on Android's global mix.
 */
final class MediaLevelerProfile {
    static final String MODE_OFF = "off";
    static final String MODE_GAIN_ONLY = "gain_only";
    static final String MODE_GENTLE = "gentle";
    static final String MODE_STRONG = "strong";
    static final String MODE_MAX = "max";

    static final String DEFAULT_MODE = MODE_STRONG;

    /** Pure-Java description of the DynamicsProcessing architecture used by the controller. */
    static final int DYNAMICS_CHANNEL_COUNT = 2;
    static final boolean DYNAMICS_MBC_IN_USE = false;
    static final int DYNAMICS_MBC_BAND_COUNT = 0;
    static final boolean DYNAMICS_LIMITER_IN_USE = true;
    static final int LIMITER_LINK_GROUP = 0;

    /** Representative loud-song RMS level used only for off-device curve comparisons. */
    static final float LOUD_REFERENCE_DBFS = -8.0f;

    /** Stored-preference values, in the order shown in the settings spinner. */
    static final String[] MODE_VALUES = {
            MODE_OFF,
            MODE_GAIN_ONLY,
            MODE_GENTLE,
            MODE_STRONG,
            MODE_MAX,
    };

    /** User-facing labels, index-aligned with {@link #MODE_VALUES}. */
    static final String[] MODE_LABELS = {
            "Off (no leveling)",
            "Gain only (legacy boost)",
            "Gentle leveling",
            "Strong leveling (recommended)",
            "Max leveling (most aggressive)",
    };

    final String mode;
    /** LoudnessEnhancer target used only when gain-only is the requested mode. */
    final int loudnessGainMb;
    final boolean dynamicsEnabled;
    final float anchorLevelDbFs;
    final float inputGainDb;
    final int limiterLinkGroup;
    final float limiterThresholdDb;
    final float limiterRatio;
    final float limiterAttackMs;
    final float limiterReleaseMs;
    final float limiterPostGainDb;

    private MediaLevelerProfile(String mode, int loudnessGainMb,
                                boolean dynamicsEnabled, float anchorLevelDbFs,
                                float inputGainDb, int limiterLinkGroup,
                                float limiterThresholdDb, float limiterRatio,
                                float limiterAttackMs, float limiterReleaseMs,
                                float limiterPostGainDb) {
        this.mode = mode;
        this.loudnessGainMb = loudnessGainMb;
        this.dynamicsEnabled = dynamicsEnabled;
        this.anchorLevelDbFs = anchorLevelDbFs;
        this.inputGainDb = inputGainDb;
        this.limiterLinkGroup = limiterLinkGroup;
        this.limiterThresholdDb = limiterThresholdDb;
        this.limiterRatio = limiterRatio;
        this.limiterAttackMs = limiterAttackMs;
        this.limiterReleaseMs = limiterReleaseMs;
        this.limiterPostGainDb = limiterPostGainDb;
    }

    static MediaLevelerProfile forMode(String rawMode) {
        String safeMode = normalizeMode(rawMode);
        switch (safeMode) {
            case MODE_OFF:
                return new MediaLevelerProfile(MODE_OFF,
                        0, false, 0f, 0f, LIMITER_LINK_GROUP,
                        0f, 1f, 0f, 0f, 0f);
            case MODE_GAIN_ONLY:
                return new MediaLevelerProfile(MODE_GAIN_ONLY,
                        1200, false, 0f, 0f, LIMITER_LINK_GROUP,
                        0f, 1f, 0f, 0f, 0f);
            case MODE_GENTLE:
                return leveling(MODE_GENTLE, -24f, 4.0f, 1f, 140f);
            case MODE_MAX:
                return leveling(MODE_MAX, -34f, 50.0f, 1f, 100f);
            case MODE_STRONG:
            default:
                return leveling(MODE_STRONG, -28f, 24.0f, 1f, 100f);
        }
    }

    private static MediaLevelerProfile leveling(String mode, float anchorLevelDbFs,
                                                 float ratio, float attackMs, float releaseMs) {
        return new MediaLevelerProfile(mode,
                0, true, anchorLevelDbFs,
                0f, LIMITER_LINK_GROUP,
                anchorLevelDbFs, ratio, attackMs, releaseMs, 0f);
    }

    private static float compressedLevelDb(float levelDbFs, float thresholdDb, float ratio) {
        if (ratio <= 1f || levelDbFs <= thresholdDb) {
            return levelDbFs;
        }
        return thresholdDb + ((levelDbFs - thresholdDb) / ratio);
    }

    static String normalizeMode(String rawMode) {
        if (rawMode == null) {
            return DEFAULT_MODE;
        }
        String trimmed = rawMode.trim().toLowerCase(java.util.Locale.US);
        for (String value : MODE_VALUES) {
            if (value.equals(trimmed)) {
                return value;
            }
        }
        return DEFAULT_MODE;
    }

    /**
     * Every legacy normalization configuration migrates to real leveling. Gain-only remains
     * available solely as an explicit user choice after migration.
     */
    static String migratedModeFromLegacyFlag(boolean ignoredLegacyDynamicsProcessing) {
        return MODE_STRONG;
    }

    static int labelIndexForMode(String rawMode) {
        String safeMode = normalizeMode(rawMode);
        for (int i = 0; i < MODE_VALUES.length; i++) {
            if (MODE_VALUES[i].equals(safeMode)) {
                return i;
            }
        }
        return 0;
    }

    static String modeForLabelIndex(int index) {
        if (index < 0 || index >= MODE_VALUES.length) {
            return DEFAULT_MODE;
        }
        return MODE_VALUES[index];
    }

    boolean anyProcessingEnabled() {
        return loudnessGainMb != 0 || dynamicsEnabled;
    }

    /** Steady-state window-RMS curve estimate; this is not a PCM or integrated-LUFS result. */
    float outputLevelDbAt(float inputLevelDbFs) {
        if (!dynamicsEnabled) {
            return inputLevelDbFs + (loudnessGainMb / 100f);
        }
        float level = inputLevelDbFs + inputGainDb;
        return compressedLevelDb(level, limiterThresholdDb, limiterRatio) + limiterPostGainDb;
    }

    /** Net signal gain. Real leveling modes are constrained to zero or negative gain. */
    float netGainDbAt(float inputLevelDbFs) {
        return outputLevelDbAt(inputLevelDbFs) - inputLevelDbFs;
    }

    float loudReferenceOutputDbFs() {
        return outputLevelDbAt(LOUD_REFERENCE_DBFS);
    }

    /** How much the leveler closes a loud/quiet mastering gap, in dB. */
    float levelingRangeReductionDb(float quietLevelDbFs) {
        float before = LOUD_REFERENCE_DBFS - quietLevelDbFs;
        float after = loudReferenceOutputDbFs() - outputLevelDbAt(quietLevelDbFs);
        return before - after;
    }

    /**
     * Continuous-time scalar estimate for a loud-to-quiet RMS-envelope transition.
     *
     * <p>This follows the shape of AOSP's first-order, linear-amplitude release detector but
     * deliberately omits FFT framing, overlap, PCM crest factor, vendor differences, and track
     * boundaries. It is a tuning calculation, not proof of audible or device behavior.
     */
    float modeledLoudToQuietGapDbAt(float quietLevelDbFs, float elapsedMs) {
        if (!dynamicsEnabled || limiterReleaseMs <= 0f) {
            return Math.abs(loudReferenceOutputDbFs() - outputLevelDbAt(quietLevelDbFs));
        }
        float safeElapsed = Math.max(0f, elapsedMs);
        double loudEnvelope = dbToLinear(LOUD_REFERENCE_DBFS + inputGainDb);
        double quietEnvelope = dbToLinear(quietLevelDbFs + inputGainDb);
        double envelope = quietEnvelope + (loudEnvelope - quietEnvelope)
                * Math.exp(-safeElapsed / limiterReleaseMs);
        float envelopeDb = linearToDb(envelope);
        float detectorGainDb = compressedLevelDb(
                envelopeDb,
                limiterThresholdDb,
                limiterRatio
        ) - envelopeDb;
        float transitioningQuietOutput = quietLevelDbFs + inputGainDb
                + detectorGainDb + limiterPostGainDb;
        return Math.abs(loudReferenceOutputDbFs() - transitioningQuietOutput);
    }

    private static double dbToLinear(float levelDb) {
        return Math.pow(10.0, levelDb / 20.0);
    }

    private static float linearToDb(double level) {
        return (float) (20.0 * Math.log10(Math.max(level, 1.0e-6)));
    }

    String summary() {
        if (!anyProcessingEnabled()) {
            return "leveler=off streamMusic=user_owned";
        }
        StringBuilder summary = new StringBuilder("leveler=").append(mode);
        summary.append(" streamMusic=user_owned");
        if (dynamicsEnabled) {
            summary.append(" processor=stereo_linked_limiter")
                    .append(" detector=window_rms")
                    .append(" direction=downward_only")
                    .append(" anchorDbFs=").append(anchorLevelDbFs)
                    .append(" inputGainDb=").append(inputGainDb)
                    .append(" thresholdDb=").append(limiterThresholdDb)
                    .append(" ratio=").append(limiterRatio)
                    .append(" attackMs=").append(limiterAttackMs)
                    .append(" releaseMs=").append(limiterReleaseMs)
                    .append(" postGainDb=").append(limiterPostGainDb)
                    .append(" linkGroup=").append(limiterLinkGroup)
                    .append(" mbc=off");
        } else {
            summary.append(" processor=gain_only gainMb=").append(loudnessGainMb);
        }
        return summary.toString();
    }
}

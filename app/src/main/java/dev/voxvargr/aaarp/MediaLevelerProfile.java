package dev.voxvargr.aaarp;

/**
 * Tuning presets for the Android Auto media leveler.
 *
 * <p>The goal is loudness matching where nothing is ever turned down. A downward
 * compressor's net gain falls as input level rises, so the whole curve is anchored by its
 * loudest point: makeup gain is derived as {@code (LOUDEST_RMS_DBFS - threshold) *
 * (1 - 1/ratio)}, which pins the net gain of the loudest expected material to roughly
 * 0 dB. Every quieter input therefore receives strictly positive gain, and quiet tracks
 * receive the most. {@code STREAM_MUSIC} is never touched by the leveler.
 *
 * <p>Note the topology: quiet tracks sit <em>below</em> the threshold, so they pass the
 * compressor untouched and collect the full makeup gain, while loud tracks are pushed
 * down toward them. Setting the threshold beneath the quiet material instead would
 * compress everything equally and level nothing. Release times stay long so the leveler
 * rides a song's overall level rather than pumping on individual drum hits.
 */
final class MediaLevelerProfile {
    static final String MODE_OFF = "off";
    static final String MODE_GAIN_ONLY = "gain_only";
    static final String MODE_GENTLE = "gentle";
    static final String MODE_STRONG = "strong";
    static final String MODE_MAX = "max";

    static final String DEFAULT_MODE = MODE_STRONG;

    /**
     * Assumed RMS of the loudest material in a modern playlist. Makeup gain is derived so
     * that this level comes out at unity, which is what guarantees nothing is attenuated.
     */
    static final float LOUDEST_RMS_DBFS = -5.0f;

    /** Makeup gain that pins {@link #LOUDEST_RMS_DBFS} to unity for a threshold/ratio pair. */
    static float derivedMakeupGainDb(float thresholdDb, float ratio) {
        if (ratio <= 1f) {
            return 0f;
        }
        return (LOUDEST_RMS_DBFS - thresholdDb) * (1f - (1f / ratio));
    }

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
    /** LoudnessEnhancer target gain in millibels, applied alongside the dynamics stage. */
    final int loudnessGainMb;
    /**
     * LoudnessEnhancer gain to use when the dynamics stage is unavailable (pre-API 28) or
     * fails to initialise. Without compression there is no makeup gain, so this larger
     * flat boost is the only lift available.
     */
    final int fallbackLoudnessGainMb;
    final boolean dynamicsEnabled;
    final float inputGainDb;
    final float mbcThresholdDb;
    final float mbcRatio;
    final float mbcAttackMs;
    final float mbcReleaseMs;
    final float mbcPreGainDb;
    /** Makeup gain: the "bring quiet tracks up" control. */
    final float mbcPostGainDb;
    final float mbcNoiseGateThresholdDb;
    final float limiterThresholdDb;
    final float limiterRatio;
    final float limiterAttackMs;
    final float limiterReleaseMs;

    private MediaLevelerProfile(String mode, int loudnessGainMb, int fallbackLoudnessGainMb,
                                boolean dynamicsEnabled,
                                float inputGainDb, float mbcThresholdDb, float mbcRatio,
                                float mbcAttackMs, float mbcReleaseMs, float mbcPreGainDb,
                                float mbcPostGainDb, float mbcNoiseGateThresholdDb,
                                float limiterThresholdDb, float limiterRatio,
                                float limiterAttackMs, float limiterReleaseMs) {
        this.mode = mode;
        this.loudnessGainMb = loudnessGainMb;
        this.fallbackLoudnessGainMb = fallbackLoudnessGainMb;
        this.dynamicsEnabled = dynamicsEnabled;
        this.inputGainDb = inputGainDb;
        this.mbcThresholdDb = mbcThresholdDb;
        this.mbcRatio = mbcRatio;
        this.mbcAttackMs = mbcAttackMs;
        this.mbcReleaseMs = mbcReleaseMs;
        this.mbcPreGainDb = mbcPreGainDb;
        this.mbcPostGainDb = mbcPostGainDb;
        this.mbcNoiseGateThresholdDb = mbcNoiseGateThresholdDb;
        this.limiterThresholdDb = limiterThresholdDb;
        this.limiterRatio = limiterRatio;
        this.limiterAttackMs = limiterAttackMs;
        this.limiterReleaseMs = limiterReleaseMs;
    }

    static MediaLevelerProfile forMode(String rawMode) {
        String safeMode = normalizeMode(rawMode);
        switch (safeMode) {
            case MODE_OFF:
                return new MediaLevelerProfile(MODE_OFF,
                        0, 0, false,
                        0f, 0f, 1f, 0f, 0f, 0f, 0f, -100f,
                        0f, 1f, 0f, 0f);
            case MODE_GAIN_ONLY:
                // Legacy behavior: flat LoudnessEnhancer gain, no dynamics stage.
                return new MediaLevelerProfile(MODE_GAIN_ONLY,
                        1200, 1200, false,
                        0f, 0f, 1f, 0f, 0f, 0f, 0f, -100f,
                        0f, 1f, 0f, 0f);
            case MODE_GENTLE:
                return leveling(MODE_GENTLE, 100, 600, -18f, 2.5f, 20f, 400f, -90f, -1.0f, 15f, 100f);
            case MODE_MAX:
                return leveling(MODE_MAX, 200, 1200, -28f, 6.0f, 30f, 900f, -75f, -0.5f, 25f, 120f);
            case MODE_STRONG:
            default:
                return leveling(MODE_STRONG, 150, 900, -24f, 4.0f, 20f, 650f, -80f, -0.7f, 20f, 100f);
        }
    }

    private static MediaLevelerProfile leveling(String mode, int loudnessGainMb,
                                                int fallbackLoudnessGainMb, float thresholdDb,
                                                float ratio, float attackMs, float releaseMs,
                                                float noiseGateDb, float limiterThresholdDb,
                                                float limiterRatio, float limiterReleaseMs) {
        return new MediaLevelerProfile(mode,
                loudnessGainMb, fallbackLoudnessGainMb, true,
                0f, thresholdDb, ratio, attackMs, releaseMs,
                0f, derivedMakeupGainDb(thresholdDb, ratio), noiseGateDb,
                limiterThresholdDb, limiterRatio, 1f, limiterReleaseMs);
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
     * Maps the retired {@code media_dynamics_processing} checkbox onto a leveler mode so
     * existing installs and saved profiles keep working after an update.
     */
    static String migratedModeFromLegacyFlag(boolean legacyDynamicsProcessing) {
        return legacyDynamicsProcessing ? MODE_STRONG : MODE_GAIN_ONLY;
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
        return loudnessGainMb > 0 || dynamicsEnabled;
    }

    /**
     * Net gain applied to steady-state material at {@code inputLevelDbFs}, ignoring the
     * limiter (which only catches transient peaks). Used to verify that the leveler lifts
     * quiet tracks toward loud ones without ever attenuating anything.
     */
    float netGainDbAt(float inputLevelDbFs) {
        float gain = loudnessGainMb / 100f;
        if (!dynamicsEnabled) {
            return gain;
        }
        float level = inputLevelDbFs + inputGainDb + mbcPreGainDb;
        float compressed = level > mbcThresholdDb
                ? mbcThresholdDb + ((level - mbcThresholdDb) / mbcRatio)
                : level;
        return gain + (compressed + mbcPostGainDb) - inputLevelDbFs;
    }

    /** Approximate lift applied to a quiet track that sits well under the threshold. */
    float approximateQuietTrackGainDb() {
        return netGainDbAt(mbcThresholdDb - 10f);
    }

    /** Net gain applied to the loudest expected material; the floor of the gain curve. */
    float loudestTrackGainDb() {
        return netGainDbAt(LOUDEST_RMS_DBFS);
    }

    /**
     * How much closer together the loudest and quietest material ends up, in dB. This is
     * the actual measure of "everything at the same volume".
     */
    float levelingRangeReductionDb(float quietLevelDbFs) {
        float before = LOUDEST_RMS_DBFS - quietLevelDbFs;
        float after = (LOUDEST_RMS_DBFS + loudestTrackGainDb())
                - (quietLevelDbFs + netGainDbAt(quietLevelDbFs));
        return before - after;
    }

    String summary() {
        if (!anyProcessingEnabled()) {
            return "leveler=off";
        }
        StringBuilder summary = new StringBuilder("leveler=").append(mode);
        summary.append(" loudnessMb=").append(loudnessGainMb);
        if (dynamicsEnabled) {
            summary.append(" thresholdDb=").append(mbcThresholdDb)
                    .append(" ratio=").append(mbcRatio)
                    .append(" releaseMs=").append(mbcReleaseMs)
                    .append(" makeupDb=").append(mbcPostGainDb)
                    .append(" ceilingDb=").append(limiterThresholdDb);
        } else {
            summary.append(" dynamics=off");
        }
        return summary.toString();
    }
}

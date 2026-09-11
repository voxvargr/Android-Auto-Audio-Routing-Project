package dev.voxvargr.aaarp;

/**
 * Tuning presets for the Android Auto media leveler.
 *
 * <p>The goal is loudness matching without ever turning a track down: a slow-release
 * full-band compressor pins loud material against a limiter ceiling while makeup gain
 * lifts quiet material toward that same ceiling. Everything comes up, nothing is
 * attenuated below where it started, and {@code STREAM_MUSIC} is never touched.
 *
 * <p>The two parameters that decide whether leveling actually happens are the MBC
 * threshold (quiet tracks must cross it or they are never processed) and the release
 * time (short releases pump on individual drum hits instead of riding the song).
 */
final class MediaLevelerProfile {
    static final String MODE_OFF = "off";
    static final String MODE_GAIN_ONLY = "gain_only";
    static final String MODE_GENTLE = "gentle";
    static final String MODE_STRONG = "strong";
    static final String MODE_MAX = "max";

    static final String DEFAULT_MODE = MODE_STRONG;

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
    /** LoudnessEnhancer target gain in millibels. */
    final int loudnessGainMb;
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

    private MediaLevelerProfile(String mode, int loudnessGainMb, boolean dynamicsEnabled,
                                float inputGainDb, float mbcThresholdDb, float mbcRatio,
                                float mbcAttackMs, float mbcReleaseMs, float mbcPreGainDb,
                                float mbcPostGainDb, float mbcNoiseGateThresholdDb,
                                float limiterThresholdDb, float limiterRatio,
                                float limiterAttackMs, float limiterReleaseMs) {
        this.mode = mode;
        this.loudnessGainMb = loudnessGainMb;
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
                        0, false,
                        0f, 0f, 1f, 0f, 0f, 0f, 0f, -100f,
                        0f, 1f, 0f, 0f);
            case MODE_GAIN_ONLY:
                // Legacy behavior: flat LoudnessEnhancer gain, no dynamics stage.
                return new MediaLevelerProfile(MODE_GAIN_ONLY,
                        1200, false,
                        0f, 0f, 1f, 0f, 0f, 0f, 0f, -100f,
                        0f, 1f, 0f, 0f);
            case MODE_GENTLE:
                return new MediaLevelerProfile(MODE_GENTLE,
                        600, true,
                        0f, -38f, 3.0f, 20f, 400f, 0f, 5.0f, -90f,
                        -1.0f, 15f, 1f, 100f);
            case MODE_MAX:
                return new MediaLevelerProfile(MODE_MAX,
                        1000, true,
                        0f, -48f, 8.0f, 30f, 900f, 0f, 11.0f, -75f,
                        -0.5f, 25f, 1f, 120f);
            case MODE_STRONG:
            default:
                return new MediaLevelerProfile(MODE_STRONG,
                        800, true,
                        0f, -42f, 5.0f, 20f, 650f, 0f, 8.0f, -80f,
                        -0.7f, 20f, 1f, 100f);
        }
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

    /** Approximate lift applied to a quiet track that sits well under the threshold. */
    float approximateQuietTrackGainDb() {
        float dynamicsGain = dynamicsEnabled ? inputGainDb + mbcPreGainDb + mbcPostGainDb : 0f;
        return dynamicsGain + (loudnessGainMb / 100f);
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

package dev.voldechse.replayframework.api.playback;

/**
 * Supported playback speed multipliers.
 */
public enum PlaybackSpeed {
    /** One quarter of normal playback speed. */
    QUARTER(0.25),
    /** One half of normal playback speed. */
    HALF(0.5),
    /** Normal playback speed. */
    NORMAL(1.0),
    /** Twice normal playback speed. */
    DOUBLE(2.0),
    /** Four times normal playback speed. */
    QUADRUPLE(4.0);

    private final double multiplier;

    PlaybackSpeed(double multiplier) {
        this.multiplier = multiplier;
    }

    /**
     * Returns the timeline multiplier represented by this enum value.
     *
     * @return playback multiplier
     */
    public double multiplier() {
        return multiplier;
    }
}

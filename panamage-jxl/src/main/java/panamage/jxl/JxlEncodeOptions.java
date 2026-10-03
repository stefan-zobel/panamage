package panamage.jxl;

import panamage.jxl.ffi.Jxl;

/**
 * Settings for {@link JxlEncoder}.
 * <p>
 * Use one of the factory methods and optionally adjust the effort:
 * {@snippet :
 * JxlEncodeOptions options = JxlEncodeOptions.ofQuality(90).withEffort(9);
 * }
 * <p>
 * Options are immutable; further settings may be added in later versions.
 */
public final class JxlEncodeOptions {

    /** The default effort of libjxl ("squirrel"). */
    public static final int DEFAULT_EFFORT = 7;

    /** The smallest supported effort ("lightning"). */
    public static final int MIN_EFFORT = 1;

    /** The largest supported effort ("glacier"). */
    public static final int MAX_EFFORT = 10;

    /** The largest distance accepted by libjxl. */
    public static final float MAX_DISTANCE = 25.0f;

    /** The Butteraugli distance, 0 for lossless encoding. */
    private final float distance;
    private final int effort;

    /**
     * Creates options.
     *
     * @throws IllegalArgumentException if the effort or distance is out of range
     */
    private JxlEncodeOptions(float distance, int effort) {
        if (effort < MIN_EFFORT || effort > MAX_EFFORT) {
            throw new IllegalArgumentException(
                    "effort must be in [" + MIN_EFFORT + ", " + MAX_EFFORT + "]: " + effort);
        }
        if (distance != 0.0f && !(distance > 0.0f && distance <= MAX_DISTANCE)) {
            throw new IllegalArgumentException(
                    "distance must be in (0, " + MAX_DISTANCE + "] for lossy encoding: " + distance);
        }
        this.distance = distance;
        this.effort = effort;
    }

    /**
     * Lossy encoding with distance 1.0 (visually lossless) and default effort.
     *
     * @return the default options
     */
    public static JxlEncodeOptions defaults() {
        return ofDistance(1.0f);
    }

    /**
     * Bit-exact lossless encoding with default effort.
     *
     * @return options for lossless encoding
     */
    public static JxlEncodeOptions ofLossless() {
        return new JxlEncodeOptions(0.0f, DEFAULT_EFFORT);
    }

    /**
     * Lossy encoding with the given Butteraugli distance and default effort.
     *
     * @param distance the target distance, {@code 0 < distance <= 25};
     *                 the recommended range is 0.5 to 3.0
     * @return options for lossy encoding
     * @throws IllegalArgumentException if the distance is out of range
     */
    public static JxlEncodeOptions ofDistance(float distance) {
        if (distance == 0.0f) {
            throw new IllegalArgumentException(
                    "distance must be in (0, " + MAX_DISTANCE + "] for lossy encoding: " + distance);
        }
        return new JxlEncodeOptions(distance, DEFAULT_EFFORT);
    }

    /**
     * Encoding with a quality from 0 to 100, mapped to a distance the same way
     * as libjxl's {@code cjxl --quality}. Quality 100 means lossless.
     *
     * @param quality the quality, {@code 0 <= quality <= 100}
     * @return options for the given quality
     * @throws IllegalArgumentException if the quality is out of range
     */
    public static JxlEncodeOptions ofQuality(float quality) {
        if (!(quality >= 0.0f && quality <= 100.0f)) {
            throw new IllegalArgumentException("quality must be in [0, 100]: " + quality);
        }
        if (quality == 100.0f) {
            return ofLossless();
        }
        return ofDistance(Jxl.JxlEncoderDistanceFromQuality(quality));
    }

    /**
     * Returns whether the image is encoded bit-exact.
     *
     * @return {@code true} for lossless encoding
     */
    public boolean lossless() {
        return distance == 0.0f;
    }

    /**
     * Returns the target Butteraugli distance for lossy encoding.
     *
     * @return the distance ({@code 0 < distance <= 25}, 1.0 is visually
     *         lossless), or 0 for lossless encoding
     */
    public float distance() {
        return distance;
    }

    /**
     * Returns the encoder effort.
     *
     * @return the effort from 1 (fastest) to 10 (slowest, best compression);
     *         it does not affect decoding speed
     */
    public int effort() {
        return effort;
    }

    /**
     * Returns a copy of these options with a different effort.
     *
     * @param effort the effort from 1 (fastest) to 10 (slowest)
     * @return the new options
     * @throws IllegalArgumentException if the effort is out of range
     */
    public JxlEncodeOptions withEffort(int effort) {
        return new JxlEncodeOptions(distance, effort);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof JxlEncodeOptions other && Float.compare(distance, other.distance) == 0
                && effort == other.effort;
    }

    @Override
    public int hashCode() {
        return 31 * Float.hashCode(distance) + effort;
    }

    @Override
    public String toString() {
        return "JxlEncodeOptions[lossless=" + lossless() + ", distance=" + distance + ", effort=" + effort + "]";
    }
}

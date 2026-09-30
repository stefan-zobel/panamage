package panamage.jxl;

import panamage.jxl.ffi.Jxl;

/**
 * Settings for {@link JxlEncoder}.
 * <p>
 * Use one of the factory methods and optionally adjust the effort:
 * {@snippet :
 * JxlEncodeOptions options = JxlEncodeOptions.ofQuality(90).withEffort(9);
 * }
 *
 * @param lossless whether the image is encoded bit-exact
 * @param distance the target Butteraugli distance for lossy encoding
 *                 ({@code 0 < distance <= 25}, 1.0 is visually lossless);
 *                 must be 0 for lossless encoding
 * @param effort   the encoder effort from 1 (fastest) to 10 (slowest, best
 *                 compression); does not affect decoding speed
 */
public record JxlEncodeOptions(boolean lossless, float distance, int effort) {

    /** The default effort of libjxl ("squirrel"). */
    public static final int DEFAULT_EFFORT = 7;

    /** The smallest supported effort ("lightning"). */
    public static final int MIN_EFFORT = 1;

    /** The largest supported effort ("glacier"). */
    public static final int MAX_EFFORT = 10;

    /** The largest distance accepted by libjxl. */
    public static final float MAX_DISTANCE = 25.0f;

    /**
     * Validates the settings.
     *
     * @throws IllegalArgumentException if the effort or distance is out of range
     */
    public JxlEncodeOptions {
        if (effort < MIN_EFFORT || effort > MAX_EFFORT) {
            throw new IllegalArgumentException(
                    "effort must be in [" + MIN_EFFORT + ", " + MAX_EFFORT + "]: " + effort);
        }
        if (lossless) {
            if (distance != 0.0f) {
                throw new IllegalArgumentException("distance must be 0 for lossless encoding: " + distance);
            }
        } else if (!(distance > 0.0f && distance <= MAX_DISTANCE)) {
            throw new IllegalArgumentException(
                    "distance must be in (0, " + MAX_DISTANCE + "] for lossy encoding: " + distance);
        }
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
        return new JxlEncodeOptions(true, 0.0f, DEFAULT_EFFORT);
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
        return new JxlEncodeOptions(false, distance, DEFAULT_EFFORT);
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
     * Returns a copy of these options with a different effort.
     *
     * @param effort the effort from 1 (fastest) to 10 (slowest)
     * @return the new options
     * @throws IllegalArgumentException if the effort is out of range
     */
    public JxlEncodeOptions withEffort(int effort) {
        return new JxlEncodeOptions(lossless, distance, effort);
    }
}

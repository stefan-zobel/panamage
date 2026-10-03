package panamage.jxl;

/**
 * The timing of an animation to encode with {@link JxlFrameEncoder}.
 * <p>
 * Frame durations are given in ticks; a tick lasts
 * {@code ticksPerSecondDenominator / ticksPerSecondNumerator} seconds. With
 * {@link #millis(long)}, a tick is one millisecond. The ranges are those of
 * the JPEG XL format; a rate such as 30000 / 1001 is possible.
 *
 * @param ticksPerSecondNumerator   the numerator of the tick rate, 1 to
 *                                  1073741824 (2<sup>30</sup>)
 * @param ticksPerSecondDenominator the denominator of the tick rate, 1 to 1024
 * @param loops                     how often the animation is played, or 0 to
 *                                  play it forever; at most 4294967295
 *                                  (2<sup>32</sup> - 1)
 * @see JxlAnimationInfo#header()
 */
public record JxlAnimationHeader(long ticksPerSecondNumerator, long ticksPerSecondDenominator, long loops) {

    static final long MAX_NUMERATOR = 1L << 30;
    static final long MAX_DENOMINATOR = 1024;
    static final long MAX_LOOPS = 0xFFFF_FFFFL;

    /**
     * Validates the header.
     *
     * @throws IllegalArgumentException if the tick rate is not positive or a
     *                                  value is out of range
     */
    public JxlAnimationHeader {
        if (ticksPerSecondNumerator < 1 || ticksPerSecondNumerator > MAX_NUMERATOR) {
            throw new IllegalArgumentException("ticksPerSecondNumerator must be 1 to " + MAX_NUMERATOR + ": "
                    + ticksPerSecondNumerator);
        }
        if (ticksPerSecondDenominator < 1 || ticksPerSecondDenominator > MAX_DENOMINATOR) {
            throw new IllegalArgumentException("ticksPerSecondDenominator must be 1 to " + MAX_DENOMINATOR + ": "
                    + ticksPerSecondDenominator);
        }
        if (loops < 0 || loops > MAX_LOOPS) {
            throw new IllegalArgumentException("loops must be 0 to " + MAX_LOOPS + ": " + loops);
        }
    }

    /**
     * Returns a header with 1000 ticks per second, so durations are given in
     * milliseconds.
     *
     * @param loops how often the animation is played, or 0 to play it forever
     * @return the header
     * @throws IllegalArgumentException if {@code loops} is out of range
     */
    public static JxlAnimationHeader millis(long loops) {
        return new JxlAnimationHeader(1000, 1, loops);
    }
}

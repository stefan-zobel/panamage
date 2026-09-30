package panamage.jxl;

import java.util.List;

/**
 * The frames of a JPEG XL image and their timing, available without decoding
 * the pixels; see {@link JxlDecoder#readAnimationInfo(byte[])}.
 * <p>
 * Frame durations are given in ticks; a tick lasts
 * {@code ticksPerSecondDenominator / ticksPerSecondNumerator} seconds. A still
 * image has one frame with a duration of 0 and a tick rate of 0 / 0.
 *
 * @param ticksPerSecondNumerator   the numerator of the tick rate
 * @param ticksPerSecondDenominator the denominator of the tick rate
 * @param loops                     how often the animation is played, or 0 to
 *                                  play it forever
 * @param frames                    the displayed frames in order
 */
public record JxlAnimationInfo(long ticksPerSecondNumerator, long ticksPerSecondDenominator, long loops,
        List<JxlFrameInfo> frames) {

    /**
     * Copies the frame list.
     *
     * @throws NullPointerException if {@code frames} or one of its elements is
     *                              {@code null}
     */
    public JxlAnimationInfo {
        frames = List.copyOf(frames);
    }

    /**
     * Returns the number of displayed frames.
     *
     * @return the number of frames, 1 for a still image
     */
    public int frameCount() {
        return frames.size();
    }
}

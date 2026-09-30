package panamage.jxl;

import java.util.Objects;

/**
 * The header of a frame of a JPEG XL animation, as it is displayed: libjxl
 * combines frames without duration with the following frame, so every frame
 * covers the whole image.
 *
 * @param durationTicks  how long the frame is shown, in ticks of the
 *                       animation ({@link JxlAnimationInfo}); 0 for a still
 *                       image
 * @param durationMillis the same duration in milliseconds
 * @param name           the name of the frame, or {@code ""} if it has none
 */
public record JxlFrameInfo(long durationTicks, double durationMillis, String name) {

    /**
     * Validates the frame information.
     *
     * @throws NullPointerException     if {@code name} is {@code null}
     * @throws IllegalArgumentException if a duration is negative
     */
    public JxlFrameInfo {
        Objects.requireNonNull(name, "name");
        if (durationTicks < 0 || durationMillis < 0) {
            throw new IllegalArgumentException("durations must not be negative: " + durationTicks + " ticks, "
                    + durationMillis + " ms");
        }
    }
}

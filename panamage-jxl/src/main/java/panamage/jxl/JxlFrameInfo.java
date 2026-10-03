package panamage.jxl;

import java.util.Objects;

/**
 * The header of a frame of a JPEG XL animation, as it is displayed: libjxl
 * combines frames without duration with the following frame, so every frame
 * covers the whole image. Further information may be added in later versions.
 */
public final class JxlFrameInfo {

    private final long durationTicks;
    private final double durationMillis;
    private final String name;

    /**
     * Creates the frame information.
     *
     * @param durationTicks  how long the frame is shown, in ticks of the
     *                       animation ({@link JxlAnimationInfo}); 0 for a
     *                       still image
     * @param durationMillis the same duration in milliseconds; ignored when
     *                       an animation is written
     * @param name           the name of the frame, or {@code ""} if it has none
     * @throws NullPointerException     if {@code name} is {@code null}
     * @throws IllegalArgumentException if a duration is negative
     */
    public JxlFrameInfo(long durationTicks, double durationMillis, String name) {
        Objects.requireNonNull(name, "name");
        if (durationTicks < 0 || durationMillis < 0) {
            throw new IllegalArgumentException("durations must not be negative: " + durationTicks + " ticks, "
                    + durationMillis + " ms");
        }
        this.durationTicks = durationTicks;
        this.durationMillis = durationMillis;
        this.name = name;
    }

    /**
     * Returns how long the frame is shown.
     *
     * @return the duration in ticks of the animation, 0 for a still image
     */
    public long durationTicks() {
        return durationTicks;
    }

    /**
     * Returns how long the frame is shown, in milliseconds.
     *
     * @return the duration in milliseconds, 0 for a still image
     */
    public double durationMillis() {
        return durationMillis;
    }

    /**
     * Returns the name of the frame.
     *
     * @return the name, or {@code ""} if it has none
     */
    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof JxlFrameInfo other && durationTicks == other.durationTicks
                && Double.compare(durationMillis, other.durationMillis) == 0 && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(durationTicks, durationMillis, name);
    }

    @Override
    public String toString() {
        return "JxlFrameInfo[durationTicks=" + durationTicks + ", durationMillis=" + durationMillis + ", name="
                + name + "]";
    }
}

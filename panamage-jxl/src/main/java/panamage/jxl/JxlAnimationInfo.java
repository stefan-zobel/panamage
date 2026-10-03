package panamage.jxl;

import java.util.List;
import java.util.Objects;

/**
 * The frames of a JPEG XL image and their timing, available without decoding
 * the pixels; see {@link JxlDecoder#readAnimationInfo(byte[])}.
 * <p>
 * Frame durations are given in ticks; a tick lasts
 * {@code ticksPerSecondDenominator / ticksPerSecondNumerator} seconds. A still
 * image has one frame with a duration of 0 and a tick rate of 0 / 0. Further
 * information may be added in later versions.
 * <p>
 * {@link #header()} gives the header for writing the frames again:
 * {@snippet :
 * JxlAnimationInfo info = JxlDecoder.readAnimationInfo(data);
 * List<JxlFrame> frames = JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8);
 * byte[] copy = JxlEncoder.encodeAnimation(frames, info.header(), JxlEncodeOptions.ofLossless(),
 *         JxlMetadata.NONE);
 * }
 */
public final class JxlAnimationInfo {

    private final long ticksPerSecondNumerator;
    private final long ticksPerSecondDenominator;
    private final long loops;
    private final List<JxlFrameInfo> frames;

    /**
     * Creates the information and copies the frame list.
     *
     * @throws NullPointerException if {@code frames} or one of its elements is
     *                              {@code null}
     */
    JxlAnimationInfo(long ticksPerSecondNumerator, long ticksPerSecondDenominator, long loops,
            List<JxlFrameInfo> frames) {
        this.ticksPerSecondNumerator = ticksPerSecondNumerator;
        this.ticksPerSecondDenominator = ticksPerSecondDenominator;
        this.loops = loops;
        this.frames = List.copyOf(frames);
    }

    /**
     * Returns the numerator of the tick rate.
     *
     * @return the numerator, 0 for a still image
     */
    public long ticksPerSecondNumerator() {
        return ticksPerSecondNumerator;
    }

    /**
     * Returns the denominator of the tick rate.
     *
     * @return the denominator, 0 for a still image
     */
    public long ticksPerSecondDenominator() {
        return ticksPerSecondDenominator;
    }

    /**
     * Returns how often the animation is played.
     *
     * @return the number of loops, or 0 to play it forever
     */
    public long loops() {
        return loops;
    }

    /**
     * Returns the displayed frames.
     *
     * @return the frames in order, as an unmodifiable list
     */
    public List<JxlFrameInfo> frames() {
        return frames;
    }

    /**
     * Returns the number of displayed frames.
     *
     * @return the number of frames, 1 for a still image
     */
    public int frameCount() {
        return frames.size();
    }

    /**
     * Returns the tick rate and the loop count as a header for writing the
     * frames again, with {@link JxlFrameEncoder} or
     * {@link JxlEncoder#encodeAnimation}.
     *
     * @return the header, or {@code null} for a still image
     */
    public JxlAnimationHeader header() {
        return ticksPerSecondNumerator == 0 ? null
                : new JxlAnimationHeader(ticksPerSecondNumerator, ticksPerSecondDenominator, loops);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof JxlAnimationInfo other && ticksPerSecondNumerator == other.ticksPerSecondNumerator
                && ticksPerSecondDenominator == other.ticksPerSecondDenominator && loops == other.loops
                && frames.equals(other.frames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ticksPerSecondNumerator, ticksPerSecondDenominator, loops, frames);
    }

    @Override
    public String toString() {
        return "JxlAnimationInfo[ticksPerSecondNumerator=" + ticksPerSecondNumerator + ", ticksPerSecondDenominator="
                + ticksPerSecondDenominator + ", loops=" + loops + ", frames=" + frames + "]";
    }
}

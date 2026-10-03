package panamage.jxl;

import java.util.Objects;

/**
 * Settings for decoding with {@link JxlDecoder} and {@link JxlFrameDecoder}:
 * the {@link JxlLimits} and the color space of the decoded pixels. Reading
 * metadata uses only the limits;
 * {@link JxlTranscoder#toJpeg(byte[], JxlDecodeOptions)} uses the limits and
 * the threads.
 * {@snippet :
 * JxlImage image = JxlDecoder.decode(data, 4, JxlSampleType.UINT8,
 *         JxlDecodeOptions.defaults().withSrgb(true));
 * }
 * <p>
 * By default, pixels keep the color space of the image, and
 * {@link JxlImage#iccProfile()} describes it if it is not sRGB. With
 * {@link #withSrgb(boolean) withSrgb(true)}, images in another color space
 * (such as Display P3, Adobe RGB, linear RGB or gray with its own profile) are
 * converted to sRGB by libjxl's color management, so their ICC profile is
 * {@code null}. Colors outside the sRGB gamut are clipped (or kept beyond
 * 0.0 to 1.0 with floating point samples), HDR images (PQ, HLG) are tone
 * mapped, and gray images stay gray, with the sRGB transfer curve. If libjxl
 * cannot convert an image, its pixels keep their color space and
 * {@link JxlImage#iccProfile()} describes it.
 * <p>
 * {@link #withThreads(JxlThreads)} sets how many native threads libjxl uses to
 * decode the pixels; by default, {@link JxlThreads#auto()} chooses them by
 * the image size. Reading headers and metadata uses no threads.
 * <p>
 * Options are immutable. Start from {@link #defaults()} and change single
 * settings with the {@code with} methods; further settings may be added in
 * later versions.
 */
public final class JxlDecodeOptions {

    private final JxlLimits limits;
    private final boolean srgb;
    private final JxlThreads threads;

    /**
     * Creates options.
     *
     * @throws NullPointerException if {@code limits} or {@code threads} is
     *                              {@code null}
     */
    private JxlDecodeOptions(JxlLimits limits, boolean srgb, JxlThreads threads) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.srgb = srgb;
        this.threads = Objects.requireNonNull(threads, "threads");
    }

    /**
     * Returns the default options: {@link JxlLimits#defaults()}, the color
     * space of the image and {@link JxlThreads#auto()}.
     *
     * @return the default options
     */
    public static JxlDecodeOptions defaults() {
        return new JxlDecodeOptions(JxlLimits.defaults(), false, JxlThreads.auto());
    }

    /**
     * Returns the limits that protect against decompression bombs.
     *
     * @return the limits
     */
    public JxlLimits limits() {
        return limits;
    }

    /**
     * Returns whether the pixels are converted to sRGB.
     *
     * @return {@code true} if the pixels are converted to sRGB
     */
    public boolean srgb() {
        return srgb;
    }

    /**
     * Returns how many native threads libjxl uses to decode the pixels.
     *
     * @return the thread setting
     */
    public JxlThreads threads() {
        return threads;
    }

    /**
     * Returns a copy of these options with different limits.
     *
     * @param limits the new limits
     * @return the new options
     * @throws NullPointerException if {@code limits} is {@code null}
     */
    public JxlDecodeOptions withLimits(JxlLimits limits) {
        return new JxlDecodeOptions(limits, srgb, threads);
    }

    /**
     * Returns a copy of these options that converts the pixels to sRGB or
     * keeps the color space of the image.
     *
     * @param srgb whether the pixels are converted to sRGB
     * @return the new options
     */
    public JxlDecodeOptions withSrgb(boolean srgb) {
        return new JxlDecodeOptions(limits, srgb, threads);
    }

    /**
     * Returns a copy of these options with a different thread setting.
     *
     * @param threads how many native threads libjxl uses to decode the pixels
     * @return the new options
     * @throws NullPointerException if {@code threads} is {@code null}
     */
    public JxlDecodeOptions withThreads(JxlThreads threads) {
        return new JxlDecodeOptions(limits, srgb, threads);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof JxlDecodeOptions other && limits.equals(other.limits) && srgb == other.srgb
                && threads.equals(other.threads);
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, srgb, threads);
    }

    @Override
    public String toString() {
        return "JxlDecodeOptions[limits=" + limits + ", srgb=" + srgb + ", threads=" + threads + "]";
    }
}

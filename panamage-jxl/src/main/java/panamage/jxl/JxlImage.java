package panamage.jxl;

import java.util.Objects;

/**
 * An image with 8-bit, 16-bit or floating point samples.
 * <p>
 * The pixels are stored row by row, top to bottom, with the samples of each
 * pixel interleaved. The channel order depends on the number of channels:
 * <ul>
 * <li>1: gray</li>
 * <li>2: gray, alpha</li>
 * <li>3: R, G, B</li>
 * <li>4: R, G, B, A</li>
 * </ul>
 * Alpha is straight (not premultiplied); the decoder converts images stored
 * with premultiplied alpha. The samples are in the sRGB color space,
 * including its transfer curve, unless an ICC profile is given.
 * <p>
 * The sample type is given by the implementation, so a {@code switch} covers
 * all cases:
 * {@snippet :
 * switch (image) {
 *     case JxlImage.Uint8 img -> process(img.pixels());   // byte[]
 *     case JxlImage.Uint16 img -> process(img.pixels());  // short[]
 *     case JxlImage.Float32 img -> process(img.pixels()); // float[]
 * }
 * }
 */
public sealed interface JxlImage permits JxlImage.Uint8, JxlImage.Uint16, JxlImage.Float32 {

    /**
     * Returns the width.
     *
     * @return the width in pixels
     */
    int width();

    /**
     * Returns the height.
     *
     * @return the height in pixels
     */
    int height();

    /**
     * Returns the number of samples per pixel.
     *
     * @return the number of channels, 1 to 4
     */
    int channels();

    /**
     * Returns the ICC profile of the samples.
     *
     * @return the ICC profile, or {@code null} for sRGB
     */
    byte[] iccProfile();

    /**
     * Returns the type of the samples.
     *
     * @return the sample type
     */
    JxlSampleType sampleType();

    /**
     * Returns whether the samples are in the sRGB color space.
     *
     * @return {@code true} if there is no ICC profile
     */
    default boolean isSrgb() {
        return iccProfile() == null;
    }

    /**
     * An image with unsigned 8-bit samples.
     *
     * @param width      the width in pixels
     * @param height     the height in pixels
     * @param channels   the number of samples per pixel
     * @param pixels     the interleaved samples, {@code width * height * channels} bytes
     * @param iccProfile the ICC profile of the samples, or {@code null} for sRGB
     */
    record Uint8(int width, int height, int channels, byte[] pixels, byte[] iccProfile) implements JxlImage {

        /**
         * Validates the dimensions against the size of the pixel array.
         */
        public Uint8 {
            Objects.requireNonNull(pixels, "pixels");
            validate(width, height, channels, pixels.length, iccProfile);
        }

        /**
         * Creates an sRGB image.
         *
         * @param width    the width in pixels
         * @param height   the height in pixels
         * @param channels the number of samples per pixel
         * @param pixels   the interleaved samples, {@code width * height * channels} bytes
         */
        public Uint8(int width, int height, int channels, byte[] pixels) {
            this(width, height, channels, pixels, null);
        }

        @Override
        public JxlSampleType sampleType() {
            return JxlSampleType.UINT8;
        }
    }

    /**
     * An image with unsigned 16-bit samples from 0 to 65535. Java has no
     * unsigned short, so use {@link Short#toUnsignedInt(short)} to read them.
     *
     * @param width      the width in pixels
     * @param height     the height in pixels
     * @param channels   the number of samples per pixel
     * @param pixels     the interleaved samples, {@code width * height * channels} values
     * @param iccProfile the ICC profile of the samples, or {@code null} for sRGB
     */
    record Uint16(int width, int height, int channels, short[] pixels, byte[] iccProfile) implements JxlImage {

        /**
         * Validates the dimensions against the size of the pixel array.
         */
        public Uint16 {
            Objects.requireNonNull(pixels, "pixels");
            validate(width, height, channels, pixels.length, iccProfile);
        }

        /**
         * Creates an sRGB image.
         *
         * @param width    the width in pixels
         * @param height   the height in pixels
         * @param channels the number of samples per pixel
         * @param pixels   the interleaved samples, {@code width * height * channels} values
         */
        public Uint16(int width, int height, int channels, short[] pixels) {
            this(width, height, channels, pixels, null);
        }

        @Override
        public JxlSampleType sampleType() {
            return JxlSampleType.UINT16;
        }
    }

    /**
     * An image with 32-bit floating point samples. The nominal range is 0.0 to
     * 1.0; HDR and wide-gamut images may go beyond it.
     *
     * @param width      the width in pixels
     * @param height     the height in pixels
     * @param channels   the number of samples per pixel
     * @param pixels     the interleaved samples, {@code width * height * channels} values
     * @param iccProfile the ICC profile of the samples, or {@code null} for sRGB
     */
    record Float32(int width, int height, int channels, float[] pixels, byte[] iccProfile) implements JxlImage {

        /**
         * Validates the dimensions against the size of the pixel array.
         */
        public Float32 {
            Objects.requireNonNull(pixels, "pixels");
            validate(width, height, channels, pixels.length, iccProfile);
        }

        /**
         * Creates an sRGB image.
         *
         * @param width    the width in pixels
         * @param height   the height in pixels
         * @param channels the number of samples per pixel
         * @param pixels   the interleaved samples, {@code width * height * channels} values
         */
        public Float32(int width, int height, int channels, float[] pixels) {
            this(width, height, channels, pixels, null);
        }

        @Override
        public JxlSampleType sampleType() {
            return JxlSampleType.FLOAT32;
        }
    }

    private static void validate(int width, int height, int channels, int samples, byte[] iccProfile) {
        if (width <= 0 || height <= 0 || channels <= 0) {
            throw new IllegalArgumentException(
                    "Invalid dimensions: " + width + "x" + height + "x" + channels);
        }
        if ((long) width * height * channels != samples) {
            throw new IllegalArgumentException("Expected " + (long) width * height * channels
                    + " samples, got " + samples);
        }
        if (iccProfile != null && iccProfile.length == 0) {
            throw new IllegalArgumentException("iccProfile must not be empty; use null for sRGB");
        }
    }
}

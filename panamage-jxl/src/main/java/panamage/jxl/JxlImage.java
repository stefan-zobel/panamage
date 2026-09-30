package panamage.jxl;

import java.util.Objects;

/**
 * An image with 8 bits per sample.
 * <p>
 * The pixels are stored row by row, top to bottom, with the samples of each
 * pixel interleaved. The channel order depends on the number of channels:
 * <ul>
 * <li>1: gray</li>
 * <li>2: gray, alpha</li>
 * <li>3: R, G, B</li>
 * <li>4: R, G, B, A</li>
 * </ul>
 * Alpha is straight (not premultiplied). The samples are in the sRGB color
 * space unless an ICC profile is given.
 *
 * @param width      the width in pixels
 * @param height     the height in pixels
 * @param channels   the number of samples per pixel
 * @param pixels     the interleaved samples, {@code width * height * channels} bytes
 * @param iccProfile the ICC profile of the samples, or {@code null} for sRGB
 */
public record JxlImage(int width, int height, int channels, byte[] pixels, byte[] iccProfile) {

    /**
     * Validates the dimensions against the size of the pixel array.
     */
    public JxlImage {
        Objects.requireNonNull(pixels, "pixels");
        if (width <= 0 || height <= 0 || channels <= 0) {
            throw new IllegalArgumentException(
                    "Invalid dimensions: " + width + "x" + height + "x" + channels);
        }
        if ((long) width * height * channels != pixels.length) {
            throw new IllegalArgumentException("Expected " + (long) width * height * channels
                    + " bytes of pixel data, got " + pixels.length);
        }
        if (iccProfile != null && iccProfile.length == 0) {
            throw new IllegalArgumentException("iccProfile must not be empty; use null for sRGB");
        }
    }

    /**
     * Creates an sRGB image.
     *
     * @param width    the width in pixels
     * @param height   the height in pixels
     * @param channels the number of samples per pixel
     * @param pixels   the interleaved samples, {@code width * height * channels} bytes
     */
    public JxlImage(int width, int height, int channels, byte[] pixels) {
        this(width, height, channels, pixels, null);
    }

    /**
     * Returns whether the samples are in the sRGB color space.
     *
     * @return {@code true} if there is no ICC profile
     */
    public boolean isSrgb() {
        return iccProfile == null;
    }
}

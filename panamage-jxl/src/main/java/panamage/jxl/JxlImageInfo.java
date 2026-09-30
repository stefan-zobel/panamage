package panamage.jxl;

/**
 * Header information about a JPEG XL image, available without decoding the
 * pixels.
 *
 * @param width         the width in pixels, after applying the orientation
 * @param height        the height in pixels, after applying the orientation
 * @param colorChannels 1 for grayscale, 3 for color
 * @param hasAlpha      whether the image has an alpha channel
 * @param bitsPerSample the bit depth of the original image
 * @param animated      whether the image is an animation
 * @param iccProfile    the ICC profile of the pixels the decoder produces, or
 *                      {@code null} if they are sRGB
 */
public record JxlImageInfo(int width, int height, int colorChannels, boolean hasAlpha,
        int bitsPerSample, boolean animated, byte[] iccProfile) {

    /**
     * Returns the number of channels that represents the image without loss:
     * gray, gray and alpha, RGB or RGBA.
     *
     * @return the number of channels, 1 to 4
     */
    public int channels() {
        return colorChannels + (hasAlpha ? 1 : 0);
    }

    /**
     * Returns whether the decoded pixels are in the sRGB color space.
     *
     * @return {@code true} if there is no ICC profile
     */
    public boolean isSrgb() {
        return iccProfile == null;
    }
}

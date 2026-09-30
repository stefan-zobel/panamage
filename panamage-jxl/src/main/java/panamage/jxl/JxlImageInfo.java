package panamage.jxl;

/**
 * Header information about a JPEG XL image, available without decoding the
 * pixels.
 *
 * @param width                 the width in pixels, after applying the orientation
 * @param height                the height in pixels, after applying the orientation
 * @param colorChannels         1 for grayscale, 3 for color
 * @param hasAlpha              whether the image has an alpha channel
 * @param bitsPerSample         the bit depth of the original image
 * @param exponentBitsPerSample the number of exponent bits of floating point
 *                              samples, or 0 for integer samples
 * @param animated              whether the image is an animation
 * @param iccProfile            the ICC profile of the pixels the decoder
 *                              produces, or {@code null} if they are sRGB
 */
public record JxlImageInfo(int width, int height, int colorChannels, boolean hasAlpha,
        int bitsPerSample, int exponentBitsPerSample, boolean animated, byte[] iccProfile) {

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
     * Returns the sample type that represents the image without loss:
     * {@link JxlSampleType#UINT8} for up to 8 bits, {@link JxlSampleType#UINT16}
     * for up to 16 bits and {@link JxlSampleType#FLOAT32} for floating point
     * samples and integers with more than 16 bits.
     *
     * @return the sample type
     */
    public JxlSampleType sampleType() {
        if (exponentBitsPerSample > 0 || bitsPerSample > 16) {
            return JxlSampleType.FLOAT32;
        }
        return bitsPerSample > 8 ? JxlSampleType.UINT16 : JxlSampleType.UINT8;
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

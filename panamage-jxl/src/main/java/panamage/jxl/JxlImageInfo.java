package panamage.jxl;

import java.util.Arrays;
import java.util.Objects;

/**
 * Header information about a JPEG XL image, available without decoding the
 * pixels; see {@link JxlDecoder#readInfo(byte[])}. Further information may be
 * added in later versions.
 */
public final class JxlImageInfo {

    private final int width;
    private final int height;
    private final int colorChannels;
    private final boolean hasAlpha;
    private final int bitsPerSample;
    private final int exponentBitsPerSample;
    private final boolean animated;
    private final byte[] iccProfile;

    JxlImageInfo(int width, int height, int colorChannels, boolean hasAlpha, int bitsPerSample,
            int exponentBitsPerSample, boolean animated, byte[] iccProfile) {
        this.width = width;
        this.height = height;
        this.colorChannels = colorChannels;
        this.hasAlpha = hasAlpha;
        this.bitsPerSample = bitsPerSample;
        this.exponentBitsPerSample = exponentBitsPerSample;
        this.animated = animated;
        this.iccProfile = iccProfile;
    }

    /**
     * Returns the width of the image.
     *
     * @return the width in pixels, after applying the orientation
     */
    public int width() {
        return width;
    }

    /**
     * Returns the height of the image.
     *
     * @return the height in pixels, after applying the orientation
     */
    public int height() {
        return height;
    }

    /**
     * Returns the number of color channels.
     *
     * @return 1 for grayscale, 3 for color
     */
    public int colorChannels() {
        return colorChannels;
    }

    /**
     * Returns whether the image has an alpha channel.
     *
     * @return {@code true} if the image has an alpha channel
     */
    public boolean hasAlpha() {
        return hasAlpha;
    }

    /**
     * Returns the bit depth of the original image.
     *
     * @return the number of bits per sample
     */
    public int bitsPerSample() {
        return bitsPerSample;
    }

    /**
     * Returns the number of exponent bits of floating point samples.
     *
     * @return the number of exponent bits, or 0 for integer samples
     */
    public int exponentBitsPerSample() {
        return exponentBitsPerSample;
    }

    /**
     * Returns whether the image is an animation.
     *
     * @return {@code true} for an animation
     */
    public boolean animated() {
        return animated;
    }

    /**
     * Returns the ICC profile of the pixels the decoder produces. The array is
     * not copied.
     *
     * @return the ICC profile, or {@code null} if the pixels are sRGB
     */
    public byte[] iccProfile() {
        return iccProfile;
    }

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

    /** Compares all fields, the ICC profile by content. */
    @Override
    public boolean equals(Object obj) {
        return obj instanceof JxlImageInfo other && width == other.width && height == other.height
                && colorChannels == other.colorChannels && hasAlpha == other.hasAlpha
                && bitsPerSample == other.bitsPerSample && exponentBitsPerSample == other.exponentBitsPerSample
                && animated == other.animated && Arrays.equals(iccProfile, other.iccProfile);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(width, height, colorChannels, hasAlpha, bitsPerSample, exponentBitsPerSample,
                animated) + Arrays.hashCode(iccProfile);
    }

    @Override
    public String toString() {
        return "JxlImageInfo[width=" + width + ", height=" + height + ", colorChannels=" + colorChannels
                + ", hasAlpha=" + hasAlpha + ", bitsPerSample=" + bitsPerSample + ", exponentBitsPerSample="
                + exponentBitsPerSample + ", animated=" + animated + ", iccProfile="
                + (iccProfile == null ? "null" : iccProfile.length + " bytes") + "]";
    }
}

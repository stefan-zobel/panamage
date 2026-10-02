package panamage.jxl;

import java.util.Objects;

/**
 * Header information about an extra channel of a JPEG XL image, available
 * without decoding the pixels.
 *
 * @param type                  the type of the channel
 * @param name                  the name of the channel, or {@code ""} if it has none
 * @param bitsPerSample         the bit depth of the channel
 * @param exponentBitsPerSample the number of exponent bits of floating point
 *                              samples, or 0 for integer samples
 * @see JxlDecoder#readExtraChannels(byte[])
 */
public record JxlExtraChannelInfo(JxlChannelType type, String name, int bitsPerSample, int exponentBitsPerSample) {

    /**
     * Validates the information.
     *
     * @throws NullPointerException if the type or the name is {@code null}
     */
    public JxlExtraChannelInfo {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
    }

    /**
     * Returns the type and name of the channel, as in {@link JxlChannels}.
     *
     * @return the channel
     */
    public JxlExtraChannel channel() {
        return new JxlExtraChannel(type, name);
    }

    /**
     * Returns the sample type that represents the channel without loss, as
     * {@link JxlImageInfo#sampleType()} does for the color channels.
     *
     * @return the sample type
     */
    public JxlSampleType sampleType() {
        if (exponentBitsPerSample > 0 || bitsPerSample > 16) {
            return JxlSampleType.FLOAT32;
        }
        return bitsPerSample > 8 ? JxlSampleType.UINT16 : JxlSampleType.UINT8;
    }
}

package panamage.jxl;

import java.util.Objects;

/**
 * Header information about an extra channel of a JPEG XL image, available
 * without decoding the pixels. Further information may be added in later
 * versions.
 *
 * @see JxlDecoder#readExtraChannels(byte[])
 */
public final class JxlExtraChannelInfo {

    private final JxlChannelType type;
    private final String name;
    private final int bitsPerSample;
    private final int exponentBitsPerSample;

    /**
     * Creates the information.
     *
     * @throws NullPointerException if the type or the name is {@code null}
     */
    JxlExtraChannelInfo(JxlChannelType type, String name, int bitsPerSample, int exponentBitsPerSample) {
        this.type = Objects.requireNonNull(type, "type");
        this.name = Objects.requireNonNull(name, "name");
        this.bitsPerSample = bitsPerSample;
        this.exponentBitsPerSample = exponentBitsPerSample;
    }

    /**
     * Returns the type of the channel.
     *
     * @return the type
     */
    public JxlChannelType type() {
        return type;
    }

    /**
     * Returns the name of the channel.
     *
     * @return the name, or {@code ""} if it has none
     */
    public String name() {
        return name;
    }

    /**
     * Returns the bit depth of the channel.
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

    @Override
    public boolean equals(Object obj) {
        return obj instanceof JxlExtraChannelInfo other && type == other.type && name.equals(other.name)
                && bitsPerSample == other.bitsPerSample && exponentBitsPerSample == other.exponentBitsPerSample;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, name, bitsPerSample, exponentBitsPerSample);
    }

    @Override
    public String toString() {
        return "JxlExtraChannelInfo[type=" + type + ", name=" + name + ", bitsPerSample=" + bitsPerSample
                + ", exponentBitsPerSample=" + exponentBitsPerSample + "]";
    }
}

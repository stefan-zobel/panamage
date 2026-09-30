package panamage.jxl;

import panamage.jxl.ffi.Jxl;

/**
 * The type of the samples of a {@link JxlImage}.
 */
public enum JxlSampleType {

    /** Unsigned 8-bit integers, stored in a {@code byte[]}. */
    UINT8(1, 8, 0),

    /** Unsigned 16-bit integers, stored in a {@code short[]}. */
    UINT16(2, 16, 0),

    /**
     * 32-bit floating point values, stored in a {@code float[]}; nominally
     * 0.0 to 1.0, but may go beyond that range for HDR and wide-gamut images.
     */
    FLOAT32(4, 32, 8);

    private final int bytesPerSample;
    private final int bits;
    private final int exponentBits;

    JxlSampleType(int bytesPerSample, int bits, int exponentBits) {
        this.bytesPerSample = bytesPerSample;
        this.bits = bits;
        this.exponentBits = exponentBits;
    }

    /**
     * Returns the size of one sample.
     *
     * @return the number of bytes per sample
     */
    public int bytesPerSample() {
        return bytesPerSample;
    }

    /** The value of {@code bits_per_sample} in {@code JxlBasicInfo}. */
    int bits() {
        return bits;
    }

    /** The value of {@code exponent_bits_per_sample} in {@code JxlBasicInfo}. */
    int exponentBits() {
        return exponentBits;
    }

    /** The corresponding {@code JxlDataType}. */
    int dataType() {
        return switch (this) {
            case UINT8 -> Jxl.JXL_TYPE_UINT8();
            case UINT16 -> Jxl.JXL_TYPE_UINT16();
            case FLOAT32 -> Jxl.JXL_TYPE_FLOAT();
        };
    }
}

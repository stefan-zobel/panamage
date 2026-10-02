package panamage.jxl;

import panamage.jxl.ffi.Jxl;

/**
 * The type of an extra channel of a JPEG XL image, such as alpha or a channel
 * of measured data. See {@link JxlChannels}.
 */
public enum JxlChannelType {

    /** Transparency; straight (not premultiplied) in {@link JxlChannels}. */
    ALPHA,

    /** Depth map. */
    DEPTH,

    /** Spot color (an ink of its own in print); read only. */
    SPOT_COLOR,

    /** Selection mask. */
    SELECTION_MASK,

    /** The K channel of a CMYK image; read only. */
    BLACK,

    /** Color filter array data of a camera sensor; read only. */
    CFA,

    /** Thermal image. */
    THERMAL,

    /**
     * A channel that decoders do not know how to use; also returned for the
     * types that the JPEG XL standard reserves for future use.
     */
    UNKNOWN,

    /**
     * A channel that decoders may ignore when they show the image, for
     * example a fluorescence channel of a microscope image.
     */
    OPTIONAL;

    /** The corresponding {@code JxlExtraChannelType}. */
    int nativeValue() {
        return switch (this) {
            case ALPHA -> Jxl.JXL_CHANNEL_ALPHA();
            case DEPTH -> Jxl.JXL_CHANNEL_DEPTH();
            case SPOT_COLOR -> Jxl.JXL_CHANNEL_SPOT_COLOR();
            case SELECTION_MASK -> Jxl.JXL_CHANNEL_SELECTION_MASK();
            case BLACK -> Jxl.JXL_CHANNEL_BLACK();
            case CFA -> Jxl.JXL_CHANNEL_CFA();
            case THERMAL -> Jxl.JXL_CHANNEL_THERMAL();
            case UNKNOWN -> Jxl.JXL_CHANNEL_UNKNOWN();
            case OPTIONAL -> Jxl.JXL_CHANNEL_OPTIONAL();
        };
    }

    /**
     * Returns whether {@link JxlEncoder} can write channels of this type:
     * spot colors, black and CFA channels need information that a
     * {@link JxlExtraChannel} does not carry.
     */
    boolean isWritable() {
        return this != SPOT_COLOR && this != BLACK && this != CFA;
    }

    /** Returns the type for a {@code JxlExtraChannelType}; reserved and unknown values give {@link #UNKNOWN}. */
    static JxlChannelType of(int nativeValue) {
        for (JxlChannelType type : values()) {
            if (type.nativeValue() == nativeValue) {
                return type;
            }
        }
        return UNKNOWN;
    }
}

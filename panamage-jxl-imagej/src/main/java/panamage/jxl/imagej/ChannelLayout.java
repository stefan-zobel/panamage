package panamage.jxl.imagej;

import java.util.List;

import panamage.jxl.JxlChannels;
import panamage.jxl.JxlDecoder;
import panamage.jxl.JxlExtraChannelInfo;
import panamage.jxl.JxlImageInfo;
import panamage.jxl.JxlSampleType;

/**
 * The rules that map the channels of a JPEG XL image to the channels of an
 * ImageJ image, shared by {@link ImagePlusConverter} and
 * {@link DatasetConverter}.
 * <p>
 * Every channel of a JPEG XL image becomes a channel of the ImageJ image, the
 * color channels first, with one exception: 8-bit red, green and blue without
 * extra channels become an RGB color image, as ImageJ shows photographs.
 */
final class ChannelLayout {

    private ChannelLayout() {
    }

    /**
     * Returns whether an image becomes an RGB color image in ImageJ.
     *
     * @param image the image
     * @return {@code true} for 8-bit red, green and blue without extra
     *         channels
     */
    static boolean isRgb(JxlChannels image) {
        return image.sampleType() == JxlSampleType.UINT8 && image.colorChannels() == 3
                && image.extraChannels().isEmpty();
    }

    /**
     * Decodes a JPEG XL image with all channels in a sample type that holds
     * every channel without loss.
     *
     * @param data the encoded image
     * @return the decoded image
     */
    static JxlChannels decode(byte[] data) {
        return JxlDecoder.decodeChannels(data, losslessType(data));
    }

    /**
     * Returns the smallest sample type that holds every channel of a JPEG XL
     * image without loss.
     *
     * @param data the encoded image
     * @return the sample type
     */
    static JxlSampleType losslessType(byte[] data) {
        return losslessType(JxlDecoder.readInfo(data), JxlDecoder.readExtraChannels(data));
    }

    /**
     * Returns the smallest sample type that holds the color channels and the
     * given extra channels without loss.
     *
     * @param info          the basic information of the image
     * @param extraChannels the extra channels of the image
     * @return the sample type
     */
    static JxlSampleType losslessType(JxlImageInfo info, List<JxlExtraChannelInfo> extraChannels) {
        JxlSampleType type = info.sampleType();
        for (JxlExtraChannelInfo channel : extraChannels) {
            type = wider(type, channel.sampleType());
        }
        return type;
    }

    private static JxlSampleType wider(JxlSampleType a, JxlSampleType b) {
        if (a == JxlSampleType.FLOAT32 || b == JxlSampleType.FLOAT32) {
            return JxlSampleType.FLOAT32;
        }
        if (a == JxlSampleType.UINT16 || b == JxlSampleType.UINT16) {
            return JxlSampleType.UINT16;
        }
        return JxlSampleType.UINT8;
    }
}

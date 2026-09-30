package panamage.jxl;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import panamage.jxl.ffi.Jxl;
import panamage.jxl.ffi.JxlBasicInfo;
import panamage.jxl.ffi.JxlColorEncoding;
import panamage.jxl.ffi.JxlPixelFormat;

/**
 * Encodes images to JPEG XL in one call.
 * <p>
 * The input is an 8-bit {@link JxlImage} with 1 to 4 channels (gray, gray and
 * alpha, RGB or RGBA) in sRGB or in the color space of its ICC profile; the
 * profile must match the channels (gray or RGB). The output is a bare JPEG XL
 * codestream. The encoder uses libjxl's native thread pool, so no Java code is
 * called back from native threads.
 */
public final class JxlEncoder {

    private JxlEncoder() {
    }

    /**
     * Encodes an image with {@link JxlEncodeOptions#defaults()}.
     *
     * @param image the image to encode
     * @return the JPEG XL codestream
     * @throws JxlException if libjxl rejects the image
     */
    public static byte[] encode(JxlImage image) {
        return encode(image, JxlEncodeOptions.defaults());
    }

    /**
     * Encodes an image with the given options.
     *
     * @param image   the image to encode
     * @param options the encoder settings
     * @return the JPEG XL codestream
     * @throws IllegalArgumentException if the image has more than 4 channels
     * @throws JxlException             if libjxl rejects the image or the settings
     */
    public static byte[] encode(JxlImage image, JxlEncodeOptions options) {
        return encode(image, options, JxlMetadata.NONE);
    }

    /**
     * Encodes an image with the given options and metadata.
     * <p>
     * With metadata, the output uses the JPEG XL container format and stores
     * EXIF and XMP in Brotli-compressed boxes. An EXIF orientation other than
     * 1 becomes the orientation of the image: the pixels are taken as stored,
     * as in a JPEG file, and decoders rotate them for display.
     *
     * @param image    the image to encode
     * @param options  the encoder settings
     * @param metadata the EXIF and XMP metadata to store
     * @return the JPEG XL file
     * @throws IllegalArgumentException if the image has more than 4 channels
     * @throws JxlException             if libjxl rejects the image, the settings or the metadata
     */
    public static byte[] encode(JxlImage image, JxlEncodeOptions options, JxlMetadata metadata) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(metadata, "metadata");
        if (image.channels() > 4) {
            throw new IllegalArgumentException("At most 4 channels are supported: " + image.channels());
        }
        try (Arena arena = Arena.ofConfined(); NativeEncoder encoder = NativeEncoder.create()) {
            if (!metadata.isEmpty()) {
                encoder.check(Jxl.JxlEncoderUseContainer(encoder.handle(), Jxl.JXL_TRUE()), "JxlEncoderUseContainer");
                encoder.check(Jxl.JxlEncoderUseBoxes(encoder.handle()), "JxlEncoderUseBoxes");
            }
            configure(encoder, image, options, metadata.orientation(), arena);
            addFrame(encoder, image, options, arena);
            addBoxes(encoder, metadata, arena);
            // Closes the frames and, if used, the boxes.
            Jxl.JxlEncoderCloseInput(encoder.handle());
            return encoder.collectOutput(arena);
        }
    }

    private static void addBoxes(NativeEncoder encoder, JxlMetadata metadata, Arena arena) {
        if (metadata.exif() != null) {
            // The Exif box starts with the offset of the TIFF header, which follows directly.
            byte[] content = new byte[4 + metadata.exif().length];
            System.arraycopy(metadata.exif(), 0, content, 4, metadata.exif().length);
            addBox(encoder, "Exif", content, arena);
        }
        if (metadata.xmp() != null) {
            addBox(encoder, "xml ", metadata.xmp(), arena);
        }
    }

    private static void addBox(NativeEncoder encoder, String type, byte[] content, Arena arena) {
        MemorySegment typeSegment = arena.allocateFrom(JAVA_BYTE, type.getBytes(StandardCharsets.US_ASCII));
        MemorySegment contentSegment = arena.allocateFrom(JAVA_BYTE, content);
        encoder.check(Jxl.JxlEncoderAddBox(encoder.handle(), typeSegment, contentSegment, contentSegment.byteSize(),
                Jxl.JXL_TRUE()), "JxlEncoderAddBox(" + type.strip() + ")");
    }

    private static void configure(NativeEncoder encoder, JxlImage image, JxlEncodeOptions options, int orientation,
            Arena arena) {
        boolean gray = image.channels() <= 2;
        boolean alpha = image.channels() == 2 || image.channels() == 4;

        MemorySegment info = arena.allocate(JxlBasicInfo.layout());
        Jxl.JxlEncoderInitBasicInfo(info);
        JxlBasicInfo.xsize(info, image.width());
        JxlBasicInfo.ysize(info, image.height());
        JxlBasicInfo.bits_per_sample(info, 8);
        JxlBasicInfo.exponent_bits_per_sample(info, 0);
        JxlBasicInfo.num_color_channels(info, gray ? 1 : 3);
        JxlBasicInfo.num_extra_channels(info, alpha ? 1 : 0);
        JxlBasicInfo.alpha_bits(info, alpha ? 8 : 0);
        JxlBasicInfo.alpha_exponent_bits(info, 0);
        JxlBasicInfo.orientation(info, orientation);
        // Lossless encoding must keep the original color space instead of converting to XYB.
        JxlBasicInfo.uses_original_profile(info, options.lossless() ? Jxl.JXL_TRUE() : Jxl.JXL_FALSE());
        encoder.check(Jxl.JxlEncoderSetBasicInfo(encoder.handle(), info), "JxlEncoderSetBasicInfo");

        if (image.iccProfile() != null) {
            MemorySegment icc = arena.allocateFrom(JAVA_BYTE, image.iccProfile());
            encoder.check(Jxl.JxlEncoderSetICCProfile(encoder.handle(), icc, icc.byteSize()),
                    "JxlEncoderSetICCProfile");
        } else {
            MemorySegment color = arena.allocate(JxlColorEncoding.layout());
            Jxl.JxlColorEncodingSetToSRGB(color, gray ? Jxl.JXL_TRUE() : Jxl.JXL_FALSE());
            encoder.check(Jxl.JxlEncoderSetColorEncoding(encoder.handle(), color), "JxlEncoderSetColorEncoding");
        }
    }

    private static void addFrame(NativeEncoder encoder, JxlImage image, JxlEncodeOptions options, Arena arena) {
        MemorySegment settings = encoder.createFrameSettings(options.effort());
        if (options.lossless()) {
            encoder.check(Jxl.JxlEncoderSetFrameLossless(settings, Jxl.JXL_TRUE()), "JxlEncoderSetFrameLossless");
        } else {
            encoder.check(Jxl.JxlEncoderSetFrameDistance(settings, options.distance()),
                    "JxlEncoderSetFrameDistance");
        }

        MemorySegment format = arena.allocate(JxlPixelFormat.layout());
        JxlPixelFormat.num_channels(format, image.channels());
        JxlPixelFormat.data_type(format, Jxl.JXL_TYPE_UINT8());
        JxlPixelFormat.endianness(format, Jxl.JXL_NATIVE_ENDIAN());
        JxlPixelFormat.align(format, 0L);

        MemorySegment pixels = arena.allocateFrom(JAVA_BYTE, image.pixels());
        encoder.check(Jxl.JxlEncoderAddImageFrame(settings, format, pixels, pixels.byteSize()),
                "JxlEncoderAddImageFrame");
    }
}

package panamage.jxl;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import panamage.jxl.ffi.Jxl;
import panamage.jxl.ffi.JxlBasicInfo;
import panamage.jxl.ffi.JxlColorEncoding;
import panamage.jxl.ffi.JxlPixelFormat;

/**
 * Decodes complete JPEG XL images in one call.
 * <p>
 * The decoder applies the orientation stored in the image, so width and
 * height always describe the upright image. Lossless images are returned in
 * their original color space; lossy images in their original color space if
 * libjxl can convert to it, otherwise in sRGB. If the returned pixels are not
 * sRGB, {@link JxlImage#iccProfile()} describes their color space; it may
 * depend on the requested sample type.
 * <p>
 * The pixels can be decoded to 8-bit, 16-bit or floating point samples,
 * independent of the bit depth of the image; {@link JxlImageInfo#sampleType()}
 * gives the type that represents the image without loss.
 * <p>
 * The decoder uses libjxl's native thread pool, so no Java code is called back
 * from native threads. For animations, only the first frame is returned.
 */
public final class JxlDecoder {

    private static final int RGBA = 4;

    /** Initial size of the native buffer that receives metadata boxes. */
    private static final int BOX_CHUNK_SIZE = 64 * 1024;

    private static final String EXIF_BOX = "Exif";
    private static final String XMP_BOX = "xml ";

    private JxlDecoder() {
    }

    /**
     * Reads the header of a JPEG XL image without decoding the pixels.
     *
     * @param data the encoded image
     * @return the image information
     * @throws JxlException if the data is not a valid JPEG XL image
     */
    public static JxlImageInfo readInfo(byte[] data) {
        Objects.requireNonNull(data, "data");
        try (Arena arena = Arena.ofConfined(); NativeDecoder decoder = NativeDecoder.create()) {
            decoder.start(Jxl.JXL_DEC_BASIC_INFO() | Jxl.JXL_DEC_COLOR_ENCODING(),
                    arena.allocateFrom(JAVA_BYTE, data));
            MemorySegment handle = decoder.handle();
            MemorySegment info = null;
            while (true) {
                int status = Jxl.JxlDecoderProcessInput(handle);
                if (status == Jxl.JXL_DEC_BASIC_INFO()) {
                    info = basicInfo(handle, arena);
                } else if (status == Jxl.JXL_DEC_COLOR_ENCODING() && info != null) {
                    return new JxlImageInfo(JxlBasicInfo.xsize(info), JxlBasicInfo.ysize(info),
                            JxlBasicInfo.num_color_channels(info), JxlBasicInfo.alpha_bits(info) > 0,
                            JxlBasicInfo.bits_per_sample(info), JxlBasicInfo.exponent_bits_per_sample(info),
                            JxlBasicInfo.have_animation(info) != 0,
                            outputProfile(handle, false, arena));
                } else {
                    throw NativeDecoder.failure(status);
                }
            }
        }
    }

    /**
     * Reads the EXIF and XMP metadata of a JPEG XL image without decoding the
     * pixels. Compressed metadata boxes are decompressed.
     * <p>
     * The EXIF orientation is set to 1 (upright) in the returned copy,
     * because the decoder applies the image orientation to the pixels.
     *
     * @param data the encoded image
     * @return the metadata, or {@link JxlMetadata#NONE} for a bare codestream
     * @throws JxlException if the data is not a valid JPEG XL image
     */
    public static JxlMetadata readMetadata(byte[] data) {
        return readMetadata(data, BOX_CHUNK_SIZE);
    }

    /**
     * Like {@link #readMetadata(byte[])}, with a given initial buffer size
     * (tests use a small size to exercise the multi-chunk path).
     */
    static JxlMetadata readMetadata(byte[] data, int chunkSize) {
        Objects.requireNonNull(data, "data");
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
        }
        try (Arena arena = Arena.ofConfined(); NativeDecoder decoder = NativeDecoder.create()) {
            MemorySegment handle = decoder.handle();
            NativeDecoder.check(Jxl.JxlDecoderSetDecompressBoxes(handle, Jxl.JXL_TRUE()),
                    "JxlDecoderSetDecompressBoxes");
            decoder.start(Jxl.JXL_DEC_BOX() | Jxl.JXL_DEC_BOX_COMPLETE(), arena.allocateFrom(JAVA_BYTE, data));

            MemorySegment type = arena.allocate(4);
            MemorySegment chunk = arena.allocate(chunkSize);
            String currentType = null;
            ByteArrayOutputStream box = null;
            byte[] exif = null;
            byte[] xmp = null;
            while (true) {
                int status = Jxl.JxlDecoderProcessInput(handle);
                if (status == Jxl.JXL_DEC_BOX()) {
                    NativeDecoder.check(Jxl.JxlDecoderGetBoxType(handle, type, Jxl.JXL_TRUE()),
                            "JxlDecoderGetBoxType");
                    String name = new String(type.toArray(JAVA_BYTE), StandardCharsets.US_ASCII);
                    boolean wanted = (EXIF_BOX.equals(name) && exif == null) || (XMP_BOX.equals(name) && xmp == null);
                    if (wanted) {
                        currentType = name;
                        box = new ByteArrayOutputStream();
                        NativeDecoder.check(Jxl.JxlDecoderSetBoxBuffer(handle, chunk, chunk.byteSize()),
                                "JxlDecoderSetBoxBuffer");
                    }
                } else if (status == Jxl.JXL_DEC_BOX_NEED_MORE_OUTPUT()) {
                    if (drainBox(handle, chunk, box) == 0) {
                        // Grow the buffer if the decoder could not write anything into it.
                        chunk = arena.allocate(Math.multiplyExact(chunk.byteSize(), 2L));
                    }
                    NativeDecoder.check(Jxl.JxlDecoderSetBoxBuffer(handle, chunk, chunk.byteSize()),
                            "JxlDecoderSetBoxBuffer");
                } else if (status == Jxl.JXL_DEC_BOX_COMPLETE() || status == Jxl.JXL_DEC_SUCCESS()) {
                    if (box != null) {
                        drainBox(handle, chunk, box);
                        if (EXIF_BOX.equals(currentType)) {
                            exif = exifFromBox(box.toByteArray());
                        } else {
                            xmp = box.toByteArray();
                        }
                        box = null;
                        currentType = null;
                    }
                    if (status == Jxl.JXL_DEC_SUCCESS()) {
                        return exif == null && xmp == null ? JxlMetadata.NONE : new JxlMetadata(exif, xmp);
                    }
                } else {
                    throw NativeDecoder.failure(status);
                }
            }
        }
    }

    /** Releases the box buffer and appends the bytes written to it. */
    private static long drainBox(MemorySegment decoder, MemorySegment chunk, ByteArrayOutputStream out) {
        long unused = Jxl.JxlDecoderReleaseBoxBuffer(decoder);
        long written = chunk.byteSize() - unused;
        out.write(chunk.asSlice(0L, written).toArray(JAVA_BYTE), 0, (int) written);
        return written;
    }

    /**
     * Strips the 4-byte TIFF header offset of an Exif box and sets the
     * orientation to upright, or returns {@code null} for a malformed box.
     */
    private static byte[] exifFromBox(byte[] box) {
        if (box.length < 4) {
            return null;
        }
        long offset = (box[0] & 0xFFL) << 24 | (box[1] & 0xFF) << 16 | (box[2] & 0xFF) << 8 | (box[3] & 0xFF);
        if (4 + offset >= box.length) {
            return null;
        }
        byte[] tiff = Arrays.copyOfRange(box, (int) (4 + offset), box.length);
        return Exif.withOrientation(tiff, Exif.UPRIGHT);
    }

    /**
     * Decodes a JPEG XL image (codestream or container) to 8-bit RGBA.
     *
     * @param data the encoded image
     * @return the decoded image with 4 channels
     * @throws JxlException if the data is not a valid JPEG XL image
     */
    public static JxlImage.Uint8 decode(byte[] data) {
        return decode(data, RGBA);
    }

    /**
     * Decodes a JPEG XL image (codestream or container) to 8 bits per sample.
     * <p>
     * Use {@link JxlImageInfo#channels()} to get all channels of the image
     * without conversion. Requesting 3 or 4 channels for a grayscale image
     * repeats the gray value; requesting 1 or 2 channels for a color image is
     * handled by libjxl.
     *
     * @param data     the encoded image
     * @param channels the number of channels to produce: 1 (gray), 2 (gray and
     *                 alpha), 3 (RGB) or 4 (RGBA); alpha is opaque if the image
     *                 has none
     * @return the decoded image
     * @throws IllegalArgumentException if {@code channels} is not 1 to 4
     * @throws JxlException             if the data is not a valid JPEG XL image
     */
    public static JxlImage.Uint8 decode(byte[] data, int channels) {
        return (JxlImage.Uint8) decode(data, channels, JxlSampleType.UINT8);
    }

    /**
     * Decodes a JPEG XL image (codestream or container) to the given sample
     * type.
     * <p>
     * Use {@link JxlImageInfo#channels()} and {@link JxlImageInfo#sampleType()}
     * to get the image without loss. Samples are scaled to the requested type:
     * an 8-bit image decoded to 16 bits has values up to 65535, and floating
     * point samples are nominally in the range 0.0 to 1.0. Converting
     * floating point samples beyond that range to integers clips them.
     * <p>
     * The returned image is a {@link JxlImage.Uint8}, {@link JxlImage.Uint16}
     * or {@link JxlImage.Float32}, according to {@code type}.
     *
     * @param data     the encoded image
     * @param channels the number of channels to produce: 1 (gray), 2 (gray and
     *                 alpha), 3 (RGB) or 4 (RGBA); alpha is opaque if the image
     *                 has none
     * @param type     the sample type to produce
     * @return the decoded image
     * @throws IllegalArgumentException if {@code channels} is not 1 to 4
     * @throws JxlException             if the data is not a valid JPEG XL image
     */
    public static JxlImage decode(byte[] data, int channels, JxlSampleType type) {
        return decode(data, channels, type, false);
    }

    /**
     * Like {@link #decode(byte[], int, JxlSampleType)}; with
     * {@code keepSrgbProfile}, the image also carries libjxl's ICC profile of
     * sRGB pixels instead of {@code null} (the conformance tests compare it
     * with the reference profile).
     */
    static JxlImage decode(byte[] data, int channels, JxlSampleType type, boolean keepSrgbProfile) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(type, "type");
        checkChannels(channels);
        try (Arena arena = Arena.ofConfined()) {
            return decode(arena.allocateFrom(JAVA_BYTE, data), channels, type, keepSrgbProfile, arena);
        }
    }

    /**
     * Decodes a JPEG XL image (codestream or container) to 8-bit RGBA.
     * <p>
     * A native segment is passed to libjxl without copying; a heap segment is
     * copied to native memory first.
     *
     * @param data the encoded image
     * @return the decoded image with 4 channels
     * @throws JxlException if the data is not a valid JPEG XL image
     */
    public static JxlImage.Uint8 decode(MemorySegment data) {
        Objects.requireNonNull(data, "data");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = data.isNative() ? data : arena.allocate(data.byteSize()).copyFrom(data);
            return (JxlImage.Uint8) decode(input, RGBA, JxlSampleType.UINT8, false, arena);
        }
    }

    private static void checkChannels(int channels) {
        if (channels < 1 || channels > 4) {
            throw new IllegalArgumentException("channels must be 1 to 4: " + channels);
        }
    }

    private static JxlImage decode(MemorySegment input, int channels, JxlSampleType type, boolean keepSrgbProfile,
            Arena arena) {
        try (NativeDecoder decoder = NativeDecoder.create()) {
            // JxlImage promises straight alpha, also for images stored with premultiplied alpha.
            NativeDecoder.check(Jxl.JxlDecoderSetUnpremultiplyAlpha(decoder.handle(), Jxl.JXL_TRUE()),
                    "JxlDecoderSetUnpremultiplyAlpha");
            decoder.start(Jxl.JXL_DEC_BASIC_INFO() | Jxl.JXL_DEC_COLOR_ENCODING() | Jxl.JXL_DEC_FULL_IMAGE(),
                    input);
            return run(decoder.handle(), channels, type, keepSrgbProfile, arena);
        }
    }

    private static JxlImage run(MemorySegment decoder, int channels, JxlSampleType type, boolean keepSrgbProfile,
            Arena arena) {
        MemorySegment format = arena.allocate(JxlPixelFormat.layout());
        JxlPixelFormat.num_channels(format, channels);
        JxlPixelFormat.data_type(format, type.dataType());
        JxlPixelFormat.endianness(format, Jxl.JXL_NATIVE_ENDIAN());
        JxlPixelFormat.align(format, 0L);

        int width = 0;
        int height = 0;
        MemorySegment pixels = null;
        while (true) {
            int status = Jxl.JxlDecoderProcessInput(decoder);
            if (status == Jxl.JXL_DEC_BASIC_INFO()) {
                MemorySegment info = basicInfo(decoder, arena);
                width = JxlBasicInfo.xsize(info);
                height = JxlBasicInfo.ysize(info);
            } else if (status == Jxl.JXL_DEC_COLOR_ENCODING()) {
                // The profile is read after decoding, when it describes the produced pixels.
                continue;
            } else if (status == Jxl.JXL_DEC_NEED_IMAGE_OUT_BUFFER()) {
                MemorySegment sizeOut = arena.allocate(JAVA_LONG);
                NativeDecoder.check(Jxl.JxlDecoderImageOutBufferSize(decoder, format, sizeOut),
                        "JxlDecoderImageOutBufferSize");
                long size = sizeOut.get(JAVA_LONG, 0L);
                if (size / type.bytesPerSample() > Integer.MAX_VALUE - 8) {
                    throw new JxlException("Image too large for a Java array: " + size + " bytes");
                }
                pixels = arena.allocate(size, type.bytesPerSample());
                NativeDecoder.check(Jxl.JxlDecoderSetImageOutBuffer(decoder, format, pixels, size),
                        "JxlDecoderSetImageOutBuffer");
            } else if (status == Jxl.JXL_DEC_FULL_IMAGE() || status == Jxl.JXL_DEC_SUCCESS()) {
                break;
            } else {
                throw NativeDecoder.failure(status);
            }
        }
        if (pixels == null) {
            throw new JxlException("The JPEG XL data contains no image");
        }
        byte[] iccProfile = outputProfile(decoder, keepSrgbProfile, arena);
        return switch (type) {
            case UINT8 -> new JxlImage.Uint8(width, height, channels, pixels.toArray(JAVA_BYTE), iccProfile);
            case UINT16 -> new JxlImage.Uint16(width, height, channels, pixels.toArray(JAVA_SHORT), iccProfile);
            case FLOAT32 -> new JxlImage.Float32(width, height, channels, pixels.toArray(JAVA_FLOAT), iccProfile);
        };
    }

    private static MemorySegment basicInfo(MemorySegment decoder, Arena arena) {
        MemorySegment info = arena.allocate(JxlBasicInfo.layout());
        NativeDecoder.check(Jxl.JxlDecoderGetBasicInfo(decoder, info), "JxlDecoderGetBasicInfo");
        return info;
    }

    /**
     * Returns the ICC profile of the pixels the decoder produces, or
     * {@code null} if they are sRGB and {@code keepSrgbProfile} is false.
     * Valid after the COLOR_ENCODING event.
     */
    private static byte[] outputProfile(MemorySegment decoder, boolean keepSrgbProfile, Arena arena) {
        MemorySegment encoding = arena.allocate(JxlColorEncoding.layout());
        int target = Jxl.JXL_COLOR_PROFILE_TARGET_DATA();
        if (!keepSrgbProfile
                && Jxl.JxlDecoderGetColorAsEncodedProfile(decoder, target, encoding) == Jxl.JXL_DEC_SUCCESS()
                && isSrgb(encoding)) {
            return null;
        }
        MemorySegment sizeOut = arena.allocate(JAVA_LONG);
        NativeDecoder.check(Jxl.JxlDecoderGetICCProfileSize(decoder, target, sizeOut), "JxlDecoderGetICCProfileSize");
        long size = sizeOut.get(JAVA_LONG, 0L);
        MemorySegment icc = arena.allocate(size);
        NativeDecoder.check(Jxl.JxlDecoderGetColorAsICCProfile(decoder, target, icc, size),
                "JxlDecoderGetColorAsICCProfile");
        return icc.toArray(JAVA_BYTE);
    }

    private static boolean isSrgb(MemorySegment encoding) {
        int colorSpace = JxlColorEncoding.color_space(encoding);
        boolean gray = colorSpace == Jxl.JXL_COLOR_SPACE_GRAY();
        return (gray || colorSpace == Jxl.JXL_COLOR_SPACE_RGB())
                && JxlColorEncoding.white_point(encoding) == Jxl.JXL_WHITE_POINT_D65()
                && JxlColorEncoding.transfer_function(encoding) == Jxl.JXL_TRANSFER_FUNCTION_SRGB()
                && (gray || JxlColorEncoding.primaries(encoding) == Jxl.JXL_PRIMARIES_SRGB());
    }
}

package panamage.jxl;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

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
 * sRGB, {@link JxlImage#iccProfile()} describes their color space.
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
                            JxlBasicInfo.bits_per_sample(info), JxlBasicInfo.have_animation(info) != 0,
                            outputProfile(handle, arena));
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
    public static JxlImage decode(byte[] data) {
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
    public static JxlImage decode(byte[] data, int channels) {
        Objects.requireNonNull(data, "data");
        checkChannels(channels);
        try (Arena arena = Arena.ofConfined()) {
            return decode(arena.allocateFrom(JAVA_BYTE, data), channels, arena);
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
    public static JxlImage decode(MemorySegment data) {
        Objects.requireNonNull(data, "data");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = data.isNative() ? data : arena.allocate(data.byteSize()).copyFrom(data);
            return decode(input, RGBA, arena);
        }
    }

    private static void checkChannels(int channels) {
        if (channels < 1 || channels > 4) {
            throw new IllegalArgumentException("channels must be 1 to 4: " + channels);
        }
    }

    private static JxlImage decode(MemorySegment input, int channels, Arena arena) {
        try (NativeDecoder decoder = NativeDecoder.create()) {
            decoder.start(Jxl.JXL_DEC_BASIC_INFO() | Jxl.JXL_DEC_COLOR_ENCODING() | Jxl.JXL_DEC_FULL_IMAGE(),
                    input);
            return run(decoder.handle(), channels, arena);
        }
    }

    private static JxlImage run(MemorySegment decoder, int channels, Arena arena) {
        MemorySegment format = arena.allocate(JxlPixelFormat.layout());
        JxlPixelFormat.num_channels(format, channels);
        JxlPixelFormat.data_type(format, Jxl.JXL_TYPE_UINT8());
        JxlPixelFormat.endianness(format, Jxl.JXL_NATIVE_ENDIAN());
        JxlPixelFormat.align(format, 0L);

        int width = 0;
        int height = 0;
        byte[] iccProfile = null;
        MemorySegment pixels = null;
        while (true) {
            int status = Jxl.JxlDecoderProcessInput(decoder);
            if (status == Jxl.JXL_DEC_BASIC_INFO()) {
                MemorySegment info = basicInfo(decoder, arena);
                width = JxlBasicInfo.xsize(info);
                height = JxlBasicInfo.ysize(info);
            } else if (status == Jxl.JXL_DEC_COLOR_ENCODING()) {
                iccProfile = outputProfile(decoder, arena);
            } else if (status == Jxl.JXL_DEC_NEED_IMAGE_OUT_BUFFER()) {
                MemorySegment sizeOut = arena.allocate(JAVA_LONG);
                NativeDecoder.check(Jxl.JxlDecoderImageOutBufferSize(decoder, format, sizeOut),
                        "JxlDecoderImageOutBufferSize");
                long size = sizeOut.get(JAVA_LONG, 0L);
                if (size > Integer.MAX_VALUE - 8) {
                    throw new JxlException("Image too large for a byte array: " + size + " bytes");
                }
                pixels = arena.allocate(size);
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
        return new JxlImage(width, height, channels, pixels.toArray(JAVA_BYTE), iccProfile);
    }

    private static MemorySegment basicInfo(MemorySegment decoder, Arena arena) {
        MemorySegment info = arena.allocate(JxlBasicInfo.layout());
        NativeDecoder.check(Jxl.JxlDecoderGetBasicInfo(decoder, info), "JxlDecoderGetBasicInfo");
        return info;
    }

    /**
     * Returns the ICC profile of the pixels the decoder produces, or
     * {@code null} if they are sRGB. Valid after the COLOR_ENCODING event.
     */
    private static byte[] outputProfile(MemorySegment decoder, Arena arena) {
        MemorySegment encoding = arena.allocate(JxlColorEncoding.layout());
        int target = Jxl.JXL_COLOR_PROFILE_TARGET_DATA();
        if (Jxl.JxlDecoderGetColorAsEncodedProfile(decoder, target, encoding) == Jxl.JXL_DEC_SUCCESS()
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

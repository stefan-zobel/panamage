package panamage.jxl;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import panamage.jxl.ffi.Jxl;
import panamage.jxl.ffi.JxlAnimationHeader;
import panamage.jxl.ffi.JxlBasicInfo;
import panamage.jxl.ffi.JxlColorEncoding;
import panamage.jxl.ffi.JxlFrameHeader;
import panamage.jxl.ffi.JxlLayerInfo;
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
 * from native threads.
 * <p>
 * Of an animation, {@link #decode(byte[], int, JxlSampleType)} returns the
 * first frame; {@link #decodeFrames(byte[], int, JxlSampleType)} returns all
 * frames, and {@link JxlFrameDecoder} decodes one frame at a time.
 * {@link #readAnimationInfo(byte[])} gives the number of frames and their
 * timing without decoding the pixels. Frames are returned as displayed:
 * libjxl combines frames without duration with the following frame, so every
 * frame covers the whole image. {@link JxlFrameEncoder} writes animations.
 * <p>
 * Images and metadata boxes beyond the {@link JxlLimits} are rejected with a
 * {@link JxlLimitException} before their memory is allocated; the methods
 * without a {@code JxlLimits} parameter use {@link JxlLimits#defaults()}.
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
        try (Arena arena = Arena.ofConfined(); NativeDecoder decoder = NativeDecoder.createWithoutThreads()) {
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
     * Reads the number of frames of a JPEG XL image and their timing without
     * decoding the pixels. A still image has one frame.
     *
     * @param data the encoded image
     * @return the frames and their timing
     * @throws JxlException if the data is not a valid JPEG XL image
     */
    public static JxlAnimationInfo readAnimationInfo(byte[] data) {
        Objects.requireNonNull(data, "data");
        try (Arena arena = Arena.ofConfined(); NativeDecoder decoder = NativeDecoder.createWithoutThreads()) {
            decoder.start(Jxl.JXL_DEC_BASIC_INFO() | Jxl.JXL_DEC_FRAME(), arena.allocateFrom(JAVA_BYTE, data));
            MemorySegment handle = decoder.handle();
            MemorySegment header = arena.allocate(JxlFrameHeader.layout());
            MemorySegment info = null;
            List<JxlFrameInfo> frames = new ArrayList<>();
            while (true) {
                int status = Jxl.JxlDecoderProcessInput(handle);
                if (status == Jxl.JXL_DEC_BASIC_INFO()) {
                    info = basicInfo(handle, arena);
                } else if (status == Jxl.JXL_DEC_FRAME() && info != null) {
                    frames.add(frameInfo(handle, info, header));
                } else if (status == Jxl.JXL_DEC_SUCCESS() && !frames.isEmpty()) {
                    if (JxlBasicInfo.have_animation(info) == 0) {
                        return new JxlAnimationInfo(0, 0, 0, frames);
                    }
                    MemorySegment animation = JxlBasicInfo.animation(info);
                    return new JxlAnimationInfo(Integer.toUnsignedLong(JxlAnimationHeader.tps_numerator(animation)),
                            Integer.toUnsignedLong(JxlAnimationHeader.tps_denominator(animation)),
                            Integer.toUnsignedLong(JxlAnimationHeader.num_loops(animation)), frames);
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
     * @throws JxlLimitException if a metadata box exceeds the default limits
     * @throws JxlException      if the data is not a valid JPEG XL image
     */
    public static JxlMetadata readMetadata(byte[] data) {
        return readMetadata(data, JxlLimits.defaults());
    }

    /**
     * Like {@link #readMetadata(byte[])}, with the given limits.
     *
     * @param data   the encoded image
     * @param limits the limits; only {@link JxlLimits#maxMetadataBytes()}
     *               applies
     * @return the metadata, or {@link JxlMetadata#NONE} for a bare codestream
     * @throws JxlLimitException if a metadata box exceeds the limits
     * @throws JxlException      if the data is not a valid JPEG XL image
     */
    public static JxlMetadata readMetadata(byte[] data, JxlLimits limits) {
        return readMetadata(data, BOX_CHUNK_SIZE, limits);
    }

    /**
     * Like {@link #readMetadata(byte[], JxlLimits)}, with a given initial
     * buffer size (tests use a small size to exercise the multi-chunk path).
     */
    static JxlMetadata readMetadata(byte[] data, int chunkSize, JxlLimits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
        }
        try (Arena arena = Arena.ofConfined(); NativeDecoder decoder = NativeDecoder.createWithoutThreads()) {
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
                    if (drainBox(handle, chunk, box, currentType, limits) == 0) {
                        // Grow the buffer if the decoder could not write anything into it;
                        // the box then holds more than the buffer can take.
                        limits.checkMetadata(currentType, box.size() + chunk.byteSize() + 1L);
                        chunk = arena.allocate(Math.multiplyExact(chunk.byteSize(), 2L));
                    }
                    NativeDecoder.check(Jxl.JxlDecoderSetBoxBuffer(handle, chunk, chunk.byteSize()),
                            "JxlDecoderSetBoxBuffer");
                } else if (status == Jxl.JXL_DEC_BOX_COMPLETE() || status == Jxl.JXL_DEC_SUCCESS()) {
                    if (box != null) {
                        drainBox(handle, chunk, box, currentType, limits);
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

    /**
     * Releases the box buffer and appends the bytes written to it.
     *
     * @throws JxlLimitException if the box grows beyond the metadata limit
     */
    private static long drainBox(MemorySegment decoder, MemorySegment chunk, ByteArrayOutputStream out,
            String boxType, JxlLimits limits) {
        long unused = Jxl.JxlDecoderReleaseBoxBuffer(decoder);
        long written = chunk.byteSize() - unused;
        limits.checkMetadata(boxType, out.size() + written);
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
     * @throws JxlLimitException if the image exceeds the default limits
     * @throws JxlException      if the data is not a valid JPEG XL image
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
     * @throws JxlLimitException        if the image exceeds the default limits
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
     * <p>
     * While decoding, the pixels are held twice for a short time: in native
     * memory, where libjxl writes them, and in the returned array. A
     * 256-megapixel RGBA image needs 1 GiB for each copy with 8-bit samples
     * and 4 GiB with floating point samples.
     *
     * @param data     the encoded image
     * @param channels the number of channels to produce: 1 (gray), 2 (gray and
     *                 alpha), 3 (RGB) or 4 (RGBA); alpha is opaque if the image
     *                 has none
     * @param type     the sample type to produce
     * @return the decoded image
     * @throws IllegalArgumentException if {@code channels} is not 1 to 4
     * @throws JxlLimitException        if the image exceeds the default limits
     * @throws JxlException             if the data is not a valid JPEG XL image
     */
    public static JxlImage decode(byte[] data, int channels, JxlSampleType type) {
        return decode(data, channels, type, JxlLimits.defaults());
    }

    /**
     * Like {@link #decode(byte[], int, JxlSampleType)}, with the given limits.
     *
     * @param data     the encoded image
     * @param channels the number of channels to produce: 1 (gray), 2 (gray and
     *                 alpha), 3 (RGB) or 4 (RGBA); alpha is opaque if the image
     *                 has none
     * @param type     the sample type to produce
     * @param limits   the limits; only {@link JxlLimits#maxPixels()} applies
     * @return the decoded image
     * @throws IllegalArgumentException if {@code channels} is not 1 to 4
     * @throws JxlLimitException        if the image exceeds the limits
     * @throws JxlException             if the data is not a valid JPEG XL image
     */
    public static JxlImage decode(byte[] data, int channels, JxlSampleType type, JxlLimits limits) {
        return decode(data, channels, type, limits, false);
    }

    /**
     * Like {@link #decode(byte[], int, JxlSampleType, JxlLimits)}; with
     * {@code keepSrgbProfile}, the image also carries libjxl's ICC profile of
     * sRGB pixels instead of {@code null} (the conformance tests compare it
     * with the reference profile).
     */
    static JxlImage decode(byte[] data, int channels, JxlSampleType type, JxlLimits limits,
            boolean keepSrgbProfile) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(limits, "limits");
        checkChannels(channels);
        try (Arena arena = Arena.ofConfined()) {
            return decode(arena.allocateFrom(JAVA_BYTE, data), channels, type, limits, keepSrgbProfile, arena);
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
     * @throws JxlLimitException if the image exceeds the default limits
     * @throws JxlException      if the data is not a valid JPEG XL image
     */
    public static JxlImage.Uint8 decode(MemorySegment data) {
        return decode(data, JxlLimits.defaults());
    }

    /**
     * Like {@link #decode(MemorySegment)}, with the given limits.
     *
     * @param data   the encoded image
     * @param limits the limits; only {@link JxlLimits#maxPixels()} applies
     * @return the decoded image with 4 channels
     * @throws JxlLimitException if the image exceeds the limits
     * @throws JxlException      if the data is not a valid JPEG XL image
     */
    public static JxlImage.Uint8 decode(MemorySegment data, JxlLimits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = data.isNative() ? data : arena.allocate(data.byteSize()).copyFrom(data);
            return (JxlImage.Uint8) decode(input, RGBA, JxlSampleType.UINT8, limits, false, arena);
        }
    }

    /**
     * Decodes all frames of a JPEG XL animation (codestream or container) to
     * the given sample type, as they are displayed; a still image gives one
     * frame. See {@link #decode(byte[], int, JxlSampleType)} for the channels
     * and sample types.
     * <p>
     * All frames are held in memory at once, so the default pixel limit
     * applies to all frames together. Use {@link JxlFrameDecoder} to process
     * long animations one frame at a time.
     *
     * @param data     the encoded image
     * @param channels the number of channels to produce: 1 (gray), 2 (gray and
     *                 alpha), 3 (RGB) or 4 (RGBA); alpha is opaque if the image
     *                 has none
     * @param type     the sample type to produce
     * @return the frames in order
     * @throws IllegalArgumentException if {@code channels} is not 1 to 4
     * @throws JxlLimitException        if the frames exceed the default limits
     * @throws JxlException             if the data is not a valid JPEG XL image
     */
    public static List<JxlFrame> decodeFrames(byte[] data, int channels, JxlSampleType type) {
        return decodeFrames(data, channels, type, JxlLimits.defaults());
    }

    /**
     * Like {@link #decodeFrames(byte[], int, JxlSampleType)}, with the given
     * limits.
     *
     * @param data     the encoded image
     * @param channels the number of channels to produce: 1 (gray), 2 (gray and
     *                 alpha), 3 (RGB) or 4 (RGBA); alpha is opaque if the image
     *                 has none
     * @param type     the sample type to produce
     * @param limits   the limits; only {@link JxlLimits#maxPixels()} applies, to
     *                 all frames together and to every layer
     * @return the frames in order
     * @throws IllegalArgumentException if {@code channels} is not 1 to 4
     * @throws JxlLimitException        if the frames exceed the limits
     * @throws JxlException             if the data is not a valid JPEG XL image
     */
    public static List<JxlFrame> decodeFrames(byte[] data, int channels, JxlSampleType type, JxlLimits limits) {
        List<JxlFrame> frames = new ArrayList<>();
        try (JxlFrameDecoder decoder = JxlFrameDecoder.open(data, channels, type, limits, true)) {
            for (JxlFrame frame = decoder.next(); frame != null; frame = decoder.next()) {
                frames.add(frame);
            }
        }
        return List.copyOf(frames);
    }

    static void checkChannels(int channels) {
        if (channels < 1 || channels > 4) {
            throw new IllegalArgumentException("channels must be 1 to 4: " + channels);
        }
    }

    private static JxlImage decode(MemorySegment input, int channels, JxlSampleType type, JxlLimits limits,
            boolean keepSrgbProfile, Arena arena) {
        checkFrames(input, limits, 1, false, arena);
        try (NativeDecoder decoder = NativeDecoder.create()) {
            // JxlImage promises straight alpha, also for images stored with premultiplied alpha.
            NativeDecoder.check(Jxl.JxlDecoderSetUnpremultiplyAlpha(decoder.handle(), Jxl.JXL_TRUE()),
                    "JxlDecoderSetUnpremultiplyAlpha");
            decoder.start(Jxl.JXL_DEC_BASIC_INFO() | Jxl.JXL_DEC_COLOR_ENCODING() | Jxl.JXL_DEC_FULL_IMAGE(),
                    input);
            return run(decoder.handle(), channels, type, limits, keepSrgbProfile, arena);
        }
    }

    /**
     * Checks the image and the layers of its first displayed frames against
     * the pixel limit, without decoding pixels. With {@code allTogether}, it
     * also checks the displayed frames together against the limit, for
     * decoding that holds them all in memory.
     * <p>
     * A layer may be larger than the image, and with coalescing, which the
     * actual decoding uses, libjxl reports every frame with the size of the
     * image. So a separate decoder without coalescing reads the frame headers
     * first; a displayed frame ends with a layer that has a duration or is the
     * last one. Invalid or truncated data ends the check silently; the actual
     * decoding reports it.
     *
     * @param displayedFrames the number of displayed frames that are decoded
     * @throws JxlLimitException if the image, a layer or the frames together
     *                           exceed the limit
     */
    static void checkFrames(MemorySegment input, JxlLimits limits, int displayedFrames, boolean allTogether,
            Arena arena) {
        if (limits.maxPixels() == Long.MAX_VALUE) {
            return;
        }
        try (NativeDecoder decoder = NativeDecoder.createWithoutThreads()) {
            MemorySegment handle = decoder.handle();
            NativeDecoder.check(Jxl.JxlDecoderSetCoalescing(handle, Jxl.JXL_FALSE()), "JxlDecoderSetCoalescing");
            decoder.start(Jxl.JXL_DEC_BASIC_INFO() | Jxl.JXL_DEC_FRAME(), input);
            MemorySegment header = arena.allocate(JxlFrameHeader.layout());
            long width = 0;
            long height = 0;
            int channels = 0;
            int displayed = 0;
            while (displayed < displayedFrames) {
                int status = Jxl.JxlDecoderProcessInput(handle);
                if (status == Jxl.JXL_DEC_BASIC_INFO()) {
                    MemorySegment info = basicInfo(handle, arena);
                    channels = checkImage(info, limits);
                    width = Integer.toUnsignedLong(JxlBasicInfo.xsize(info));
                    height = Integer.toUnsignedLong(JxlBasicInfo.ysize(info));
                } else if (status == Jxl.JXL_DEC_FRAME()) {
                    NativeDecoder.check(Jxl.JxlDecoderGetFrameHeader(handle, header), "JxlDecoderGetFrameHeader");
                    MemorySegment layer = JxlFrameHeader.layer_info(header);
                    limits.checkPixels("Frame layer", Integer.toUnsignedLong(JxlLayerInfo.xsize(layer)),
                            Integer.toUnsignedLong(JxlLayerInfo.ysize(layer)), channels);
                    if (JxlFrameHeader.duration(header) != 0 || JxlFrameHeader.is_last(header) != 0) {
                        displayed++;
                        if (allTogether) {
                            limits.checkAnimation(displayed, width, height, channels);
                        }
                    }
                } else {
                    return;
                }
            }
        }
    }

    /**
     * Checks the size of the image against the pixel limit.
     *
     * @return the number of color and extra channels
     * @throws JxlLimitException if the image exceeds the limit
     */
    static int checkImage(MemorySegment info, JxlLimits limits) {
        int channels = JxlBasicInfo.num_color_channels(info) + JxlBasicInfo.num_extra_channels(info);
        limits.checkPixels("Image", Integer.toUnsignedLong(JxlBasicInfo.xsize(info)),
                Integer.toUnsignedLong(JxlBasicInfo.ysize(info)), channels);
        return channels;
    }

    private static JxlImage run(MemorySegment decoder, int channels, JxlSampleType type, JxlLimits limits,
            boolean keepSrgbProfile, Arena arena) {
        MemorySegment format = pixelFormat(channels, type, arena);
        int width = 0;
        int height = 0;
        MemorySegment pixels = null;
        while (true) {
            int status = Jxl.JxlDecoderProcessInput(decoder);
            if (status == Jxl.JXL_DEC_BASIC_INFO()) {
                MemorySegment info = basicInfo(decoder, arena);
                checkImage(info, limits);
                width = JxlBasicInfo.xsize(info);
                height = JxlBasicInfo.ysize(info);
            } else if (status == Jxl.JXL_DEC_COLOR_ENCODING()) {
                // The profile is read after decoding, when it describes the produced pixels.
                continue;
            } else if (status == Jxl.JXL_DEC_NEED_IMAGE_OUT_BUFFER()) {
                pixels = setImageOutBuffer(decoder, format, type, null, arena);
            } else if (status == Jxl.JXL_DEC_FULL_IMAGE() || status == Jxl.JXL_DEC_SUCCESS()) {
                break;
            } else {
                throw NativeDecoder.failure(status);
            }
        }
        if (pixels == null) {
            throw new JxlException("The JPEG XL data contains no image");
        }
        return toImage(type, width, height, channels, pixels, outputProfile(decoder, keepSrgbProfile, arena));
    }

    /** Describes interleaved samples of the given type in native byte order. */
    static MemorySegment pixelFormat(int channels, JxlSampleType type, Arena arena) {
        MemorySegment format = arena.allocate(JxlPixelFormat.layout());
        JxlPixelFormat.num_channels(format, channels);
        JxlPixelFormat.data_type(format, type.dataType());
        JxlPixelFormat.endianness(format, Jxl.JXL_NATIVE_ENDIAN());
        JxlPixelFormat.align(format, 0L);
        return format;
    }

    /**
     * Passes an output buffer for the pixels of the current frame to the
     * decoder, answering {@code JXL_DEC_NEED_IMAGE_OUT_BUFFER}. The given
     * buffer is reused if it has the required size.
     *
     * @param buffer a buffer of an earlier frame, or {@code null}
     * @return the buffer the decoder writes to
     * @throws JxlException if the pixels do not fit into a Java array
     */
    static MemorySegment setImageOutBuffer(MemorySegment decoder, MemorySegment format, JxlSampleType type,
            MemorySegment buffer, Arena arena) {
        long size;
        try (Arena temporary = Arena.ofConfined()) {
            MemorySegment sizeOut = temporary.allocate(JAVA_LONG);
            NativeDecoder.check(Jxl.JxlDecoderImageOutBufferSize(decoder, format, sizeOut),
                    "JxlDecoderImageOutBufferSize");
            size = sizeOut.get(JAVA_LONG, 0L);
        }
        if (size / type.bytesPerSample() > Integer.MAX_VALUE - 8) {
            throw new JxlException("Image too large for a Java array: " + size + " bytes");
        }
        MemorySegment pixels = buffer != null && buffer.byteSize() == size
                ? buffer : arena.allocate(size, type.bytesPerSample());
        NativeDecoder.check(Jxl.JxlDecoderSetImageOutBuffer(decoder, format, pixels, size),
                "JxlDecoderSetImageOutBuffer");
        return pixels;
    }

    /** Copies decoded pixels into a {@link JxlImage} of the given sample type. */
    static JxlImage toImage(JxlSampleType type, int width, int height, int channels, MemorySegment pixels,
            byte[] iccProfile) {
        return switch (type) {
            case UINT8 -> new JxlImage.Uint8(width, height, channels, pixels.toArray(JAVA_BYTE), iccProfile);
            case UINT16 -> new JxlImage.Uint16(width, height, channels, pixels.toArray(JAVA_SHORT), iccProfile);
            case FLOAT32 -> new JxlImage.Float32(width, height, channels, pixels.toArray(JAVA_FLOAT), iccProfile);
        };
    }

    /**
     * Reads the header of the current frame, answering {@code JXL_DEC_FRAME}.
     *
     * @param info   the basic info of the image, for the tick rate
     * @param header a buffer for the frame header
     */
    static JxlFrameInfo frameInfo(MemorySegment decoder, MemorySegment info, MemorySegment header) {
        NativeDecoder.check(Jxl.JxlDecoderGetFrameHeader(decoder, header), "JxlDecoderGetFrameHeader");
        long ticks = Integer.toUnsignedLong(JxlFrameHeader.duration(header));
        double millis = 0;
        if (JxlBasicInfo.have_animation(info) != 0) {
            MemorySegment animation = JxlBasicInfo.animation(info);
            long numerator = Integer.toUnsignedLong(JxlAnimationHeader.tps_numerator(animation));
            long denominator = Integer.toUnsignedLong(JxlAnimationHeader.tps_denominator(animation));
            if (numerator != 0) {
                millis = ticks * 1000.0 * denominator / numerator;
            }
        }
        String name = "";
        long nameLength = Integer.toUnsignedLong(JxlFrameHeader.name_length(header));
        if (nameLength > 0) {
            try (Arena arena = Arena.ofConfined()) {
                // The name is returned with a terminating zero byte.
                MemorySegment buffer = arena.allocate(nameLength + 1);
                NativeDecoder.check(Jxl.JxlDecoderGetFrameName(decoder, buffer, buffer.byteSize()),
                        "JxlDecoderGetFrameName");
                name = new String(buffer.asSlice(0L, nameLength).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
            }
        }
        return new JxlFrameInfo(ticks, millis, name);
    }

    static MemorySegment basicInfo(MemorySegment decoder, Arena arena) {
        MemorySegment info = arena.allocate(JxlBasicInfo.layout());
        NativeDecoder.check(Jxl.JxlDecoderGetBasicInfo(decoder, info), "JxlDecoderGetBasicInfo");
        return info;
    }

    /**
     * Returns the ICC profile of the pixels the decoder produces, or
     * {@code null} if they are sRGB and {@code keepSrgbProfile} is false.
     * Valid after the COLOR_ENCODING event.
     */
    static byte[] outputProfile(MemorySegment decoder, boolean keepSrgbProfile, Arena arena) {
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

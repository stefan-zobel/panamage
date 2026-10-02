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
import panamage.jxl.ffi.JxlBitDepth;
import panamage.jxl.ffi.JxlCmsInterface;
import panamage.jxl.ffi.JxlColorEncoding;
import panamage.jxl.ffi.JxlFrameHeader;
import panamage.jxl.ffi.JxlLayerInfo;
import panamage.jxl.ffi.JxlPixelFormat;
import panamage.jxl.internal.NativeLibraries;

/**
 * Decodes complete JPEG XL images in one call.
 * <p>
 * The decoder applies the orientation stored in the image, so width and
 * height always describe the upright image. Lossless images are returned in
 * their original color space; lossy images in their original color space if
 * libjxl can convert to it, otherwise in sRGB. If the returned pixels are not
 * sRGB, {@link JxlImage#iccProfile()} describes their color space; it may
 * depend on the requested sample type. With
 * {@link JxlDecodeOptions#withSrgb(boolean)}, libjxl converts the pixels of
 * all images to sRGB.
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
    private static final String BROTLI_BOX = "brob";

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
     * Reads the type, name and bit depth of the extra channels of a JPEG XL
     * image, such as alpha or the fluorescence channels of a microscope image,
     * without decoding the pixels.
     *
     * @param data the encoded image
     * @return the extra channels in the order of their planes in
     *         {@link #decodeChannels(byte[], JxlSampleType)}; empty if the
     *         image has only color channels
     * @throws JxlException if the data is not a valid JPEG XL image
     */
    public static List<JxlExtraChannelInfo> readExtraChannels(byte[] data) {
        Objects.requireNonNull(data, "data");
        try (Arena arena = Arena.ofConfined(); NativeDecoder decoder = NativeDecoder.createWithoutThreads()) {
            decoder.start(Jxl.JXL_DEC_BASIC_INFO(), arena.allocateFrom(JAVA_BYTE, data));
            MemorySegment handle = decoder.handle();
            int status = Jxl.JxlDecoderProcessInput(handle);
            if (status != Jxl.JXL_DEC_BASIC_INFO()) {
                throw NativeDecoder.failure(status);
            }
            return extraChannels(handle, basicInfo(handle, arena), arena);
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
     * Reads the EXIF and XMP metadata and the application-specific boxes of a
     * JPEG XL image without decoding the pixels. Compressed metadata boxes
     * are decompressed. Of several EXIF or XMP boxes, the first is returned.
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
            boolean currentCompressed = false;
            ByteArrayOutputStream box = null;
            byte[] exif = null;
            byte[] xmp = null;
            List<JxlBox> boxes = new ArrayList<>();
            long boxBytes = 0;
            while (true) {
                int status = Jxl.JxlDecoderProcessInput(handle);
                if (status == Jxl.JXL_DEC_BOX()) {
                    NativeDecoder.check(Jxl.JxlDecoderGetBoxType(handle, type, Jxl.JXL_TRUE()),
                            "JxlDecoderGetBoxType");
                    String name = new String(type.toArray(JAVA_BYTE), StandardCharsets.US_ASCII);
                    boolean wanted = (EXIF_BOX.equals(name) && exif == null) || (XMP_BOX.equals(name) && xmp == null)
                            || (JxlBox.isValidType(name) && !JxlBox.isReserved(name));
                    if (wanted) {
                        NativeDecoder.check(Jxl.JxlDecoderGetBoxType(handle, type, Jxl.JXL_FALSE()),
                                "JxlDecoderGetBoxType");
                        currentCompressed = BROTLI_BOX.equals(new String(type.toArray(JAVA_BYTE),
                                StandardCharsets.US_ASCII));
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
                        } else if (XMP_BOX.equals(currentType)) {
                            xmp = box.toByteArray();
                        } else {
                            // Many boxes of the limit each could still exhaust the memory.
                            boxBytes += box.size();
                            limits.checkMetadataBoxes(boxBytes);
                            boxes.add(new JxlBox(currentType, box.toByteArray(), currentCompressed));
                        }
                        box = null;
                        currentType = null;
                    }
                    if (status == Jxl.JXL_DEC_SUCCESS()) {
                        return exif == null && xmp == null && boxes.isEmpty() ? JxlMetadata.NONE
                                : new JxlMetadata(exif, xmp, boxes);
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
        return decode(data, channels, type, new JxlDecodeOptions(limits, false));
    }

    /**
     * Like {@link #decode(byte[], int, JxlSampleType)}, with the given
     * options, for example to convert the pixels to sRGB.
     *
     * @param data     the encoded image
     * @param channels the number of channels to produce: 1 (gray), 2 (gray and
     *                 alpha), 3 (RGB) or 4 (RGBA); alpha is opaque if the image
     *                 has none
     * @param type     the sample type to produce
     * @param options  the limits (only {@link JxlLimits#maxPixels()} applies)
     *                 and the color space
     * @return the decoded image
     * @throws IllegalArgumentException if {@code channels} is not 1 to 4
     * @throws JxlLimitException        if the image exceeds the limits
     * @throws JxlException             if the data is not a valid JPEG XL image
     */
    public static JxlImage decode(byte[] data, int channels, JxlSampleType type, JxlDecodeOptions options) {
        return decode(data, channels, type, options, false);
    }

    /**
     * Like {@link #decode(byte[], int, JxlSampleType, JxlDecodeOptions)}; with
     * {@code keepSrgbProfile}, the image also carries libjxl's ICC profile of
     * sRGB pixels instead of {@code null} (the conformance tests compare it
     * with the reference profile).
     */
    static JxlImage decode(byte[] data, int channels, JxlSampleType type, JxlDecodeOptions options,
            boolean keepSrgbProfile) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(options, "options");
        checkChannels(channels);
        try (Arena arena = Arena.ofConfined()) {
            return decode(arena.allocateFrom(JAVA_BYTE, data), channels, type, options, keepSrgbProfile, arena);
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
            return (JxlImage.Uint8) decode(input, RGBA, JxlSampleType.UINT8, new JxlDecodeOptions(limits, false),
                    false, arena);
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
        return decodeFrames(data, channels, type, new JxlDecodeOptions(limits, false));
    }

    /**
     * Like {@link #decodeFrames(byte[], int, JxlSampleType)}, with the given
     * options, for example to convert the pixels to sRGB.
     *
     * @param data     the encoded image
     * @param channels the number of channels to produce: 1 (gray), 2 (gray and
     *                 alpha), 3 (RGB) or 4 (RGBA); alpha is opaque if the image
     *                 has none
     * @param type     the sample type to produce
     * @param options  the limits (only {@link JxlLimits#maxPixels()} applies, to
     *                 all frames together and to every layer) and the color
     *                 space
     * @return the frames in order
     * @throws IllegalArgumentException if {@code channels} is not 1 to 4
     * @throws JxlLimitException        if the frames exceed the limits
     * @throws JxlException             if the data is not a valid JPEG XL image
     */
    public static List<JxlFrame> decodeFrames(byte[] data, int channels, JxlSampleType type,
            JxlDecodeOptions options) {
        checkChannels(channels);
        List<JxlFrame> frames = new ArrayList<>();
        try (JxlFrameDecoder decoder = JxlFrameDecoder.open(data, channels, type, options, true)) {
            for (JxlFrame frame = decoder.next(); frame != null; frame = decoder.next()) {
                frames.add(frame);
            }
        }
        return List.copyOf(frames);
    }

    /**
     * Decodes a JPEG XL image (codestream or container) with every channel in
     * an array of its own: the color channels (gray, or red, green and blue)
     * and all extra channels, such as alpha or the fluorescence channels of a
     * microscope image. Of an animation, the first frame is decoded.
     * <p>
     * The color channels are converted as by
     * {@link #decode(byte[], int, JxlSampleType)}, and alpha is straight (not
     * premultiplied). If all channels are integers with the same bit depth
     * and the requested type is an integer type that can hold them, the
     * samples keep their values, and {@link JxlChannels#bitsPerSample()} is
     * that bit depth: a 12-bit image decoded to {@link JxlSampleType#UINT16}
     * has samples from 0 to 4095. Otherwise, the samples of every channel are
     * scaled from its bit depth to the full range of the requested type. Use
     * {@link JxlImageInfo#sampleType()} and {@link #readExtraChannels(byte[])}
     * to choose a type without loss.
     *
     * @param data the encoded image
     * @param type the sample type to produce
     * @return the decoded image
     * @throws JxlLimitException if the image exceeds the default limits
     * @throws JxlException      if the data is not a valid JPEG XL image
     */
    public static JxlChannels decodeChannels(byte[] data, JxlSampleType type) {
        return decodeChannels(data, type, JxlLimits.defaults());
    }

    /**
     * Like {@link #decodeChannels(byte[], JxlSampleType)}, with the given
     * limits.
     *
     * @param data   the encoded image
     * @param type   the sample type to produce
     * @param limits the limits; only {@link JxlLimits#maxPixels()} applies
     * @return the decoded image
     * @throws JxlLimitException if the image exceeds the limits
     * @throws JxlException      if the data is not a valid JPEG XL image
     */
    public static JxlChannels decodeChannels(byte[] data, JxlSampleType type, JxlLimits limits) {
        return decodeChannels(data, type, new JxlDecodeOptions(limits, false));
    }

    /**
     * Like {@link #decodeChannels(byte[], JxlSampleType)}, with the given
     * options, for example to convert the color channels to sRGB.
     *
     * @param data    the encoded image
     * @param type    the sample type to produce
     * @param options the limits (only {@link JxlLimits#maxPixels()} applies)
     *                and the color space
     * @return the decoded image
     * @throws JxlLimitException if the image exceeds the limits
     * @throws JxlException      if the data is not a valid JPEG XL image
     */
    public static JxlChannels decodeChannels(byte[] data, JxlSampleType type, JxlDecodeOptions options) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(options, "options");
        JxlLimits limits = options.limits();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocateFrom(JAVA_BYTE, data);
            checkFrames(input, limits, 1, false, arena);
            try (NativeDecoder decoder = NativeDecoder.create()) {
                MemorySegment handle = decoder.handle();
                NativeDecoder.check(Jxl.JxlDecoderSetUnpremultiplyAlpha(handle, Jxl.JXL_TRUE()),
                        "JxlDecoderSetUnpremultiplyAlpha");
                if (options.srgb()) {
                    setCms(handle);
                }
                decoder.start(Jxl.JXL_DEC_BASIC_INFO() | Jxl.JXL_DEC_COLOR_ENCODING() | Jxl.JXL_DEC_FULL_IMAGE(),
                        input);
                ChannelBuffers buffers = null;
                MemorySegment info = null;
                boolean srgb = false;
                while (true) {
                    int status = Jxl.JxlDecoderProcessInput(handle);
                    if (status == Jxl.JXL_DEC_BASIC_INFO()) {
                        info = basicInfo(handle, arena);
                        checkImage(info, limits);
                    } else if (status == Jxl.JXL_DEC_COLOR_ENCODING()) {
                        srgb = options.srgb() && info != null && requestSrgb(handle, info, arena);
                    } else if (status == Jxl.JXL_DEC_NEED_IMAGE_OUT_BUFFER() && info != null) {
                        buffers = ChannelBuffers.set(handle, info, type, buffers, arena);
                    } else if (status == Jxl.JXL_DEC_FULL_IMAGE() || status == Jxl.JXL_DEC_SUCCESS()) {
                        break;
                    } else {
                        throw NativeDecoder.failure(status);
                    }
                }
                if (buffers == null) {
                    throw new JxlException("The JPEG XL data contains no image");
                }
                return buffers.toChannels(extraChannels(handle, info, arena),
                        srgb ? null : outputProfile(handle, false, arena));
            }
        }
    }

    /**
     * The native buffers that receive the color channels (interleaved) and
     * the extra channels (one each) of a frame.
     *
     * @param stride the number of interleaved channels in {@code color}: the
     *               color channels, followed by alpha if libjxl has to convert
     *               premultiplied alpha
     */
    record ChannelBuffers(JxlSampleType type, int bitsPerSample, int width, int height, int colorChannels,
            int stride, MemorySegment color, List<MemorySegment> extra) {

        /**
         * Passes output buffers for all channels of the current frame to the
         * decoder, answering {@code JXL_DEC_NEED_IMAGE_OUT_BUFFER}. The
         * buffers of an earlier frame are reused if they have the required
         * size.
         *
         * @param previous the buffers of an earlier frame, or {@code null}
         * @throws JxlException if a channel does not fit into a Java array
         */
        static ChannelBuffers set(MemorySegment decoder, MemorySegment info, JxlSampleType type,
                ChannelBuffers previous, Arena arena) {
            int colorChannels = JxlBasicInfo.num_color_channels(info);
            // libjxl converts premultiplied alpha only in a buffer that also receives alpha.
            int stride = colorChannels
                    + (JxlBasicInfo.alpha_bits(info) > 0 && JxlBasicInfo.alpha_premultiplied(info) != 0 ? 1 : 0);
            int count = JxlBasicInfo.num_extra_channels(info);
            List<MemorySegment> extra = new ArrayList<>(count);
            int bits;
            MemorySegment color;
            // libjxl copies the formats and the bit depth, so they need not outlive the call.
            try (Arena temporary = Arena.ofConfined()) {
                color = setImageOutBuffer(decoder, pixelFormat(stride, type, temporary), type,
                        previous == null ? null : previous.color(), arena);
                bits = codestreamBits(decoder, info, type, temporary);
                if (bits < type.bits()) {
                    // The samples keep the values they have in the codestream instead of being scaled to the type.
                    MemorySegment depth = temporary.allocate(JxlBitDepth.layout());
                    JxlBitDepth.type(depth, Jxl.JXL_BIT_DEPTH_FROM_CODESTREAM());
                    NativeDecoder.check(Jxl.JxlDecoderSetImageOutBitDepth(decoder, depth),
                            "JxlDecoderSetImageOutBitDepth");
                }
                // libjxl ignores the number of channels of an extra channel buffer.
                MemorySegment format = pixelFormat(1, type, temporary);
                MemorySegment sizeOut = temporary.allocate(JAVA_LONG);
                for (int i = 0; i < count; i++) {
                    NativeDecoder.check(Jxl.JxlDecoderExtraChannelBufferSize(decoder, format, sizeOut, i),
                            "JxlDecoderExtraChannelBufferSize");
                    long size = sizeOut.get(JAVA_LONG, 0L);
                    if (size / type.bytesPerSample() > Integer.MAX_VALUE - 8) {
                        throw new JxlException("Image too large for a Java array: " + size + " bytes");
                    }
                    MemorySegment reused = previous == null ? null : previous.extra().get(i);
                    MemorySegment buffer = reused != null && reused.byteSize() == size
                            ? reused : arena.allocate(size, type.bytesPerSample());
                    NativeDecoder.check(Jxl.JxlDecoderSetExtraChannelBuffer(decoder, format, buffer, size, i),
                            "JxlDecoderSetExtraChannelBuffer");
                    extra.add(buffer);
                }
            }
            return new ChannelBuffers(type, bits, JxlBasicInfo.xsize(info), JxlBasicInfo.ysize(info),
                    colorChannels, stride, color, extra);
        }

        /**
         * Returns the bit depth of all channels if they are integers with the
         * same bit depth that fits into the sample type, so that they can be
         * returned with their values unchanged; otherwise the size of the
         * sample type, to which libjxl scales the samples.
         */
        private static int codestreamBits(MemorySegment decoder, MemorySegment info, JxlSampleType type,
                Arena arena) {
            int bits = JxlBasicInfo.bits_per_sample(info);
            if (type == JxlSampleType.FLOAT32 || JxlBasicInfo.exponent_bits_per_sample(info) != 0
                    || bits > type.bits()) {
                return type.bits();
            }
            MemorySegment channel = arena.allocate(panamage.jxl.ffi.JxlExtraChannelInfo.layout());
            for (int i = 0; i < JxlBasicInfo.num_extra_channels(info); i++) {
                NativeDecoder.check(Jxl.JxlDecoderGetExtraChannelInfo(decoder, i, channel),
                        "JxlDecoderGetExtraChannelInfo");
                if (panamage.jxl.ffi.JxlExtraChannelInfo.bits_per_sample(channel) != bits
                        || panamage.jxl.ffi.JxlExtraChannelInfo.exponent_bits_per_sample(channel) != 0) {
                    return type.bits();
                }
            }
            return bits;
        }

        /** Copies the decoded samples into a {@link JxlChannels} of the sample type. */
        JxlChannels toChannels(List<JxlExtraChannelInfo> extraChannels, byte[] iccProfile) {
            List<JxlExtraChannel> channels = extraChannels.stream().map(JxlExtraChannelInfo::channel).toList();
            int samples = width * height;
            return switch (type) {
                case UINT8 -> {
                    List<byte[]> planes = new ArrayList<>();
                    for (int c = 0; c < colorChannels; c++) {
                        byte[] plane = new byte[samples];
                        for (int i = 0; i < samples; i++) {
                            plane[i] = color.getAtIndex(JAVA_BYTE, (long) i * stride + c);
                        }
                        planes.add(plane);
                    }
                    extra.forEach(buffer -> planes.add(buffer.toArray(JAVA_BYTE)));
                    yield new JxlChannels.Uint8(width, height, colorChannels, planes, channels, bitsPerSample,
                            iccProfile);
                }
                case UINT16 -> {
                    List<short[]> planes = new ArrayList<>();
                    for (int c = 0; c < colorChannels; c++) {
                        short[] plane = new short[samples];
                        for (int i = 0; i < samples; i++) {
                            plane[i] = color.getAtIndex(JAVA_SHORT, (long) i * stride + c);
                        }
                        planes.add(plane);
                    }
                    extra.forEach(buffer -> planes.add(buffer.toArray(JAVA_SHORT)));
                    yield new JxlChannels.Uint16(width, height, colorChannels, planes, channels, bitsPerSample,
                            iccProfile);
                }
                case FLOAT32 -> {
                    List<float[]> planes = new ArrayList<>();
                    for (int c = 0; c < colorChannels; c++) {
                        float[] plane = new float[samples];
                        for (int i = 0; i < samples; i++) {
                            plane[i] = color.getAtIndex(JAVA_FLOAT, (long) i * stride + c);
                        }
                        planes.add(plane);
                    }
                    extra.forEach(buffer -> planes.add(buffer.toArray(JAVA_FLOAT)));
                    yield new JxlChannels.Float32(width, height, colorChannels, planes, channels, bitsPerSample,
                            iccProfile);
                }
            };
        }
    }

    /** Reads the type, name and bit depth of every extra channel; valid after the BASIC_INFO event. */
    static List<JxlExtraChannelInfo> extraChannels(MemorySegment decoder, MemorySegment info, Arena arena) {
        int count = JxlBasicInfo.num_extra_channels(info);
        List<JxlExtraChannelInfo> channels = new ArrayList<>(count);
        MemorySegment channel = arena.allocate(panamage.jxl.ffi.JxlExtraChannelInfo.layout());
        for (int i = 0; i < count; i++) {
            NativeDecoder.check(Jxl.JxlDecoderGetExtraChannelInfo(decoder, i, channel),
                    "JxlDecoderGetExtraChannelInfo");
            String name = "";
            long nameLength = Integer.toUnsignedLong(panamage.jxl.ffi.JxlExtraChannelInfo.name_length(channel));
            if (nameLength > 0) {
                try (Arena temporary = Arena.ofConfined()) {
                    // The name is returned with a terminating zero byte.
                    MemorySegment buffer = temporary.allocate(nameLength + 1);
                    NativeDecoder.check(Jxl.JxlDecoderGetExtraChannelName(decoder, i, buffer, buffer.byteSize()),
                            "JxlDecoderGetExtraChannelName");
                    name = channelName(buffer.asSlice(0L, nameLength).toArray(JAVA_BYTE));
                }
            }
            channels.add(new JxlExtraChannelInfo(JxlChannelType.of(panamage.jxl.ffi.JxlExtraChannelInfo.type(channel)),
                    name, panamage.jxl.ffi.JxlExtraChannelInfo.bits_per_sample(channel),
                    panamage.jxl.ffi.JxlExtraChannelInfo.exponent_bits_per_sample(channel)));
        }
        return List.copyOf(channels);
    }

    /**
     * Decodes the name of an extra channel so that it is valid for
     * {@link JxlExtraChannel}: U+0000 becomes U+FFFD, and a name that grows
     * beyond the limit by replaced invalid UTF-8 is shortened.
     */
    static String channelName(byte[] utf8) {
        String name = new String(utf8, StandardCharsets.UTF_8).replace('\0', '\uFFFD');
        while (name.getBytes(StandardCharsets.UTF_8).length > JxlExtraChannel.MAX_NAME_BYTES) {
            name = name.substring(0, name.offsetByCodePoints(name.length(), -1));
        }
        return name;
    }

    static void checkChannels(int channels) {
        if (channels < 1 || channels > 4) {
            throw new IllegalArgumentException("channels must be 1 to 4: " + channels);
        }
    }

    private static JxlImage decode(MemorySegment input, int channels, JxlSampleType type, JxlDecodeOptions options,
            boolean keepSrgbProfile, Arena arena) {
        checkFrames(input, options.limits(), 1, false, arena);
        try (NativeDecoder decoder = NativeDecoder.create()) {
            // JxlImage promises straight alpha, also for images stored with premultiplied alpha.
            NativeDecoder.check(Jxl.JxlDecoderSetUnpremultiplyAlpha(decoder.handle(), Jxl.JXL_TRUE()),
                    "JxlDecoderSetUnpremultiplyAlpha");
            if (options.srgb()) {
                setCms(decoder.handle());
            }
            decoder.start(Jxl.JXL_DEC_BASIC_INFO() | Jxl.JXL_DEC_COLOR_ENCODING() | Jxl.JXL_DEC_FULL_IMAGE(),
                    input);
            return run(decoder.handle(), channels, type, options, keepSrgbProfile, arena);
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

    private static JxlImage run(MemorySegment decoder, int channels, JxlSampleType type, JxlDecodeOptions options,
            boolean keepSrgbProfile, Arena arena) {
        MemorySegment format = pixelFormat(channels, type, arena);
        MemorySegment info = null;
        boolean srgb = false;
        int width = 0;
        int height = 0;
        MemorySegment pixels = null;
        while (true) {
            int status = Jxl.JxlDecoderProcessInput(decoder);
            if (status == Jxl.JXL_DEC_BASIC_INFO()) {
                info = basicInfo(decoder, arena);
                checkImage(info, options.limits());
                width = JxlBasicInfo.xsize(info);
                height = JxlBasicInfo.ysize(info);
            } else if (status == Jxl.JXL_DEC_COLOR_ENCODING()) {
                // The profile is read after decoding, when it describes the produced pixels.
                srgb = options.srgb() && info != null && requestSrgb(decoder, info, arena);
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
        byte[] profile = srgb && !keepSrgbProfile ? null : outputProfile(decoder, keepSrgbProfile, arena);
        return toImage(type, width, height, channels, pixels, profile);
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
     * Passes libjxl's default color management system to a decoder that is
     * to produce sRGB pixels; called before decoding starts.
     *
     * @throws JxlException if libjxl_cms is not loaded
     */
    static void setCms(MemorySegment decoder) {
        MemorySegment cms = NativeLibraries.defaultCms();
        if (cms.equals(MemorySegment.NULL)) {
            throw new JxlException("Converting to sRGB needs libjxl_cms, which was not found with "
                    + NativeLibraries.source());
        }
        // The interface is passed by value; libjxl copies it.
        NativeDecoder.check(Jxl.JxlDecoderSetCms(decoder, cms.reinterpret(JxlCmsInterface.layout().byteSize())),
                "JxlDecoderSetCms");
    }

    /**
     * Asks a decoder prepared with {@link #setCms} for sRGB pixels, answering
     * {@code JXL_DEC_COLOR_ENCODING}. Gray images get sRGB gray.
     * <p>
     * For images that are not XYB encoded, libjxl keeps reporting the
     * original color space as the one of the pixels after a conversion, so
     * the caller has to remember the result instead of asking
     * {@link #outputProfile}.
     *
     * @return {@code true} if the pixels will be sRGB; {@code false} if libjxl
     *         cannot convert the image, so they keep its color space, which
     *         {@link #outputProfile} reports
     */
    static boolean requestSrgb(MemorySegment decoder, MemorySegment info, Arena arena) {
        MemorySegment encoding = arena.allocate(JxlColorEncoding.layout());
        if (Jxl.JxlDecoderGetColorAsEncodedProfile(decoder, Jxl.JXL_COLOR_PROFILE_TARGET_DATA(), encoding)
                == Jxl.JXL_DEC_SUCCESS() && isSrgb(encoding)) {
            return true;
        }
        boolean gray = JxlBasicInfo.num_color_channels(info) == 1;
        Jxl.JxlColorEncodingSetToSRGB(encoding, gray ? Jxl.JXL_TRUE() : Jxl.JXL_FALSE());
        return Jxl.JxlDecoderSetOutputColorProfile(decoder, encoding, MemorySegment.NULL, 0L)
                == Jxl.JXL_DEC_SUCCESS();
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

package panamage.jxl;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import panamage.jxl.ffi.Jxl;
import panamage.jxl.ffi.JxlBasicInfo;
import panamage.jxl.ffi.JxlBitDepth;
import panamage.jxl.ffi.JxlColorEncoding;
import panamage.jxl.ffi.JxlPixelFormat;

/**
 * Encodes images to JPEG XL in one call.
 * <p>
 * The input is a {@link JxlImage} with 8-bit, 16-bit or floating point samples
 * and 1 to 4 channels (gray, gray and alpha, RGB or RGBA) in sRGB or in the
 * color space of its ICC profile; the profile must match the channels (gray or
 * RGB). The image is stored with the bit depth of its sample type, so lossless
 * encoding reproduces every sample exactly, including floating point values
 * outside the range 0.0 to 1.0. The output is a bare JPEG XL codestream, or
 * a file in the JPEG XL container format if it has metadata or needs
 * codestream level 10 (libjxl chooses the level; some lossless images with
 * 16-bit or floating point samples need it, for example). It is returned as a
 * byte array or written to an {@link OutputStream} or a
 * {@link WritableByteChannel} as it is produced. The encoder uses libjxl's
 * native thread pool, so no Java code is called back from native threads.
 * <p>
 * Images with more channels, such as microscope images with several
 * fluorescence channels, are encoded from a {@link JxlChannels} with every
 * channel in an array of its own.
 * <p>
 * {@link #encodeAnimation} encodes a list of frames as an animation, and
 * {@link JxlFrameEncoder} encodes animations one frame at a time.
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
        return OutputSink.toBytes(sink -> encode(image, options, metadata, sink, NativeEncoder.OUTPUT_CHUNK_SIZE));
    }

    /**
     * Encodes an image with the given options and metadata and writes the
     * result to a stream, piece by piece, without holding the whole output in
     * memory.
     * <p>
     * The stream is neither flushed nor closed. If an exception is thrown,
     * part of the output may already have been written.
     *
     * @param image    the image to encode
     * @param options  the encoder settings
     * @param metadata the EXIF and XMP metadata to store
     * @param out      the stream that receives the JPEG XL file
     * @return the number of bytes written
     * @throws IOException              if writing to the stream fails
     * @throws IllegalArgumentException if the image has more than 4 channels
     * @throws JxlException             if libjxl rejects the image, the settings or the metadata
     * @see #encode(JxlImage, JxlEncodeOptions, JxlMetadata)
     */
    public static long encode(JxlImage image, JxlEncodeOptions options, JxlMetadata metadata, OutputStream out)
            throws IOException {
        return encode(image, options, metadata, OutputSink.of(out), NativeEncoder.OUTPUT_CHUNK_SIZE);
    }

    /**
     * Encodes an image with the given options and metadata and writes the
     * result to a channel, piece by piece, without holding the whole output in
     * memory or copying it to the Java heap.
     * <p>
     * The channel must be in blocking mode; it is not closed. If an exception
     * is thrown, part of the output may already have been written.
     *
     * @param image    the image to encode
     * @param options  the encoder settings
     * @param metadata the EXIF and XMP metadata to store
     * @param out      the channel that receives the JPEG XL file
     * @return the number of bytes written
     * @throws IOException              if writing to the channel fails
     * @throws IllegalArgumentException if the image has more than 4 channels or
     *                                  the channel is in non-blocking mode
     * @throws JxlException             if libjxl rejects the image, the settings or the metadata
     * @see #encode(JxlImage, JxlEncodeOptions, JxlMetadata)
     */
    public static long encode(JxlImage image, JxlEncodeOptions options, JxlMetadata metadata,
            WritableByteChannel out) throws IOException {
        return encode(image, options, metadata, OutputSink.of(out), NativeEncoder.OUTPUT_CHUNK_SIZE);
    }

    /**
     * Encodes an image with separate channels, such as a microscope image
     * with several fluorescence channels, with the given options.
     * <p>
     * The color channels become the gray or RGB channels of the JPEG XL
     * image, the extra channels its extra channels with their types and
     * names; every channel is stored with the bit depth of the sample type.
     * Lossy encoding applies the same distance to the extra channels. Images
     * with more than 4 extra channels need codestream level 10, which some
     * decoders may not support.
     *
     * @param image   the image to encode
     * @param options the encoder settings
     * @return the JPEG XL file
     * @throws IllegalArgumentException if an extra channel has a type that
     *                                  cannot be written
     * @throws JxlException             if libjxl rejects the image or the settings
     */
    public static byte[] encode(JxlChannels image, JxlEncodeOptions options) {
        return encode(image, options, JxlMetadata.NONE);
    }

    /**
     * Encodes an image with separate channels with the given options and
     * metadata; see {@link #encode(JxlChannels, JxlEncodeOptions)} and, for
     * the metadata, {@link #encode(JxlImage, JxlEncodeOptions, JxlMetadata)}.
     *
     * @param image    the image to encode
     * @param options  the encoder settings
     * @param metadata the EXIF and XMP metadata to store
     * @return the JPEG XL file
     * @throws IllegalArgumentException if an extra channel has a type that
     *                                  cannot be written
     * @throws JxlException             if libjxl rejects the image, the settings or the metadata
     */
    public static byte[] encode(JxlChannels image, JxlEncodeOptions options, JxlMetadata metadata) {
        return OutputSink.toBytes(sink -> encode(image, options, metadata, sink, NativeEncoder.OUTPUT_CHUNK_SIZE));
    }

    /**
     * Encodes an image with separate channels with the given options and
     * metadata and writes the result to a stream, piece by piece; see
     * {@link #encode(JxlChannels, JxlEncodeOptions, JxlMetadata)}.
     * <p>
     * The stream is neither flushed nor closed. If an exception is thrown,
     * part of the output may already have been written.
     *
     * @param image    the image to encode
     * @param options  the encoder settings
     * @param metadata the EXIF and XMP metadata to store
     * @param out      the stream that receives the JPEG XL file
     * @return the number of bytes written
     * @throws IOException              if writing to the stream fails
     * @throws IllegalArgumentException if an extra channel has a type that
     *                                  cannot be written
     * @throws JxlException             if libjxl rejects the image, the settings or the metadata
     */
    public static long encode(JxlChannels image, JxlEncodeOptions options, JxlMetadata metadata, OutputStream out)
            throws IOException {
        return encode(image, options, metadata, OutputSink.of(out), NativeEncoder.OUTPUT_CHUNK_SIZE);
    }

    /**
     * Encodes an image with separate channels with the given options and
     * metadata and writes the result to a channel, piece by piece; see
     * {@link #encode(JxlChannels, JxlEncodeOptions, JxlMetadata)}.
     * <p>
     * The channel must be in blocking mode; it is not closed. If an exception
     * is thrown, part of the output may already have been written.
     *
     * @param image    the image to encode
     * @param options  the encoder settings
     * @param metadata the EXIF and XMP metadata to store
     * @param out      the channel that receives the JPEG XL file
     * @return the number of bytes written
     * @throws IOException              if writing to the channel fails
     * @throws IllegalArgumentException if an extra channel has a type that
     *                                  cannot be written, or the channel is
     *                                  in non-blocking mode
     * @throws JxlException             if libjxl rejects the image, the settings or the metadata
     */
    public static long encode(JxlChannels image, JxlEncodeOptions options, JxlMetadata metadata,
            WritableByteChannel out) throws IOException {
        return encode(image, options, metadata, OutputSink.of(out), NativeEncoder.OUTPUT_CHUNK_SIZE);
    }

    /**
     * Encodes frames as an animation, for example the frames of
     * {@link JxlDecoder#decodeFrames(byte[], int, JxlSampleType)} with the
     * tick rate and loop count of {@link JxlDecoder#readAnimationInfo(byte[])}.
     * <p>
     * Each frame is shown for its duration in ticks and keeps its name; the
     * duration in milliseconds is ignored. All frames must have the size, the
     * channels, the sample type and the ICC profile of the first frame, and
     * only the last frame may have a duration of 0. Every frame covers the
     * whole image. With metadata, the output uses the JPEG XL container
     * format. To write the output piece by piece, or to add frames one at a
     * time, use {@link JxlFrameEncoder}.
     *
     * @param frames   the frames in display order, at least one
     * @param header   the tick rate and the number of loops
     * @param options  the encoder settings for all frames
     * @param metadata the EXIF and XMP metadata to store
     * @return the JPEG XL file
     * @throws NullPointerException     if an argument or a frame is {@code null}
     * @throws IllegalArgumentException if there is no frame, a frame does not
     *                                  match the first, a duration or name is
     *                                  invalid, or a frame other than the last
     *                                  has a duration of 0
     * @throws JxlException             if libjxl rejects a frame, the settings
     *                                  or the metadata
     */
    public static byte[] encodeAnimation(List<JxlFrame> frames, JxlAnimationHeader header, JxlEncodeOptions options,
            JxlMetadata metadata) {
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(metadata, "metadata");
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("At least one frame is needed");
        }
        return OutputSink.toBytes(sink -> {
            try (JxlFrameEncoder encoder = JxlFrameEncoder.open(sink, header, options, metadata,
                    NativeEncoder.OUTPUT_CHUNK_SIZE)) {
                for (JxlFrame frame : frames) {
                    encoder.add(frame);
                }
                encoder.finish();
            }
        });
    }

    /**
     * Encodes an image to a sink, with a given output buffer size (tests use a
     * small size to exercise the multi-chunk path).
     */
    static long encode(JxlImage image, JxlEncodeOptions options, JxlMetadata metadata, OutputSink sink,
            int chunkSize) throws IOException {
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
            configure(encoder, image, options, metadata.orientation(), null, arena);
            MemorySegment settings = createFrameSettings(encoder, options);
            MemorySegment pixels = copyPixels(image, arena);
            encoder.check(Jxl.JxlEncoderAddImageFrame(settings, pixelFormat(image, arena), pixels,
                    pixels.byteSize()), "JxlEncoderAddImageFrame");
            addBoxes(encoder, metadata, arena);
            // Closes the frames and, if used, the boxes.
            Jxl.JxlEncoderCloseInput(encoder.handle());
            return encoder.writeOutput(arena, sink, chunkSize);
        }
    }

    /**
     * Encodes an image with separate channels to a sink, with a given output
     * buffer size.
     */
    static long encode(JxlChannels image, JxlEncodeOptions options, JxlMetadata metadata, OutputSink sink,
            int chunkSize) throws IOException {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(metadata, "metadata");
        checkWritable(image.extraChannels());
        try (Arena arena = Arena.ofConfined(); NativeEncoder encoder = NativeEncoder.create()) {
            if (!metadata.isEmpty()) {
                encoder.check(Jxl.JxlEncoderUseContainer(encoder.handle(), Jxl.JXL_TRUE()), "JxlEncoderUseContainer");
                encoder.check(Jxl.JxlEncoderUseBoxes(encoder.handle()), "JxlEncoderUseBoxes");
            }
            configure(encoder, image, options, metadata.orientation(), null, arena);
            MemorySegment settings = createFrameSettings(encoder, options, image);
            addFrame(encoder, settings, image, arena);
            addBoxes(encoder, metadata, arena);
            Jxl.JxlEncoderCloseInput(encoder.handle());
            return encoder.writeOutput(arena, sink, chunkSize);
        }
    }

    /**
     * Checks that the extra channels can be written.
     *
     * @throws IllegalArgumentException if a channel has a type that cannot be written
     */
    static void checkWritable(List<JxlExtraChannel> extraChannels) {
        for (JxlExtraChannel channel : extraChannels) {
            if (!channel.type().isWritable()) {
                throw new IllegalArgumentException("Extra channels of type " + channel.type() + " cannot be written");
            }
        }
    }

    /**
     * Hands the planes of an image to libjxl as the next frame: the color
     * channels interleaved, the extra channels one by one.
     */
    static void addFrame(NativeEncoder encoder, MemorySegment settings, JxlChannels image, Arena arena) {
        JxlSampleType type = image.sampleType();
        MemorySegment color = copyColorPlanes(image, arena);
        encoder.check(Jxl.JxlEncoderAddImageFrame(settings, pixelFormat(image.colorChannels(), type, arena), color,
                color.byteSize()), "JxlEncoderAddImageFrame");
        // libjxl ignores the number of channels of an extra channel buffer.
        MemorySegment format = pixelFormat(1, type, arena);
        for (int i = 0; i < image.extraChannels().size(); i++) {
            MemorySegment plane = copyPlane(image, image.colorChannels() + i, arena);
            encoder.check(Jxl.JxlEncoderSetExtraChannelBuffer(settings, format, plane, plane.byteSize(), i),
                    "JxlEncoderSetExtraChannelBuffer");
        }
    }

    /** Copies the color channels of the image to native memory, interleaved. */
    static MemorySegment copyColorPlanes(JxlChannels image, Arena arena) {
        int bytes = image.sampleType().bytesPerSample();
        MemorySegment target = arena.allocate((long) image.width() * image.height() * image.colorChannels() * bytes,
                bytes);
        writeColorPlanes(image, target);
        return target;
    }

    /** Writes the color channels of the image to native memory, interleaved. */
    static void writeColorPlanes(JxlChannels image, MemorySegment target) {
        int colors = image.colorChannels();
        if (colors == 1) {
            writePlane(image, 0, target);
            return;
        }
        int samples = image.width() * image.height();
        for (int c = 0; c < colors; c++) {
            switch (image) {
                case JxlChannels.Uint8 img -> {
                    byte[] plane = img.planes().get(c);
                    for (int i = 0; i < samples; i++) {
                        target.setAtIndex(JAVA_BYTE, (long) i * colors + c, plane[i]);
                    }
                }
                case JxlChannels.Uint16 img -> {
                    short[] plane = img.planes().get(c);
                    for (int i = 0; i < samples; i++) {
                        target.setAtIndex(JAVA_SHORT, (long) i * colors + c, plane[i]);
                    }
                }
                case JxlChannels.Float32 img -> {
                    float[] plane = img.planes().get(c);
                    for (int i = 0; i < samples; i++) {
                        target.setAtIndex(JAVA_FLOAT, (long) i * colors + c, plane[i]);
                    }
                }
            }
        }
    }

    /** Copies one plane of the image to native memory. */
    static MemorySegment copyPlane(JxlChannels image, int index, Arena arena) {
        return switch (image) {
            case JxlChannels.Uint8 img -> arena.allocateFrom(JAVA_BYTE, img.planes().get(index));
            case JxlChannels.Uint16 img -> arena.allocateFrom(JAVA_SHORT, img.planes().get(index));
            case JxlChannels.Float32 img -> arena.allocateFrom(JAVA_FLOAT, img.planes().get(index));
        };
    }

    /** Writes one plane of the image to native memory. */
    static void writePlane(JxlChannels image, int index, MemorySegment target) {
        switch (image) {
            case JxlChannels.Uint8 img -> {
                byte[] plane = img.planes().get(index);
                MemorySegment.copy(plane, 0, target, JAVA_BYTE, 0L, plane.length);
            }
            case JxlChannels.Uint16 img -> {
                short[] plane = img.planes().get(index);
                MemorySegment.copy(plane, 0, target, JAVA_SHORT, 0L, plane.length);
            }
            case JxlChannels.Float32 img -> {
                float[] plane = img.planes().get(index);
                MemorySegment.copy(plane, 0, target, JAVA_FLOAT, 0L, plane.length);
            }
        }
    }

    /** Adds the EXIF and XMP boxes; the encoder must use boxes if there is metadata. */
    static void addBoxes(NativeEncoder encoder, JxlMetadata metadata, Arena arena) {
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

    /**
     * Sets the basic information and the color encoding of the image; with an
     * animation header, the image is an animation.
     */
    static void configure(NativeEncoder encoder, JxlImage image, JxlEncodeOptions options, int orientation,
            JxlAnimationHeader animation, Arena arena) {
        boolean alpha = image.channels() == 2 || image.channels() == 4;
        configure(encoder, image.width(), image.height(), image.sampleType(), image.sampleType().bits(),
                image.channels() <= 2 ? 1 : 3, alpha ? List.of(JxlExtraChannel.alpha()) : List.of(),
                image.iccProfile(), options, orientation, animation, arena);
    }

    /**
     * Sets the basic information, the extra channels and the color encoding
     * of an image with separate channels; with an animation header, the image
     * is an animation.
     */
    static void configure(NativeEncoder encoder, JxlChannels image, JxlEncodeOptions options, int orientation,
            JxlAnimationHeader animation, Arena arena) {
        configure(encoder, image.width(), image.height(), image.sampleType(), image.bitsPerSample(),
                image.colorChannels(), image.extraChannels(), image.iccProfile(), options, orientation, animation,
                arena);
    }

    private static void configure(NativeEncoder encoder, int width, int height, JxlSampleType type, int bits,
            int colorChannels, List<JxlExtraChannel> extraChannels, byte[] iccProfile, JxlEncodeOptions options,
            int orientation, JxlAnimationHeader animation, Arena arena) {
        boolean gray = colorChannels == 1;
        // libjxl describes the first extra channel through the alpha fields if it is an alpha channel.
        boolean alpha = !extraChannels.isEmpty() && extraChannels.getFirst().type() == JxlChannelType.ALPHA;

        MemorySegment info = arena.allocate(JxlBasicInfo.layout());
        Jxl.JxlEncoderInitBasicInfo(info);
        JxlBasicInfo.xsize(info, width);
        JxlBasicInfo.ysize(info, height);
        JxlBasicInfo.bits_per_sample(info, bits);
        JxlBasicInfo.exponent_bits_per_sample(info, type.exponentBits());
        JxlBasicInfo.num_color_channels(info, colorChannels);
        JxlBasicInfo.num_extra_channels(info, extraChannels.size());
        JxlBasicInfo.alpha_bits(info, alpha ? bits : 0);
        JxlBasicInfo.alpha_exponent_bits(info, alpha ? type.exponentBits() : 0);
        JxlBasicInfo.orientation(info, orientation);
        // Lossless encoding must keep the original color space instead of converting to XYB.
        JxlBasicInfo.uses_original_profile(info, options.lossless() ? Jxl.JXL_TRUE() : Jxl.JXL_FALSE());
        if (animation != null) {
            JxlBasicInfo.have_animation(info, Jxl.JXL_TRUE());
            MemorySegment header = JxlBasicInfo.animation(info);
            // The values are uint32 in C; JxlAnimationHeader keeps them in that range.
            panamage.jxl.ffi.JxlAnimationHeader.tps_numerator(header, (int) animation.ticksPerSecondNumerator());
            panamage.jxl.ffi.JxlAnimationHeader.tps_denominator(header,
                    (int) animation.ticksPerSecondDenominator());
            panamage.jxl.ffi.JxlAnimationHeader.num_loops(header, (int) animation.loops());
        }
        encoder.check(Jxl.JxlEncoderSetBasicInfo(encoder.handle(), info), "JxlEncoderSetBasicInfo");
        // The basic information already describes a single alpha channel without a name.
        if (!extraChannels.isEmpty() && !extraChannels.equals(List.of(JxlExtraChannel.alpha()))) {
            describeExtraChannels(encoder, extraChannels, type, bits, arena);
        }

        if (iccProfile != null) {
            MemorySegment icc = arena.allocateFrom(JAVA_BYTE, iccProfile);
            encoder.check(Jxl.JxlEncoderSetICCProfile(encoder.handle(), icc, icc.byteSize()),
                    "JxlEncoderSetICCProfile");
        } else {
            MemorySegment color = arena.allocate(JxlColorEncoding.layout());
            Jxl.JxlColorEncodingSetToSRGB(color, gray ? Jxl.JXL_TRUE() : Jxl.JXL_FALSE());
            encoder.check(Jxl.JxlEncoderSetColorEncoding(encoder.handle(), color), "JxlEncoderSetColorEncoding");
        }
    }

    /** Sets the type, the bit depth and the name of every extra channel. */
    private static void describeExtraChannels(NativeEncoder encoder, List<JxlExtraChannel> extraChannels,
            JxlSampleType type, int bits, Arena arena) {
        MemorySegment info = arena.allocate(panamage.jxl.ffi.JxlExtraChannelInfo.layout());
        for (int i = 0; i < extraChannels.size(); i++) {
            JxlExtraChannel channel = extraChannels.get(i);
            Jxl.JxlEncoderInitExtraChannelInfo(channel.type().nativeValue(), info);
            panamage.jxl.ffi.JxlExtraChannelInfo.bits_per_sample(info, bits);
            panamage.jxl.ffi.JxlExtraChannelInfo.exponent_bits_per_sample(info, type.exponentBits());
            encoder.check(Jxl.JxlEncoderSetExtraChannelInfo(encoder.handle(), i, info),
                    "JxlEncoderSetExtraChannelInfo");
            if (!channel.name().isEmpty()) {
                byte[] name = channel.name().getBytes(StandardCharsets.UTF_8);
                encoder.check(Jxl.JxlEncoderSetExtraChannelName(encoder.handle(), i,
                        arena.allocateFrom(JAVA_BYTE, name), name.length), "JxlEncoderSetExtraChannelName");
            }
        }
    }

    /**
     * Creates frame settings with the effort and the lossless or lossy mode of
     * the options.
     */
    static MemorySegment createFrameSettings(NativeEncoder encoder, JxlEncodeOptions options) {
        MemorySegment settings = encoder.createFrameSettings(options.effort());
        if (options.lossless()) {
            encoder.check(Jxl.JxlEncoderSetFrameLossless(settings, Jxl.JXL_TRUE()), "JxlEncoderSetFrameLossless");
        } else {
            encoder.check(Jxl.JxlEncoderSetFrameDistance(settings, options.distance()),
                    "JxlEncoderSetFrameDistance");
        }
        return settings;
    }

    /**
     * Creates frame settings for an image with separate channels; samples
     * with fewer bits than their type are passed with their values unchanged
     * instead of being scaled from the range of the type.
     */
    static MemorySegment createFrameSettings(NativeEncoder encoder, JxlEncodeOptions options, JxlChannels image) {
        MemorySegment settings = createFrameSettings(encoder, options);
        if (image.bitsPerSample() < image.sampleType().bits()) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment depth = arena.allocate(JxlBitDepth.layout());
                JxlBitDepth.type(depth, Jxl.JXL_BIT_DEPTH_FROM_CODESTREAM());
                encoder.check(Jxl.JxlEncoderSetFrameBitDepth(settings, depth), "JxlEncoderSetFrameBitDepth");
            }
        }
        return settings;
    }

    /** Describes the interleaved samples of the image for libjxl. */
    static MemorySegment pixelFormat(JxlImage image, Arena arena) {
        return pixelFormat(image.channels(), image.sampleType(), arena);
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

    /** Copies the samples of the image to native memory. */
    static MemorySegment copyPixels(JxlImage image, Arena arena) {
        return switch (image) {
            case JxlImage.Uint8 img -> arena.allocateFrom(JAVA_BYTE, img.pixels());
            case JxlImage.Uint16 img -> arena.allocateFrom(JAVA_SHORT, img.pixels());
            case JxlImage.Float32 img -> arena.allocateFrom(JAVA_FLOAT, img.pixels());
        };
    }
}

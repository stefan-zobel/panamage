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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import panamage.jxl.ffi.Jxl;
import panamage.jxl.ffi.JxlFrameHeader;

/**
 * Encodes a JPEG XL animation one frame at a time and writes the output as it
 * is produced, so only about one frame is held in memory.
 * {@snippet :
 * try (OutputStream out = Files.newOutputStream(path);
 *         JxlFrameEncoder frames = JxlFrameEncoder.open(out, JxlAnimationHeader.millis(0),
 *                 JxlEncodeOptions.defaults(), JxlMetadata.NONE)) {
 *     for (JxlImage image : images) {
 *         frames.add(image, 100); // shown for 100 ms
 *     }
 *     frames.finish();
 * }
 * }
 * The first frame sets the size, the channels, the sample type and the ICC
 * profile of the animation; every further frame must match it (see
 * {@link JxlEncoder} for the supported images). The frames are either all
 * {@link JxlImage}s or all {@link JxlChannels} with the same extra channels,
 * for example the slices or time points of a stack of microscope images
 * (JPEG XL has no further dimensions, so a stack is stored as an animation).
 * Every frame covers the whole image and replaces the previous one. Frame
 * durations are given in ticks of the {@link JxlAnimationHeader}; a frame
 * with a duration of 0 is only allowed as the last frame, because decoders
 * combine it with the following frame. The same {@link JxlEncodeOptions}
 * apply to every frame, and an EXIF orientation in the metadata applies to
 * every frame.
 * <p>
 * The pixels are copied when a frame is added, so the caller may reuse the
 * arrays. A frame is encoded and written when the next frame is added or
 * when {@link #finish()} is called, because libjxl needs to know which frame
 * is the last one. The output is only complete after {@link #finish()};
 * closing the encoder without finishing it leaves a truncated file. The
 * stream or channel is neither flushed nor closed.
 * <p>
 * The encoder holds native memory and a thread pool until it is closed. Its
 * methods are synchronized: a call waits until a call in another thread has
 * finished, so {@link #close()} never releases memory that libjxl still uses.
 * An {@link IllegalArgumentException} for an invalid frame leaves the encoder
 * unchanged; after any other exception, the encoder can only be closed.
 */
public final class JxlFrameEncoder implements AutoCloseable {

    /** The longest frame name that libjxl accepts, in UTF-8 bytes. */
    static final int MAX_NAME_BYTES = 1071;

    /** The longest frame duration, in ticks (an unsigned 32-bit number). */
    static final long MAX_DURATION_TICKS = 0xFFFF_FFFFL;

    private final Arena arena;
    private final NativeEncoder encoder;
    private final OutputSink sink;
    private final int chunkSize;
    private final JxlAnimationHeader header;
    private final JxlEncodeOptions options;
    private final JxlMetadata metadata;
    private MemorySegment settings;
    private Layout first;
    /** The interleaved samples of a pending {@link JxlImage}, or the color channels of {@link JxlChannels}. */
    private MemorySegment pending;
    private final List<MemorySegment> pendingExtra = new ArrayList<>();
    private long pendingDuration;
    private String pendingName;
    private boolean finished;
    private boolean failed;
    private boolean closed;

    private JxlFrameEncoder(Arena arena, NativeEncoder encoder, OutputSink sink, int chunkSize,
            JxlAnimationHeader header, JxlEncodeOptions options, JxlMetadata metadata) {
        this.arena = arena;
        this.encoder = encoder;
        this.sink = sink;
        this.chunkSize = chunkSize;
        this.header = header;
        this.options = options;
        this.metadata = metadata;
    }

    /**
     * Opens an encoder that writes the animation to a stream.
     * <p>
     * With metadata, the output uses the JPEG XL container format and stores
     * EXIF and XMP in Brotli-compressed boxes before the frames.
     *
     * @param out      the stream that receives the JPEG XL file
     * @param header   the tick rate and the number of loops
     * @param options  the encoder settings for all frames
     * @param metadata the EXIF and XMP metadata to store
     * @return the encoder, ready for the first frame
     * @throws JxlException if libjxl cannot create the encoder
     */
    public static JxlFrameEncoder open(OutputStream out, JxlAnimationHeader header, JxlEncodeOptions options,
            JxlMetadata metadata) {
        return open(OutputSink.of(out), header, options, metadata, NativeEncoder.OUTPUT_CHUNK_SIZE);
    }

    /**
     * Opens an encoder that writes the animation to a channel, without
     * copying the output to the Java heap.
     * <p>
     * With metadata, the output uses the JPEG XL container format and stores
     * EXIF and XMP in Brotli-compressed boxes before the frames.
     *
     * @param out      the channel that receives the JPEG XL file; it must be
     *                 in blocking mode
     * @param header   the tick rate and the number of loops
     * @param options  the encoder settings for all frames
     * @param metadata the EXIF and XMP metadata to store
     * @return the encoder, ready for the first frame
     * @throws IllegalArgumentException if the channel is in non-blocking mode
     * @throws JxlException             if libjxl cannot create the encoder
     */
    public static JxlFrameEncoder open(WritableByteChannel out, JxlAnimationHeader header,
            JxlEncodeOptions options, JxlMetadata metadata) {
        return open(OutputSink.of(out), header, options, metadata, NativeEncoder.OUTPUT_CHUNK_SIZE);
    }

    /**
     * Opens an encoder for a sink, with a given output buffer size (tests use
     * a small size to exercise the multi-chunk path).
     */
    static JxlFrameEncoder open(OutputSink sink, JxlAnimationHeader header, JxlEncodeOptions options,
            JxlMetadata metadata, int chunkSize) {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(metadata, "metadata");
        if (chunkSize < NativeEncoder.MIN_OUTPUT_CHUNK_SIZE) {
            throw new IllegalArgumentException("chunkSize must be at least " + NativeEncoder.MIN_OUTPUT_CHUNK_SIZE);
        }
        Arena arena = Arena.ofShared();
        NativeEncoder encoder = null;
        try {
            encoder = NativeEncoder.create();
            if (!metadata.isEmpty()) {
                encoder.check(Jxl.JxlEncoderUseContainer(encoder.handle(), Jxl.JXL_TRUE()), "JxlEncoderUseContainer");
                encoder.check(Jxl.JxlEncoderUseBoxes(encoder.handle()), "JxlEncoderUseBoxes");
            }
            return new JxlFrameEncoder(arena, encoder, sink, chunkSize, header, options, metadata);
        } catch (RuntimeException | Error e) {
            if (encoder != null) {
                encoder.close();
            }
            arena.close();
            throw e;
        }
    }

    /**
     * Adds a frame without a name.
     *
     * @param image         the pixels of the frame
     * @param durationTicks how long the frame is shown, in ticks, at most
     *                      4294967295 (2<sup>32</sup> - 1); 0 only for the
     *                      last frame
     * @throws IOException              if writing the previous frame fails
     * @throws IllegalArgumentException if the image does not match the first
     *                                  frame, the duration is out of range or
     *                                  the previous frame has a duration of 0
     * @throws IllegalStateException    if the encoder is finished, closed or
     *                                  failed
     * @throws JxlException             if libjxl rejects the image or the
     *                                  settings
     */
    public synchronized void add(JxlImage image, long durationTicks) throws IOException {
        add(image, durationTicks, "");
    }

    /**
     * Adds a frame. The previous frame is encoded and written now.
     *
     * @param image         the pixels of the frame
     * @param durationTicks how long the frame is shown, in ticks, at most
     *                      4294967295 (2<sup>32</sup> - 1); 0 only for the
     *                      last frame
     * @param name          the name of the frame, or {@code ""} for none; at
     *                      most 1071 bytes in UTF-8
     * @throws IOException              if writing the previous frame fails
     * @throws IllegalArgumentException if the image does not match the first
     *                                  frame, the duration or the name is
     *                                  invalid, or the previous frame has a
     *                                  duration of 0
     * @throws IllegalStateException    if the encoder is finished, closed or
     *                                  failed
     * @throws JxlException             if libjxl rejects the image or the
     *                                  settings
     */
    public synchronized void add(JxlImage image, long durationTicks, String name) throws IOException {
        checkWritable();
        Objects.requireNonNull(image, "image");
        if (first == null && image.channels() > 4) {
            throw new IllegalArgumentException("At most 4 channels are supported: " + image.channels());
        }
        add(Layout.of(image), image, durationTicks, name);
    }

    /**
     * Adds a frame with separate channels without a name.
     *
     * @param channels      the channels of the frame
     * @param durationTicks how long the frame is shown, in ticks, at most
     *                      4294967295 (2<sup>32</sup> - 1); 0 only for the
     *                      last frame
     * @throws IOException              if writing the previous frame fails
     * @throws IllegalArgumentException if the frame does not match the first
     *                                  frame, an extra channel has a type that
     *                                  cannot be written, the duration is out
     *                                  of range or the previous frame has a
     *                                  duration of 0
     * @throws IllegalStateException    if the encoder is finished, closed or
     *                                  failed
     * @throws JxlException             if libjxl rejects the image or the
     *                                  settings
     */
    public synchronized void add(JxlChannels channels, long durationTicks) throws IOException {
        add(channels, durationTicks, "");
    }

    /**
     * Adds a frame with separate channels, for example one time point or
     * slice of a stack of microscope images; see
     * {@link JxlEncoder#encode(JxlChannels, JxlEncodeOptions)}. The previous
     * frame is encoded and written now. All frames must have the same color
     * and extra channels.
     *
     * @param channels      the channels of the frame
     * @param durationTicks how long the frame is shown, in ticks, at most
     *                      4294967295 (2<sup>32</sup> - 1); 0 only for the
     *                      last frame
     * @param name          the name of the frame, or {@code ""} for none; at
     *                      most 1071 bytes in UTF-8
     * @throws IOException              if writing the previous frame fails
     * @throws IllegalArgumentException if the frame does not match the first
     *                                  frame, an extra channel has a type that
     *                                  cannot be written, the duration or the
     *                                  name is invalid, or the previous frame
     *                                  has a duration of 0
     * @throws IllegalStateException    if the encoder is finished, closed or
     *                                  failed
     * @throws JxlException             if libjxl rejects the image or the
     *                                  settings
     */
    public synchronized void add(JxlChannels channels, long durationTicks, String name) throws IOException {
        checkWritable();
        Objects.requireNonNull(channels, "channels");
        if (first == null) {
            JxlEncoder.checkWritable(channels.extraChannels());
        }
        add(Layout.of(channels), channels, durationTicks, name);
    }

    /**
     * Adds a decoded frame with separate channels, with its duration in ticks
     * and its name; the duration in milliseconds is ignored.
     *
     * @param frame the frame
     * @throws IOException              if writing the previous frame fails
     * @throws IllegalArgumentException if the frame does not match the first
     *                                  frame, the duration or the name is
     *                                  invalid, or the previous frame has a
     *                                  duration of 0
     * @throws IllegalStateException    if the encoder is finished, closed or
     *                                  failed
     * @throws JxlException             if libjxl rejects the image or the
     *                                  settings
     * @see JxlFrameDecoder#nextChannels()
     */
    public synchronized void add(JxlChannelsFrame frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        add(frame.channels(), frame.info().durationTicks(), frame.info().name());
    }

    /** Adds a {@link JxlImage} or {@link JxlChannels} frame with the given layout. */
    private void add(Layout layout, Object image, long durationTicks, String name) throws IOException {
        Objects.requireNonNull(name, "name");
        if (durationTicks < 0 || durationTicks > MAX_DURATION_TICKS) {
            throw new IllegalArgumentException("durationTicks must be 0 to " + MAX_DURATION_TICKS + ": "
                    + durationTicks);
        }
        checkName(name);
        if (first != null) {
            first.checkMatches(layout);
            if (pendingDuration == 0) {
                throw new IllegalArgumentException("Only the last frame may have a duration of 0");
            }
        }
        try {
            if (first == null) {
                start(layout, image);
            } else {
                try (Arena call = Arena.ofConfined()) {
                    queuePending(call);
                    encoder.writeOutput(call, sink, chunkSize);
                }
            }
            copyToPending(image);
            pendingDuration = durationTicks;
            pendingName = name;
        } catch (IOException | RuntimeException | Error e) {
            failed = true;
            throw e;
        }
    }

    /**
     * Adds a decoded frame with its duration in ticks and its name; the
     * duration in milliseconds is ignored, so the tick rate of the
     * {@link JxlAnimationHeader} should match the one of the source.
     *
     * @param frame the frame
     * @throws IOException              if writing the previous frame fails
     * @throws IllegalArgumentException if the image does not match the first
     *                                  frame, the duration or the name is
     *                                  invalid, or the previous frame has a
     *                                  duration of 0
     * @throws IllegalStateException    if the encoder is finished, closed or
     *                                  failed
     * @throws JxlException             if libjxl rejects the image or the
     *                                  settings
     * @see JxlDecoder#readAnimationInfo(byte[])
     */
    public synchronized void add(JxlFrame frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        add(frame.image(), frame.info().durationTicks(), frame.info().name());
    }

    /**
     * Encodes the last frame and writes the rest of the file.
     *
     * @return the number of bytes written in total
     * @throws IOException           if writing fails
     * @throws IllegalStateException if no frame was added, or the encoder is
     *                               finished, closed or failed
     * @throws JxlException          if libjxl rejects the image or the
     *                               settings
     */
    public synchronized long finish() throws IOException {
        checkWritable();
        if (first == null) {
            throw new IllegalStateException("No frame was added");
        }
        try (Arena call = Arena.ofConfined()) {
            queuePending(call);
            // Marks the pending frame as the last one before libjxl encodes it.
            Jxl.JxlEncoderCloseInput(encoder.handle());
            encoder.writeOutput(call, sink, chunkSize);
            finished = true;
            return sink.written();
        } catch (IOException | RuntimeException | Error e) {
            failed = true;
            throw e;
        }
    }

    /**
     * Releases the native memory and the thread pool. Closing a closed
     * encoder has no effect; closing it before {@link #finish()} leaves the
     * output incomplete.
     */
    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            encoder.close();
            arena.close();
        }
    }

    /** Sets up the image from the first frame and writes the metadata boxes before it. */
    private void start(Layout layout, Object image) {
        try (Arena call = Arena.ofConfined()) {
            switch (image) {
                case JxlImage img -> JxlEncoder.configure(encoder, img, options, metadata.orientation(), header, call);
                case JxlChannels img -> JxlEncoder.configure(encoder, img, options, metadata.orientation(), header,
                        call);
                default -> throw new IllegalArgumentException(image.getClass().getName());
            }
            JxlEncoder.addBoxes(encoder, metadata, call);
        }
        settings = image instanceof JxlChannels channels
                ? JxlEncoder.createFrameSettings(encoder, options, channels)
                : JxlEncoder.createFrameSettings(encoder, options);
        int sampleBytes = layout.sampleType().bytesPerSample();
        long planeBytes = (long) layout.width() * layout.height() * sampleBytes;
        pending = arena.allocate(planeBytes * layout.channels(), sampleBytes);
        for (int i = 0; i < layout.extraChannels().size(); i++) {
            pendingExtra.add(arena.allocate(planeBytes, sampleBytes));
        }
        first = layout;
    }

    /** Hands the pending frame to libjxl, which encodes it on the next output call. */
    private void queuePending(Arena call) {
        MemorySegment frameHeader = call.allocate(JxlFrameHeader.layout());
        Jxl.JxlEncoderInitFrameHeader(frameHeader);
        // The duration is uint32 in C and checked to be in that range.
        JxlFrameHeader.duration(frameHeader, (int) pendingDuration);
        encoder.check(Jxl.JxlEncoderSetFrameHeader(settings, frameHeader), "JxlEncoderSetFrameHeader");
        encoder.check(Jxl.JxlEncoderSetFrameName(settings, call.allocateFrom(pendingName)),
                "JxlEncoderSetFrameName");
        JxlSampleType type = first.sampleType();
        encoder.check(Jxl.JxlEncoderAddImageFrame(settings, JxlEncoder.pixelFormat(first.channels(), type, call),
                pending, pending.byteSize()), "JxlEncoderAddImageFrame");
        // libjxl ignores the number of channels of an extra channel buffer.
        MemorySegment format = JxlEncoder.pixelFormat(1, type, call);
        for (int i = 0; i < pendingExtra.size(); i++) {
            MemorySegment plane = pendingExtra.get(i);
            encoder.check(Jxl.JxlEncoderSetExtraChannelBuffer(settings, format, plane, plane.byteSize(), i),
                    "JxlEncoderSetExtraChannelBuffer");
        }
    }

    private void copyToPending(Object image) {
        switch (image) {
            case JxlImage.Uint8 img -> MemorySegment.copy(img.pixels(), 0, pending, JAVA_BYTE, 0L,
                    img.pixels().length);
            case JxlImage.Uint16 img -> MemorySegment.copy(img.pixels(), 0, pending, JAVA_SHORT, 0L,
                    img.pixels().length);
            case JxlImage.Float32 img -> MemorySegment.copy(img.pixels(), 0, pending, JAVA_FLOAT, 0L,
                    img.pixels().length);
            case JxlChannels channels -> {
                JxlEncoder.writeColorPlanes(channels, pending);
                for (int i = 0; i < pendingExtra.size(); i++) {
                    JxlEncoder.writePlane(channels, channels.colorChannels() + i, pendingExtra.get(i));
                }
            }
            default -> throw new IllegalArgumentException(image.getClass().getName());
        }
    }

    /**
     * What all frames of an animation must have in common.
     *
     * @param channels      the interleaved channels of a {@link JxlImage}, or the
     *                      color channels of {@link JxlChannels}
     * @param extraChannels the extra channels of {@link JxlChannels}
     * @param separate      whether the frames are {@link JxlChannels}
     */
    private record Layout(int width, int height, JxlSampleType sampleType, int bitsPerSample, int channels,
            List<JxlExtraChannel> extraChannels, byte[] iccProfile, boolean separate) {

        static Layout of(JxlImage image) {
            return new Layout(image.width(), image.height(), image.sampleType(), image.sampleType().bits(),
                    image.channels(), List.of(), image.iccProfile(), false);
        }

        static Layout of(JxlChannels channels) {
            return new Layout(channels.width(), channels.height(), channels.sampleType(), channels.bitsPerSample(),
                    channels.colorChannels(), channels.extraChannels(), channels.iccProfile(), true);
        }

        /**
         * Checks that a frame matches the first frame.
         *
         * @throws IllegalArgumentException if it does not
         */
        void checkMatches(Layout frame) {
            if (frame.separate != separate) {
                throw new IllegalArgumentException(separate
                        ? "The frame is a JxlImage, the animation has JxlChannels frames"
                        : "The frame is a JxlChannels, the animation has JxlImage frames");
            }
            if (frame.width != width || frame.height != height) {
                throw new IllegalArgumentException("The frame is " + frame.width + "x" + frame.height
                        + ", the animation " + width + "x" + height);
            }
            if (frame.channels != channels) {
                throw new IllegalArgumentException("The frame has " + frame.channels + (separate ? " color" : "")
                        + " channels, the animation " + channels);
            }
            if (!frame.extraChannels.equals(extraChannels)) {
                throw new IllegalArgumentException("The frame has the extra channels " + frame.extraChannels
                        + ", the animation " + extraChannels);
            }
            if (frame.sampleType != sampleType) {
                throw new IllegalArgumentException("The frame has " + frame.sampleType + " samples, the animation "
                        + sampleType);
            }
            if (frame.bitsPerSample != bitsPerSample) {
                throw new IllegalArgumentException("The frame has " + frame.bitsPerSample
                        + " bits per sample, the animation " + bitsPerSample);
            }
            if (!Arrays.equals(frame.iccProfile, iccProfile)) {
                throw new IllegalArgumentException("The frame has a different ICC profile than the animation");
            }
        }
    }

    private static void checkName(String name) {
        if (name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("The frame name must not contain U+0000");
        }
        int length = name.getBytes(StandardCharsets.UTF_8).length;
        if (length > MAX_NAME_BYTES) {
            throw new IllegalArgumentException("The frame name has " + length + " bytes in UTF-8, at most "
                    + MAX_NAME_BYTES + " are allowed");
        }
    }

    private void checkWritable() {
        if (closed) {
            throw new IllegalStateException("The frame encoder is closed");
        }
        if (failed) {
            throw new IllegalStateException("The frame encoder failed and can only be closed");
        }
        if (finished) {
            throw new IllegalStateException("The frame encoder is finished");
        }
    }
}

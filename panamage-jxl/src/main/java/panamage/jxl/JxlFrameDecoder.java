package panamage.jxl;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import panamage.jxl.ffi.Jxl;
import panamage.jxl.ffi.JxlBasicInfo;
import panamage.jxl.ffi.JxlFrameHeader;

/**
 * Decodes the frames of a JPEG XL animation one at a time, so only one frame
 * is held in memory. A still image has one frame.
 * <p>
 * Frames are returned as displayed: libjxl combines frames without duration
 * with the following frame, so every frame covers the whole image. See
 * {@link JxlDecoder#decode(byte[], int, JxlSampleType)} for the channels and
 * sample types.
 * {@snippet :
 * try (JxlFrameDecoder frames = JxlFrameDecoder.open(data, 4, JxlSampleType.UINT8)) {
 *     for (JxlFrame frame = frames.next(); frame != null; frame = frames.next()) {
 *         show(frame.image(), frame.info().durationMillis());
 *     }
 * }
 * }
 * A decoder opened with {@link #openChannels(byte[], JxlSampleType)} returns
 * every frame with all its channels, each in an array of its own, from
 * {@link #nextChannels()}; see
 * {@link JxlDecoder#decodeChannels(byte[], JxlSampleType)}.
 * <p>
 * The pixel limit applies to each frame and to every layer of all frames.
 * <p>
 * The encoded image is passed as a byte array or a {@link MemorySegment},
 * which are copied, or as a file {@link Path}, which is read
 * into native memory, not onto the Java heap; every way of opening takes the
 * same parameters otherwise. The decoder holds this copy, so it does not
 * depend on the array, segment or file afterwards.
 * <p>
 * The decoder holds native memory and a thread pool until it is closed. Its
 * methods are synchronized: a call waits until a call in another thread has
 * finished, so {@link #close()} never releases memory that libjxl still uses.
 * After an exception, the decoder should be closed.
 */
public final class JxlFrameDecoder implements AutoCloseable {

    /** The number of channels that stands for separate channels. */
    private static final int SEPARATE = 0;

    private final Arena arena;
    private final NativeDecoder decoder;
    private final int channels;
    private final JxlSampleType type;
    private final MemorySegment info;
    private final boolean srgb;
    private final MemorySegment format;
    private final MemorySegment header;
    private final List<JxlExtraChannelInfo> extraChannels;
    private MemorySegment pixels;
    private JxlDecoder.ChannelBuffers buffers;
    private byte[] iccProfile;
    private boolean profileRead;
    /** Whether libjxl accepted the conversion to sRGB. */
    private boolean srgbOutput;
    private int nextIndex;
    private boolean finished;
    private boolean closed;

    private JxlFrameDecoder(Arena arena, NativeDecoder decoder, int channels, JxlSampleType type,
            MemorySegment info, boolean srgb) {
        this.arena = arena;
        this.decoder = decoder;
        this.channels = channels;
        this.type = type;
        this.info = info;
        this.srgb = srgb;
        this.format = channels == SEPARATE ? null : JxlDecoder.pixelFormat(channels, type, arena);
        this.header = arena.allocate(JxlFrameHeader.layout());
        this.extraChannels = channels == SEPARATE ? JxlDecoder.extraChannels(decoder.handle(), info, arena) : null;
    }

    /**
     * Opens a decoder for the frames of a JPEG XL image (codestream or
     * container), with the default limits.
     *
     * @param data     the encoded image; it is copied
     * @param channels the number of channels to produce: 1 (gray), 2 (gray and
     *                 alpha), 3 (RGB) or 4 (RGBA); alpha is opaque if the image
     *                 has none
     * @param type     the sample type to produce
     * @return the decoder, positioned before the first frame
     * @throws IllegalArgumentException if {@code channels} is not 1 to 4
     * @throws JxlLimitException        if a frame or layer exceeds the default
     *                                  limits
     * @throws JxlException             if the data is not a valid JPEG XL image
     */
    public static JxlFrameDecoder open(byte[] data, int channels, JxlSampleType type) {
        return open(data, channels, type, JxlDecodeOptions.defaults());
    }

    /**
     * Like {@link #open(byte[], int, JxlSampleType)}, with the given options,
     * for example to convert the pixels to sRGB.
     *
     * @param data     the encoded image; it is copied
     * @param channels the number of channels to produce: 1 (gray), 2 (gray and
     *                 alpha), 3 (RGB) or 4 (RGBA); alpha is opaque if the image
     *                 has none
     * @param type     the sample type to produce
     * @param options  the limits (only {@link JxlLimits#maxPixels()} applies, to
     *                 each frame and to every layer) and the color space
     * @return the decoder, positioned before the first frame
     * @throws IllegalArgumentException if {@code channels} is not 1 to 4
     * @throws JxlLimitException        if a frame or layer exceeds the limits
     * @throws JxlException             if the data is not a valid JPEG XL image
     */
    public static JxlFrameDecoder open(byte[] data, int channels, JxlSampleType type, JxlDecodeOptions options) {
        Objects.requireNonNull(data, "data");
        return open(MemorySegment.ofArray(data), channels, type, options);
    }

    /**
     * Like {@link #open(byte[], int, JxlSampleType)}, reading the encoded
     * image from a memory segment.
     * The data is copied, so the segment need not stay alive while the
     * decoder is open.
     *
     * @param data     the encoded image; it is copied
     * @param channels the number of channels to produce: 1 (gray), 2 (gray and
     *                 alpha), 3 (RGB) or 4 (RGBA); alpha is opaque if the image
     *                 has none
     * @param type     the sample type to produce
     * @return the decoder, positioned before the first frame
     * @throws IllegalArgumentException if {@code channels} is not 1 to 4
     * @throws JxlLimitException        if a frame or layer exceeds the default
     *                                  limits
     * @throws JxlException             if the data is not a valid JPEG XL image
     */
    public static JxlFrameDecoder open(MemorySegment data, int channels, JxlSampleType type) {
        return open(data, channels, type, JxlDecodeOptions.defaults());
    }

    /**
     * Like {@link #open(byte[], int, JxlSampleType, JxlDecodeOptions)},
     * reading the encoded image from a memory segment.
     * The data is copied, so the segment need not stay alive while the
     * decoder is open.
     *
     * @param data     the encoded image; it is copied
     * @param channels the number of channels to produce: 1 (gray), 2 (gray and
     *                 alpha), 3 (RGB) or 4 (RGBA); alpha is opaque if the image
     *                 has none
     * @param type     the sample type to produce
     * @param options  the limits (only {@link JxlLimits#maxPixels()} applies, to
     *                 each frame and to every layer) and the color space
     * @return the decoder, positioned before the first frame
     * @throws IllegalArgumentException if {@code channels} is not 1 to 4
     * @throws JxlLimitException        if a frame or layer exceeds the limits
     * @throws JxlException             if the data is not a valid JPEG XL image
     */
    public static JxlFrameDecoder open(MemorySegment data, int channels, JxlSampleType type,
            JxlDecodeOptions options) {
        JxlDecoder.checkChannels(channels);
        return open(data, true, channels, type, options, false);
    }

    /**
     * Like {@link #open(byte[], int, JxlSampleType)}, reading the encoded
     * image from a file.
     * The file is read into native memory, not onto the Java heap, which the
     * decoder holds until it is closed; the file may be larger than 2 GiB.
     *
     * @param file     the JPEG XL file
     * @param channels the number of channels to produce: 1 (gray), 2 (gray and
     *                 alpha), 3 (RGB) or 4 (RGBA); alpha is opaque if the image
     *                 has none
     * @param type     the sample type to produce
     * @return the decoder, positioned before the first frame
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if {@code channels} is not 1 to 4
     * @throws JxlLimitException        if a frame or layer exceeds the default
     *                                  limits
     * @throws JxlException             if the file is not a valid JPEG XL image
     */
    public static JxlFrameDecoder open(Path file, int channels, JxlSampleType type) throws IOException {
        return open(file, channels, type, JxlDecodeOptions.defaults());
    }

    /**
     * Like {@link #open(byte[], int, JxlSampleType, JxlDecodeOptions)},
     * reading the encoded image from a file.
     * The file is read into native memory, not onto the Java heap, which the
     * decoder holds until it is closed; the file may be larger than 2 GiB.
     *
     * @param file     the JPEG XL file
     * @param channels the number of channels to produce: 1 (gray), 2 (gray and
     *                 alpha), 3 (RGB) or 4 (RGBA); alpha is opaque if the image
     *                 has none
     * @param type     the sample type to produce
     * @param options  the limits (only {@link JxlLimits#maxPixels()} applies, to
     *                 each frame and to every layer) and the color space
     * @return the decoder, positioned before the first frame
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if {@code channels} is not 1 to 4
     * @throws JxlLimitException        if a frame or layer exceeds the limits
     * @throws JxlException             if the file is not a valid JPEG XL image
     */
    public static JxlFrameDecoder open(Path file, int channels, JxlSampleType type, JxlDecodeOptions options)
            throws IOException {
        JxlDecoder.checkChannels(channels);
        return openFile(file, channels, type, options);
    }

    /**
     * Opens a decoder that returns every frame with all its channels, the
     * color channels and the extra channels, each in an array of its own,
     * with the default limits.
     *
     * @param data the encoded image; it is copied
     * @param type the sample type to produce
     * @return the decoder, positioned before the first frame; use
     *         {@link #nextChannels()} to decode the frames
     * @throws JxlLimitException if a frame or layer exceeds the default limits
     * @throws JxlException      if the data is not a valid JPEG XL image
     */
    public static JxlFrameDecoder openChannels(byte[] data, JxlSampleType type) {
        return openChannels(data, type, JxlDecodeOptions.defaults());
    }

    /**
     * Like {@link #openChannels(byte[], JxlSampleType)}, with the given
     * options, for example to convert the color channels to sRGB.
     *
     * @param data    the encoded image; it is copied
     * @param type    the sample type to produce
     * @param options the limits (only {@link JxlLimits#maxPixels()} applies, to
     *                each frame and to every layer) and the color space
     * @return the decoder, positioned before the first frame; use
     *         {@link #nextChannels()} to decode the frames
     * @throws JxlLimitException if a frame or layer exceeds the limits
     * @throws JxlException      if the data is not a valid JPEG XL image
     */
    public static JxlFrameDecoder openChannels(byte[] data, JxlSampleType type, JxlDecodeOptions options) {
        Objects.requireNonNull(data, "data");
        return openChannels(MemorySegment.ofArray(data), type, options);
    }

    /**
     * Like {@link #openChannels(byte[], JxlSampleType)}, reading the encoded
     * image from a memory segment.
     * The data is copied, so the segment need not stay alive while the
     * decoder is open.
     *
     * @param data the encoded image; it is copied
     * @param type the sample type to produce
     * @return the decoder, positioned before the first frame; use
     *         {@link #nextChannels()} to decode the frames
     * @throws JxlLimitException if a frame or layer exceeds the default limits
     * @throws JxlException      if the data is not a valid JPEG XL image
     */
    public static JxlFrameDecoder openChannels(MemorySegment data, JxlSampleType type) {
        return openChannels(data, type, JxlDecodeOptions.defaults());
    }

    /**
     * Like {@link #openChannels(byte[], JxlSampleType, JxlDecodeOptions)},
     * reading the encoded image from a memory segment.
     * The data is copied, so the segment need not stay alive while the
     * decoder is open.
     *
     * @param data    the encoded image; it is copied
     * @param type    the sample type to produce
     * @param options the limits (only {@link JxlLimits#maxPixels()} applies, to
     *                each frame and to every layer) and the color space
     * @return the decoder, positioned before the first frame; use
     *         {@link #nextChannels()} to decode the frames
     * @throws JxlLimitException if a frame or layer exceeds the limits
     * @throws JxlException      if the data is not a valid JPEG XL image
     */
    public static JxlFrameDecoder openChannels(MemorySegment data, JxlSampleType type, JxlDecodeOptions options) {
        return open(data, true, SEPARATE, type, options, false);
    }

    /**
     * Like {@link #openChannels(byte[], JxlSampleType)}, reading the encoded
     * image from a file.
     * The file is read into native memory, not onto the Java heap, which the
     * decoder holds until it is closed; the file may be larger than 2 GiB.
     *
     * @param file the JPEG XL file
     * @param type the sample type to produce
     * @return the decoder, positioned before the first frame; use
     *         {@link #nextChannels()} to decode the frames
     * @throws IOException       if the file cannot be read
     * @throws JxlLimitException if a frame or layer exceeds the default limits
     * @throws JxlException      if the file is not a valid JPEG XL image
     */
    public static JxlFrameDecoder openChannels(Path file, JxlSampleType type) throws IOException {
        return openChannels(file, type, JxlDecodeOptions.defaults());
    }

    /**
     * Like {@link #openChannels(byte[], JxlSampleType, JxlDecodeOptions)},
     * reading the encoded image from a file.
     * The file is read into native memory, not onto the Java heap, which the
     * decoder holds until it is closed; the file may be larger than 2 GiB.
     *
     * @param file    the JPEG XL file
     * @param type    the sample type to produce
     * @param options the limits (only {@link JxlLimits#maxPixels()} applies, to
     *                each frame and to every layer) and the color space
     * @return the decoder, positioned before the first frame; use
     *         {@link #nextChannels()} to decode the frames
     * @throws IOException       if the file cannot be read
     * @throws JxlLimitException if a frame or layer exceeds the limits
     * @throws JxlException      if the file is not a valid JPEG XL image
     */
    public static JxlFrameDecoder openChannels(Path file, JxlSampleType type, JxlDecodeOptions options)
            throws IOException {
        return openFile(file, SEPARATE, type, options);
    }

    /** Opens a decoder for a file; {@code channels} is 1 to 4, or {@link #SEPARATE}. */
    private static JxlFrameDecoder openFile(Path file, int channels, JxlSampleType type, JxlDecodeOptions options)
            throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(options, "options");
        Arena arena = Arena.ofShared();
        MemorySegment input;
        try {
            input = NativeInput.read(file, arena);
        } catch (IOException | RuntimeException | Error e) {
            arena.close();
            throw e;
        }
        return start(arena, input, channels, type, options, false);
    }

    /**
     * Opens the decoder; {@code channels} is 1 to 4, or {@link #SEPARATE}.
     * With {@code copy}, the data is copied, so the decoder does not depend on
     * the segment; without, a native segment must stay alive until the
     * decoder is closed. With {@code allTogether}, the pixel limit also
     * applies to all frames together, for callers that keep every frame.
     */
    static JxlFrameDecoder open(MemorySegment data, boolean copy, int channels, JxlSampleType type,
            JxlDecodeOptions options, boolean allTogether) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(options, "options");
        Arena arena = Arena.ofShared();
        MemorySegment input;
        try {
            input = copy ? NativeInput.copy(data, arena) : NativeInput.of(data, arena);
        } catch (RuntimeException | Error e) {
            arena.close();
            throw e;
        }
        return start(arena, input, channels, type, options, allTogether);
    }

    /**
     * Starts decoding the input; the decoder takes over the arena, which is
     * closed if starting fails.
     */
    private static JxlFrameDecoder start(Arena arena, MemorySegment input, int channels, JxlSampleType type,
            JxlDecodeOptions options, boolean allTogether) {
        JxlLimits limits = options.limits();
        NativeDecoder decoder = null;
        try {
            if (channels != SEPARATE) {
                JxlDecoder.checkChannels(channels);
            }
            JxlDecoder.checkFrames(input, limits, Integer.MAX_VALUE, allTogether, arena);
            decoder = NativeDecoder.create();
            MemorySegment handle = decoder.handle();
            // JxlImage and JxlChannels promise straight alpha, also for images stored with premultiplied alpha.
            NativeDecoder.check(Jxl.JxlDecoderSetUnpremultiplyAlpha(handle, Jxl.JXL_TRUE()),
                    "JxlDecoderSetUnpremultiplyAlpha");
            if (options.srgb()) {
                JxlDecoder.setCms(handle);
            }
            decoder.start(Jxl.JXL_DEC_BASIC_INFO() | Jxl.JXL_DEC_COLOR_ENCODING() | Jxl.JXL_DEC_FRAME()
                    | Jxl.JXL_DEC_FULL_IMAGE(), input);
            int status = Jxl.JxlDecoderProcessInput(handle);
            if (status != Jxl.JXL_DEC_BASIC_INFO()) {
                throw NativeDecoder.failure(status);
            }
            MemorySegment info = JxlDecoder.basicInfo(handle, arena);
            JxlDecoder.checkImage(info, limits);
            return new JxlFrameDecoder(arena, decoder, channels, type, info, options.srgb());
        } catch (RuntimeException | Error e) {
            if (decoder != null) {
                decoder.close();
            }
            arena.close();
            throw e;
        }
    }

    /**
     * Decodes the next frame.
     *
     * @return the frame, or {@code null} after the last frame
     * @throws IllegalStateException if the decoder is closed or was opened
     *                               with {@link #openChannels(byte[], JxlSampleType)}
     * @throws JxlException          if the data is not a valid JPEG XL image
     */
    public synchronized JxlFrame next() {
        checkOpen();
        if (channels == SEPARATE) {
            throw new IllegalStateException("The frame decoder was opened with openChannels; use nextChannels");
        }
        JxlFrameInfo frame = decodeFrame();
        if (frame == null) {
            return null;
        }
        JxlImage image = JxlDecoder.toImage(type, JxlBasicInfo.xsize(info), JxlBasicInfo.ysize(info), channels,
                pixels, profile());
        return new JxlFrame(image, frame);
    }

    /**
     * Decodes the next frame with all its channels, each in an array of its
     * own.
     *
     * @return the frame, or {@code null} after the last frame
     * @throws IllegalStateException if the decoder is closed or was opened
     *                               with {@link #open(byte[], int, JxlSampleType)}
     * @throws JxlException          if the data is not a valid JPEG XL image
     */
    public synchronized JxlChannelsFrame nextChannels() {
        checkOpen();
        if (channels != SEPARATE) {
            throw new IllegalStateException("The frame decoder was opened with open; use next");
        }
        JxlFrameInfo frame = decodeFrame();
        if (frame == null) {
            return null;
        }
        return new JxlChannelsFrame(buffers.toChannels(extraChannels, profile()), frame);
    }

    /**
     * Decodes the next frame into the native buffers.
     *
     * @return the frame header, or {@code null} after the last frame
     */
    private JxlFrameInfo decodeFrame() {
        if (finished) {
            return null;
        }
        MemorySegment handle = decoder.handle();
        JxlFrameInfo frame = null;
        while (true) {
            int status = Jxl.JxlDecoderProcessInput(handle);
            if (status == Jxl.JXL_DEC_FRAME()) {
                frame = JxlDecoder.frameInfo(handle, info, header);
            } else if (status == Jxl.JXL_DEC_COLOR_ENCODING()) {
                // The profile is read after the first frame, when it describes the produced pixels.
                srgbOutput = srgb && JxlDecoder.requestSrgb(handle, info, arena);
            } else if (status == Jxl.JXL_DEC_NEED_IMAGE_OUT_BUFFER()) {
                if (channels == SEPARATE) {
                    buffers = JxlDecoder.ChannelBuffers.set(handle, info, type, buffers, arena);
                } else {
                    pixels = JxlDecoder.setImageOutBuffer(handle, format, type, pixels, arena);
                }
            } else if (status == Jxl.JXL_DEC_FULL_IMAGE() && frame != null) {
                if (!profileRead) {
                    iccProfile = srgbOutput ? null : JxlDecoder.outputProfile(handle, false, arena);
                    profileRead = true;
                }
                nextIndex++;
                return frame;
            } else if (status == Jxl.JXL_DEC_SUCCESS()) {
                finished = true;
                return null;
            } else {
                throw NativeDecoder.failure(status);
            }
        }
    }

    /** Returns a copy of the ICC profile of the decoded pixels, or {@code null} for sRGB. */
    private byte[] profile() {
        return iccProfile == null ? null : iccProfile.clone();
    }

    /**
     * Skips frames without returning them. Skipping is faster than decoding,
     * but frames that later frames build on are still decoded internally.
     * Skipping beyond the last frame makes {@link #next()} and
     * {@link #nextChannels()} return {@code null}.
     *
     * @param frames the number of frames to skip
     * @throws IllegalArgumentException if {@code frames} is negative
     * @throws IllegalStateException    if the decoder is closed
     */
    public synchronized void skip(int frames) {
        checkOpen();
        if (frames < 0) {
            throw new IllegalArgumentException("frames must not be negative: " + frames);
        }
        if (frames == 0 || finished) {
            return;
        }
        Jxl.JxlDecoderSkipFrames(decoder.handle(), frames);
        nextIndex = (int) Math.min(Integer.MAX_VALUE, (long) nextIndex + frames);
    }

    /**
     * Returns the index of the frame that {@link #next()} or
     * {@link #nextChannels()} returns, if there is one: 0 before the first
     * frame.
     *
     * @return the index of the next frame
     */
    public synchronized int nextIndex() {
        return nextIndex;
    }

    /**
     * Releases the native memory and the thread pool. Closing a closed
     * decoder has no effect.
     */
    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            decoder.close();
            arena.close();
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The frame decoder is closed");
        }
    }
}

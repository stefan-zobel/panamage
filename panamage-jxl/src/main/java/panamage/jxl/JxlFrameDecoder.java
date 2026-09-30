package panamage.jxl;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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
 * The pixel limit applies to each frame and to every layer of all frames.
 * <p>
 * The decoder holds native memory and a thread pool until it is closed. It is
 * not thread-safe, but it may be used by different threads one after the
 * other. After an exception, it should be closed.
 */
public final class JxlFrameDecoder implements AutoCloseable {

    private final Arena arena;
    private final NativeDecoder decoder;
    private final int channels;
    private final JxlSampleType type;
    private final MemorySegment info;
    private final MemorySegment format;
    private final MemorySegment header;
    private MemorySegment pixels;
    private byte[] iccProfile;
    private boolean profileRead;
    private int nextIndex;
    private boolean finished;
    private boolean closed;

    private JxlFrameDecoder(Arena arena, NativeDecoder decoder, int channels, JxlSampleType type,
            MemorySegment info) {
        this.arena = arena;
        this.decoder = decoder;
        this.channels = channels;
        this.type = type;
        this.info = info;
        this.format = JxlDecoder.pixelFormat(channels, type, arena);
        this.header = arena.allocate(JxlFrameHeader.layout());
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
        return open(data, channels, type, JxlLimits.defaults());
    }

    /**
     * Like {@link #open(byte[], int, JxlSampleType)}, with the given limits.
     *
     * @param data     the encoded image; it is copied
     * @param channels the number of channels to produce: 1 (gray), 2 (gray and
     *                 alpha), 3 (RGB) or 4 (RGBA); alpha is opaque if the image
     *                 has none
     * @param type     the sample type to produce
     * @param limits   the limits; only {@link JxlLimits#maxPixels()} applies, to
     *                 each frame and to every layer
     * @return the decoder, positioned before the first frame
     * @throws IllegalArgumentException if {@code channels} is not 1 to 4
     * @throws JxlLimitException        if a frame or layer exceeds the limits
     * @throws JxlException             if the data is not a valid JPEG XL image
     */
    public static JxlFrameDecoder open(byte[] data, int channels, JxlSampleType type, JxlLimits limits) {
        return open(data, channels, type, limits, false);
    }

    /**
     * Opens the decoder; with {@code allTogether}, the pixel limit also applies
     * to all frames together, for callers that keep every frame.
     */
    static JxlFrameDecoder open(byte[] data, int channels, JxlSampleType type, JxlLimits limits,
            boolean allTogether) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(limits, "limits");
        JxlDecoder.checkChannels(channels);
        Arena arena = Arena.ofShared();
        NativeDecoder decoder = null;
        try {
            MemorySegment input = arena.allocateFrom(JAVA_BYTE, data);
            JxlDecoder.checkFrames(input, limits, Integer.MAX_VALUE, allTogether, arena);
            decoder = NativeDecoder.create();
            MemorySegment handle = decoder.handle();
            // JxlImage promises straight alpha, also for images stored with premultiplied alpha.
            NativeDecoder.check(Jxl.JxlDecoderSetUnpremultiplyAlpha(handle, Jxl.JXL_TRUE()),
                    "JxlDecoderSetUnpremultiplyAlpha");
            decoder.start(Jxl.JXL_DEC_BASIC_INFO() | Jxl.JXL_DEC_COLOR_ENCODING() | Jxl.JXL_DEC_FRAME()
                    | Jxl.JXL_DEC_FULL_IMAGE(), input);
            int status = Jxl.JxlDecoderProcessInput(handle);
            if (status != Jxl.JXL_DEC_BASIC_INFO()) {
                throw NativeDecoder.failure(status);
            }
            MemorySegment info = JxlDecoder.basicInfo(handle, arena);
            JxlDecoder.checkImage(info, limits);
            return new JxlFrameDecoder(arena, decoder, channels, type, info);
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
     * @throws IllegalStateException if the decoder is closed
     * @throws JxlException          if the data is not a valid JPEG XL image
     */
    public JxlFrame next() {
        checkOpen();
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
                continue;
            } else if (status == Jxl.JXL_DEC_NEED_IMAGE_OUT_BUFFER()) {
                pixels = JxlDecoder.setImageOutBuffer(handle, format, type, pixels, arena);
            } else if (status == Jxl.JXL_DEC_FULL_IMAGE() && frame != null) {
                if (!profileRead) {
                    iccProfile = JxlDecoder.outputProfile(handle, false, arena);
                    profileRead = true;
                }
                JxlImage image = JxlDecoder.toImage(type, JxlBasicInfo.xsize(info), JxlBasicInfo.ysize(info),
                        channels, pixels, iccProfile == null ? null : iccProfile.clone());
                nextIndex++;
                return new JxlFrame(image, frame);
            } else if (status == Jxl.JXL_DEC_SUCCESS()) {
                finished = true;
                return null;
            } else {
                throw NativeDecoder.failure(status);
            }
        }
    }

    /**
     * Skips frames without returning them. Skipping is faster than decoding,
     * but frames that later frames build on are still decoded internally.
     * Skipping beyond the last frame makes {@link #next()} return
     * {@code null}.
     *
     * @param frames the number of frames to skip
     * @throws IllegalArgumentException if {@code frames} is negative
     * @throws IllegalStateException    if the decoder is closed
     */
    public void skip(int frames) {
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
     * Returns the index of the frame that {@link #next()} returns, if there is
     * one: 0 before the first frame.
     *
     * @return the index of the next frame
     */
    public int nextIndex() {
        return nextIndex;
    }

    /**
     * Releases the native memory and the thread pool. Closing a closed
     * decoder has no effect.
     */
    @Override
    public void close() {
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

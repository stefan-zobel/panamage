package panamage.jxl;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

import panamage.jxl.ffi.Jxl;

/**
 * Lossless conversion between JPEG and JPEG XL.
 * <p>
 * {@link #fromJpeg(byte[])} repacks the DCT coefficients of an existing JPEG
 * without decoding it to pixels, which typically saves 10 to 20 percent. The
 * JPEG XL file keeps reconstruction data, so {@link #toJpeg(byte[])} restores
 * the original JPEG file bit for bit, including its metadata.
 * <p>
 * The result of {@code fromJpeg} is a regular JPEG XL image that
 * {@link JxlDecoder} decodes to pixels as well.
 * <p>
 * The results are returned as byte arrays or written to an
 * {@link OutputStream} or a {@link WritableByteChannel} as they are produced.
 * <p>
 * {@code toJpeg} rejects images beyond the pixel limit of {@link JxlLimits}
 * with a {@link JxlLimitException}, like {@link JxlDecoder}.
 */
public final class JxlTranscoder {

    /** Size of the native buffer that receives the reconstructed JPEG piece by piece. */
    private static final int OUTPUT_CHUNK_SIZE = 64 * 1024;

    private JxlTranscoder() {
    }

    /**
     * Converts a JPEG file losslessly to JPEG XL with the default effort.
     *
     * @param jpeg the JPEG file
     * @return the JPEG XL file, including JPEG reconstruction data
     * @throws JxlException if the JPEG cannot be transcoded
     */
    public static byte[] fromJpeg(byte[] jpeg) {
        return fromJpeg(jpeg, JxlEncodeOptions.DEFAULT_EFFORT);
    }

    /**
     * Converts a JPEG file losslessly to JPEG XL.
     *
     * @param jpeg   the JPEG file
     * @param effort the encoder effort from 1 (fastest) to 10 (smallest output)
     * @return the JPEG XL file, including JPEG reconstruction data
     * @throws IllegalArgumentException if the effort is out of range
     * @throws JxlException             if the JPEG cannot be transcoded
     */
    public static byte[] fromJpeg(byte[] jpeg, int effort) {
        return fromJpeg(jpeg, effort, NativeEncoder.OUTPUT_CHUNK_SIZE);
    }

    /**
     * Converts a JPEG file losslessly to JPEG XL and writes the result to a
     * stream, piece by piece, without holding the whole output in memory.
     * <p>
     * The stream is neither flushed nor closed. If an exception is thrown,
     * part of the output may already have been written.
     *
     * @param jpeg   the JPEG file
     * @param effort the encoder effort from 1 (fastest) to 10 (smallest output)
     * @param out    the stream that receives the JPEG XL file
     * @return the number of bytes written
     * @throws IOException              if writing to the stream fails
     * @throws IllegalArgumentException if the effort is out of range
     * @throws JxlException             if the JPEG cannot be transcoded
     * @see #fromJpeg(byte[], int)
     */
    public static long fromJpeg(byte[] jpeg, int effort, OutputStream out) throws IOException {
        return fromJpeg(jpeg, effort, OutputSink.of(out), NativeEncoder.OUTPUT_CHUNK_SIZE);
    }

    /**
     * Converts a JPEG file losslessly to JPEG XL and writes the result to a
     * channel, piece by piece, without holding the whole output in memory or
     * copying it to the Java heap.
     * <p>
     * The channel must be in blocking mode; it is not closed. If an exception
     * is thrown, part of the output may already have been written.
     *
     * @param jpeg   the JPEG file
     * @param effort the encoder effort from 1 (fastest) to 10 (smallest output)
     * @param out    the channel that receives the JPEG XL file
     * @return the number of bytes written
     * @throws IOException              if writing to the channel fails
     * @throws IllegalArgumentException if the effort is out of range or the
     *                                  channel is in non-blocking mode
     * @throws JxlException             if the JPEG cannot be transcoded
     * @see #fromJpeg(byte[], int)
     */
    public static long fromJpeg(byte[] jpeg, int effort, WritableByteChannel out) throws IOException {
        return fromJpeg(jpeg, effort, OutputSink.of(out), NativeEncoder.OUTPUT_CHUNK_SIZE);
    }

    /** Like {@link #fromJpeg(byte[], int)}, with a given output buffer size. */
    static byte[] fromJpeg(byte[] jpeg, int effort, int chunkSize) {
        return OutputSink.toBytes(sink -> fromJpeg(jpeg, effort, sink, chunkSize));
    }

    /** Transcodes to a sink, with a given output buffer size. */
    static long fromJpeg(byte[] jpeg, int effort, OutputSink sink, int chunkSize) throws IOException {
        Objects.requireNonNull(jpeg, "jpeg");
        if (effort < JxlEncodeOptions.MIN_EFFORT || effort > JxlEncodeOptions.MAX_EFFORT) {
            throw new IllegalArgumentException("effort must be in [" + JxlEncodeOptions.MIN_EFFORT + ", "
                    + JxlEncodeOptions.MAX_EFFORT + "]: " + effort);
        }
        try (Arena arena = Arena.ofConfined(); NativeEncoder encoder = NativeEncoder.create()) {
            MemorySegment handle = encoder.handle();
            // The reconstruction data is stored in a box, which requires the container format.
            encoder.check(Jxl.JxlEncoderUseContainer(handle, Jxl.JXL_TRUE()), "JxlEncoderUseContainer");
            encoder.check(Jxl.JxlEncoderStoreJPEGMetadata(handle, Jxl.JXL_TRUE()), "JxlEncoderStoreJPEGMetadata");
            MemorySegment settings = encoder.createFrameSettings(effort);
            MemorySegment input = arena.allocateFrom(JAVA_BYTE, jpeg);
            encoder.check(Jxl.JxlEncoderAddJPEGFrame(settings, input, input.byteSize()), "JxlEncoderAddJPEGFrame");
            Jxl.JxlEncoderCloseInput(handle);
            return encoder.writeOutput(arena, sink, chunkSize);
        }
    }

    /**
     * Restores the original JPEG file from a JPEG XL file that was created by
     * lossless JPEG transcoding.
     *
     * @param jxl the JPEG XL file
     * @return the original JPEG file
     * @throws JxlLimitException if the image or the JPEG file exceeds the
     *                           default limits
     * @throws JxlException      if the data is not valid JPEG XL or contains
     *                           no JPEG reconstruction data
     */
    public static byte[] toJpeg(byte[] jxl) {
        return toJpeg(jxl, JxlLimits.defaults());
    }

    /**
     * Like {@link #toJpeg(byte[])}, with the given limits.
     *
     * @param jxl    the JPEG XL file
     * @param limits the limits; {@link JxlLimits#maxPixels()} and
     *               {@link JxlLimits#maxJpegBytes()} apply
     * @return the original JPEG file
     * @throws JxlLimitException if the image or the JPEG file exceeds the
     *                           limits
     * @throws JxlException      if the data is not valid JPEG XL or contains
     *                           no JPEG reconstruction data
     */
    public static byte[] toJpeg(byte[] jxl, JxlLimits limits) {
        return toJpeg(jxl, OUTPUT_CHUNK_SIZE, limits);
    }

    /**
     * Restores the original JPEG file from a JPEG XL file that was created by
     * lossless JPEG transcoding and writes it to a stream, piece by piece,
     * without holding the whole JPEG file in memory.
     * <p>
     * The stream is neither flushed nor closed. The image is checked against
     * the pixel limit before anything is written; if an exception is thrown
     * later, part of the JPEG file may already have been written.
     *
     * @param jxl    the JPEG XL file
     * @param limits the limits; {@link JxlLimits#maxPixels()} and
     *               {@link JxlLimits#maxJpegBytes()} apply
     * @param out    the stream that receives the JPEG file
     * @return the number of bytes written
     * @throws IOException       if writing to the stream fails
     * @throws JxlLimitException if the image or the JPEG file exceeds the
     *                           limits
     * @throws JxlException      if the data is not valid JPEG XL or contains
     *                           no JPEG reconstruction data
     * @see #toJpeg(byte[], JxlLimits)
     */
    public static long toJpeg(byte[] jxl, JxlLimits limits, OutputStream out) throws IOException {
        return toJpeg(jxl, OUTPUT_CHUNK_SIZE, limits, OutputSink.of(out));
    }

    /**
     * Restores the original JPEG file from a JPEG XL file that was created by
     * lossless JPEG transcoding and writes it to a channel, piece by piece,
     * without holding the whole JPEG file in memory or copying it to the Java
     * heap.
     * <p>
     * The channel must be in blocking mode; it is not closed. The image is
     * checked against the pixel limit before anything is written; if an
     * exception is thrown later, part of the JPEG file may already have been
     * written.
     *
     * @param jxl    the JPEG XL file
     * @param limits the limits; {@link JxlLimits#maxPixels()} and
     *               {@link JxlLimits#maxJpegBytes()} apply
     * @param out    the channel that receives the JPEG file
     * @return the number of bytes written
     * @throws IOException              if writing to the channel fails
     * @throws IllegalArgumentException if the channel is in non-blocking mode
     * @throws JxlLimitException        if the image or the JPEG file exceeds
     *                                  the limits
     * @throws JxlException             if the data is not valid JPEG XL or
     *                                  contains no JPEG reconstruction data
     * @see #toJpeg(byte[], JxlLimits)
     */
    public static long toJpeg(byte[] jxl, JxlLimits limits, WritableByteChannel out) throws IOException {
        return toJpeg(jxl, OUTPUT_CHUNK_SIZE, limits, OutputSink.of(out));
    }

    /**
     * Like {@link #toJpeg(byte[], JxlLimits)}, with a given output buffer size
     * (tests use a small size to exercise the multi-chunk path).
     */
    static byte[] toJpeg(byte[] jxl, int chunkSize, JxlLimits limits) {
        return OutputSink.toBytes(sink -> toJpeg(jxl, chunkSize, limits, sink));
    }

    /** Restores the JPEG file to a sink, with a given output buffer size. */
    static long toJpeg(byte[] jxl, int chunkSize, JxlLimits limits, OutputSink sink) throws IOException {
        Objects.requireNonNull(jxl, "jxl");
        Objects.requireNonNull(limits, "limits");
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocateFrom(JAVA_BYTE, jxl);
            JxlDecoder.checkFrames(input, limits, 1, false, arena);
            return reconstruct(input, chunkSize, limits, sink, arena);
        }
    }

    private static long reconstruct(MemorySegment input, int chunkSize, JxlLimits limits, OutputSink sink,
            Arena arena) throws IOException {
        try (NativeDecoder decoder = NativeDecoder.create()) {
            MemorySegment handle = decoder.handle();
            decoder.start(Jxl.JXL_DEC_JPEG_RECONSTRUCTION() | Jxl.JXL_DEC_FULL_IMAGE(), input);

            MemorySegment chunk = arena.allocate(chunkSize);
            boolean reconstructing = false;
            while (true) {
                int status = Jxl.JxlDecoderProcessInput(handle);
                if (status == Jxl.JXL_DEC_JPEG_RECONSTRUCTION()) {
                    reconstructing = true;
                    NativeDecoder.check(Jxl.JxlDecoderSetJPEGBuffer(handle, chunk, chunk.byteSize()),
                            "JxlDecoderSetJPEGBuffer");
                } else if (status == Jxl.JXL_DEC_JPEG_NEED_MORE_OUTPUT()) {
                    if (drainChunk(handle, chunk, sink, limits) == 0) {
                        // libjxl writes some JPEG segments only as a whole, so a buffer
                        // that is too small never fills; grow it until it does.
                        limits.checkJpeg(sink.written() + chunk.byteSize() + 1L);
                        chunk = arena.allocate(Math.multiplyExact(chunk.byteSize(), 2L));
                    }
                    NativeDecoder.check(Jxl.JxlDecoderSetJPEGBuffer(handle, chunk, chunk.byteSize()),
                            "JxlDecoderSetJPEGBuffer");
                } else if (reconstructing
                        && (status == Jxl.JXL_DEC_FULL_IMAGE() || status == Jxl.JXL_DEC_SUCCESS())) {
                    drainChunk(handle, chunk, sink, limits);
                    return sink.written();
                } else if (status == Jxl.JXL_DEC_NEED_IMAGE_OUT_BUFFER()
                        || status == Jxl.JXL_DEC_FULL_IMAGE() || status == Jxl.JXL_DEC_SUCCESS()) {
                    throw new JxlException("The JPEG XL data contains no JPEG reconstruction data");
                } else {
                    throw NativeDecoder.failure(status);
                }
            }
        }
    }

    /**
     * Releases the JPEG buffer and writes the bytes the decoder wrote to it to
     * the sink.
     *
     * @return the number of bytes written
     * @throws IOException       if the sink fails
     * @throws JxlLimitException if the JPEG grows beyond the JPEG limit
     */
    private static long drainChunk(MemorySegment decoder, MemorySegment chunk, OutputSink sink, JxlLimits limits)
            throws IOException {
        long unused = Jxl.JxlDecoderReleaseJPEGBuffer(decoder);
        long written = chunk.byteSize() - unused;
        limits.checkJpeg(sink.written() + written);
        sink.write(chunk.asSlice(0L, written));
        return written;
    }
}

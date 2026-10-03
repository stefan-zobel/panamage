package panamage.jxl;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
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
 * Both directions take the input as a byte array, as a {@link MemorySegment}
 * (for example a mapped file, passed to libjxl without a copy) or as a file
 * {@link Path} (read into native memory), with the same parameters otherwise.
 * The results are returned as byte arrays or written to an
 * {@link OutputStream} or a {@link WritableByteChannel} as they are produced.
 * <p>
 * {@code fromJpeg} takes {@link JxlEncodeOptions}, of which the effort and
 * the threads apply. {@code toJpeg} takes {@link JxlDecodeOptions} and rejects images
 * beyond the pixel limit or JPEG files beyond the JPEG limit of
 * {@link JxlLimits} with a {@link JxlLimitException}, like
 * {@link JxlDecoder}; the methods without options use
 * {@link JxlDecodeOptions#defaults()}.
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
        return fromJpeg(jpeg, JxlEncodeOptions.ofLossless());
    }

    /**
     * Converts a JPEG file losslessly to JPEG XL.
     *
     * @param jpeg    the JPEG file
     * @param options the encoder settings; the effort and the threads apply,
     *                the JPEG is always transcoded without loss
     * @return the JPEG XL file, including JPEG reconstruction data
     * @throws JxlException if the JPEG cannot be transcoded
     */
    public static byte[] fromJpeg(byte[] jpeg, JxlEncodeOptions options) {
        return fromJpeg(jpeg, options, NativeEncoder.OUTPUT_CHUNK_SIZE);
    }

    /**
     * Converts a JPEG file losslessly to JPEG XL and writes the result to a
     * stream, piece by piece, without holding the whole output in memory.
     * <p>
     * The stream is neither flushed nor closed. If an exception is thrown,
     * part of the output may already have been written.
     *
     * @param jpeg    the JPEG file
     * @param options the encoder settings; the effort and the threads apply,
     *                the JPEG is always transcoded without loss
     * @param out     the stream that receives the JPEG XL file
     * @return the number of bytes written
     * @throws IOException  if writing to the stream fails
     * @throws JxlException if the JPEG cannot be transcoded
     */
    public static long fromJpeg(byte[] jpeg, JxlEncodeOptions options, OutputStream out)
            throws IOException {
        return fromJpeg(jpeg, options, OutputSink.of(out), NativeEncoder.OUTPUT_CHUNK_SIZE);
    }

    /**
     * Converts a JPEG file losslessly to JPEG XL and writes the result to a
     * channel, piece by piece, without holding the whole output in memory or
     * copying it to the Java heap.
     * <p>
     * The channel must be in blocking mode; it is not closed. If an exception
     * is thrown, part of the output may already have been written.
     *
     * @param jpeg    the JPEG file
     * @param options the encoder settings; the effort and the threads apply,
     *                the JPEG is always transcoded without loss
     * @param out     the channel that receives the JPEG XL file
     * @return the number of bytes written
     * @throws IOException              if writing to the channel fails
     * @throws IllegalArgumentException if the channel is in non-blocking mode
     * @throws JxlException             if the JPEG cannot be transcoded
     */
    public static long fromJpeg(byte[] jpeg, JxlEncodeOptions options, WritableByteChannel out)
            throws IOException {
        return fromJpeg(jpeg, options, OutputSink.of(out), NativeEncoder.OUTPUT_CHUNK_SIZE);
    }

    /**
     * Like {@link #fromJpeg(byte[])}, reading the JPEG from a memory segment.
     * A native segment, such as a mapped file, is passed to libjxl without
     * copying and must not change during the call; a heap segment is copied
     * to native memory first.
     *
     * @param jpeg the JPEG file
     * @return the JPEG XL file, including JPEG reconstruction data
     * @throws JxlException if the JPEG cannot be transcoded
     */
    public static byte[] fromJpeg(MemorySegment jpeg) {
        return fromJpeg(jpeg, JxlEncodeOptions.ofLossless());
    }

    /**
     * Like {@link #fromJpeg(byte[], JxlEncodeOptions)}, reading the JPEG
     * from a memory segment.
     * A native segment, such as a mapped file, is passed to libjxl without
     * copying and must not change during the call; a heap segment is copied
     * to native memory first.
     *
     * @param jpeg    the JPEG file
     * @param options the encoder settings; the effort and the threads apply,
     *                the JPEG is always transcoded without loss
     * @return the JPEG XL file, including JPEG reconstruction data
     * @throws JxlException if the JPEG cannot be transcoded
     */
    public static byte[] fromJpeg(MemorySegment jpeg, JxlEncodeOptions options) {
        Objects.requireNonNull(jpeg, "jpeg");
        Objects.requireNonNull(options, "options");
        return OutputSink.toBytes(sink -> fromJpeg(jpeg, options, sink));
    }

    /**
     * Like {@link #fromJpeg(byte[], JxlEncodeOptions, OutputStream)}, reading
     * the JPEG from a memory segment.
     * A native segment, such as a mapped file, is passed to libjxl without
     * copying and must not change during the call; a heap segment is copied
     * to native memory first.
     *
     * @param jpeg    the JPEG file
     * @param options the encoder settings; the effort and the threads apply,
     *                the JPEG is always transcoded without loss
     * @param out     the stream that receives the JPEG XL file
     * @return the number of bytes written
     * @throws IOException  if writing to the stream fails
     * @throws JxlException if the JPEG cannot be transcoded
     */
    public static long fromJpeg(MemorySegment jpeg, JxlEncodeOptions options, OutputStream out)
            throws IOException {
        return fromJpeg(jpeg, options, OutputSink.of(out));
    }

    /**
     * Like {@link #fromJpeg(byte[], JxlEncodeOptions, WritableByteChannel)},
     * reading the JPEG from a memory segment.
     * A native segment, such as a mapped file, is passed to libjxl without
     * copying and must not change during the call; a heap segment is copied
     * to native memory first.
     *
     * @param jpeg    the JPEG file
     * @param options the encoder settings; the effort and the threads apply,
     *                the JPEG is always transcoded without loss
     * @param out     the channel that receives the JPEG XL file
     * @return the number of bytes written
     * @throws IOException              if writing to the channel fails
     * @throws IllegalArgumentException if the channel is in non-blocking mode
     * @throws JxlException             if the JPEG cannot be transcoded
     */
    public static long fromJpeg(MemorySegment jpeg, JxlEncodeOptions options, WritableByteChannel out)
            throws IOException {
        return fromJpeg(jpeg, options, OutputSink.of(out));
    }

    /**
     * Like {@link #fromJpeg(byte[])}, reading the JPEG from a file.
     * The file is read into native memory, not onto the Java heap, so it may
     * be larger than 2 GiB.
     *
     * @param file the JPEG file
     * @return the JPEG XL file, including JPEG reconstruction data
     * @throws IOException  if the file cannot be read
     * @throws JxlException if the JPEG cannot be transcoded
     */
    public static byte[] fromJpeg(Path file) throws IOException {
        return fromJpeg(file, JxlEncodeOptions.ofLossless());
    }

    /**
     * Like {@link #fromJpeg(byte[], JxlEncodeOptions)}, reading the JPEG
     * from a file.
     * The file is read into native memory, not onto the Java heap, so it may
     * be larger than 2 GiB.
     *
     * @param file    the JPEG file
     * @param options the encoder settings; the effort and the threads apply,
     *                the JPEG is always transcoded without loss
     * @return the JPEG XL file, including JPEG reconstruction data
     * @throws IOException  if the file cannot be read
     * @throws JxlException if the JPEG cannot be transcoded
     */
    public static byte[] fromJpeg(Path file, JxlEncodeOptions options) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = NativeInput.read(file, arena);
            return OutputSink.toBytes(sink -> transcode(input, options, sink, NativeEncoder.OUTPUT_CHUNK_SIZE));
        }
    }

    /**
     * Like {@link #fromJpeg(byte[], JxlEncodeOptions, OutputStream)}, reading
     * the JPEG from a file.
     * The file is read into native memory, not onto the Java heap, so it may
     * be larger than 2 GiB.
     *
     * @param file    the JPEG file
     * @param options the encoder settings; the effort and the threads apply,
     *                the JPEG is always transcoded without loss
     * @param out     the stream that receives the JPEG XL file
     * @return the number of bytes written
     * @throws IOException  if the file cannot be read or writing to
     *                      the stream fails
     * @throws JxlException if the JPEG cannot be transcoded
     */
    public static long fromJpeg(Path file, JxlEncodeOptions options, OutputStream out)
            throws IOException {
        return fromJpeg(file, options, OutputSink.of(out));
    }

    /**
     * Like {@link #fromJpeg(byte[], JxlEncodeOptions, WritableByteChannel)},
     * reading the JPEG from a file.
     * The file is read into native memory, not onto the Java heap, so it may
     * be larger than 2 GiB.
     *
     * @param file    the JPEG file
     * @param options the encoder settings; the effort and the threads apply,
     *                the JPEG is always transcoded without loss
     * @param out     the channel that receives the JPEG XL file
     * @return the number of bytes written
     * @throws IOException              if the file cannot be read or writing to
     *                                  the channel fails
     * @throws IllegalArgumentException if the channel is in non-blocking mode
     * @throws JxlException             if the JPEG cannot be transcoded
     */
    public static long fromJpeg(Path file, JxlEncodeOptions options, WritableByteChannel out)
            throws IOException {
        return fromJpeg(file, options, OutputSink.of(out));
    }

    /**
     * Restores the original JPEG file from a JPEG XL file that was created by
     * lossless JPEG transcoding.
     *
     * @param jxl  the JPEG XL file
     * @return the original JPEG file
     * @throws JxlLimitException if the image or the JPEG file exceeds the
     *                           default limits
     * @throws JxlException      if the data is not valid JPEG XL or contains
     *                           no JPEG reconstruction data
     */
    public static byte[] toJpeg(byte[] jxl) {
        return toJpeg(jxl, JxlDecodeOptions.defaults());
    }

    /**
     * Like {@link #toJpeg(byte[])}, with the given options.
     *
     * @param jxl     the JPEG XL file
     * @param options the limits ({@link JxlLimits#maxPixels()} and
     *                {@link JxlLimits#maxJpegBytes()} apply) and the threads;
     *                the color space setting has no effect
     * @return the original JPEG file
     * @throws JxlLimitException if the image or the JPEG file exceeds the
     *                           limits
     * @throws JxlException      if the data is not valid JPEG XL or contains
     *                           no JPEG reconstruction data
     */
    public static byte[] toJpeg(byte[] jxl, JxlDecodeOptions options) {
        return toJpeg(jxl, OUTPUT_CHUNK_SIZE, options);
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
     * @param jxl     the JPEG XL file
     * @param options the limits ({@link JxlLimits#maxPixels()} and
     *                {@link JxlLimits#maxJpegBytes()} apply) and the threads;
     *                the color space setting has no effect
     * @param out     the stream that receives the JPEG file
     * @return the number of bytes written
     * @throws IOException       if writing to the stream fails
     * @throws JxlLimitException if the image or the JPEG file exceeds
     *                           the limits
     * @throws JxlException      if the data is not valid JPEG XL or
     *                           contains no JPEG reconstruction data
     */
    public static long toJpeg(byte[] jxl, JxlDecodeOptions options, OutputStream out)
            throws IOException {
        return toJpeg(jxl, OUTPUT_CHUNK_SIZE, options, OutputSink.of(out));
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
     * @param jxl     the JPEG XL file
     * @param options the limits ({@link JxlLimits#maxPixels()} and
     *                {@link JxlLimits#maxJpegBytes()} apply) and the threads;
     *                the color space setting has no effect
     * @param out     the channel that receives the JPEG file
     * @return the number of bytes written
     * @throws IOException              if writing to the channel fails
     * @throws IllegalArgumentException if the channel is in non-blocking mode
     * @throws JxlLimitException        if the image or the JPEG file exceeds
     *                                  the limits
     * @throws JxlException             if the data is not valid JPEG XL or
     *                                  contains no JPEG reconstruction data
     */
    public static long toJpeg(byte[] jxl, JxlDecodeOptions options, WritableByteChannel out)
            throws IOException {
        return toJpeg(jxl, OUTPUT_CHUNK_SIZE, options, OutputSink.of(out));
    }

    /**
     * Like {@link #toJpeg(byte[])}, reading the JPEG XL image from a memory
     * segment.
     * A native segment, such as a mapped file, is passed to libjxl without
     * copying and must not change during the call; a heap segment is copied
     * to native memory first.
     *
     * @param jxl  the JPEG XL file
     * @return the original JPEG file
     * @throws JxlLimitException if the image or the JPEG file exceeds the
     *                           default limits
     * @throws JxlException      if the data is not valid JPEG XL or contains
     *                           no JPEG reconstruction data
     */
    public static byte[] toJpeg(MemorySegment jxl) {
        return toJpeg(jxl, JxlDecodeOptions.defaults());
    }

    /**
     * Like {@link #toJpeg(byte[], JxlDecodeOptions)}, reading the JPEG XL image
     * from a memory segment.
     * A native segment, such as a mapped file, is passed to libjxl without
     * copying and must not change during the call; a heap segment is copied
     * to native memory first.
     *
     * @param jxl     the JPEG XL file
     * @param options the limits ({@link JxlLimits#maxPixels()} and
     *                {@link JxlLimits#maxJpegBytes()} apply) and the threads;
     *                the color space setting has no effect
     * @return the original JPEG file
     * @throws JxlLimitException if the image or the JPEG file exceeds the
     *                           limits
     * @throws JxlException      if the data is not valid JPEG XL or contains
     *                           no JPEG reconstruction data
     */
    public static byte[] toJpeg(MemorySegment jxl, JxlDecodeOptions options) {
        Objects.requireNonNull(jxl, "jxl");
        Objects.requireNonNull(options, "options");
        return OutputSink.toBytes(sink -> toJpeg(jxl, OUTPUT_CHUNK_SIZE, options, sink));
    }

    /**
     * Like {@link #toJpeg(byte[], JxlDecodeOptions, OutputStream)}, reading the
     * JPEG XL image from a memory segment.
     * A native segment, such as a mapped file, is passed to libjxl without
     * copying and must not change during the call; a heap segment is copied
     * to native memory first.
     *
     * @param jxl     the JPEG XL file
     * @param options the limits ({@link JxlLimits#maxPixels()} and
     *                {@link JxlLimits#maxJpegBytes()} apply) and the threads;
     *                the color space setting has no effect
     * @param out     the stream that receives the JPEG file
     * @return the number of bytes written
     * @throws IOException       if writing to the stream fails
     * @throws JxlLimitException if the image or the JPEG file exceeds
     *                           the limits
     * @throws JxlException      if the data is not valid JPEG XL or
     *                           contains no JPEG reconstruction data
     */
    public static long toJpeg(MemorySegment jxl, JxlDecodeOptions options, OutputStream out)
            throws IOException {
        return toJpeg(jxl, OUTPUT_CHUNK_SIZE, options, OutputSink.of(out));
    }

    /**
     * Like {@link #toJpeg(byte[], JxlDecodeOptions, WritableByteChannel)},
     * reading the JPEG XL image from a memory segment.
     * A native segment, such as a mapped file, is passed to libjxl without
     * copying and must not change during the call; a heap segment is copied
     * to native memory first.
     *
     * @param jxl     the JPEG XL file
     * @param options the limits ({@link JxlLimits#maxPixels()} and
     *                {@link JxlLimits#maxJpegBytes()} apply) and the threads;
     *                the color space setting has no effect
     * @param out     the channel that receives the JPEG file
     * @return the number of bytes written
     * @throws IOException              if writing to the channel fails
     * @throws IllegalArgumentException if the channel is in non-blocking mode
     * @throws JxlLimitException        if the image or the JPEG file exceeds
     *                                  the limits
     * @throws JxlException             if the data is not valid JPEG XL or
     *                                  contains no JPEG reconstruction data
     */
    public static long toJpeg(MemorySegment jxl, JxlDecodeOptions options, WritableByteChannel out)
            throws IOException {
        return toJpeg(jxl, OUTPUT_CHUNK_SIZE, options, OutputSink.of(out));
    }

    /**
     * Like {@link #toJpeg(byte[])}, reading the JPEG XL image from a file.
     * The file is read into native memory, not onto the Java heap, so it may
     * be larger than 2 GiB.
     *
     * @param file the JPEG XL file
     * @return the original JPEG file
     * @throws IOException       if the file cannot be read
     * @throws JxlLimitException if the image or the JPEG file exceeds the
     *                           default limits
     * @throws JxlException      if the data is not valid JPEG XL or contains
     *                           no JPEG reconstruction data
     */
    public static byte[] toJpeg(Path file) throws IOException {
        return toJpeg(file, JxlDecodeOptions.defaults());
    }

    /**
     * Like {@link #toJpeg(byte[], JxlDecodeOptions)}, reading the JPEG XL image
     * from a file.
     * The file is read into native memory, not onto the Java heap, so it may
     * be larger than 2 GiB.
     *
     * @param file    the JPEG XL file
     * @param options the limits ({@link JxlLimits#maxPixels()} and
     *                {@link JxlLimits#maxJpegBytes()} apply) and the threads;
     *                the color space setting has no effect
     * @return the original JPEG file
     * @throws IOException       if the file cannot be read
     * @throws JxlLimitException if the image or the JPEG file exceeds the
     *                           limits
     * @throws JxlException      if the data is not valid JPEG XL or contains
     *                           no JPEG reconstruction data
     */
    public static byte[] toJpeg(Path file, JxlDecodeOptions options) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = NativeInput.read(file, arena);
            return OutputSink.toBytes(sink -> restore(input, OUTPUT_CHUNK_SIZE, options, sink));
        }
    }

    /**
     * Like {@link #toJpeg(byte[], JxlDecodeOptions, OutputStream)}, reading the
     * JPEG XL image from a file.
     * The file is read into native memory, not onto the Java heap, so it may
     * be larger than 2 GiB.
     *
     * @param file    the JPEG XL file
     * @param options the limits ({@link JxlLimits#maxPixels()} and
     *                {@link JxlLimits#maxJpegBytes()} apply) and the threads;
     *                the color space setting has no effect
     * @param out     the stream that receives the JPEG file
     * @return the number of bytes written
     * @throws IOException       if the file cannot be read or writing to
     *                           the stream fails
     * @throws JxlLimitException if the image or the JPEG file exceeds
     *                           the limits
     * @throws JxlException      if the data is not valid JPEG XL or
     *                           contains no JPEG reconstruction data
     */
    public static long toJpeg(Path file, JxlDecodeOptions options, OutputStream out)
            throws IOException {
        return toJpeg(file, options, OutputSink.of(out));
    }

    /**
     * Like {@link #toJpeg(byte[], JxlDecodeOptions, WritableByteChannel)},
     * reading the JPEG XL image from a file.
     * The file is read into native memory, not onto the Java heap, so it may
     * be larger than 2 GiB.
     *
     * @param file    the JPEG XL file
     * @param options the limits ({@link JxlLimits#maxPixels()} and
     *                {@link JxlLimits#maxJpegBytes()} apply) and the threads;
     *                the color space setting has no effect
     * @param out     the channel that receives the JPEG file
     * @return the number of bytes written
     * @throws IOException              if the file cannot be read or writing to
     *                                  the channel fails
     * @throws IllegalArgumentException if the channel is in non-blocking mode
     * @throws JxlLimitException        if the image or the JPEG file exceeds
     *                                  the limits
     * @throws JxlException             if the data is not valid JPEG XL or
     *                                  contains no JPEG reconstruction data
     */
    public static long toJpeg(Path file, JxlDecodeOptions options, WritableByteChannel out)
            throws IOException {
        return toJpeg(file, options, OutputSink.of(out));
    }

    /** Like {@link #fromJpeg(byte[], JxlEncodeOptions)}, with a given output buffer size. */
    static byte[] fromJpeg(byte[] jpeg, JxlEncodeOptions options, int chunkSize) {
        return OutputSink.toBytes(sink -> fromJpeg(jpeg, options, sink, chunkSize));
    }

    /** Transcodes to a sink, with a given output buffer size. */
    static long fromJpeg(byte[] jpeg, JxlEncodeOptions options, OutputSink sink, int chunkSize) throws IOException {
        Objects.requireNonNull(jpeg, "jpeg");
        Objects.requireNonNull(options, "options");
        try (Arena arena = Arena.ofConfined()) {
            return transcode(arena.allocateFrom(JAVA_BYTE, jpeg), options, sink, chunkSize);
        }
    }

    private static long fromJpeg(MemorySegment jpeg, JxlEncodeOptions options, OutputSink sink) throws IOException {
        Objects.requireNonNull(jpeg, "jpeg");
        Objects.requireNonNull(options, "options");
        try (Arena arena = Arena.ofConfined()) {
            return transcode(NativeInput.of(jpeg, arena), options, sink, NativeEncoder.OUTPUT_CHUNK_SIZE);
        }
    }

    private static long fromJpeg(Path file, JxlEncodeOptions options, OutputSink sink) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        try (Arena arena = Arena.ofConfined()) {
            return transcode(NativeInput.read(file, arena), options, sink, NativeEncoder.OUTPUT_CHUNK_SIZE);
        }
    }

    /** Transcodes a JPEG file in native memory to a sink. */
    private static long transcode(MemorySegment jpeg, JxlEncodeOptions options, OutputSink sink, int chunkSize)
            throws IOException {
        try (Arena arena = Arena.ofConfined(); NativeEncoder encoder = NativeEncoder.create(options.threads())) {
            // libjxl reads the size of the JPEG only when it encodes it.
            encoder.fitThreadsToUnknownSize();
            MemorySegment handle = encoder.handle();
            // The reconstruction data is stored in a box, which requires the container format.
            encoder.check(Jxl.JxlEncoderUseContainer(handle, Jxl.JXL_TRUE()), "JxlEncoderUseContainer");
            encoder.check(Jxl.JxlEncoderStoreJPEGMetadata(handle, Jxl.JXL_TRUE()), "JxlEncoderStoreJPEGMetadata");
            MemorySegment settings = encoder.createFrameSettings(options.effort());
            encoder.check(Jxl.JxlEncoderAddJPEGFrame(settings, jpeg, jpeg.byteSize()), "JxlEncoderAddJPEGFrame");
            Jxl.JxlEncoderCloseInput(handle);
            return encoder.writeOutput(arena, sink, chunkSize);
        }
    }

    /**
     * Like {@link #toJpeg(byte[], JxlDecodeOptions)}, with a given output
     * buffer size (tests use a small size to exercise the multi-chunk path).
     */
    static byte[] toJpeg(byte[] jxl, int chunkSize, JxlDecodeOptions options) {
        return OutputSink.toBytes(sink -> toJpeg(jxl, chunkSize, options, sink));
    }

    /** Restores the JPEG file to a sink, with a given output buffer size. */
    static long toJpeg(byte[] jxl, int chunkSize, JxlDecodeOptions options, OutputSink sink) throws IOException {
        Objects.requireNonNull(jxl, "jxl");
        return toJpeg(MemorySegment.ofArray(jxl), chunkSize, options, sink);
    }

    private static long toJpeg(MemorySegment jxl, int chunkSize, JxlDecodeOptions options, OutputSink sink)
            throws IOException {
        Objects.requireNonNull(jxl, "jxl");
        Objects.requireNonNull(options, "options");
        try (Arena arena = Arena.ofConfined()) {
            return restore(NativeInput.of(jxl, arena), chunkSize, options, sink);
        }
    }

    private static long toJpeg(Path file, JxlDecodeOptions options, OutputSink sink) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        try (Arena arena = Arena.ofConfined()) {
            return restore(NativeInput.read(file, arena), OUTPUT_CHUNK_SIZE, options, sink);
        }
    }

    /** Restores the JPEG file from a JPEG XL file in native memory. */
    private static long restore(MemorySegment input, int chunkSize, JxlDecodeOptions options, OutputSink sink)
            throws IOException {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
        }
        try (Arena arena = Arena.ofConfined()) {
            JxlDecoder.checkFrames(input, options.limits(), 1, false, arena);
        }
        return reconstruct(input, chunkSize, options, sink);
    }

    private static long reconstruct(MemorySegment input, int chunkSize, JxlDecodeOptions options, OutputSink sink)
            throws IOException {
        JxlLimits limits = options.limits();
        // The buffer is released after the decoder, which may still refer to it.
        try (NativeBuffer chunk = new NativeBuffer(chunkSize);
                NativeDecoder decoder = NativeDecoder.create(options.threads())) {
            MemorySegment handle = decoder.handle();
            decoder.start(Jxl.JXL_DEC_BASIC_INFO() | Jxl.JXL_DEC_JPEG_RECONSTRUCTION() | Jxl.JXL_DEC_FULL_IMAGE(),
                    input);

            boolean reconstructing = false;
            while (true) {
                int status = Jxl.JxlDecoderProcessInput(handle);
                if (status == Jxl.JXL_DEC_BASIC_INFO()) {
                    try (Arena arena = Arena.ofConfined()) {
                        decoder.fitThreads(JxlDecoder.basicInfo(handle, arena));
                    }
                } else if (status == Jxl.JXL_DEC_JPEG_RECONSTRUCTION()) {
                    reconstructing = true;
                    NativeDecoder.check(Jxl.JxlDecoderSetJPEGBuffer(handle, chunk.segment(),
                            chunk.segment().byteSize()), "JxlDecoderSetJPEGBuffer");
                } else if (status == Jxl.JXL_DEC_JPEG_NEED_MORE_OUTPUT()) {
                    if (drainChunk(handle, chunk.segment(), sink, limits) == 0) {
                        // libjxl writes some JPEG segments only as a whole, so a buffer
                        // that is too small never fills; grow it until it does.
                        limits.checkJpeg(sink.written() + chunk.segment().byteSize() + 1L);
                        chunk.grow();
                    }
                    NativeDecoder.check(Jxl.JxlDecoderSetJPEGBuffer(handle, chunk.segment(),
                            chunk.segment().byteSize()), "JxlDecoderSetJPEGBuffer");
                } else if (reconstructing
                        && (status == Jxl.JXL_DEC_FULL_IMAGE() || status == Jxl.JXL_DEC_SUCCESS())) {
                    drainChunk(handle, chunk.segment(), sink, limits);
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

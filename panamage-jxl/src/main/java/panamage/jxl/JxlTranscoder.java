package panamage.jxl;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
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

    /** Like {@link #fromJpeg(byte[], int)}, with a given output buffer size. */
    static byte[] fromJpeg(byte[] jpeg, int effort, int chunkSize) {
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
            return encoder.collectOutput(arena, chunkSize);
        }
    }

    /**
     * Restores the original JPEG file from a JPEG XL file that was created by
     * lossless JPEG transcoding.
     *
     * @param jxl the JPEG XL file
     * @return the original JPEG file
     * @throws JxlLimitException if the image exceeds the default limits
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
     * @param limits the limits; only {@link JxlLimits#maxPixels()} applies
     * @return the original JPEG file
     * @throws JxlLimitException if the image exceeds the limits
     * @throws JxlException      if the data is not valid JPEG XL or contains
     *                           no JPEG reconstruction data
     */
    public static byte[] toJpeg(byte[] jxl, JxlLimits limits) {
        return toJpeg(jxl, OUTPUT_CHUNK_SIZE, limits);
    }

    /**
     * Like {@link #toJpeg(byte[], JxlLimits)}, with a given output buffer size
     * (tests use a small size to exercise the multi-chunk path).
     */
    static byte[] toJpeg(byte[] jxl, int chunkSize, JxlLimits limits) {
        Objects.requireNonNull(jxl, "jxl");
        Objects.requireNonNull(limits, "limits");
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = arena.allocateFrom(JAVA_BYTE, jxl);
            JxlDecoder.checkFrames(input, limits, arena);
            return reconstruct(input, chunkSize, arena);
        }
    }

    private static byte[] reconstruct(MemorySegment input, int chunkSize, Arena arena) {
        try (NativeDecoder decoder = NativeDecoder.create()) {
            MemorySegment handle = decoder.handle();
            decoder.start(Jxl.JXL_DEC_JPEG_RECONSTRUCTION() | Jxl.JXL_DEC_FULL_IMAGE(), input);

            MemorySegment chunk = arena.allocate(chunkSize);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean reconstructing = false;
            while (true) {
                int status = Jxl.JxlDecoderProcessInput(handle);
                if (status == Jxl.JXL_DEC_JPEG_RECONSTRUCTION()) {
                    reconstructing = true;
                    NativeDecoder.check(Jxl.JxlDecoderSetJPEGBuffer(handle, chunk, chunk.byteSize()),
                            "JxlDecoderSetJPEGBuffer");
                } else if (status == Jxl.JXL_DEC_JPEG_NEED_MORE_OUTPUT()) {
                    if (drainChunk(handle, chunk, out) == 0) {
                        // libjxl writes some JPEG segments only as a whole, so a buffer
                        // that is too small never fills; grow it until it does.
                        chunk = arena.allocate(Math.multiplyExact(chunk.byteSize(), 2L));
                    }
                    NativeDecoder.check(Jxl.JxlDecoderSetJPEGBuffer(handle, chunk, chunk.byteSize()),
                            "JxlDecoderSetJPEGBuffer");
                } else if (reconstructing
                        && (status == Jxl.JXL_DEC_FULL_IMAGE() || status == Jxl.JXL_DEC_SUCCESS())) {
                    drainChunk(handle, chunk, out);
                    return out.toByteArray();
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
     * Releases the JPEG buffer and appends the bytes the decoder wrote to it.
     *
     * @return the number of bytes written
     */
    private static long drainChunk(MemorySegment decoder, MemorySegment chunk, ByteArrayOutputStream out) {
        long unused = Jxl.JxlDecoderReleaseJPEGBuffer(decoder);
        long written = chunk.byteSize() - unused;
        out.write(chunk.asSlice(0L, written).toArray(JAVA_BYTE), 0, (int) written);
        return written;
    }
}

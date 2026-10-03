package panamage.jxl;

import static java.lang.foreign.MemorySegment.NULL;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import panamage.jxl.ffi.Jxl;

/**
 * Owns a native {@code JxlEncoder} together with its thread pool, if any.
 * <p>
 * Closing destroys the encoder first and then the thread pool it refers to.
 */
final class NativeEncoder implements AutoCloseable {

    /** Default size of the native buffer that receives the encoded bytes piece by piece. */
    static final int OUTPUT_CHUNK_SIZE = 64 * 1024;

    /** libjxl requires at least this much output space per call. */
    static final int MIN_OUTPUT_CHUNK_SIZE = 32;

    /** The thread pool, or {@code null} for an encoder that runs on the calling thread. */
    private final ParallelRunner runner;
    private final MemorySegment handle;

    private NativeEncoder(ParallelRunner runner, MemorySegment handle) {
        this.runner = runner;
        this.handle = handle;
    }

    /**
     * Creates an encoder that uses libjxl's native thread pool as the setting
     * says; with {@link JxlThreads#auto()}, call {@link #fitThreads} when the
     * image size is known.
     *
     * @return the new encoder
     * @throws JxlException if the encoder cannot be created
     */
    static NativeEncoder create(JxlThreads threads) {
        ParallelRunner runner = ParallelRunner.create(threads);
        MemorySegment handle = Jxl.JxlEncoderCreate(NULL);
        if (handle.equals(NULL)) {
            if (runner != null) {
                runner.close();
            }
            throw new JxlException("JxlEncoderCreate failed");
        }
        NativeEncoder encoder = new NativeEncoder(runner, handle);
        if (runner != null) {
            try {
                encoder.check(Jxl.JxlEncoderSetParallelRunner(handle, runner.function(), runner.opaque()),
                        "JxlEncoderSetParallelRunner");
            } catch (JxlException e) {
                encoder.close();
                throw e;
            }
        }
        return encoder;
    }

    MemorySegment handle() {
        return handle;
    }

    /** Sizes an automatic thread pool for an image of the given size. */
    void fitThreads(long xsize, long ysize) {
        if (runner != null) {
            runner.fitTo(xsize, ysize);
        }
    }

    /** Sizes an automatic thread pool for an input whose size only libjxl knows. */
    void fitThreadsToUnknownSize() {
        if (runner != null) {
            runner.fitToUnknownSize();
        }
    }

    /** Returns the thread pool, or {@code null}; for tests. */
    ParallelRunner runner() {
        return runner;
    }

    /**
     * Creates frame settings with the given effort.
     *
     * @throws JxlException if libjxl rejects the settings
     */
    MemorySegment createFrameSettings(int effort) {
        MemorySegment settings = Jxl.JxlEncoderFrameSettingsCreate(handle, NULL);
        if (settings.equals(NULL)) {
            throw new JxlException("JxlEncoderFrameSettingsCreate failed");
        }
        check(Jxl.JxlEncoderFrameSettingsSetOption(settings, Jxl.JXL_ENC_FRAME_SETTING_EFFORT(), effort),
                "JxlEncoderFrameSettingsSetOption(EFFORT)");
        return settings;
    }

    /**
     * Runs the encoder until all input is encoded and returns the output.
     * The input must have been closed with {@code JxlEncoderCloseInput}.
     *
     * @throws JxlException if encoding fails
     */
    byte[] collectOutput(Arena arena) {
        return collectOutput(arena, OUTPUT_CHUNK_SIZE);
    }

    /**
     * Like {@link #collectOutput(Arena)}, with a given buffer size (tests use a
     * small size to exercise the multi-chunk path).
     */
    byte[] collectOutput(Arena arena, int chunkSize) {
        return OutputSink.toBytes(sink -> writeOutput(arena, sink, chunkSize));
    }

    /**
     * Runs the encoder until all input added so far is encoded and writes the
     * output to the sink, one buffer of the given size at a time. Before
     * {@code JxlEncoderCloseInput}, libjxl encodes the queued frames as not
     * being the last one; afterwards, it also finishes the file.
     *
     * @return the number of bytes written
     * @throws IOException  if the sink fails
     * @throws JxlException if encoding fails
     */
    long writeOutput(Arena arena, OutputSink sink, int chunkSize) throws IOException {
        if (chunkSize < MIN_OUTPUT_CHUNK_SIZE) {
            throw new IllegalArgumentException("chunkSize must be at least " + MIN_OUTPUT_CHUNK_SIZE);
        }
        MemorySegment chunk = arena.allocate(chunkSize);
        MemorySegment nextOut = arena.allocate(ADDRESS);
        MemorySegment availOut = arena.allocate(JAVA_LONG);
        int status;
        do {
            nextOut.set(ADDRESS, 0L, chunk);
            availOut.set(JAVA_LONG, 0L, chunk.byteSize());
            status = Jxl.JxlEncoderProcessOutput(handle, nextOut, availOut);
            long written = chunk.byteSize() - availOut.get(JAVA_LONG, 0L);
            sink.write(chunk.asSlice(0L, written));
        } while (status == Jxl.JXL_ENC_NEED_MORE_OUTPUT());
        check(status, "JxlEncoderProcessOutput");
        return sink.written();
    }

    /**
     * Throws a {@link JxlException} with libjxl's error reason if the status
     * is not {@code JXL_ENC_SUCCESS}.
     */
    void check(int status, String function) {
        if (status != Jxl.JXL_ENC_SUCCESS()) {
            throw new JxlException(function + " failed: " + errorText(Jxl.JxlEncoderGetError(handle)));
        }
    }

    private static String errorText(int error) {
        if (error == Jxl.JXL_ENC_ERR_GENERIC()) {
            return "generic error";
        } else if (error == Jxl.JXL_ENC_ERR_OOM()) {
            return "out of memory";
        } else if (error == Jxl.JXL_ENC_ERR_JBRD()) {
            return "JPEG bitstream reconstruction data could not be represented";
        } else if (error == Jxl.JXL_ENC_ERR_BAD_INPUT()) {
            return "invalid input";
        } else if (error == Jxl.JXL_ENC_ERR_NOT_SUPPORTED()) {
            return "feature not supported";
        } else if (error == Jxl.JXL_ENC_ERR_API_USAGE()) {
            return "invalid API usage";
        }
        return "error code " + error;
    }

    @Override
    public void close() {
        try {
            Jxl.JxlEncoderDestroy(handle);
        } finally {
            if (runner != null) {
                runner.close();
            }
        }
    }
}

package panamage.jxl;

import static java.lang.foreign.MemorySegment.NULL;

import java.lang.foreign.MemorySegment;

import panamage.jxl.ffi.Jxl;

/**
 * Owns a native {@code JxlDecoder} together with its thread pool, if any.
 * <p>
 * Closing destroys the decoder first and then the thread pool it refers to.
 */
final class NativeDecoder implements AutoCloseable {

    /** The thread pool, or {@code null} for a decoder that runs on the calling thread. */
    private final ParallelRunner runner;
    private final MemorySegment handle;

    private NativeDecoder(ParallelRunner runner, MemorySegment handle) {
        this.runner = runner;
        this.handle = handle;
    }

    /**
     * Creates a decoder that uses libjxl's native thread pool.
     *
     * @return the new decoder
     * @throws JxlException if the decoder cannot be created
     */
    static NativeDecoder create() {
        ParallelRunner runner = ParallelRunner.create();
        MemorySegment handle = Jxl.JxlDecoderCreate(NULL);
        if (handle.equals(NULL)) {
            runner.close();
            throw new JxlException("JxlDecoderCreate failed");
        }
        NativeDecoder decoder = new NativeDecoder(runner, handle);
        try {
            check(Jxl.JxlDecoderSetParallelRunner(handle, runner.function(), runner.opaque()),
                    "JxlDecoderSetParallelRunner");
        } catch (JxlException e) {
            decoder.close();
            throw e;
        }
        return decoder;
    }

    /**
     * Creates a decoder that runs on the calling thread, for reading headers
     * and metadata boxes without the cost of starting a thread pool.
     *
     * @return the new decoder
     * @throws JxlException if the decoder cannot be created
     */
    static NativeDecoder createWithoutThreads() {
        MemorySegment handle = Jxl.JxlDecoderCreate(NULL);
        if (handle.equals(NULL)) {
            throw new JxlException("JxlDecoderCreate failed");
        }
        return new NativeDecoder(null, handle);
    }

    MemorySegment handle() {
        return handle;
    }

    /**
     * Subscribes to the given {@code JXL_DEC_*} events and passes the complete
     * input. The input segment must stay alive until the decoder is closed.
     *
     * @throws JxlException if libjxl rejects the call
     */
    void start(int events, MemorySegment input) {
        check(Jxl.JxlDecoderSubscribeEvents(handle, events), "JxlDecoderSubscribeEvents");
        check(Jxl.JxlDecoderSetInput(handle, input, input.byteSize()), "JxlDecoderSetInput");
        Jxl.JxlDecoderCloseInput(handle);
    }

    /**
     * Converts a status of {@code JxlDecoderProcessInput} that ends decoding
     * unexpectedly into an exception.
     */
    static JxlException failure(int status) {
        if (status == Jxl.JXL_DEC_ERROR()) {
            return new JxlException("Invalid or corrupt JPEG XL data");
        } else if (status == Jxl.JXL_DEC_NEED_MORE_INPUT()) {
            return new JxlException("Truncated JPEG XL data");
        }
        return new JxlException("Unexpected decoder status: " + status);
    }

    /**
     * Throws a {@link JxlException} if the status is not {@code JXL_DEC_SUCCESS}.
     */
    static void check(int status, String function) {
        if (status != Jxl.JXL_DEC_SUCCESS()) {
            throw new JxlException(function + " failed with status " + status);
        }
    }

    @Override
    public void close() {
        try {
            Jxl.JxlDecoderDestroy(handle);
        } finally {
            if (runner != null) {
                runner.close();
            }
        }
    }
}

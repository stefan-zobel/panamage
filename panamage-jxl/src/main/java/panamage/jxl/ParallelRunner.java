package panamage.jxl;

import static java.lang.foreign.MemorySegment.NULL;

import java.lang.foreign.MemorySegment;

import panamage.jxl.ffi.Jxl;

/**
 * Owns a libjxl thread pool ({@code JxlThreadParallelRunner}) for one encoder
 * or decoder.
 * <p>
 * The runner function is a native function of libjxl, so work is distributed
 * to native threads without calling back into Java. Close the runner only
 * after the encoder or decoder that uses it has been destroyed.
 */
final class ParallelRunner implements AutoCloseable {

    private final MemorySegment handle;

    private ParallelRunner(MemorySegment handle) {
        this.handle = handle;
    }

    /**
     * Creates a thread pool with libjxl's default number of worker threads.
     *
     * @return the new runner
     * @throws JxlException if the runner cannot be created
     */
    static ParallelRunner create() {
        MemorySegment handle = Jxl.JxlThreadParallelRunnerCreate(NULL,
                Jxl.JxlThreadParallelRunnerDefaultNumWorkerThreads());
        if (handle.equals(NULL)) {
            throw new JxlException("JxlThreadParallelRunnerCreate failed");
        }
        return new ParallelRunner(handle);
    }

    /** Address of the native runner function, for {@code Jxl*SetParallelRunner}. */
    MemorySegment function() {
        return Jxl.JxlThreadParallelRunner$address();
    }

    /** Opaque runner handle, for {@code Jxl*SetParallelRunner}. */
    MemorySegment opaque() {
        return handle;
    }

    @Override
    public void close() {
        Jxl.JxlThreadParallelRunnerDestroy(handle);
    }
}

package panamage.jxl;

import static java.lang.foreign.MemorySegment.NULL;

import java.lang.foreign.MemorySegment;

import panamage.jxl.ffi.Jxl;

/**
 * Owns a libjxl thread pool ({@code JxlResizableParallelRunner}) for one
 * encoder or decoder.
 * <p>
 * The pool runs tasks on the calling thread until its number of threads is
 * set. With {@link JxlThreads#auto()}, that happens in {@link #fitTo} once
 * the image size is known, so a small image never starts a thread.
 * <p>
 * The runner function is a native function of libjxl, so work is distributed
 * to native threads without calling back into Java. Close the runner only
 * after the encoder or decoder that uses it has been destroyed.
 */
final class ParallelRunner implements AutoCloseable {

    private final MemorySegment handle;
    private final boolean automatic;
    private int threads;
    private boolean fitted;

    private ParallelRunner(MemorySegment handle, boolean automatic) {
        this.handle = handle;
        this.automatic = automatic;
    }

    /**
     * Creates a thread pool for the given setting.
     *
     * @return the new runner, or {@code null} for {@link JxlThreads#none()}
     * @throws JxlException if the runner cannot be created
     */
    static ParallelRunner create(JxlThreads threads) {
        if (threads.threads() == 0) {
            return null;
        }
        MemorySegment handle = Jxl.JxlResizableParallelRunnerCreate(NULL);
        if (handle.equals(NULL)) {
            throw new JxlException("JxlResizableParallelRunnerCreate failed");
        }
        ParallelRunner runner = new ParallelRunner(handle, threads.threads() == JxlThreads.AUTO);
        if (!runner.automatic) {
            runner.setThreads(threads.threads());
        }
        return runner;
    }

    /**
     * Sets the number of threads for an image of the given size, the first
     * time it is called for an automatic runner; otherwise does nothing.
     */
    void fitTo(long xsize, long ysize) {
        if (automatic && !fitted) {
            fitted = true;
            setThreads(Integer.toUnsignedLong(Jxl.JxlResizableParallelRunnerSuggestThreads(xsize, ysize)));
        }
    }

    /**
     * Sets the number of threads for an input of unknown size: one per
     * processor, the first time it is called for an automatic runner.
     */
    void fitToUnknownSize() {
        if (automatic && !fitted) {
            fitted = true;
            setThreads(Jxl.JxlThreadParallelRunnerDefaultNumWorkerThreads());
        }
    }

    private void setThreads(long count) {
        Jxl.JxlResizableParallelRunnerSetThreads(handle, count);
        threads = (int) Math.min(count, Integer.MAX_VALUE);
    }

    /** Returns the number of worker threads set so far; for tests. */
    int threads() {
        return threads;
    }

    /** Address of the native runner function, for {@code Jxl*SetParallelRunner}. */
    MemorySegment function() {
        return Jxl.JxlResizableParallelRunner$address();
    }

    /** Opaque runner handle, for {@code Jxl*SetParallelRunner}. */
    MemorySegment opaque() {
        return handle;
    }

    @Override
    public void close() {
        Jxl.JxlResizableParallelRunnerDestroy(handle);
    }
}

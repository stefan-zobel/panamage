package panamage.jxl;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * A native output buffer that can grow. It has an arena of its own, so the
 * memory of a smaller buffer is released as soon as the buffer grows,
 * instead of staying allocated until the end of the call.
 */
final class NativeBuffer implements AutoCloseable {

    private Arena arena;
    private MemorySegment segment;

    /**
     * Allocates a buffer of the given size.
     *
     * @param size the size in bytes
     */
    NativeBuffer(long size) {
        arena = Arena.ofConfined();
        try {
            segment = arena.allocate(size);
        } catch (RuntimeException | Error e) {
            arena.close();
            throw e;
        }
    }

    /** Returns the current buffer. */
    MemorySegment segment() {
        if (arena == null) {
            throw new IllegalStateException("The buffer is closed");
        }
        return segment;
    }

    /**
     * Replaces the buffer with one of twice the size; the content is
     * discarded. The old buffer is released before the new one is allocated,
     * so the caller must no longer use it, and libjxl must have released it.
     *
     * @return the new buffer
     * @throws ArithmeticException if the size overflows
     */
    MemorySegment grow() {
        long size = Math.multiplyExact(segment().byteSize(), 2L);
        close();
        Arena next = Arena.ofConfined();
        try {
            segment = next.allocate(size);
        } catch (RuntimeException | Error e) {
            next.close();
            throw e;
        }
        arena = next;
        return segment;
    }

    /** Releases the buffer; further calls have no effect. */
    @Override
    public void close() {
        if (arena != null) {
            arena.close();
            arena = null;
            segment = null;
        }
    }
}

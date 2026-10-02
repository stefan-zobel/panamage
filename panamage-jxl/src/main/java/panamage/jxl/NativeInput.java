package panamage.jxl;

import java.io.EOFException;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Provides encoded input for libjxl in native memory.
 */
final class NativeInput {

    /** The largest piece read at once; a ByteBuffer holds at most 2 GiB. */
    private static final long READ_CHUNK_SIZE = 1L << 30;

    private NativeInput() {
    }

    /**
     * Returns the data in native memory: a native segment as it is, a heap
     * segment copied into the arena.
     */
    static MemorySegment of(MemorySegment data, Arena arena) {
        return data.isNative() ? data : copy(data, arena);
    }

    /** Copies the data into native memory of the arena. */
    static MemorySegment copy(MemorySegment data, Arena arena) {
        return arena.allocate(data.byteSize()).copyFrom(data);
    }

    /**
     * Reads a file into native memory of the arena, without a copy on the
     * Java heap. The file is read instead of mapped, because libjxl would
     * crash the process if another process truncated a mapped file while it
     * is being decoded.
     *
     * @throws IOException if the file cannot be read or becomes shorter while
     *                     it is read
     */
    static MemorySegment read(Path path, Arena arena) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            MemorySegment data = arena.allocate(size);
            for (long position = 0; position < size; position += READ_CHUNK_SIZE) {
                ByteBuffer buffer = data.asSlice(position, Math.min(READ_CHUNK_SIZE, size - position)).asByteBuffer();
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer, position + buffer.position()) < 0) {
                        throw new EOFException(path + " became shorter while it was read");
                    }
                }
            }
            return data;
        }
    }
}

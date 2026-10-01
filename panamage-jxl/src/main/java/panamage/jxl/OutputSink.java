package panamage.jxl;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/**
 * Receives the output of the encoder and the transcoder chunk by chunk and
 * counts the bytes written.
 * <p>
 * A sink neither flushes nor closes the stream or channel it writes to.
 */
abstract class OutputSink {

    /** Writes to a sink; may fail with the exception of the stream or channel. */
    @FunctionalInterface
    interface Writer {
        void writeTo(OutputSink sink) throws IOException;
    }

    private long written;

    private OutputSink() {
    }

    /**
     * Creates a sink that copies the chunks to a stream.
     *
     * @throws NullPointerException if {@code out} is {@code null}
     */
    static OutputSink of(OutputStream out) {
        return new StreamSink(Objects.requireNonNull(out, "out"));
    }

    /**
     * Creates a sink that writes the chunks to a channel without a copy to the
     * heap.
     *
     * @throws NullPointerException     if {@code out} is {@code null}
     * @throws IllegalArgumentException if {@code out} is in non-blocking mode
     */
    static OutputSink of(WritableByteChannel out) {
        Objects.requireNonNull(out, "out");
        if (out instanceof SelectableChannel selectable && !selectable.isBlocking()) {
            throw new IllegalArgumentException("The channel must be in blocking mode");
        }
        return new ChannelSink(out);
    }

    /**
     * Runs a writer with a sink that collects the output in a byte array.
     *
     * @return the output
     */
    static byte[] toBytes(Writer writer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            writer.writeTo(of(out));
        } catch (IOException e) {
            // A ByteArrayOutputStream does not throw.
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * Writes the whole chunk.
     *
     * @throws IOException if the stream or channel fails
     */
    final void write(MemorySegment chunk) throws IOException {
        if (chunk.byteSize() > 0) {
            writeChunk(chunk);
            written += chunk.byteSize();
        }
    }

    /** Returns the number of bytes written so far. */
    final long written() {
        return written;
    }

    abstract void writeChunk(MemorySegment chunk) throws IOException;

    private static final class StreamSink extends OutputSink {

        private final OutputStream out;
        private byte[] buffer = new byte[0];

        StreamSink(OutputStream out) {
            this.out = out;
        }

        @Override
        void writeChunk(MemorySegment chunk) throws IOException {
            int size = Math.toIntExact(chunk.byteSize());
            if (buffer.length < size) {
                buffer = new byte[size];
            }
            MemorySegment.copy(chunk, JAVA_BYTE, 0L, buffer, 0, size);
            out.write(buffer, 0, size);
        }
    }

    private static final class ChannelSink extends OutputSink {

        private final WritableByteChannel out;

        ChannelSink(WritableByteChannel out) {
            this.out = out;
        }

        @Override
        void writeChunk(MemorySegment chunk) throws IOException {
            ByteBuffer buffer = chunk.asByteBuffer();
            // A channel in blocking mode writes at least one byte per call.
            while (buffer.hasRemaining()) {
                out.write(buffer);
            }
        }
    }
}

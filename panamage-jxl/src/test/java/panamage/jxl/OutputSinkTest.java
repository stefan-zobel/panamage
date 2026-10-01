package panamage.jxl;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Pipe;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputSinkTest {

    private static final byte[] DATA = data(1000);

    @Test
    void streamSinkWritesChunksOfChangingSizes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutputSink sink = OutputSink.of(out);

        writeInChunks(sink, 100, 7, 0, 500, 1, 392);

        assertArrayEquals(DATA, out.toByteArray());
        assertEquals(DATA.length, sink.written());
    }

    @Test
    void channelSinkHandlesPartialWrites() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutputSink sink = OutputSink.of(new TrickleChannel(out, 7));

        writeInChunks(sink, 300, 0, 700);

        assertArrayEquals(DATA, out.toByteArray());
        assertEquals(DATA.length, sink.written());
    }

    @Test
    void channelSinkWritesToAFileChannel(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("out.bin");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writeInChunks(OutputSink.of(channel), 999, 1);
        }
        assertArrayEquals(DATA, Files.readAllBytes(file));
    }

    @Test
    void rejectsAChannelInNonBlockingMode() throws IOException {
        Pipe pipe = Pipe.open();
        try (Pipe.SinkChannel sink = pipe.sink(); Pipe.SourceChannel source = pipe.source()) {
            sink.configureBlocking(false);
            assertThrows(IllegalArgumentException.class, () -> OutputSink.of(sink));
        }
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> OutputSink.of((OutputStream) null));
        assertThrows(NullPointerException.class, () -> OutputSink.of((WritableByteChannel) null));
    }

    @Test
    void toBytesCollectsTheOutput() {
        byte[] bytes = OutputSink.toBytes(sink -> writeInChunks(sink, 1000));
        assertArrayEquals(DATA, bytes);
    }

    @Test
    void sinkFailuresArePassedOn() {
        OutputSink sink = OutputSink.of(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disk full");
            }
        });
        IOException e = assertThrows(IOException.class, () -> writeInChunks(sink, 1000));
        assertTrue(e.getMessage().contains("disk full"), e.getMessage());
        assertEquals(0, sink.written());
    }

    /** Writes {@link #DATA} to the sink in chunks of the given sizes, which must add up to its length. */
    private static void writeInChunks(OutputSink sink, int... sizes) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocateFrom(JAVA_BYTE, DATA);
            long offset = 0;
            for (int size : sizes) {
                sink.write(data.asSlice(offset, size));
                offset += size;
            }
            assertEquals(DATA.length, offset);
        }
    }

    private static byte[] data(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        return data;
    }

    /** A blocking channel that accepts at most a few bytes per call, as a socket may. */
    private static final class TrickleChannel implements WritableByteChannel {

        private final OutputStream out;
        private final int maxBytes;

        TrickleChannel(OutputStream out, int maxBytes) {
            this.out = out;
            this.maxBytes = maxBytes;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            int n = Math.min(maxBytes, src.remaining());
            byte[] bytes = new byte[n];
            src.get(bytes);
            out.write(bytes);
            return n;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
        }
    }
}

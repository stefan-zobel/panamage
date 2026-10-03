package panamage.jxl.imageio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import javax.imageio.stream.ImageInputStream;

import panamage.jxl.JxlAnimationInfo;
import panamage.jxl.JxlDecodeOptions;
import panamage.jxl.JxlDecoder;
import panamage.jxl.JxlFrameDecoder;
import panamage.jxl.JxlImage;
import panamage.jxl.JxlImageInfo;
import panamage.jxl.JxlMetadata;
import panamage.jxl.JxlSampleType;

/**
 * The encoded input of {@link JxlImageReader}, copied to native memory once,
 * so the decoder methods do not copy it on every call.
 * <p>
 * The reader itself does not use the foreign memory API: on Java 21 it is a
 * preview API, and the service provider, which is compiled for Java 8, could
 * not refer to a reader class that uses it.
 */
final class EncodedInput implements AutoCloseable {

    private static final int READ_CHUNK_SIZE = 64 * 1024;

    /** Shared, because a reader may be used by one thread after another. */
    private final Arena arena;
    private final MemorySegment data;

    private EncodedInput(Arena arena, MemorySegment data) {
        this.arena = arena;
        this.data = data;
    }

    /** Reads the stream from its current position to the end into native memory. */
    static EncodedInput read(ImageInputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[READ_CHUNK_SIZE];
        int read;
        while ((read = stream.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        byte[] bytes = out.toByteArray();
        Arena arena = Arena.ofShared();
        try {
            return new EncodedInput(arena, arena.allocate(bytes.length).copyFrom(MemorySegment.ofArray(bytes)));
        } catch (RuntimeException | Error e) {
            arena.close();
            throw e;
        }
    }

    /** Returns the native copy; for tests. */
    MemorySegment segment() {
        return data;
    }

    JxlImageInfo readInfo() {
        return JxlDecoder.readInfo(data);
    }

    JxlAnimationInfo readAnimationInfo() {
        return JxlDecoder.readAnimationInfo(data);
    }

    JxlMetadata readMetadata(JxlDecodeOptions options) {
        return JxlDecoder.readMetadata(data, options);
    }

    JxlImage decode(int channels, JxlSampleType type, JxlDecodeOptions options) {
        return JxlDecoder.decode(data, channels, type, options);
    }

    /** Opens a frame decoder, which holds a copy of its own. */
    JxlFrameDecoder openFrames(int channels, JxlSampleType type, JxlDecodeOptions options) {
        return JxlFrameDecoder.open(data, channels, type, options);
    }

    /** Releases the native copy. */
    @Override
    public void close() {
        arena.close();
    }
}

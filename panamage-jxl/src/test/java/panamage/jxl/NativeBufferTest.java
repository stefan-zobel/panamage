package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;

import org.junit.jupiter.api.Test;

class NativeBufferTest {

    @Test
    void growingDoublesTheSizeAndReleasesTheOldBuffer() {
        try (NativeBuffer buffer = new NativeBuffer(100)) {
            MemorySegment first = buffer.segment();
            assertEquals(100, first.byteSize());

            MemorySegment second = buffer.grow();

            assertEquals(200, second.byteSize());
            assertEquals(second, buffer.segment());
            assertFalse(first.scope().isAlive());
            assertTrue(second.scope().isAlive());
            assertEquals(400, buffer.grow().byteSize());
            assertFalse(second.scope().isAlive());
        }
    }

    @Test
    void closingReleasesTheBufferOnce() {
        NativeBuffer buffer = new NativeBuffer(16);
        MemorySegment segment = buffer.segment();

        buffer.close();
        buffer.close();

        assertFalse(segment.scope().isAlive());
        assertThrows(IllegalStateException.class, buffer::segment);
        assertThrows(IllegalStateException.class, buffer::grow);
    }
}

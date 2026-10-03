package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JpegSizeTest {

    @ParameterizedTest
    @ValueSource(strings = {"photo-420-exif.jpg", "photo-444-progressive.jpg", "photo-gray.jpg",
            "photo-orient6-xmp.jpg"})
    void readsTheStoredSizeOfTheTestImages(String name) {
        // The orientation is not applied: the frame header holds the stored size.
        assertArrayEquals(new long[] {TestImages.PHOTO_WIDTH, TestImages.PHOTO_HEIGHT},
                JpegSize.of(MemorySegment.ofArray(TestImages.resource(name))));
    }

    @Test
    void skipsSegmentsAndFillBytes() {
        byte[] jpeg = jpeg(app(), new byte[] {(byte) 0xFF, (byte) 0xFF}, sof(0xC0, 3000, 4000));
        assertArrayEquals(new long[] {4000, 3000}, JpegSize.of(MemorySegment.ofArray(jpeg)));
        // Progressive and lossless frame headers count as well.
        assertArrayEquals(new long[] {65535, 1}, JpegSize.of(MemorySegment.ofArray(jpeg(sof(0xC2, 1, 65535)))));
        assertArrayEquals(new long[] {7, 9}, JpegSize.of(MemorySegment.ofArray(jpeg(sof(0xC3, 9, 7)))));
    }

    @Test
    void givesNoSizeWithoutAReadableFrameHeader() {
        assertNull(size(new byte[0]));
        assertNull(size(new byte[] {(byte) 0xFF}));
        // No SOI.
        assertNull(size(Arrays.copyOfRange(jpeg(sof(0xC0, 10, 10)), 2, 15)));
        // Height 0: the size follows in a DNL marker.
        assertNull(size(jpeg(sof(0xC0, 0, 10))));
        // The image data starts before a frame header.
        assertNull(size(jpeg(new byte[] {(byte) 0xFF, (byte) 0xDA, 0, 2}, sof(0xC0, 10, 10))));
        // DHT is not a frame header.
        assertNull(size(jpeg(new byte[] {(byte) 0xFF, (byte) 0xC4, 0, 2})));
        // Truncated after a marker, in a length and before the end of the width.
        byte[] full = jpeg(app(), sof(0xC0, 10, 20));
        int sizeEnd = 2 + app().length + 9;
        for (int length = 2; length < sizeEnd; length++) {
            assertNull(size(Arrays.copyOf(full, length)), "length " + length);
        }
        // The components after the size are not needed.
        assertArrayEquals(new long[] {20, 10}, size(Arrays.copyOf(full, sizeEnd)));
        // Garbage instead of a marker.
        assertNull(size(new byte[] {(byte) 0xFF, (byte) 0xD8, 0x12, 0x34}));
    }

    private static long[] size(byte[] data) {
        return JpegSize.of(MemorySegment.ofArray(data));
    }

    /** SOI followed by the given segments. */
    private static byte[] jpeg(byte[]... segments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        for (byte[] segment : segments) {
            out.writeBytes(segment);
        }
        return out.toByteArray();
    }

    /** An APP0 segment with 4 bytes of content. */
    private static byte[] app() {
        return new byte[] {(byte) 0xFF, (byte) 0xE0, 0, 6, 'J', 'F', 'I', 'F'};
    }

    /** A frame header with one component. */
    private static byte[] sof(int marker, int height, int width) {
        return new byte[] {(byte) 0xFF, (byte) marker, 0, 11, 8, (byte) (height >> 8), (byte) height,
            (byte) (width >> 8), (byte) width, 1, 1, 0x11, 0};
    }
}

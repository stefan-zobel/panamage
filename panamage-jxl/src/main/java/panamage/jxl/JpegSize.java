package panamage.jxl;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.MemorySegment;

/**
 * Reads the size of a JPEG image from its frame header (SOF marker), so the
 * thread pool for transcoding can be sized before libjxl reads the file.
 * <p>
 * Only the markers up to the frame header are read. Data that cannot be read
 * safely gives no size; libjxl reports invalid JPEG files itself.
 */
final class JpegSize {

    private static final int MARKER = 0xFF;
    private static final int SOI = 0xD8;
    private static final int EOI = 0xD9;
    private static final int SOS = 0xDA;
    private static final int TEM = 0x01;
    private static final int RST0 = 0xD0;
    private static final int RST7 = 0xD7;
    private static final int SOF0 = 0xC0;
    private static final int SOF15 = 0xCF;
    /** DHT, JPG and DAC lie in the SOF range but are no frame headers. */
    private static final int DHT = 0xC4;
    private static final int JPG = 0xC8;
    private static final int DAC = 0xCC;

    private JpegSize() {
    }

    /**
     * Returns the width and height of the JPEG image.
     *
     * @return {@code {width, height}}, or {@code null} if the data has no
     *         readable frame header before the image data
     */
    static long[] of(MemorySegment jpeg) {
        long size = jpeg.byteSize();
        if (size < 2 || unsigned(jpeg, 0) != MARKER || unsigned(jpeg, 1) != SOI) {
            return null;
        }
        long offset = 2;
        while (offset < size) {
            if (unsigned(jpeg, offset) != MARKER) {
                return null;
            }
            // Fill bytes: any number of 0xFF before the marker code.
            while (offset < size && unsigned(jpeg, offset) == MARKER) {
                offset++;
            }
            if (offset >= size) {
                return null;
            }
            int marker = unsigned(jpeg, offset++);
            if (marker == TEM || (marker >= RST0 && marker <= RST7)) {
                continue;
            }
            if (marker == SOS || marker == EOI || offset + 2 > size) {
                return null;
            }
            int length = unsignedShort(jpeg, offset);
            if (length < 2) {
                return null;
            }
            if (marker >= SOF0 && marker <= SOF15 && marker != DHT && marker != JPG && marker != DAC) {
                // Length, sample precision, height, width.
                if (length < 7 || offset + 7 > size) {
                    return null;
                }
                int height = unsignedShort(jpeg, offset + 3);
                int width = unsignedShort(jpeg, offset + 5);
                return width == 0 || height == 0 ? null : new long[] {width, height};
            }
            offset += length;
        }
        return null;
    }

    private static int unsigned(MemorySegment data, long offset) {
        return data.get(JAVA_BYTE, offset) & 0xFF;
    }

    private static int unsignedShort(MemorySegment data, long offset) {
        return unsigned(data, offset) << 8 | unsigned(data, offset + 1);
    }
}

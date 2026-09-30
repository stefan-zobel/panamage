package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class ExifTest {

    /** TIFF data with an IFD0 that holds an orientation tag and one other tag. */
    static byte[] tiff(boolean littleEndian, int orientation) {
        byte[] data = new byte[8 + 2 + 2 * 12 + 4];
        Writer w = new Writer(data, littleEndian);
        data[0] = data[1] = (byte) (littleEndian ? 'I' : 'M');
        w.short16(2, 42);
        w.int32(4, 8);
        w.short16(8, 2);
        // ImageWidth (SHORT) = 640
        w.short16(10, 0x0100);
        w.short16(12, 3);
        w.int32(14, 1);
        w.short16(18, 640);
        // Orientation (SHORT)
        w.short16(22, 0x0112);
        w.short16(24, 3);
        w.int32(26, 1);
        w.short16(30, orientation);
        return data;
    }

    private record Writer(byte[] data, boolean littleEndian) {
        void short16(int offset, int value) {
            data[offset + (littleEndian ? 0 : 1)] = (byte) value;
            data[offset + (littleEndian ? 1 : 0)] = (byte) (value >>> 8);
        }

        void int32(int offset, int value) {
            for (int i = 0; i < 4; i++) {
                data[offset + (littleEndian ? i : 3 - i)] = (byte) (value >>> (8 * i));
            }
        }
    }

    @Test
    void readsTheOrientationInBothByteOrders() {
        assertEquals(6, Exif.orientation(tiff(true, 6)));
        assertEquals(8, Exif.orientation(tiff(false, 8)));
    }

    @Test
    void patchesOnlyTheOrientationValue() {
        for (boolean littleEndian : new boolean[] {true, false}) {
            byte[] original = tiff(littleEndian, 6);
            byte[] patched = Exif.withOrientation(original, 1);

            assertEquals(1, Exif.orientation(patched));
            assertEquals(6, Exif.orientation(original), "the input stays unchanged");
            byte[] expected = tiff(littleEndian, 1);
            assertArrayEquals(expected, patched);
        }
    }

    @Test
    void treatsMissingOrInvalidOrientationAsUpright() {
        byte[] noOrientation = tiff(true, 6);
        noOrientation[22] = 0x13; // change the tag number
        assertEquals(1, Exif.orientation(noOrientation));
        assertSame(noOrientation, Exif.withOrientation(noOrientation, 1));

        assertEquals(1, Exif.orientation(tiff(true, 9)));
        assertEquals(1, Exif.orientation(null));
        assertEquals(1, Exif.orientation(new byte[3]));
        assertEquals(1, Exif.orientation("not a tiff header".getBytes()));
    }

    @Test
    void toleratesTruncatedData() {
        byte[] full = tiff(false, 6);
        for (int length = 0; length < full.length; length++) {
            byte[] truncated = Arrays.copyOf(full, length);
            int orientation = Exif.orientation(truncated);
            // The orientation entry occupies bytes 22 to 33.
            assertEquals(length >= 34 ? 6 : 1, orientation, "length " + length);
        }
    }

    @Test
    void rejectsIfdOffsetsOutsideTheData() {
        byte[] data = tiff(true, 6);
        data[4] = (byte) 0xF0;
        data[7] = 0x7F;
        assertEquals(1, Exif.orientation(data));
    }
}

package panamage.jxl;

/**
 * Reads and changes the orientation tag in the first image file directory
 * (IFD0) of EXIF data. Malformed data is treated as having no orientation.
 */
final class Exif {

    /** Orientation value for an upright image. */
    static final int UPRIGHT = 1;

    private static final int ORIENTATION_TAG = 0x0112;
    private static final int TYPE_SHORT = 3;
    private static final int TIFF_MAGIC = 42;
    private static final int ENTRY_SIZE = 12;

    private Exif() {
    }

    /**
     * Returns the orientation from 1 to 8, or 1 if the tag is missing or the
     * data is malformed.
     */
    static int orientation(byte[] tiff) {
        int offset = orientationValueOffset(tiff);
        if (offset < 0) {
            return UPRIGHT;
        }
        int value = readShort(tiff, offset, isLittleEndian(tiff));
        return value >= 1 && value <= 8 ? value : UPRIGHT;
    }

    /**
     * Returns a copy with the orientation tag set to the given value, or the
     * data itself if it has no orientation tag.
     */
    static byte[] withOrientation(byte[] tiff, int orientation) {
        int offset = orientationValueOffset(tiff);
        if (offset < 0) {
            return tiff;
        }
        byte[] copy = tiff.clone();
        if (isLittleEndian(tiff)) {
            copy[offset] = (byte) orientation;
            copy[offset + 1] = (byte) (orientation >>> 8);
        } else {
            copy[offset] = (byte) (orientation >>> 8);
            copy[offset + 1] = (byte) orientation;
        }
        return copy;
    }

    /** Offset of the orientation value, or -1 if there is none. */
    private static int orientationValueOffset(byte[] tiff) {
        if (tiff == null || tiff.length < 8) {
            return -1;
        }
        boolean littleEndian;
        if (tiff[0] == 'I' && tiff[1] == 'I') {
            littleEndian = true;
        } else if (tiff[0] == 'M' && tiff[1] == 'M') {
            littleEndian = false;
        } else {
            return -1;
        }
        if (readShort(tiff, 2, littleEndian) != TIFF_MAGIC) {
            return -1;
        }
        long ifd = readInt(tiff, 4, littleEndian);
        if (ifd < 8 || ifd + 2 > tiff.length) {
            return -1;
        }
        int entries = readShort(tiff, (int) ifd, littleEndian);
        for (int i = 0; i < entries; i++) {
            long entry = ifd + 2 + (long) i * ENTRY_SIZE;
            if (entry + ENTRY_SIZE > tiff.length) {
                return -1;
            }
            int e = (int) entry;
            if (readShort(tiff, e, littleEndian) == ORIENTATION_TAG) {
                boolean valid = readShort(tiff, e + 2, littleEndian) == TYPE_SHORT
                        && readInt(tiff, e + 4, littleEndian) == 1;
                return valid ? e + 8 : -1;
            }
        }
        return -1;
    }

    private static boolean isLittleEndian(byte[] tiff) {
        return tiff[0] == 'I';
    }

    private static int readShort(byte[] data, int offset, boolean littleEndian) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        return littleEndian ? b0 | b1 << 8 : b0 << 8 | b1;
    }

    private static long readInt(byte[] data, int offset, boolean littleEndian) {
        long value = 0;
        for (int i = 0; i < 4; i++) {
            int b = data[offset + (littleEndian ? 3 - i : i)] & 0xFF;
            value = value << 8 | b;
        }
        return value;
    }
}

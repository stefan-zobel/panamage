package panamage.jxl;

import java.lang.foreign.MemorySegment;

/**
 * A rectangle of an image, used for the area of an animation frame that
 * differs from the previous frame.
 *
 * @param x      the left column
 * @param y      the top row
 * @param width  the width in pixels, at least 1
 * @param height the height in pixels, at least 1
 */
record FrameRegion(int x, int y, int width, int height) {

    /**
     * Returns the smallest rectangle that contains every pixel that differs
     * between two images of the same size and layout, with the rows stored
     * one after the other without padding.
     *
     * @param current       the samples of the current image
     * @param previous      the samples of the previous image
     * @param width         the width in pixels
     * @param height        the height in pixels
     * @param bytesPerPixel the bytes of all samples of one pixel
     * @return the changed rectangle, or {@code null} if the images are equal
     */
    static FrameRegion changed(MemorySegment current, MemorySegment previous, int width, int height,
            int bytesPerPixel) {
        long rowBytes = (long) width * bytesPerPixel;
        int top = -1;
        int bottom = -1;
        int left = width;
        int right = -1;
        for (int row = 0; row < height; row++) {
            long start = row * rowBytes;
            long first = MemorySegment.mismatch(current, start, start + rowBytes, previous, start, start + rowBytes);
            if (first < 0) {
                continue;
            }
            if (top < 0) {
                top = row;
            }
            bottom = row;
            left = Math.min(left, (int) (first / bytesPerPixel));
            right = Math.max(right, lastChangedPixel(current, previous, start, width, bytesPerPixel));
        }
        return top < 0 ? null : new FrameRegion(left, top, right - left + 1, bottom - top + 1);
    }

    /** The last pixel of a row that differs; the row is known to differ. */
    private static int lastChangedPixel(MemorySegment current, MemorySegment previous, long rowStart, int width,
            int bytesPerPixel) {
        for (int pixel = width - 1; pixel > 0; pixel--) {
            long offset = rowStart + (long) pixel * bytesPerPixel;
            if (MemorySegment.mismatch(current, offset, offset + bytesPerPixel, previous, offset,
                    offset + bytesPerPixel) >= 0) {
                return pixel;
            }
        }
        return 0;
    }

    /**
     * Returns the smallest rectangle that contains both rectangles; either
     * may be {@code null}.
     */
    static FrameRegion union(FrameRegion a, FrameRegion b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        int x = Math.min(a.x, b.x);
        int y = Math.min(a.y, b.y);
        int right = Math.max(a.x + a.width, b.x + b.width);
        int bottom = Math.max(a.y + a.height, b.y + b.height);
        return new FrameRegion(x, y, right - x, bottom - y);
    }

    /** Whether the rectangle covers a whole image of the given size. */
    boolean covers(int imageWidth, int imageHeight) {
        return x == 0 && y == 0 && width == imageWidth && height == imageHeight;
    }

    /**
     * Copies the rectangle out of an image into a new buffer, row by row.
     *
     * @param image         the samples of the whole image
     * @param imageWidth    the width of the image in pixels
     * @param bytesPerPixel the bytes of all samples of one pixel
     * @param target        the buffer for the rectangle, at least
     *                      {@code width * height * bytesPerPixel} bytes
     */
    void copy(MemorySegment image, int imageWidth, int bytesPerPixel, MemorySegment target) {
        long rowBytes = (long) width * bytesPerPixel;
        for (int row = 0; row < height; row++) {
            long source = ((long) (y + row) * imageWidth + x) * bytesPerPixel;
            MemorySegment.copy(image, source, target, row * rowBytes, rowBytes);
        }
    }
}

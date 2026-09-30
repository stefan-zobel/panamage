package panamage.jxl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Access to the test images in {@code src/test/resources}, created by
 * {@code scripts/make_test_images.py}.
 */
final class TestImages {

    static final int WIDTH = 64;
    static final int HEIGHT = 48;

    static final int PHOTO_WIDTH = 256;
    static final int PHOTO_HEIGHT = 192;

    /** JPEG variants of the same photo-like image, created with Pillow. */
    static final String[] PHOTO_JPEGS = {
            "photo-420-exif.jpg", "photo-444-progressive.jpg", "photo-gray.jpg"
    };

    /** {@code photo-420-exif.jpg} transcoded with cjxl. */
    static final String PHOTO_CJXL_REFERENCE = "photo-420-exif.jxl";

    private TestImages() {
    }

    /** The lossless JPEG XL file created with cjxl. */
    static byte[] gradientJxl() {
        return resource("gradient.jxl");
    }

    /** The reference pixels as raw interleaved RGBA bytes. */
    static byte[] gradientRgbaPixels() {
        return resource("gradient.rgba");
    }

    /** The reference image with 4 channels (RGBA). */
    static JxlImage gradientRgba() {
        return new JxlImage(WIDTH, HEIGHT, 4, gradientRgbaPixels());
    }

    /**
     * Keeps the given channels of the RGBA reference image.
     *
     * @param channelIndexes indexes into R, G, B, A, in output order
     */
    static JxlImage gradientChannels(int... channelIndexes) {
        byte[] rgba = gradientRgbaPixels();
        int pixelCount = WIDTH * HEIGHT;
        byte[] pixels = new byte[pixelCount * channelIndexes.length];
        for (int p = 0; p < pixelCount; p++) {
            for (int c = 0; c < channelIndexes.length; c++) {
                pixels[p * channelIndexes.length + c] = rgba[p * 4 + channelIndexes[c]];
            }
        }
        return new JxlImage(WIDTH, HEIGHT, channelIndexes.length, pixels);
    }

    static byte[] resource(String name) {
        try (InputStream in = TestImages.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Test resource not found: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

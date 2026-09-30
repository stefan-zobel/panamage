package panamage.jxl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

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

    static final int ANIMATION_WIDTH = 16;
    static final int ANIMATION_HEIGHT = 12;

    /** The frame durations of {@code animation.jxl} in milliseconds. */
    static final double[] ANIMATION_DURATIONS = {100, 200, 300};

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
    static JxlImage.Uint8 gradientRgba() {
        return new JxlImage.Uint8(WIDTH, HEIGHT, 4, gradientRgbaPixels());
    }

    /**
     * Keeps the given channels of the RGBA reference image.
     *
     * @param channelIndexes indexes into R, G, B, A, in output order
     */
    static JxlImage.Uint8 gradientChannels(int... channelIndexes) {
        byte[] rgba = gradientRgbaPixels();
        int pixelCount = WIDTH * HEIGHT;
        byte[] pixels = new byte[pixelCount * channelIndexes.length];
        for (int p = 0; p < pixelCount; p++) {
            for (int c = 0; c < channelIndexes.length; c++) {
                pixels[p * channelIndexes.length + c] = rgba[p * 4 + channelIndexes[c]];
            }
        }
        return new JxlImage.Uint8(WIDTH, HEIGHT, channelIndexes.length, pixels);
    }

    /** The lossless 16-bit RGBA JPEG XL file created with cjxl. */
    static byte[] gradient16Jxl() {
        return resource("gradient16.jxl");
    }

    /** The reference samples of {@code gradient16.jxl}, interleaved RGBA. */
    static short[] gradient16Pixels() {
        byte[] raw = resource("gradient16.rgba16");
        short[] samples = new short[raw.length / 2];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
        return samples;
    }

    /** The lossless float32 RGB JPEG XL file created with cjxl. */
    static byte[] gradientFloatJxl() {
        return resource("gradient-float.jxl");
    }

    /** The reference samples of {@code gradient-float.jxl}, interleaved RGB. */
    static float[] gradientFloatPixels() {
        byte[] raw = resource("gradient-float.rgbf32");
        float[] samples = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        return samples;
    }

    /** The lossless 8-bit RGBA animation created with cjxl from an APNG file. */
    static byte[] animationJxl() {
        return resource("animation.jxl");
    }

    /** The reference pixels of a frame of {@code animation.jxl}, interleaved RGBA. */
    static byte[] animationRgbaPixels(int frame) {
        int size = ANIMATION_WIDTH * ANIMATION_HEIGHT * 4;
        return Arrays.copyOfRange(resource("animation.rgba"), frame * size, (frame + 1) * size);
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

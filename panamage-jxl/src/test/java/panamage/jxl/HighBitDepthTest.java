package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Decoding and encoding with 16-bit and floating point samples.
 */
class HighBitDepthTest {

    /** Encoded files are kept here for cross-checks with the libjxl command line tools. */
    private static final Path OUTPUT_DIR = Path.of("target", "test-output");

    private static final int PIXELS = TestImages.WIDTH * TestImages.HEIGHT;

    @Test
    void readsTheHeaderOfA16BitImage() {
        JxlImageInfo info = JxlDecoder.readInfo(TestImages.gradient16Jxl());

        assertEquals(16, info.bitsPerSample());
        assertEquals(0, info.exponentBitsPerSample());
        assertEquals(JxlSampleType.UINT16, info.sampleType());
        assertEquals(4, info.channels());
    }

    @Test
    void decodesA16BitImageBitExact() {
        JxlImage image = JxlDecoder.decode(TestImages.gradient16Jxl(), 4, JxlSampleType.UINT16);

        JxlImage.Uint16 uint16 = assertInstanceOf(JxlImage.Uint16.class, image);
        assertEquals(TestImages.WIDTH, uint16.width());
        assertEquals(TestImages.HEIGHT, uint16.height());
        assertArrayEquals(TestImages.gradient16Pixels(), uint16.pixels());
    }

    @Test
    void decodesA16BitImageTo8Bits() {
        short[] reference = TestImages.gradient16Pixels();
        byte[] pixels = JxlDecoder.decode(TestImages.gradient16Jxl(), 4).pixels();

        for (int i = 0; i < reference.length; i++) {
            double expected = Short.toUnsignedInt(reference[i]) * 255.0 / 65535.0;
            assertEquals(expected, Byte.toUnsignedInt(pixels[i]), 1.0, "sample " + i);
        }
    }

    @Test
    void decodesAn8BitImageTo16Bits() {
        byte[] reference = TestImages.gradientRgbaPixels();
        JxlImage.Uint16 image = (JxlImage.Uint16) JxlDecoder.decode(TestImages.gradientJxl(), 4,
                JxlSampleType.UINT16);

        for (int i = 0; i < reference.length; i++) {
            assertEquals(Byte.toUnsignedInt(reference[i]) * 257, Short.toUnsignedInt(image.pixels()[i]), 1,
                    "sample " + i);
        }
    }

    @Test
    void readsTheHeaderOfAFloatImage() {
        JxlImageInfo info = JxlDecoder.readInfo(TestImages.gradientFloatJxl());

        assertEquals(32, info.bitsPerSample());
        assertEquals(8, info.exponentBitsPerSample());
        assertEquals(JxlSampleType.FLOAT32, info.sampleType());
        assertEquals(3, info.channels());
    }

    @Test
    void decodesAFloatImageBitExact() {
        JxlImage image = JxlDecoder.decode(TestImages.gradientFloatJxl(), 3, JxlSampleType.FLOAT32);

        JxlImage.Float32 float32 = assertInstanceOf(JxlImage.Float32.class, image);
        assertArrayEquals(TestImages.gradientFloatPixels(), float32.pixels());
    }

    @Test
    void decodesAn8BitImageToFloats() {
        byte[] reference = TestImages.gradientRgbaPixels();
        JxlImage.Float32 image = (JxlImage.Float32) JxlDecoder.decode(TestImages.gradientJxl(), 4,
                JxlSampleType.FLOAT32);

        for (int i = 0; i < reference.length; i++) {
            assertEquals(Byte.toUnsignedInt(reference[i]) / 255.0f, image.pixels()[i], 1e-6f, "sample " + i);
        }
    }

    @Test
    void lossless16BitRoundTripIsBitExactForEveryChannelCount() throws IOException {
        for (int[] channels : new int[][] {{1}, {1, 3}, {0, 1, 2}, {0, 1, 2, 3}}) {
            JxlImage.Uint16 image = gradient16Channels(channels);
            byte[] encoded = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless());
            if (channels.length == 4) {
                save("gradient16-lossless.jxl", encoded);
            }

            JxlImageInfo info = JxlDecoder.readInfo(encoded);
            assertEquals(16, info.bitsPerSample());
            assertEquals(0, info.exponentBitsPerSample());
            assertEquals(channels.length, info.channels());
            JxlImage decoded = JxlDecoder.decode(encoded, channels.length, info.sampleType());
            assertArrayEquals(image.pixels(), ((JxlImage.Uint16) decoded).pixels(), channels.length + " channels");
        }
    }

    @Test
    void losslessFloatRoundTripIsBitExact() throws IOException {
        float[] rgb = TestImages.gradientFloatPixels();
        float[] rgba = new float[PIXELS * 4];
        for (int p = 0; p < PIXELS; p++) {
            System.arraycopy(rgb, p * 3, rgba, p * 4, 3);
            rgba[p * 4 + 3] = (p % 17) / 16.0f;
        }
        for (JxlImage.Float32 image : new JxlImage.Float32[] {
                new JxlImage.Float32(TestImages.WIDTH, TestImages.HEIGHT, 3, rgb),
                new JxlImage.Float32(TestImages.WIDTH, TestImages.HEIGHT, 4, rgba)}) {
            byte[] encoded = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless());
            if (image.channels() == 3) {
                save("gradient-float-lossless.jxl", encoded);
            }

            JxlImageInfo info = JxlDecoder.readInfo(encoded);
            assertEquals(32, info.bitsPerSample());
            assertEquals(8, info.exponentBitsPerSample());
            JxlImage decoded = JxlDecoder.decode(encoded, image.channels(), info.sampleType());
            assertArrayEquals(image.pixels(), ((JxlImage.Float32) decoded).pixels(), image.channels() + " channels");
        }
    }

    @Test
    void lossyFloatImageKeepsItsBitDepthAndRange() {
        JxlImage.Float32 image = new JxlImage.Float32(TestImages.WIDTH, TestImages.HEIGHT, 3,
                TestImages.gradientFloatPixels());
        byte[] encoded = JxlEncoder.encode(image, JxlEncodeOptions.ofDistance(0.5f));

        JxlImageInfo info = JxlDecoder.readInfo(encoded);
        assertEquals(JxlSampleType.FLOAT32, info.sampleType());
        JxlImage.Float32 decoded = (JxlImage.Float32) JxlDecoder.decode(encoded, 3, JxlSampleType.FLOAT32);
        float max = Float.NEGATIVE_INFINITY;
        for (float sample : decoded.pixels()) {
            max = Math.max(max, sample);
        }
        assertTrue(max > 1.0f, "values above 1.0 survive lossy encoding, max " + max);
    }

    @Test
    void lossy16BitImageIsCloseToTheOriginal() {
        JxlImage.Uint16 image = gradient16Channels(0, 1, 2);
        byte[] encoded = JxlEncoder.encode(image, JxlEncodeOptions.ofDistance(0.5f));

        JxlImageInfo info = JxlDecoder.readInfo(encoded);
        assertEquals(16, info.bitsPerSample());
        JxlImage.Uint16 decoded = (JxlImage.Uint16) JxlDecoder.decode(encoded, 3, JxlSampleType.UINT16);
        assertTrue(decoded.isSrgb());
        double totalError = 0;
        for (int i = 0; i < image.pixels().length; i++) {
            totalError += Math.abs(Short.toUnsignedInt(image.pixels()[i]) - Short.toUnsignedInt(decoded.pixels()[i]));
        }
        double meanError = totalError / image.pixels().length;
        assertTrue(meanError < 0.01 * 65535, "mean error " + meanError);
    }

    @Test
    void decodedLosslessImagesKeepTheSrgbColorSpace() {
        assertTrue(JxlDecoder.decode(TestImages.gradient16Jxl(), 4, JxlSampleType.UINT16).isSrgb());
        byte[] encoded = JxlEncoder.encode(gradient16Channels(0, 1, 2), JxlEncodeOptions.ofLossless());
        assertTrue(JxlDecoder.readInfo(encoded).isSrgb());
    }

    /** Keeps the given channels of the 16-bit RGBA reference image. */
    private static JxlImage.Uint16 gradient16Channels(int... channelIndexes) {
        short[] rgba = TestImages.gradient16Pixels();
        short[] pixels = new short[PIXELS * channelIndexes.length];
        for (int p = 0; p < PIXELS; p++) {
            for (int c = 0; c < channelIndexes.length; c++) {
                pixels[p * channelIndexes.length + c] = rgba[p * 4 + channelIndexes[c]];
            }
        }
        return new JxlImage.Uint16(TestImages.WIDTH, TestImages.HEIGHT, channelIndexes.length, pixels);
    }

    private static void save(String name, byte[] data) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Files.write(OUTPUT_DIR.resolve(name), data);
    }
}

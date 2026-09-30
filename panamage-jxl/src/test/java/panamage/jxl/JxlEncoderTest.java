package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class JxlEncoderTest {

    /** Encoded files are kept here for cross-checks with the libjxl command line tools. */
    private static final Path OUTPUT_DIR = Path.of("target", "test-output");

    @Test
    void losslessRgbaRoundTripIsBitExact() throws IOException {
        byte[] encoded = JxlEncoder.encode(TestImages.gradientRgba(), JxlEncodeOptions.ofLossless());
        save("gradient-lossless.jxl", encoded);

        JxlImage.Uint8 decoded = JxlDecoder.decode(encoded);

        assertEquals(TestImages.WIDTH, decoded.width());
        assertEquals(TestImages.HEIGHT, decoded.height());
        assertArrayEquals(TestImages.gradientRgbaPixels(), decoded.pixels());
    }

    @Test
    void losslessRgbRoundTripIsBitExactWithOpaqueAlpha() {
        JxlImage.Uint8 rgb = TestImages.gradientChannels(0, 1, 2);

        JxlImage.Uint8 decoded = JxlDecoder.decode(JxlEncoder.encode(rgb, JxlEncodeOptions.ofLossless()));

        byte[] rgba = decoded.pixels();
        for (int p = 0; p < TestImages.WIDTH * TestImages.HEIGHT; p++) {
            for (int c = 0; c < 3; c++) {
                assertEquals(rgb.pixels()[p * 3 + c], rgba[p * 4 + c], "pixel " + p + ", channel " + c);
            }
            assertEquals((byte) 0xFF, rgba[p * 4 + 3], "alpha of pixel " + p);
        }
    }

    @Test
    void losslessGrayRoundTripIsBitExact() {
        JxlImage.Uint8 gray = TestImages.gradientChannels(1);

        JxlImage.Uint8 decoded = JxlDecoder.decode(JxlEncoder.encode(gray, JxlEncodeOptions.ofLossless()));

        byte[] rgba = decoded.pixels();
        for (int p = 0; p < TestImages.WIDTH * TestImages.HEIGHT; p++) {
            byte expected = gray.pixels()[p];
            assertEquals(expected, rgba[p * 4], "R of pixel " + p);
            assertEquals(expected, rgba[p * 4 + 1], "G of pixel " + p);
            assertEquals(expected, rgba[p * 4 + 2], "B of pixel " + p);
            assertEquals((byte) 0xFF, rgba[p * 4 + 3], "alpha of pixel " + p);
        }
    }

    @Test
    void losslessGrayAlphaRoundTripIsBitExact() {
        JxlImage.Uint8 grayAlpha = TestImages.gradientChannels(1, 3);

        JxlImage.Uint8 decoded = JxlDecoder.decode(JxlEncoder.encode(grayAlpha, JxlEncodeOptions.ofLossless()));

        byte[] rgba = decoded.pixels();
        for (int p = 0; p < TestImages.WIDTH * TestImages.HEIGHT; p++) {
            byte expectedGray = grayAlpha.pixels()[p * 2];
            assertEquals(expectedGray, rgba[p * 4], "R of pixel " + p);
            assertEquals(expectedGray, rgba[p * 4 + 1], "G of pixel " + p);
            assertEquals(expectedGray, rgba[p * 4 + 2], "B of pixel " + p);
            assertEquals(grayAlpha.pixels()[p * 2 + 1], rgba[p * 4 + 3], "alpha of pixel " + p);
        }
    }

    @Test
    void lossyEncodingStaysCloseToTheOriginal() throws IOException {
        byte[] encoded = JxlEncoder.encode(TestImages.gradientRgba(), JxlEncodeOptions.ofDistance(1.0f));
        save("gradient-d1.jxl", encoded);

        JxlImage.Uint8 decoded = JxlDecoder.decode(encoded);

        assertEquals(TestImages.WIDTH, decoded.width());
        assertEquals(TestImages.HEIGHT, decoded.height());
        double psnr = rgbPsnr(TestImages.gradientRgbaPixels(), decoded.pixels());
        // libjxl 0.12.0 reaches about 40.7 dB on this image at distance 1.0.
        assertTrue(psnr > 35.0, "PSNR too low: " + psnr);
    }

    @Test
    void defaultOptionsProduceADecodableImage() {
        JxlImage.Uint8 decoded = JxlDecoder.decode(JxlEncoder.encode(TestImages.gradientChannels(0, 1, 2)));
        assertEquals(TestImages.WIDTH, decoded.width());
        assertEquals(TestImages.HEIGHT, decoded.height());
    }

    @Test
    void quality90ProducesADecodableImage() {
        JxlEncodeOptions options = JxlEncodeOptions.ofQuality(90);
        assertTrue(!options.lossless() && options.distance() > 0.0f);

        JxlImage.Uint8 decoded = JxlDecoder.decode(JxlEncoder.encode(TestImages.gradientRgba(), options));

        assertEquals(TestImages.WIDTH, decoded.width());
        assertEquals(TestImages.HEIGHT, decoded.height());
    }

    @Test
    void quality100IsLossless() {
        JxlEncodeOptions options = JxlEncodeOptions.ofQuality(100);
        assertTrue(options.lossless());

        JxlImage.Uint8 decoded = JxlDecoder.decode(JxlEncoder.encode(TestImages.gradientRgba(), options));

        assertArrayEquals(TestImages.gradientRgbaPixels(), decoded.pixels());
    }

    @Test
    void everyEffortLevelProducesALosslessImage() {
        for (int effort = JxlEncodeOptions.MIN_EFFORT; effort <= JxlEncodeOptions.MAX_EFFORT; effort++) {
            JxlEncodeOptions options = JxlEncodeOptions.ofLossless().withEffort(effort);
            JxlImage.Uint8 decoded = JxlDecoder.decode(JxlEncoder.encode(TestImages.gradientRgba(), options));
            assertArrayEquals(TestImages.gradientRgbaPixels(), decoded.pixels(), "effort " + effort);
        }
    }

    @Test
    void losslessOutputIsSmallerThanTheRawPixels() {
        byte[] encoded = JxlEncoder.encode(TestImages.gradientRgba(), JxlEncodeOptions.ofLossless());
        assertTrue(encoded.length < TestImages.gradientRgbaPixels().length,
                "encoded size " + encoded.length);
    }

    @Test
    void decodesLikeTheCjxlReferenceFile() {
        byte[] ours = JxlEncoder.encode(TestImages.gradientRgba(), JxlEncodeOptions.ofLossless());

        assertArrayEquals(JxlDecoder.decode(TestImages.gradientJxl()).pixels(),
                JxlDecoder.decode(ours).pixels());
    }

    @Test
    void rejectsInvalidOptions() {
        assertThrows(IllegalArgumentException.class, () -> JxlEncodeOptions.ofLossless().withEffort(0));
        assertThrows(IllegalArgumentException.class, () -> JxlEncodeOptions.ofLossless().withEffort(11));
        assertThrows(IllegalArgumentException.class, () -> JxlEncodeOptions.ofDistance(-1.0f));
        assertThrows(IllegalArgumentException.class, () -> JxlEncodeOptions.ofDistance(0.0f));
        assertThrows(IllegalArgumentException.class, () -> JxlEncodeOptions.ofDistance(26.0f));
        assertThrows(IllegalArgumentException.class, () -> JxlEncodeOptions.ofDistance(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> JxlEncodeOptions.ofQuality(101));
        assertThrows(IllegalArgumentException.class, () -> JxlEncodeOptions.ofQuality(-1));
        assertThrows(IllegalArgumentException.class, () -> new JxlEncodeOptions(true, 1.0f, 7));
    }

    @Test
    void rejectsMoreThanFourChannels() {
        JxlImage.Uint8 fiveChannels = new JxlImage.Uint8(2, 2, 5, new byte[2 * 2 * 5]);
        assertThrows(IllegalArgumentException.class, () -> JxlEncoder.encode(fiveChannels));
    }

    /** Peak signal-to-noise ratio over the R, G and B samples of two RGBA buffers. */
    private static double rgbPsnr(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        double squaredError = 0;
        long samples = 0;
        for (int i = 0; i < expected.length; i++) {
            if (i % 4 == 3) {
                continue;
            }
            int diff = Byte.toUnsignedInt(expected[i]) - Byte.toUnsignedInt(actual[i]);
            squaredError += diff * diff;
            samples++;
        }
        double mse = squaredError / samples;
        return mse == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 / mse);
    }

    private static void save(String name, byte[] data) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Files.write(OUTPUT_DIR.resolve(name), data);
    }
}

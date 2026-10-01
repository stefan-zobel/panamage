package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

/**
 * Encodes and decodes images that span many groups at high effort, so that
 * libjxl runs its most demanding code on the threads of its thread pool. On
 * musl-based systems, whose threads get a small stack by default, this checks
 * that the stack of these threads is large enough.
 */
class LargeImageTest {

    @Test
    void lossyRoundTripAtHighEffort() {
        JxlImage.Uint8 image = noisyGradient(1024, 1024, 3);

        byte[] encoded = JxlEncoder.encode(image, JxlEncodeOptions.ofDistance(1.0f).withEffort(9));
        JxlImage.Uint8 decoded = JxlDecoder.decode(encoded, 3);

        assertEquals(image.width(), decoded.width());
        assertEquals(image.height(), decoded.height());
        double meanError = meanAbsoluteError(image.pixels(), decoded.pixels());
        assertTrue(meanError < 8, "mean absolute error " + meanError);
    }

    @Test
    void losslessRoundTripAtHighEffort() {
        JxlImage.Uint8 image = noisyGradient(512, 512, 4);

        byte[] encoded = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless().withEffort(9));

        assertArrayEquals(image.pixels(), JxlDecoder.decode(encoded, 4).pixels());
    }

    /** A smooth gradient with deterministic noise, which neither compresses trivially nor is pure noise. */
    private static JxlImage.Uint8 noisyGradient(int width, int height, int channels) {
        SplittableRandom random = new SplittableRandom(42);
        byte[] pixels = new byte[width * height * channels];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int base = (y * width + x) * channels;
                for (int c = 0; c < channels; c++) {
                    int value = c == 3 ? 255 - (x + y) * 255 / (width + height)
                            : ((c + 1) * x * 255 / width + y * 255 / height) / 2;
                    pixels[base + c] = (byte) Math.clamp(value + random.nextInt(-12, 13), 0, 255);
                }
            }
        }
        return new JxlImage.Uint8(width, height, channels, pixels);
    }

    private static double meanAbsoluteError(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        long sum = 0;
        for (int i = 0; i < expected.length; i++) {
            sum += Math.abs((expected[i] & 0xFF) - (actual[i] & 0xFF));
        }
        return (double) sum / expected.length;
    }
}

package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Every thread setting gives the same pixels and the same files: libjxl
 * decodes and encodes independently of the number of threads.
 */
class ThreadsTest {

    private static final List<JxlThreads> SETTINGS = List.of(JxlThreads.none(), JxlThreads.auto(),
            JxlThreads.fixed(1), JxlThreads.fixed(4));

    /** Larger than one group of 256 x 256 pixels, so the automatic setting starts threads. */
    private static final int WIDTH = 600;
    private static final int HEIGHT = 400;

    @Test
    void encodingAndDecodingAreIndependentOfTheThreads() {
        JxlImage.Uint8 image = largeImage();
        byte[] expected = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless().withThreads(JxlThreads.none()));

        for (JxlThreads threads : SETTINGS) {
            byte[] encoded = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless().withThreads(threads));
            assertArrayEquals(expected, encoded, threads.toString());

            JxlImage decoded = JxlDecoder.decode(encoded, 4, JxlSampleType.UINT8, options(threads));
            assertArrayEquals(image.pixels(), ((JxlImage.Uint8) decoded).pixels(), threads.toString());
            JxlChannels channels = JxlDecoder.decodeChannels(encoded, JxlSampleType.UINT8, options(threads));
            assertEquals(4, channels.channels(), threads.toString());
        }
    }

    @Test
    void framesAreIndependentOfTheThreads() {
        byte[] data = TestImages.animationJxl();
        List<JxlFrame> expected = JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8);

        for (JxlThreads threads : SETTINGS) {
            List<JxlFrame> frames = JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8, options(threads));
            assertSamePixels(expected, frames, threads);
            List<JxlFrame> decoded = new ArrayList<>();
            try (JxlFrameDecoder decoder = JxlFrameDecoder.open(data, 4, JxlSampleType.UINT8, options(threads))) {
                for (JxlFrame frame = decoder.next(); frame != null; frame = decoder.next()) {
                    decoded.add(frame);
                }
            }
            assertSamePixels(expected, decoded, threads);
        }
    }

    @Test
    void transcodingIsIndependentOfTheThreads() {
        byte[] jpeg = TestImages.resource("photo-420-exif.jpg");
        byte[] expected = JxlTranscoder.fromJpeg(jpeg, JxlEncodeOptions.ofLossless().withThreads(JxlThreads.none()));

        for (JxlThreads threads : SETTINGS) {
            byte[] jxl = JxlTranscoder.fromJpeg(jpeg, JxlEncodeOptions.ofLossless().withThreads(threads));
            assertArrayEquals(expected, jxl, threads.toString());
            assertArrayEquals(jpeg, JxlTranscoder.toJpeg(jxl, options(threads)), threads.toString());
        }
    }

    private static JxlDecodeOptions options(JxlThreads threads) {
        return JxlDecodeOptions.defaults().withThreads(threads);
    }

    /** An RGBA image with smooth and noisy parts. */
    private static JxlImage.Uint8 largeImage() {
        byte[] pixels = new byte[WIDTH * HEIGHT * 4];
        int seed = 12345;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                seed = seed * 1103515245 + 12345;
                int i = (y * WIDTH + x) * 4;
                pixels[i] = (byte) (x * 255 / WIDTH);
                pixels[i + 1] = (byte) (y * 255 / HEIGHT);
                pixels[i + 2] = (byte) (x < WIDTH / 2 ? (x + y) : seed >>> 24);
                pixels[i + 3] = (byte) 255;
            }
        }
        return new JxlImage.Uint8(WIDTH, HEIGHT, 4, pixels);
    }

    private static void assertSamePixels(List<JxlFrame> expected, List<JxlFrame> actual, JxlThreads threads) {
        assertEquals(expected.size(), actual.size(), threads.toString());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(((JxlImage.Uint8) expected.get(i).image()).pixels(),
                    ((JxlImage.Uint8) actual.get(i).image()).pixels(), threads + ", frame " + i);
        }
    }
}

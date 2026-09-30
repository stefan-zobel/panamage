package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

class JxlTranscoderTest {

    /** Transcoded files are kept here for cross-checks with the libjxl command line tools. */
    private static final Path OUTPUT_DIR = Path.of("target", "test-output");

    @ParameterizedTest
    @FieldSource("panamage.jxl.TestImages#PHOTO_JPEGS")
    void roundTripRestoresTheOriginalJpegBitExact(String name) throws IOException {
        byte[] jpeg = TestImages.resource(name);

        byte[] jxl = JxlTranscoder.fromJpeg(jpeg);
        save(name.replace(".jpg", "-ours.jxl"), jxl);

        assertArrayEquals(jpeg, JxlTranscoder.toJpeg(jxl));
    }

    @ParameterizedTest
    @FieldSource("panamage.jxl.TestImages#PHOTO_JPEGS")
    void transcodedFileIsSmallerThanTheJpeg(String name) {
        byte[] jpeg = TestImages.resource(name);

        byte[] jxl = JxlTranscoder.fromJpeg(jpeg);

        assertTrue(jxl.length < jpeg.length, name + ": " + jxl.length + " >= " + jpeg.length);
    }

    @Test
    void restoresTheJpegFromACjxlTranscodedFile() {
        byte[] reference = TestImages.resource(TestImages.PHOTO_CJXL_REFERENCE);

        assertArrayEquals(TestImages.resource("photo-420-exif.jpg"), JxlTranscoder.toJpeg(reference));
    }

    @ParameterizedTest
    @FieldSource("panamage.jxl.TestImages#PHOTO_JPEGS")
    void transcodedFileDecodesToPixels(String name) {
        JxlImage.Uint8 image = JxlDecoder.decode(JxlTranscoder.fromJpeg(TestImages.resource(name)));

        assertEquals(TestImages.PHOTO_WIDTH, image.width());
        assertEquals(TestImages.PHOTO_HEIGHT, image.height());
        assertEquals(4, image.channels());
    }

    @Test
    void roundTripIsBitExactForLowAndHighEffort() {
        byte[] jpeg = TestImages.resource("photo-420-exif.jpg");
        for (int effort : new int[] {1, 9}) {
            assertArrayEquals(jpeg, JxlTranscoder.toJpeg(JxlTranscoder.fromJpeg(jpeg, effort)), "effort " + effort);
        }
    }

    @ParameterizedTest
    @FieldSource("panamage.jxl.TestImages#PHOTO_JPEGS")
    void roundTripIsBitExactWithSmallOutputBuffers(String name) {
        // Odd sizes far below the file sizes force many partial buffers in both directions.
        byte[] jpeg = TestImages.resource(name);

        byte[] jxl = JxlTranscoder.fromJpeg(jpeg, JxlEncodeOptions.DEFAULT_EFFORT, 997);

        assertArrayEquals(JxlTranscoder.fromJpeg(jpeg), jxl);
        assertArrayEquals(jpeg, JxlTranscoder.toJpeg(jxl, 331, JxlLimits.defaults()));
        assertArrayEquals(jpeg, JxlTranscoder.toJpeg(jxl, 1, JxlLimits.defaults()));
    }

    @Test
    void rejectsInvalidEffort() {
        byte[] jpeg = TestImages.resource("photo-420-exif.jpg");
        assertThrows(IllegalArgumentException.class, () -> JxlTranscoder.fromJpeg(jpeg, 0));
        assertThrows(IllegalArgumentException.class, () -> JxlTranscoder.fromJpeg(jpeg, 11));
    }

    @Test
    void rejectsDataThatIsNotAJpeg() {
        byte[] garbage = new byte[256];
        Arrays.fill(garbage, (byte) 0x5A);
        assertThrows(JxlException.class, () -> JxlTranscoder.fromJpeg(garbage));
    }

    @Test
    void rejectsTruncatedJpeg() {
        byte[] jpeg = TestImages.resource("photo-420-exif.jpg");
        byte[] truncated = Arrays.copyOf(jpeg, jpeg.length / 2);
        assertThrows(JxlException.class, () -> JxlTranscoder.fromJpeg(truncated));
    }

    @Test
    void refusesToRestoreAJpegFromAPixelEncodedFile() {
        JxlException e = assertThrows(JxlException.class, () -> JxlTranscoder.toJpeg(TestImages.gradientJxl()));
        assertTrue(e.getMessage().contains("no JPEG reconstruction data"), e.getMessage());
    }

    @Test
    void rejectsDataThatIsNotJxl() {
        byte[] garbage = new byte[256];
        Arrays.fill(garbage, (byte) 0x5A);
        assertThrows(JxlException.class, () -> JxlTranscoder.toJpeg(garbage));
    }

    private static void save(String name, byte[] data) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Files.write(OUTPUT_DIR.resolve(name), data);
    }
}

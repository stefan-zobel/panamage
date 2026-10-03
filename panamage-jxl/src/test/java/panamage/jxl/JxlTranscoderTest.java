package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
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
            byte[] jxl = JxlTranscoder.fromJpeg(jpeg, JxlEncodeOptions.ofLossless().withEffort(effort));
            assertArrayEquals(jpeg, JxlTranscoder.toJpeg(jxl), "effort " + effort);
        }
    }

    @ParameterizedTest
    @FieldSource("panamage.jxl.TestImages#PHOTO_JPEGS")
    void roundTripIsBitExactWithSmallOutputBuffers(String name) {
        // Odd sizes far below the file sizes force many partial buffers in both directions.
        byte[] jpeg = TestImages.resource(name);

        byte[] jxl = JxlTranscoder.fromJpeg(jpeg, JxlEncodeOptions.ofLossless(), 997);

        assertArrayEquals(JxlTranscoder.fromJpeg(jpeg), jxl);
        assertArrayEquals(jpeg, JxlTranscoder.toJpeg(jxl, 331, JxlDecodeOptions.defaults()));
        assertArrayEquals(jpeg, JxlTranscoder.toJpeg(jxl, 1, JxlDecodeOptions.defaults()));
    }

    @Test
    void streamAndChannelOutputEqualTheByteArrays() throws IOException {
        byte[] jpeg = TestImages.resource("photo-420-exif.jpg");
        byte[] jxl = JxlTranscoder.fromJpeg(jpeg);

        ByteArrayOutputStream jxlStream = new ByteArrayOutputStream();
        assertEquals(jxl.length, JxlTranscoder.fromJpeg(jpeg, JxlEncodeOptions.ofLossless(), jxlStream));
        assertArrayEquals(jxl, jxlStream.toByteArray());

        ByteArrayOutputStream jxlChannel = new ByteArrayOutputStream();
        try (WritableByteChannel channel = Channels.newChannel(jxlChannel)) {
            assertEquals(jxl.length, JxlTranscoder.fromJpeg(jpeg, JxlEncodeOptions.ofLossless(), channel));
        }
        assertArrayEquals(jxl, jxlChannel.toByteArray());

        ByteArrayOutputStream jpegStream = new ByteArrayOutputStream();
        assertEquals(jpeg.length, JxlTranscoder.toJpeg(jxl, JxlDecodeOptions.defaults(), jpegStream));
        assertArrayEquals(jpeg, jpegStream.toByteArray());

        ByteArrayOutputStream jpegChannel = new ByteArrayOutputStream();
        try (WritableByteChannel channel = Channels.newChannel(jpegChannel)) {
            assertEquals(jpeg.length, JxlTranscoder.toJpeg(jxl, JxlDecodeOptions.defaults(), channel));
        }
        assertArrayEquals(jpeg, jpegChannel.toByteArray());
    }

    @ParameterizedTest
    @FieldSource("panamage.jxl.TestImages#PHOTO_JPEGS")
    void sinksGetTheSameOutputWithSmallBuffers(String name) throws IOException {
        byte[] jpeg = TestImages.resource(name);
        byte[] jxl = JxlTranscoder.fromJpeg(jpeg);

        ByteArrayOutputStream jxlOut = new ByteArrayOutputStream();
        JxlTranscoder.fromJpeg(jpeg, JxlEncodeOptions.ofLossless(), OutputSink.of(jxlOut), 997);
        assertArrayEquals(jxl, jxlOut.toByteArray());

        for (int chunkSize : new int[] {331, 1}) {
            ByteArrayOutputStream jpegOut = new ByteArrayOutputStream();
            long written = JxlTranscoder.toJpeg(jxl, chunkSize, JxlDecodeOptions.defaults(), OutputSink.of(jpegOut));
            assertArrayEquals(jpeg, jpegOut.toByteArray(), "chunk size " + chunkSize);
            assertEquals(jpeg.length, written, "chunk size " + chunkSize);
        }
    }

    @Test
    void jpegLimitAppliesWhenWritingToAStream() {
        byte[] jpeg = TestImages.resource("photo-420-exif.jpg");
        byte[] jxl = JxlTranscoder.fromJpeg(jpeg);
        JxlDecodeOptions tooSmall = TestImages.maxJpegBytes(jpeg.length - 1L);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(JxlLimitException.class, () -> JxlTranscoder.toJpeg(jxl, tooSmall, out));
        assertTrue(out.size() < jpeg.length, "written " + out.size());
    }

    @Test
    void rejectsANullSink() {
        byte[] jpeg = TestImages.resource("photo-420-exif.jpg");
        byte[] jxl = TestImages.gradientJxl();
        assertThrows(NullPointerException.class,
                () -> JxlTranscoder.fromJpeg(jpeg, JxlEncodeOptions.ofLossless(), (OutputStream) null));
        assertThrows(NullPointerException.class,
                () -> JxlTranscoder.fromJpeg(jpeg, JxlEncodeOptions.ofLossless(), (WritableByteChannel) null));
        assertThrows(NullPointerException.class,
                () -> JxlTranscoder.toJpeg(jxl, JxlDecodeOptions.defaults(), (OutputStream) null));
        assertThrows(NullPointerException.class,
                () -> JxlTranscoder.toJpeg(jxl, JxlDecodeOptions.defaults(), (WritableByteChannel) null));
    }

    @Test
    void rejectsMissingOptions() {
        byte[] jpeg = TestImages.resource("photo-420-exif.jpg");
        byte[] jxl = JxlTranscoder.fromJpeg(jpeg);
        assertThrows(NullPointerException.class, () -> JxlTranscoder.fromJpeg(jpeg, (JxlEncodeOptions) null));
        assertThrows(NullPointerException.class, () -> JxlTranscoder.toJpeg(jxl, (JxlDecodeOptions) null));
    }

    @Test
    void fromJpegUsesOnlyTheEffort() {
        byte[] jpeg = TestImages.resource("photo-420-exif.jpg");

        assertArrayEquals(JxlTranscoder.fromJpeg(jpeg), JxlTranscoder.fromJpeg(jpeg, JxlEncodeOptions.ofDistance(3)));
        assertArrayEquals(JxlTranscoder.fromJpeg(jpeg, JxlEncodeOptions.ofLossless().withEffort(3)),
                JxlTranscoder.fromJpeg(jpeg, JxlEncodeOptions.ofDistance(3).withEffort(3)));
    }

    @Test
    void toJpegIgnoresTheColorSpaceSetting() {
        byte[] jpeg = TestImages.resource("photo-420-exif.jpg");
        byte[] jxl = JxlTranscoder.fromJpeg(jpeg);

        assertArrayEquals(jpeg, JxlTranscoder.toJpeg(jxl, JxlDecodeOptions.defaults().withSrgb(true)));
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

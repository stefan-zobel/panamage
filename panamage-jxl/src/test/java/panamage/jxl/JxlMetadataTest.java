package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class JxlMetadataTest {

    private static final Path OUTPUT_DIR = Path.of("target", "test-output");

    private static final byte[] XMP = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">\
            <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/" dc:format="image/jxl"/>\
            </rdf:RDF></x:xmpmeta>""".getBytes(StandardCharsets.UTF_8);

    @Test
    void readsExifFromATranscodedJpeg() {
        JxlMetadata metadata = JxlDecoder.readMetadata(TestImages.resource(TestImages.PHOTO_CJXL_REFERENCE));

        assertNotNull(metadata.exif());
        assertNull(metadata.xmp());
        String text = new String(metadata.exif(), StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("II*\0") || text.startsWith("MM\0*"), "TIFF header");
        assertTrue(text.contains("Panamage"), "Make");
        assertTrue(text.contains("Synthetic Test Image"), "Model");
    }

    @Test
    void bareCodestreamsHaveNoMetadata() {
        assertSame(JxlMetadata.NONE, JxlDecoder.readMetadata(TestImages.gradientJxl()));
    }

    @Test
    void encoderStoresExifAndXmp() throws IOException {
        byte[] exif = ExifTest.tiff(true, 1);
        JxlMetadata metadata = new JxlMetadata(exif, XMP);

        byte[] encoded = JxlEncoder.encode(TestImages.gradientRgba(), JxlEncodeOptions.ofLossless(), metadata);
        save("gradient-metadata.jxl", encoded);

        JxlMetadata read = JxlDecoder.readMetadata(encoded);
        assertArrayEquals(exif, read.exif());
        assertArrayEquals(XMP, read.xmp());
        assertArrayEquals(TestImages.gradientRgbaPixels(), JxlDecoder.decode(encoded).pixels());
    }

    @Test
    void readsLargeBoxesWithSmallBuffers() {
        byte[] xmp = ("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><!-- " + "0123456789abcdef".repeat(16_000)
                + " --></x:xmpmeta>").getBytes(StandardCharsets.UTF_8);
        byte[] encoded = JxlEncoder.encode(TestImages.gradientRgba(), JxlEncodeOptions.ofLossless(),
                new JxlMetadata(null, xmp));

        assertTrue(xmp.length > 200_000);
        assertArrayEquals(xmp, JxlDecoder.readMetadata(encoded).xmp());
        assertArrayEquals(xmp, JxlDecoder.readMetadata(encoded, 1000).xmp());
        assertArrayEquals(xmp, JxlDecoder.readMetadata(encoded, 1).xmp());
    }

    @Test
    void exifOrientationRotatesTheImageAndIsReadBackAsUpright() throws IOException {
        // A 4x2 image; every pixel encodes its coordinates.
        int width = 4;
        int height = 2;
        byte[] pixels = new byte[width * height * 3];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 3;
                pixels[i] = (byte) (x * 60);
                pixels[i + 1] = (byte) (y * 200);
            }
        }
        JxlMetadata metadata = new JxlMetadata(ExifTest.tiff(false, 6), null);

        byte[] encoded = JxlEncoder.encode(new JxlImage.Uint8(width, height, 3, pixels), JxlEncodeOptions.ofLossless(),
                metadata);
        save("orientation-6.jxl", encoded);
        JxlImage.Uint8 decoded = JxlDecoder.decode(encoded, 3);

        // Orientation 6 rotates 90 degrees clockwise: the result is 2x4, and the
        // pixel at (x', y') comes from (x = y', y = height - 1 - x').
        assertEquals(height, decoded.width());
        assertEquals(width, decoded.height());
        for (int y2 = 0; y2 < decoded.height(); y2++) {
            for (int x2 = 0; x2 < decoded.width(); x2++) {
                int source = ((height - 1 - x2) * width + y2) * 3;
                int target = (y2 * decoded.width() + x2) * 3;
                assertArrayEquals(Arrays.copyOfRange(pixels, source, source + 3),
                        Arrays.copyOfRange(decoded.pixels(), target, target + 3), "pixel " + x2 + "," + y2);
            }
        }
        assertEquals(1, JxlDecoder.readMetadata(encoded).orientation());
    }

    @Test
    void transcodingKeepsTheJpegExif() {
        byte[] jpeg = TestImages.resource("photo-420-exif.jpg");

        JxlMetadata metadata = JxlDecoder.readMetadata(JxlTranscoder.fromJpeg(jpeg));

        assertArrayEquals(exifOfJpeg(jpeg), metadata.exif());
    }

    @Test
    void transcodingKeepsJpegXmp() {
        byte[] jpeg = TestImages.resource("photo-orient6-xmp.jpg");

        JxlMetadata metadata = JxlDecoder.readMetadata(JxlTranscoder.fromJpeg(jpeg));

        assertArrayEquals(TestImages.resource("photo-orient6-xmp.xmp"), metadata.xmp());
        assertEquals(1, metadata.orientation(), "normalized, because decoded pixels are upright");
        assertEquals(TestImages.PHOTO_HEIGHT, JxlDecoder.readInfo(JxlTranscoder.fromJpeg(jpeg)).width());
    }

    /** The TIFF data of the first APP1 Exif segment of a JPEG file. */
    static byte[] exifOfJpeg(byte[] jpeg) {
        int pos = 2;
        while (pos + 4 <= jpeg.length && (jpeg[pos] & 0xFF) == 0xFF) {
            int marker = jpeg[pos + 1] & 0xFF;
            int length = (jpeg[pos + 2] & 0xFF) << 8 | (jpeg[pos + 3] & 0xFF);
            byte[] header = Arrays.copyOfRange(jpeg, pos + 4, Math.min(pos + 10, jpeg.length));
            if (marker == 0xE1 && Arrays.equals(header, "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1))) {
                return Arrays.copyOfRange(jpeg, pos + 10, pos + 2 + length);
            }
            pos += 2 + length;
        }
        throw new AssertionError("No Exif segment");
    }

    private static void save(String name, byte[] data) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Files.write(OUTPUT_DIR.resolve(name), data);
    }
}

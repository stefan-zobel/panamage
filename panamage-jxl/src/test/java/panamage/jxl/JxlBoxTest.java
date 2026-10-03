package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class JxlBoxTest {

    /** Encoded files are kept here for cross-checks with the libjxl command line tools. */
    private static final Path OUTPUT_DIR = Path.of("target", "test-output");

    private static final byte[] JSON = "{\"pixelWidth\": 0.065, \"unit\": \"micron\"}"
            .getBytes(StandardCharsets.UTF_8);

    private static final byte[] XMP = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".getBytes(StandardCharsets.UTF_8);

    @Test
    void boxesRoundTripInOrderWithTheirCompression() throws IOException {
        List<JxlBox> boxes = List.of(JxlBox.of("myCo", JSON), new JxlBox("jumb", bytes(300, 7), false),
                new JxlBox("abcd", new byte[0], false), JxlBox.of("myCo", bytes(5000, 3)));
        JxlMetadata metadata = new JxlMetadata(null, XMP, boxes);

        byte[] data = JxlEncoder.encode(TestImages.gradientRgba(), JxlEncodeOptions.ofLossless(), metadata);
        save("boxes.jxl", data);

        for (int chunkSize : new int[] {64 * 1024, 100, 1}) {
            JxlMetadata read = JxlDecoder.readMetadata(data, chunkSize, JxlDecodeOptions.defaults());
            assertArrayEquals(XMP, read.xmp(), "chunk size " + chunkSize);
            assertNull(read.exif());
            assertSameBoxes(boxes, read.boxes());
            assertArrayEquals(JSON, read.box("myCo").content());
            assertNull(read.box("none"));
        }
        // The pixels are not affected.
        assertArrayEquals(TestImages.gradientRgbaPixels(), JxlDecoder.decode(data).pixels());
    }

    @Test
    void boxesAloneMakeAContainer() {
        JxlMetadata metadata = JxlMetadata.NONE.withBoxes(List.of(JxlBox.of("myCo", JSON)));
        assertFalse(metadata.isEmpty());

        byte[] data = JxlEncoder.encode(TestImages.gradientChannels(1), JxlEncodeOptions.ofLossless(), metadata);

        assertSameBoxes(metadata.boxes(), JxlDecoder.readMetadata(data).boxes());
    }

    @Test
    void channelsAndAnimationsCarryBoxes() throws IOException {
        JxlMetadata metadata = JxlMetadata.NONE.withBoxes(List.of(JxlBox.of("myCo", JSON)));
        JxlChannels channels = JxlChannels.builder(4, 3).gray(new short[12]).add("a", new short[12]).build();

        byte[] still = JxlEncoder.encode(channels, JxlEncodeOptions.ofLossless(), metadata);
        assertSameBoxes(metadata.boxes(), JxlDecoder.readMetadata(still).boxes());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JxlFrameEncoder frames = JxlFrameEncoder.open(out, new JxlAnimationHeader(1, 1, 0),
                JxlEncodeOptions.ofLossless(), metadata)) {
            frames.add(channels, 1);
            frames.add(channels, 1);
            frames.finish();
        }
        assertSameBoxes(metadata.boxes(), JxlDecoder.readMetadata(out.toByteArray()).boxes());
    }

    @Test
    void invalidAndReservedTypesAreRejected() {
        for (String type : List.of("abc", "abcde", "ab\tc", "ab\u007fc", "jxlc", "jxlp", "JXL ", "ftyp", "brob",
                "jbrd", "Exif", "xml ")) {
            assertThrows(IllegalArgumentException.class, () -> JxlBox.of(type, JSON), type);
        }
        assertThrows(NullPointerException.class, () -> JxlBox.of(null, JSON));
        assertThrows(NullPointerException.class, () -> JxlBox.of("myCo", null));
        assertThrows(NullPointerException.class, () -> JxlMetadata.NONE.withBoxes(null));
        assertThrows(NullPointerException.class,
                () -> JxlMetadata.NONE.withBoxes(java.util.Arrays.asList((JxlBox) null)));
        assertTrue(JxlBox.of("jumb", JSON).compressed());
    }

    @Test
    void metadataWithoutBoxesIsUnchanged() {
        assertEquals(List.of(), new JxlMetadata(null, XMP).boxes());
        assertEquals(JxlMetadata.NONE, new JxlMetadata(null, null));
        assertTrue(JxlMetadata.NONE.isEmpty());
        assertSame(JxlMetadata.NONE, JxlDecoder.readMetadata(TestImages.gradientJxl()));
    }

    @Test
    void theMetadataLimitAppliesToEachBoxAndToAllBoxesTogether() {
        List<JxlBox> boxes = List.of(JxlBox.of("aaaa", bytes(1000, 1)), JxlBox.of("bbbb", bytes(1000, 2)),
                JxlBox.of("cccc", bytes(1000, 3)));
        byte[] data = JxlEncoder.encode(TestImages.gradientChannels(1), JxlEncodeOptions.ofLossless(),
                JxlMetadata.NONE.withBoxes(boxes));

        assertThrows(JxlLimitException.class,
                () -> JxlDecoder.readMetadata(data, TestImages.maxMetadataBytes(2999)));
        assertEquals(3, JxlDecoder.readMetadata(data, TestImages.maxMetadataBytes(3000)).boxes()
                .size());
        assertThrows(JxlLimitException.class,
                () -> JxlDecoder.readMetadata(data, TestImages.maxMetadataBytes(999)));
    }

    @Test
    void aCompressedBoxCannotExpandBeyondTheDefaultLimit() {
        // Zeros compress to a few bytes but expand beyond 16 MiB.
        byte[] zeros = new byte[(int) JxlLimits.DEFAULT_MAX_METADATA_BYTES + 1];
        byte[] data = JxlEncoder.encode(TestImages.gradientChannels(1), JxlEncodeOptions.ofLossless(),
                JxlMetadata.NONE.withBoxes(List.of(JxlBox.of("bomb", zeros))));
        assertTrue(data.length < 100_000, "compressed size " + data.length);

        assertThrows(JxlLimitException.class, () -> JxlDecoder.readMetadata(data));
    }

    private static void assertSameBoxes(List<JxlBox> expected, List<JxlBox> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).type(), actual.get(i).type(), "box " + i);
            assertEquals(expected.get(i).compressed(), actual.get(i).compressed(), "box " + i);
            assertArrayEquals(expected.get(i).content(), actual.get(i).content(), "box " + i);
        }
    }

    private static byte[] bytes(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 31 + seed * 7 + (i >> 5));
        }
        return bytes;
    }

    private static void save(String name, byte[] data) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Files.write(OUTPUT_DIR.resolve(name), data);
    }
}

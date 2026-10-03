package panamage.jxl;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading the encoded image from memory segments and files gives the same
 * results as from byte arrays, with every form of the methods.
 */
class InputSourceTest {

    private static final JxlDecodeOptions OPTIONS = JxlDecodeOptions.defaults();

    @TempDir
    Path directory;

    @Test
    void headersFromAllSources() throws IOException {
        byte[] data = TestImages.resource(TestImages.PHOTO_CJXL_REFERENCE);
        JxlImageInfo expected = JxlDecoder.readInfo(data);
        Path file = write("photo.jxl", data);

        try (Arena arena = Arena.ofConfined()) {
            for (MemorySegment segment : segments(data, file, arena)) {
                assertSameInfo(expected, JxlDecoder.readInfo(segment));
                assertEquals(JxlDecoder.readExtraChannels(data), JxlDecoder.readExtraChannels(segment));
                assertEquals(JxlDecoder.readAnimationInfo(data), JxlDecoder.readAnimationInfo(segment));
                assertArrayEquals(JxlDecoder.readMetadata(data).exif(), JxlDecoder.readMetadata(segment).exif());
                assertArrayEquals(JxlDecoder.readMetadata(data).exif(),
                        JxlDecoder.readMetadata(segment, OPTIONS).exif());
            }
        }
        assertSameInfo(expected, JxlDecoder.readInfo(file));
        assertEquals(JxlDecoder.readExtraChannels(data), JxlDecoder.readExtraChannels(file));
        assertEquals(JxlDecoder.readAnimationInfo(data), JxlDecoder.readAnimationInfo(file));
        assertArrayEquals(JxlDecoder.readMetadata(data).exif(), JxlDecoder.readMetadata(file).exif());
        assertArrayEquals(JxlDecoder.readMetadata(data).exif(), JxlDecoder.readMetadata(file, OPTIONS).exif());
    }

    @Test
    void imagesFromAllSources() throws IOException {
        byte[] data = TestImages.gradientJxl();
        byte[] expected = TestImages.gradientRgbaPixels();
        Path file = write("gradient.jxl", data);

        try (Arena arena = Arena.ofConfined()) {
            for (MemorySegment segment : segments(data, file, arena)) {
                assertArrayEquals(expected, pixels(JxlDecoder.decode(segment, 4, JxlSampleType.UINT8, OPTIONS)));
                assertArrayEquals(expected, pixels(JxlDecoder.decode(segment, 4, JxlSampleType.UINT8)));
                assertArrayEquals(expected, JxlDecoder.decode(segment).pixels());
                assertEquals(TestImages.WIDTH, JxlDecoder.decodeChannels(segment, JxlSampleType.UINT8, OPTIONS)
                        .width());
                assertEquals(TestImages.WIDTH, JxlDecoder.decodeChannels(segment, JxlSampleType.UINT8).width());
            }
        }
        assertArrayEquals(expected, pixels(JxlDecoder.decode(file, 4, JxlSampleType.UINT8, OPTIONS)));
        assertArrayEquals(expected, pixels(JxlDecoder.decode(file, 4, JxlSampleType.UINT8)));
        assertArrayEquals(expected, JxlDecoder.decode(file).pixels());
        int channels = JxlDecoder.decodeChannels(data, JxlSampleType.UINT8).channels();
        assertEquals(channels, JxlDecoder.decodeChannels(file, JxlSampleType.UINT8, OPTIONS).channels());
        assertEquals(channels, JxlDecoder.decodeChannels(file, JxlSampleType.UINT8).channels());
    }

    @Test
    void framesFromAllSources() throws IOException {
        byte[] data = TestImages.animationJxl();
        Path file = write("animation.jxl", data);
        List<JxlFrame> expected = JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8);

        try (Arena arena = Arena.ofConfined()) {
            for (MemorySegment segment : segments(data, file, arena)) {
                assertSameFrames(expected, JxlDecoder.decodeFrames(segment, 4, JxlSampleType.UINT8, OPTIONS));
                assertSameFrames(expected, JxlDecoder.decodeFrames(segment, 4, JxlSampleType.UINT8));
                try (JxlFrameDecoder decoder = JxlFrameDecoder.open(segment, 4, JxlSampleType.UINT8, OPTIONS)) {
                    assertSameFrames(expected, readAll(decoder));
                }
                try (JxlFrameDecoder decoder = JxlFrameDecoder.open(segment, 4, JxlSampleType.UINT8)) {
                    assertSameFrames(expected, readAll(decoder));
                }
                try (JxlFrameDecoder decoder = JxlFrameDecoder.openChannels(segment, JxlSampleType.UINT8, OPTIONS)) {
                    assertEquals(expected.size(), countChannelFrames(decoder));
                }
                try (JxlFrameDecoder decoder = JxlFrameDecoder.openChannels(segment, JxlSampleType.UINT8)) {
                    assertEquals(expected.size(), countChannelFrames(decoder));
                }
            }
        }
        assertSameFrames(expected, JxlDecoder.decodeFrames(file, 4, JxlSampleType.UINT8, OPTIONS));
        assertSameFrames(expected, JxlDecoder.decodeFrames(file, 4, JxlSampleType.UINT8));
        try (JxlFrameDecoder decoder = JxlFrameDecoder.open(file, 4, JxlSampleType.UINT8)) {
            assertSameFrames(expected, readAll(decoder));
        }
        try (JxlFrameDecoder decoder = JxlFrameDecoder.openChannels(file, JxlSampleType.UINT8, OPTIONS)) {
            assertEquals(expected.size(), countChannelFrames(decoder));
        }
        try (JxlFrameDecoder decoder = JxlFrameDecoder.openChannels(file, JxlSampleType.UINT8)) {
            assertEquals(expected.size(), countChannelFrames(decoder));
        }
    }

    @Test
    void frameDecodersDoNotDependOnTheirSource() throws IOException {
        byte[] data = TestImages.animationJxl();
        List<JxlFrame> expected = JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8);
        Path file = write("animation.jxl", data);

        JxlFrameDecoder fromSegment;
        try (Arena arena = Arena.ofConfined()) {
            fromSegment = JxlFrameDecoder.open(arena.allocateFrom(JAVA_BYTE, data), 4,
                    JxlSampleType.UINT8, OPTIONS);
        }
        JxlFrameDecoder fromFile = JxlFrameDecoder.open(file, 4, JxlSampleType.UINT8, OPTIONS);
        Files.delete(file);

        try (fromSegment; fromFile) {
            assertSameFrames(expected, readAll(fromSegment));
            assertSameFrames(expected, readAll(fromFile));
        }
    }

    @Test
    void jpegReconstructionFromAllSources() throws IOException {
        byte[] data = TestImages.resource(TestImages.PHOTO_CJXL_REFERENCE);
        byte[] jpeg = TestImages.resource("photo-420-exif.jpg");
        Path file = write("photo.jxl", data);

        try (Arena arena = Arena.ofConfined()) {
            for (MemorySegment segment : segments(data, file, arena)) {
                assertArrayEquals(jpeg, JxlTranscoder.toJpeg(segment));
                assertArrayEquals(jpeg, JxlTranscoder.toJpeg(segment, OPTIONS));
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                assertEquals(jpeg.length, JxlTranscoder.toJpeg(segment, OPTIONS, stream));
                assertArrayEquals(jpeg, stream.toByteArray());

                ByteArrayOutputStream channelTarget = new ByteArrayOutputStream();
                JxlTranscoder.toJpeg(segment, OPTIONS, Channels.newChannel(channelTarget));
                assertArrayEquals(jpeg, channelTarget.toByteArray());
            }
        }
        assertArrayEquals(jpeg, JxlTranscoder.toJpeg(file));
        assertArrayEquals(jpeg, JxlTranscoder.toJpeg(file, OPTIONS));
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        assertEquals(jpeg.length, JxlTranscoder.toJpeg(file, OPTIONS, stream));
        assertArrayEquals(jpeg, stream.toByteArray());
        ByteArrayOutputStream channelTarget = new ByteArrayOutputStream();
        assertEquals(jpeg.length, JxlTranscoder.toJpeg(file, OPTIONS, Channels.newChannel(channelTarget)));
        assertArrayEquals(jpeg, channelTarget.toByteArray());
    }

    @Test
    void jpegTranscodingFromAllSources() throws IOException {
        byte[] jpeg = TestImages.resource("photo-420-exif.jpg");
        byte[] expected = JxlTranscoder.fromJpeg(jpeg);
        JxlEncodeOptions fast = JxlEncodeOptions.ofLossless().withEffort(1);
        byte[] expectedFast = JxlTranscoder.fromJpeg(jpeg, fast);
        Path file = write("photo.jpg", jpeg);

        try (Arena arena = Arena.ofConfined()) {
            for (MemorySegment segment : segments(jpeg, file, arena)) {
                assertArrayEquals(expected, JxlTranscoder.fromJpeg(segment));
                assertArrayEquals(expectedFast, JxlTranscoder.fromJpeg(segment, fast));
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                assertEquals(expected.length, JxlTranscoder.fromJpeg(segment, JxlEncodeOptions.ofLossless(), stream));
                assertArrayEquals(expected, stream.toByteArray());
                ByteArrayOutputStream channelTarget = new ByteArrayOutputStream();
                JxlTranscoder.fromJpeg(segment, JxlEncodeOptions.ofLossless(), Channels.newChannel(channelTarget));
                assertArrayEquals(expected, channelTarget.toByteArray());
            }
        }
        assertArrayEquals(expected, JxlTranscoder.fromJpeg(file));
        assertArrayEquals(expectedFast, JxlTranscoder.fromJpeg(file, fast));
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        assertEquals(expected.length, JxlTranscoder.fromJpeg(file, JxlEncodeOptions.ofLossless(), stream));
        assertArrayEquals(expected, stream.toByteArray());
        ByteArrayOutputStream channelTarget = new ByteArrayOutputStream();
        assertEquals(expected.length,
                JxlTranscoder.fromJpeg(file, JxlEncodeOptions.ofLossless(), Channels.newChannel(channelTarget)));
        assertArrayEquals(expected, channelTarget.toByteArray());
    }

    @Test
    void limitsApplyToAllSources() throws IOException {
        byte[] data = TestImages.gradientJxl();
        Path file = write("gradient.jxl", data);
        JxlDecodeOptions small = OPTIONS.withLimits(JxlLimits.defaults().withMaxPixels(100));

        assertThrows(JxlLimitException.class,
                () -> JxlDecoder.decode(MemorySegment.ofArray(data), 4, JxlSampleType.UINT8, small));
        assertThrows(JxlLimitException.class, () -> JxlDecoder.decode(file, 4, JxlSampleType.UINT8, small));
        assertThrows(JxlLimitException.class, () -> JxlFrameDecoder.open(file, 4, JxlSampleType.UINT8, small));
    }

    @Test
    void reportsMissingAndInvalidFiles() throws IOException {
        Path missing = directory.resolve("missing.jxl");
        Path empty = write("empty.jxl", new byte[0]);
        Path invalid = write("invalid.jxl", new byte[] {1, 2, 3, 4, 5, 6, 7, 8});

        assertThrows(NoSuchFileException.class, () -> JxlDecoder.readInfo(missing));
        assertThrows(NoSuchFileException.class, () -> JxlFrameDecoder.open(missing, 4, JxlSampleType.UINT8, OPTIONS));
        assertThrows(NoSuchFileException.class, () -> JxlDecoder.readAnimationInfo(missing));
        assertThrows(NoSuchFileException.class, () -> JxlTranscoder.fromJpeg(missing));
        assertThrows(NoSuchFileException.class, () -> JxlTranscoder.toJpeg(missing));
        assertThrows(JxlException.class, () -> JxlDecoder.decode(empty, 4, JxlSampleType.UINT8, OPTIONS));
        assertThrows(JxlException.class, () -> JxlDecoder.readInfo(invalid));
        assertThrows(JxlException.class, () -> JxlFrameDecoder.open(invalid, 4, JxlSampleType.UINT8, OPTIONS));
        assertFalse(Files.exists(missing));
    }

    @Test
    void requiresArguments() {
        assertThrows(NullPointerException.class, () -> JxlDecoder.readInfo((Path) null));
        assertThrows(NullPointerException.class, () -> JxlDecoder.readInfo((MemorySegment) null));
        assertThrows(NullPointerException.class,
                () -> JxlDecoder.decode((Path) null, 4, JxlSampleType.UINT8, OPTIONS));
        assertThrows(NullPointerException.class,
                () -> JxlFrameDecoder.open((MemorySegment) null, 4, JxlSampleType.UINT8, OPTIONS));
        assertThrows(IllegalArgumentException.class,
                () -> JxlFrameDecoder.open(MemorySegment.ofArray(TestImages.gradientJxl()), 5, JxlSampleType.UINT8,
                        OPTIONS));
        assertNull(JxlDecoder.decode(MemorySegment.ofArray(TestImages.gradientJxl()), 4, JxlSampleType.UINT8,
                OPTIONS).iccProfile());
    }

    /** A heap segment, a native segment and a read-only mapped file with the same data. */
    private static List<MemorySegment> segments(byte[] data, Path file, Arena arena) throws IOException {
        MemorySegment mapped;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
        }
        return List.of(MemorySegment.ofArray(data), arena.allocateFrom(JAVA_BYTE, data),
                mapped);
    }

    private Path write(String name, byte[] data) throws IOException {
        return Files.write(directory.resolve(name), data);
    }

    private static byte[] pixels(JxlImage image) {
        return ((JxlImage.Uint8) image).pixels();
    }

    private static List<JxlFrame> readAll(JxlFrameDecoder decoder) {
        List<JxlFrame> frames = new ArrayList<>();
        for (JxlFrame frame = decoder.next(); frame != null; frame = decoder.next()) {
            frames.add(frame);
        }
        return frames;
    }

    private static int countChannelFrames(JxlFrameDecoder decoder) {
        int count = 0;
        while (decoder.nextChannels() != null) {
            count++;
        }
        return count;
    }

    private static void assertSameFrames(List<JxlFrame> expected, List<JxlFrame> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).info(), actual.get(i).info());
            assertArrayEquals(pixels(expected.get(i).image()), pixels(actual.get(i).image()), "frame " + i);
        }
    }

    private static void assertSameInfo(JxlImageInfo expected, JxlImageInfo actual) {
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.height(), actual.height());
        assertEquals(expected.channels(), actual.channels());
        assertEquals(expected.bitsPerSample(), actual.bitsPerSample());
        assertEquals(expected.animated(), actual.animated());
        assertArrayEquals(expected.iccProfile(), actual.iccProfile());
    }
}

package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class JxlFrameEncoderTest {

    /** Encoded files are kept here for cross-checks with the libjxl command line tools. */
    private static final Path OUTPUT_DIR = Path.of("target", "test-output");

    private static final int WIDTH = 16;
    private static final int HEIGHT = 12;

    @Test
    void losslessRoundTripKeepsPixelsTicksNamesAndLoops() throws IOException {
        List<JxlImage.Uint8> images = List.of(rgba(1), rgba(2), rgba(3));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (JxlFrameEncoder frames = JxlFrameEncoder.open(out, new JxlAnimationHeader(10, 1, 3),
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE)) {
            frames.add(images.get(0), 2, "first");
            frames.add(images.get(1), 5);
            frames.add(images.get(2), 1, "third");
            long written = frames.finish();
            assertEquals(out.size(), written);
        }
        byte[] data = out.toByteArray();
        save("animation-lossless.jxl", data);

        assertEquals(new JxlAnimationInfo(10, 1, 3, List.of(new JxlFrameInfo(2, 200, "first"),
                new JxlFrameInfo(5, 500, ""), new JxlFrameInfo(1, 100, "third"))), JxlDecoder.readAnimationInfo(data));
        List<JxlFrame> decoded = JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8);
        assertEquals(3, decoded.size());
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(images.get(i).pixels(), ((JxlImage.Uint8) decoded.get(i).image()).pixels(), "frame " + i);
        }
    }

    @Test
    void losslessRoundTripOf16BitFloatAndGrayFrames() throws IOException {
        List<JxlImage> uint16 = new ArrayList<>();
        List<JxlImage> float32 = new ArrayList<>();
        List<JxlImage> grayAlpha = new ArrayList<>();
        for (int f = 0; f < 3; f++) {
            short[] shorts = new short[WIDTH * HEIGHT * 3];
            float[] floats = new float[WIDTH * HEIGHT * 4];
            byte[] bytes = new byte[WIDTH * HEIGHT * 2];
            for (int i = 0; i < shorts.length; i++) {
                shorts[i] = (short) (i * 977 + f * 13_001);
            }
            for (int i = 0; i < floats.length; i++) {
                floats[i] = (i % 37) / 36.0f + f * 0.25f - 0.125f;
            }
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (i * 7 + f * 50);
            }
            uint16.add(new JxlImage.Uint16(WIDTH, HEIGHT, 3, shorts));
            float32.add(new JxlImage.Float32(WIDTH, HEIGHT, 4, floats));
            grayAlpha.add(new JxlImage.Uint8(WIDTH, HEIGHT, 2, bytes));
        }

        List<JxlFrame> decoded16 = JxlDecoder.decodeFrames(encode(uint16), 3, JxlSampleType.UINT16);
        List<JxlFrame> decodedFloat = JxlDecoder.decodeFrames(encode(float32), 4, JxlSampleType.FLOAT32);
        List<JxlFrame> decodedGray = JxlDecoder.decodeFrames(encode(grayAlpha), 2, JxlSampleType.UINT8);

        for (int f = 0; f < 3; f++) {
            assertArrayEquals(((JxlImage.Uint16) uint16.get(f)).pixels(),
                    ((JxlImage.Uint16) decoded16.get(f).image()).pixels(), "16-bit frame " + f);
            assertArrayEquals(((JxlImage.Float32) float32.get(f)).pixels(),
                    ((JxlImage.Float32) decodedFloat.get(f).image()).pixels(), "float frame " + f);
            assertArrayEquals(((JxlImage.Uint8) grayAlpha.get(f)).pixels(),
                    ((JxlImage.Uint8) decodedGray.get(f).image()).pixels(), "gray frame " + f);
        }
    }

    @Test
    void millisHeaderMakesTicksMilliseconds() throws IOException {
        byte[] data = encode(List.of(rgba(1), rgba(2)), JxlAnimationHeader.millis(0), JxlEncodeOptions.ofLossless(),
                JxlMetadata.NONE, 40, 60);

        JxlAnimationInfo animation = JxlDecoder.readAnimationInfo(data);
        assertEquals(new JxlAnimationInfo(1000, 1, 0, List.of(new JxlFrameInfo(40, 40, ""),
                new JxlFrameInfo(60, 60, ""))), animation);
        assertEquals(new JxlAnimationHeader(1000, 1, 2), JxlAnimationHeader.millis(2));
    }

    @Test
    void reencodesDecodedFrames() throws IOException {
        byte[] original = TestImages.animationJxl();
        JxlAnimationInfo info = JxlDecoder.readAnimationInfo(original);
        List<JxlFrame> frames = JxlDecoder.decodeFrames(original, 4, JxlSampleType.UINT8);
        JxlAnimationHeader header = info.header();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JxlFrameEncoder encoder = JxlFrameEncoder.open(out, header, JxlEncodeOptions.ofLossless(),
                JxlMetadata.NONE)) {
            for (JxlFrame frame : frames) {
                encoder.add(frame);
            }
            encoder.finish();
        }
        byte[] data = out.toByteArray();

        assertEquals(info, JxlDecoder.readAnimationInfo(data));
        List<JxlFrame> decoded = JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8);
        for (int i = 0; i < frames.size(); i++) {
            assertArrayEquals(TestImages.animationRgbaPixels(i), ((JxlImage.Uint8) decoded.get(i).image()).pixels(),
                    "frame " + i);
        }
    }

    @Test
    void lossyAnimationDecodesToAllFrames() throws IOException {
        byte[] data = encode(List.of(rgba(1), rgba(2), rgba(3)), JxlAnimationHeader.millis(0),
                JxlEncodeOptions.defaults(), JxlMetadata.NONE, 100, 100, 100);

        List<JxlFrame> decoded = JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8);
        assertEquals(3, decoded.size());
        for (JxlFrame frame : decoded) {
            assertEquals(WIDTH, frame.image().width());
            assertEquals(HEIGHT, frame.image().height());
        }
    }

    @Test
    void aSingleFrameMayHaveNoDuration() throws IOException {
        byte[] data = encode(List.of(rgba(1)), JxlAnimationHeader.millis(0), JxlEncodeOptions.ofLossless(),
                JxlMetadata.NONE, 0);

        assertEquals(1, JxlDecoder.readAnimationInfo(data).frameCount());
        assertArrayEquals(rgba(1).pixels(), JxlDecoder.decode(data).pixels());
    }

    @Test
    void streamAndChannelOutputAreEqual() throws IOException {
        List<JxlImage.Uint8> images = List.of(rgba(1), rgba(2), rgba(3));
        JxlAnimationHeader header = JxlAnimationHeader.millis(0);
        JxlEncodeOptions options = JxlEncodeOptions.ofLossless();
        byte[] streamed = encode(images, header, options, JxlMetadata.NONE, 10, 20, 30);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (WritableByteChannel channel = Channels.newChannel(out);
                JxlFrameEncoder frames = JxlFrameEncoder.open(channel, header, options, JxlMetadata.NONE)) {
            frames.add(images.get(0), 10);
            frames.add(images.get(1), 20);
            frames.add(images.get(2), 30);
            assertEquals(streamed.length, frames.finish());
        }
        assertArrayEquals(streamed, out.toByteArray());
    }

    @Test
    void smallOutputBuffersGiveTheSameBytes() throws IOException {
        List<JxlImage.Uint8> images = List.of(rgba(1), rgba(2), rgba(3));
        JxlAnimationHeader header = JxlAnimationHeader.millis(0);
        JxlEncodeOptions options = JxlEncodeOptions.ofLossless();
        byte[] xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".getBytes(StandardCharsets.UTF_8);
        for (JxlMetadata metadata : List.of(JxlMetadata.NONE, new JxlMetadata(null, xmp))) {
            byte[] expected = encode(images, header, options, metadata, 10, 20, 30);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (JxlFrameEncoder frames = JxlFrameEncoder.open(OutputSink.of(out), header, options, metadata,
                    NativeEncoder.MIN_OUTPUT_CHUNK_SIZE + 5)) {
                frames.add(images.get(0), 10);
                frames.add(images.get(1), 20);
                frames.add(images.get(2), 30);
                frames.finish();
            }
            assertArrayEquals(expected, out.toByteArray(), "metadata " + !metadata.isEmpty());
        }
    }

    @Test
    void writesEachFrameWhenTheNextOneIsAdded() throws IOException {
        RecordingStream out = new RecordingStream();
        try (JxlFrameEncoder frames = JxlFrameEncoder.open(out, JxlAnimationHeader.millis(0),
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE)) {
            frames.add(rgba(1), 10);
            assertEquals(0, out.size());
            frames.add(rgba(2), 10);
            int afterSecond = out.size();
            assertTrue(afterSecond > 0);
            frames.add(rgba(3), 10);
            assertTrue(out.size() > afterSecond);
            long written = frames.finish();
            assertEquals(out.size(), written);
        }
        assertFalse(out.flushed);
        assertFalse(out.closed);
        assertEquals(3, JxlDecoder.readAnimationInfo(out.toByteArray()).frameCount());
    }

    @Test
    void passesOnAFailingSinkAndCanOnlyBeClosedAfterwards() throws IOException {
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("disk full");
            }
        };
        JxlFrameEncoder frames = JxlFrameEncoder.open(failing, JxlAnimationHeader.millis(0),
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE);
        frames.add(rgba(1), 10);

        IOException e = assertThrows(IOException.class, () -> frames.add(rgba(2), 10));
        assertEquals("disk full", e.getMessage());
        assertThrows(IllegalStateException.class, () -> frames.add(rgba(3), 10));
        assertThrows(IllegalStateException.class, frames::finish);
        frames.close();
    }

    @Test
    void copiesThePixelsWhenAFrameIsAdded() throws IOException {
        JxlImage.Uint8 reused = rgba(1);
        byte[] expected = reused.pixels().clone();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (JxlFrameEncoder frames = JxlFrameEncoder.open(out, JxlAnimationHeader.millis(0),
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE)) {
            frames.add(reused, 10);
            System.arraycopy(rgba(2).pixels(), 0, reused.pixels(), 0, expected.length);
            frames.add(reused, 10);
            frames.finish();
        }

        List<JxlFrame> decoded = JxlDecoder.decodeFrames(out.toByteArray(), 4, JxlSampleType.UINT8);
        assertArrayEquals(expected, ((JxlImage.Uint8) decoded.get(0).image()).pixels());
        assertArrayEquals(rgba(2).pixels(), ((JxlImage.Uint8) decoded.get(1).image()).pixels());
    }

    @Test
    void storesExifAndXmp() throws IOException {
        byte[] exif = ExifTest.tiff(true, 1);
        byte[] xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".getBytes(StandardCharsets.UTF_8);

        byte[] data = encode(List.of(rgba(1), rgba(2)), JxlAnimationHeader.millis(0), JxlEncodeOptions.ofLossless(),
                new JxlMetadata(exif, xmp), 10, 20);
        save("animation-metadata.jxl", data);

        JxlMetadata read = JxlDecoder.readMetadata(data);
        assertArrayEquals(exif, read.exif());
        assertArrayEquals(xmp, read.xmp());
        List<JxlFrame> decoded = JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8);
        assertArrayEquals(rgba(1).pixels(), ((JxlImage.Uint8) decoded.get(0).image()).pixels());
        assertArrayEquals(rgba(2).pixels(), ((JxlImage.Uint8) decoded.get(1).image()).pixels());
    }

    @Test
    void exifOrientationAppliesToEveryFrame() throws IOException {
        JxlImage.Uint8 wide = new JxlImage.Uint8(4, 2, 3, new byte[4 * 2 * 3]);

        byte[] data = encode(List.of(wide, wide), JxlAnimationHeader.millis(0), JxlEncodeOptions.ofLossless(),
                new JxlMetadata(ExifTest.tiff(false, 6), null), 10, 10);

        JxlImageInfo info = JxlDecoder.readInfo(data);
        assertEquals(2, info.width());
        assertEquals(4, info.height());
        for (JxlFrame frame : JxlDecoder.decodeFrames(data, 3, JxlSampleType.UINT8)) {
            assertEquals(2, frame.image().width());
            assertEquals(4, frame.image().height());
        }
    }

    @Test
    void rejectsFramesThatDoNotMatchTheFirst() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JxlFrameEncoder frames = JxlFrameEncoder.open(out, JxlAnimationHeader.millis(0),
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE)) {
            frames.add(rgba(1), 10);

            assertThrows(IllegalArgumentException.class,
                    () -> frames.add(new JxlImage.Uint8(WIDTH, HEIGHT + 1, 4, new byte[WIDTH * (HEIGHT + 1) * 4]), 10));
            assertThrows(IllegalArgumentException.class,
                    () -> frames.add(new JxlImage.Uint8(WIDTH, HEIGHT, 3, new byte[WIDTH * HEIGHT * 3]), 10));
            assertThrows(IllegalArgumentException.class,
                    () -> frames.add(new JxlImage.Uint16(WIDTH, HEIGHT, 4, new short[WIDTH * HEIGHT * 4]), 10));
            assertThrows(IllegalArgumentException.class, () -> frames.add(
                    new JxlImage.Uint8(WIDTH, HEIGHT, 4, new byte[WIDTH * HEIGHT * 4], new byte[] {1, 2, 3}), 10));

            // The rejected frames leave the encoder unchanged.
            frames.finish();
        }
        assertEquals(1, JxlDecoder.readAnimationInfo(out.toByteArray()).frameCount());
    }

    @Test
    void rejectsInvalidDurationsAndNames() throws IOException {
        try (JxlFrameEncoder frames = JxlFrameEncoder.open(new ByteArrayOutputStream(), JxlAnimationHeader.millis(0),
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE)) {
            assertThrows(IllegalArgumentException.class, () -> frames.add(rgba(1), -1));
            assertThrows(IllegalArgumentException.class,
                    () -> frames.add(rgba(1), JxlFrameEncoder.MAX_DURATION_TICKS + 1));
            assertThrows(IllegalArgumentException.class, () -> frames.add(rgba(1), 10, "a\0b"));
            assertThrows(IllegalArgumentException.class, () -> frames.add(rgba(1), 10, "x".repeat(1072)));
            // Two bytes per character in UTF-8.
            assertThrows(IllegalArgumentException.class, () -> frames.add(rgba(1), 10, "\u00e4".repeat(536)));
            assertThrows(NullPointerException.class, () -> frames.add(rgba(1), 10, null));
            assertThrows(NullPointerException.class, () -> frames.add((JxlImage) null, 10));
            assertThrows(NullPointerException.class, () -> frames.add((JxlFrame) null));
        }
    }

    @Test
    void acceptsTheLongestNameAndTheLongestDuration() throws IOException {
        String name = "x".repeat(JxlFrameEncoder.MAX_NAME_BYTES);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JxlFrameEncoder frames = JxlFrameEncoder.open(out, JxlAnimationHeader.millis(0),
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE)) {
            frames.add(rgba(1), JxlFrameEncoder.MAX_DURATION_TICKS, name);
            frames.finish();
        }

        JxlFrameInfo frame = JxlDecoder.readAnimationInfo(out.toByteArray()).frames().get(0);
        assertEquals(JxlFrameEncoder.MAX_DURATION_TICKS, frame.durationTicks());
        assertEquals(name, frame.name());
    }

    @Test
    void onlyTheLastFrameMayHaveNoDuration() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JxlFrameEncoder frames = JxlFrameEncoder.open(out, JxlAnimationHeader.millis(0),
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE)) {
            frames.add(rgba(1), 10);
            frames.add(rgba(2), 0);
            assertThrows(IllegalArgumentException.class, () -> frames.add(rgba(3), 10));
            frames.finish();
        }

        JxlAnimationInfo animation = JxlDecoder.readAnimationInfo(out.toByteArray());
        assertEquals(List.of(new JxlFrameInfo(10, 10, ""), new JxlFrameInfo(0, 0, "")), animation.frames());
    }

    @Test
    void validatesTheAnimationHeader() {
        long numerator = JxlAnimationHeader.MAX_NUMERATOR;
        long denominator = JxlAnimationHeader.MAX_DENOMINATOR;
        long loops = JxlAnimationHeader.MAX_LOOPS;
        assertThrows(IllegalArgumentException.class, () -> new JxlAnimationHeader(0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new JxlAnimationHeader(1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new JxlAnimationHeader(numerator + 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new JxlAnimationHeader(1, denominator + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new JxlAnimationHeader(1, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new JxlAnimationHeader(1, 1, loops + 1));
        assertThrows(IllegalArgumentException.class, () -> JxlAnimationHeader.millis(-1));
        assertDoesNotThrow(() -> new JxlAnimationHeader(numerator, denominator, loops));
    }

    @Test
    void largestHeaderValuesSurviveTheRoundTrip() throws IOException {
        JxlAnimationHeader header = new JxlAnimationHeader(JxlAnimationHeader.MAX_NUMERATOR,
                JxlAnimationHeader.MAX_DENOMINATOR, JxlAnimationHeader.MAX_LOOPS);
        byte[] data = encode(List.of(rgba(1), rgba(2)), header, JxlEncodeOptions.ofLossless(), JxlMetadata.NONE, 1, 1);

        JxlAnimationInfo animation = JxlDecoder.readAnimationInfo(data);
        assertEquals(header, animation.header());
    }

    @Test
    void keepsAnNtscTickRate() throws IOException {
        byte[] data = encode(List.of(rgba(1), rgba(2)), new JxlAnimationHeader(30000, 1001, 0),
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE, 1, 1);

        JxlAnimationInfo animation = JxlDecoder.readAnimationInfo(data);
        assertEquals(30000, animation.ticksPerSecondNumerator());
        assertEquals(1001, animation.ticksPerSecondDenominator());
        assertEquals(1001.0 / 30.0, animation.frames().get(0).durationMillis(), 1e-9);
    }

    @Test
    void enforcesTheOrderOfCalls() throws IOException {
        JxlFrameEncoder frames = JxlFrameEncoder.open(new ByteArrayOutputStream(), JxlAnimationHeader.millis(0),
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE);
        assertThrows(IllegalStateException.class, frames::finish);
        frames.add(rgba(1), 10);
        frames.finish();
        assertThrows(IllegalStateException.class, () -> frames.add(rgba(2), 10));
        assertThrows(IllegalStateException.class, frames::finish);
        frames.close();
        frames.close();
        assertThrows(IllegalStateException.class, () -> frames.add(rgba(2), 10));
        assertThrows(IllegalStateException.class, frames::finish);
    }

    @Test
    void closingWithoutFinishingLeavesAnIncompleteFile() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JxlFrameEncoder frames = JxlFrameEncoder.open(out, JxlAnimationHeader.millis(0),
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE)) {
            frames.add(rgba(1), 10);
            frames.add(rgba(2), 10);
        }

        byte[] data = out.toByteArray();
        assertTrue(data.length > 0);
        assertThrows(JxlException.class, () -> JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8));
    }

    @Test
    void rejectsNullArgumentsAndNonBlockingChannels() throws IOException {
        JxlAnimationHeader header = JxlAnimationHeader.millis(0);
        JxlEncodeOptions options = JxlEncodeOptions.defaults();
        assertThrows(NullPointerException.class,
                () -> JxlFrameEncoder.open((OutputStream) null, header, options, JxlMetadata.NONE));
        assertThrows(NullPointerException.class,
                () -> JxlFrameEncoder.open((WritableByteChannel) null, header, options, JxlMetadata.NONE));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(NullPointerException.class, () -> JxlFrameEncoder.open(out, null, options, JxlMetadata.NONE));
        assertThrows(NullPointerException.class, () -> JxlFrameEncoder.open(out, header, null, JxlMetadata.NONE));
        assertThrows(NullPointerException.class, () -> JxlFrameEncoder.open(out, header, options, null));

        Pipe pipe = Pipe.open();
        try (Pipe.SinkChannel sink = pipe.sink(); Pipe.SourceChannel source = pipe.source()) {
            sink.configureBlocking(false);
            assertThrows(IllegalArgumentException.class,
                    () -> JxlFrameEncoder.open(sink, header, options, JxlMetadata.NONE));
        }
    }

    @Test
    void encodeAnimationGivesTheBytesOfTheFrameEncoder() throws IOException {
        List<JxlImage.Uint8> images = List.of(rgba(1), rgba(2), rgba(3));
        JxlAnimationHeader header = new JxlAnimationHeader(10, 1, 3);
        byte[] xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".getBytes(StandardCharsets.UTF_8);
        JxlMetadata metadata = new JxlMetadata(null, xmp);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JxlFrameEncoder frames = JxlFrameEncoder.open(out, header, JxlEncodeOptions.ofLossless(), metadata)) {
            frames.add(images.get(0), 2, "first");
            frames.add(images.get(1), 5);
            frames.add(images.get(2), 1, "third");
            frames.finish();
        }
        // The durations in milliseconds are ignored.
        List<JxlFrame> frames = List.of(new JxlFrame(images.get(0), new JxlFrameInfo(2, 1.0, "first")),
                new JxlFrame(images.get(1), new JxlFrameInfo(5, 2.0, "")),
                new JxlFrame(images.get(2), new JxlFrameInfo(1, 3.0, "third")));

        byte[] data = JxlEncoder.encodeAnimation(frames, header, JxlEncodeOptions.ofLossless(), metadata);

        assertArrayEquals(out.toByteArray(), data);
    }

    @Test
    void encodeAnimationRestoresDecodedFrames() throws IOException {
        byte[] original = TestImages.animationJxl();
        JxlAnimationInfo info = JxlDecoder.readAnimationInfo(original);
        JxlAnimationHeader header = info.header();

        byte[] data = JxlEncoder.encodeAnimation(JxlDecoder.decodeFrames(original, 4, JxlSampleType.UINT8), header,
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE);
        save("animation-reencoded.jxl", data);

        assertEquals(info, JxlDecoder.readAnimationInfo(data));
        List<JxlFrame> decoded = JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8);
        for (int i = 0; i < decoded.size(); i++) {
            assertArrayEquals(TestImages.animationRgbaPixels(i), ((JxlImage.Uint8) decoded.get(i).image()).pixels(),
                    "frame " + i);
        }
    }

    @Test
    void encodeAnimationRejectsInvalidArguments() {
        JxlFrame frame = new JxlFrame(rgba(1), new JxlFrameInfo(10, 10.0, ""));
        JxlFrame still = new JxlFrame(rgba(2), new JxlFrameInfo(0, 0.0, ""));
        JxlFrame small = new JxlFrame(new JxlImage.Uint8(1, 1, 4, new byte[4]), new JxlFrameInfo(10, 10.0, ""));
        JxlAnimationHeader header = JxlAnimationHeader.millis(0);
        JxlEncodeOptions options = JxlEncodeOptions.ofLossless();

        assertThrows(IllegalArgumentException.class,
                () -> JxlEncoder.encodeAnimation(List.of(), header, options, JxlMetadata.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> JxlEncoder.encodeAnimation(List.of(frame, small), header, options, JxlMetadata.NONE));
        assertThrows(IllegalArgumentException.class,
                () -> JxlEncoder.encodeAnimation(List.of(still, frame), header, options, JxlMetadata.NONE));
        assertThrows(NullPointerException.class,
                () -> JxlEncoder.encodeAnimation(Arrays.asList(frame, null), header, options, JxlMetadata.NONE));
        assertThrows(NullPointerException.class,
                () -> JxlEncoder.encodeAnimation(null, header, options, JxlMetadata.NONE));
        assertThrows(NullPointerException.class,
                () -> JxlEncoder.encodeAnimation(List.of(frame), null, options, JxlMetadata.NONE));
        assertThrows(NullPointerException.class,
                () -> JxlEncoder.encodeAnimation(List.of(frame), header, null, JxlMetadata.NONE));
        assertThrows(NullPointerException.class,
                () -> JxlEncoder.encodeAnimation(List.of(frame), header, options, null));
        // A last frame without duration is allowed.
        assertEquals(2, JxlDecoder.readAnimationInfo(
                JxlEncoder.encodeAnimation(List.of(frame, still), header, options, JxlMetadata.NONE)).frameCount());
    }

    /** Encodes lossless frames of 1 millisecond each. */
    private static byte[] encode(List<? extends JxlImage> images) throws IOException {
        long[] durations = new long[images.size()];
        Arrays.fill(durations, 1);
        return encode(images, JxlAnimationHeader.millis(0), JxlEncodeOptions.ofLossless(), JxlMetadata.NONE,
                durations);
    }

    private static byte[] encode(List<? extends JxlImage> images, JxlAnimationHeader header, JxlEncodeOptions options,
            JxlMetadata metadata, long... durations) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JxlFrameEncoder frames = JxlFrameEncoder.open(out, header, options, metadata)) {
            for (int i = 0; i < images.size(); i++) {
                frames.add(images.get(i), durations[i]);
            }
            frames.finish();
        }
        return out.toByteArray();
    }

    /** An RGBA frame whose pixels depend on the seed. */
    private static JxlImage.Uint8 rgba(int seed) {
        byte[] pixels = new byte[WIDTH * HEIGHT * 4];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i * seed * 31 + seed * 17);
        }
        return new JxlImage.Uint8(WIDTH, HEIGHT, 4, pixels);
    }

    /** Remembers whether it was flushed or closed. */
    private static final class RecordingStream extends ByteArrayOutputStream {

        boolean flushed;
        boolean closed;

        @Override
        public void flush() {
            flushed = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static void save(String name, byte[] data) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Files.write(OUTPUT_DIR.resolve(name), data);
    }
}

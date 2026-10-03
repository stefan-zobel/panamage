package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class JxlChannelsTest {

    /** Encoded files are kept here for cross-checks with the libjxl command line tools. */
    private static final Path OUTPUT_DIR = Path.of("target", "test-output");

    private static final int WIDTH = 23;
    private static final int HEIGHT = 17;
    private static final int SAMPLES = WIDTH * HEIGHT;

    @Test
    void builderListsColorPlanesFirstAndFindsExtraChannelsByName() {
        short[] dapi = uint16Plane(1);
        short[] gfp = uint16Plane(2);
        short[] mcherry = uint16Plane(3);

        JxlChannels image = JxlChannels.builder(WIDTH, HEIGHT).add("GFP", gfp).gray(dapi).add("mCherry", mcherry)
                .build();

        JxlChannels.Uint16 uint16 = assertInstanceOfUint16(image);
        assertEquals(JxlSampleType.UINT16, image.sampleType());
        assertEquals(1, image.colorChannels());
        assertEquals(3, image.channels());
        assertEquals(List.of(dapi, gfp, mcherry), uint16.planes());
        assertEquals(List.of(JxlExtraChannel.of("GFP"), JxlExtraChannel.of("mCherry")), image.extraChannels());
        assertEquals(2, image.indexOf("mCherry"));
        assertEquals(-1, image.indexOf("DAPI"));
        assertTrue(image.isSrgb());
    }

    @Test
    void invalidImagesAreRejected() {
        byte[] plane = new byte[SAMPLES];
        List<JxlExtraChannel> none = List.of();
        assertThrows(IllegalArgumentException.class,
                () -> new JxlChannels.Uint8(WIDTH, HEIGHT, 2, List.of(plane, plane), none));
        assertThrows(IllegalArgumentException.class,
                () -> new JxlChannels.Uint8(WIDTH, HEIGHT, 1, List.of(plane, plane), none));
        assertThrows(IllegalArgumentException.class,
                () -> new JxlChannels.Uint8(WIDTH, HEIGHT, 1, List.of(new byte[SAMPLES - 1]), none));
        assertThrows(IllegalArgumentException.class,
                () -> new JxlChannels.Uint8(0, HEIGHT, 1, List.of(plane), none));
        assertThrows(IllegalArgumentException.class,
                () -> new JxlChannels.Uint8(WIDTH, HEIGHT, 1, List.of(plane), none, 8, new byte[0]));
        assertThrows(NullPointerException.class,
                () -> new JxlChannels.Uint8(WIDTH, HEIGHT, 1, java.util.Arrays.asList((byte[]) null), none));

        assertThrows(IllegalStateException.class, () -> JxlChannels.builder(WIDTH, HEIGHT).add("a", plane).build());
        assertThrows(IllegalStateException.class, () -> JxlChannels.builder(WIDTH, HEIGHT).gray(plane).gray(plane));
        assertThrows(IllegalArgumentException.class,
                () -> JxlChannels.builder(WIDTH, HEIGHT).gray(plane).add("a", new short[SAMPLES]));

        assertThrows(IllegalArgumentException.class, () -> JxlExtraChannel.of("a\0b"));
        assertThrows(IllegalArgumentException.class, () -> JxlExtraChannel.of("x".repeat(1072)));
        assertEquals(1071, JxlExtraChannel.of("x".repeat(1071)).name().length());
    }

    @Test
    void extraChannelTypesWithoutTheirInformationCannotBeWritten() {
        for (JxlChannelType type : List.of(JxlChannelType.SPOT_COLOR, JxlChannelType.BLACK, JxlChannelType.CFA)) {
            JxlChannels image = JxlChannels.builder(WIDTH, HEIGHT).gray(uint8Plane(0))
                    .add(new JxlExtraChannel(type, ""), uint8Plane(1)).build();
            assertThrows(IllegalArgumentException.class,
                    () -> JxlEncoder.encode(image, JxlEncodeOptions.ofLossless()), type.name());
        }
    }

    @Test
    void losslessRoundTripOfGrayWithNamedExtraChannelsIsBitExact() throws IOException {
        String chinese = new String(new int[] {0x8367, 0x5149}, 0, 2);
        List<JxlChannels> images = List.of(
                JxlChannels.builder(WIDTH, HEIGHT).gray(uint8Plane(0)).add("GFP", uint8Plane(1))
                        .add(chinese, uint8Plane(2)).build(),
                JxlChannels.builder(WIDTH, HEIGHT).gray(uint16Plane(0)).add("GFP", uint16Plane(1))
                        .add(chinese, uint16Plane(2)).build(),
                JxlChannels.builder(WIDTH, HEIGHT).gray(float32Plane(0)).add("GFP", float32Plane(1))
                        .add(chinese, float32Plane(2)).build());
        for (JxlChannels image : images) {
            JxlSampleType type = image.sampleType();
            byte[] data = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless());
            save("channels-gray-" + type.name().toLowerCase() + ".jxl", data);

            assertEquals(List.of(new JxlExtraChannelInfo(JxlChannelType.OPTIONAL, "GFP", type.bits(), type.exponentBits()),
                    new JxlExtraChannelInfo(JxlChannelType.OPTIONAL, chinese, type.bits(), type.exponentBits())),
                    JxlDecoder.readExtraChannels(data), type.name());
            JxlImageInfo info = JxlDecoder.readInfo(data);
            assertEquals(1, info.colorChannels());
            assertEquals(type, info.sampleType());

            JxlChannels decoded = JxlDecoder.decodeChannels(data, type);
            assertSameChannels(image, decoded);
            // An ordinary decoder sees the gray channel.
            assertSamePlane(planes(image).get(0), interleaved(JxlDecoder.decode(data, 1, type)), type.name());
        }
    }

    @Test
    void losslessRoundTripOfRgbWithManyExtraChannelsIsBitExact() {
        // More than 4 extra channels need codestream level 10.
        for (int extra : new int[] {0, 4, 7}) {
            JxlChannels.Builder builder = JxlChannels.builder(WIDTH, HEIGHT)
                    .rgb(uint16Plane(0), uint16Plane(1), uint16Plane(2));
            for (int i = 0; i < extra; i++) {
                builder.add("channel " + i, uint16Plane(3 + i));
            }
            JxlChannels image = builder.build();

            byte[] data = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless().withEffort(3));
            save("channels-rgb-" + extra + ".jxl", data);

            assertEquals(extra, JxlDecoder.readExtraChannels(data).size());
            assertSameChannels(image, JxlDecoder.decodeChannels(data, JxlSampleType.UINT16));
        }
    }

    @Test
    void decodedChannelsMatchTheInterleavedImage() {
        JxlImage.Uint8 rgba = TestImages.gradientRgba();
        byte[] data = JxlEncoder.encode(rgba, JxlEncodeOptions.ofLossless());

        JxlChannels channels = JxlDecoder.decodeChannels(data, JxlSampleType.UINT8);

        assertEquals(3, channels.colorChannels());
        assertEquals(List.of(JxlExtraChannel.alpha()), channels.extraChannels());
        assertEquals(List.of(new JxlExtraChannelInfo(JxlChannelType.ALPHA, "", 8, 0)),
                JxlDecoder.readExtraChannels(data));
        assertArrayEquals(rgba.pixels(), (byte[]) interleaved(channels));

        // And the other way round: separate channels with alpha give the same interleaved image.
        byte[] again = JxlEncoder.encode(channels, JxlEncodeOptions.ofLossless());
        assertArrayEquals(rgba.pixels(), JxlDecoder.decode(again).pixels());
    }

    @Test
    void encodesWithTheDefaultOptions() {
        JxlChannels channels = JxlDecoder.decodeChannels(TestImages.gradientJxl(), JxlSampleType.UINT8);

        byte[] data = JxlEncoder.encode(channels);

        assertArrayEquals(JxlEncoder.encode(channels, JxlEncodeOptions.defaults()), data);
        assertEquals(TestImages.WIDTH, JxlDecoder.readInfo(data).width());
    }

    @Test
    void namedAlphaChannelAndAlphaAfterOtherChannelsRoundTrip() {
        JxlChannels namedAlpha = JxlChannels.builder(WIDTH, HEIGHT).gray(uint8Plane(0))
                .add(new JxlExtraChannel(JxlChannelType.ALPHA, "mask"), uint8Plane(1)).build();
        byte[] data = JxlEncoder.encode(namedAlpha, JxlEncodeOptions.ofLossless());
        assertSameChannels(namedAlpha, JxlDecoder.decodeChannels(data, JxlSampleType.UINT8));
        assertTrue(JxlDecoder.readInfo(data).hasAlpha());

        JxlChannels lateAlpha = JxlChannels.builder(WIDTH, HEIGHT).gray(uint8Plane(0))
                .add(new JxlExtraChannel(JxlChannelType.DEPTH, "depth"), uint8Plane(1))
                .add(JxlExtraChannel.alpha(), uint8Plane(2)).build();
        data = JxlEncoder.encode(lateAlpha, JxlEncodeOptions.ofLossless());
        assertSameChannels(lateAlpha, JxlDecoder.decodeChannels(data, JxlSampleType.UINT8));
    }

    @Test
    void lossyEncodingKeepsChannelsClose() {
        JxlChannels image = JxlChannels.builder(WIDTH * 4, HEIGHT * 4).gray(smoothPlane(0)).add("a", smoothPlane(1))
                .add("b", smoothPlane(2)).build();

        byte[] data = JxlEncoder.encode(image, JxlEncodeOptions.ofDistance(1.0f));
        JxlChannels decoded = JxlDecoder.decodeChannels(data, JxlSampleType.FLOAT32);

        assertEquals(image.extraChannels(), decoded.extraChannels());
        for (int c = 0; c < 3; c++) {
            float[] expected = ((JxlChannels.Float32) image).planes().get(c);
            float[] actual = ((JxlChannels.Float32) decoded).planes().get(c);
            double error = 0;
            for (int i = 0; i < expected.length; i++) {
                error = Math.max(error, Math.abs(expected[i] - actual[i]));
            }
            assertTrue(error < 0.1, "channel " + c + ": maximum error " + error);
        }
    }

    @Test
    void decodingToAWiderTypeKeepsTheValues() {
        JxlChannels image = JxlChannels.builder(WIDTH, HEIGHT).gray(uint8Plane(0)).add("a", uint8Plane(1)).build();
        byte[] data = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless());

        JxlChannels.Uint16 wide = (JxlChannels.Uint16) JxlDecoder.decodeChannels(data, JxlSampleType.UINT16);

        assertEquals(8, wide.bitsPerSample());
        for (int c = 0; c < 2; c++) {
            byte[] expected = ((JxlChannels.Uint8) image).planes().get(c);
            for (int i = 0; i < SAMPLES; i++) {
                assertEquals(expected[i] & 0xFF, Short.toUnsignedInt(wide.planes().get(c)[i]),
                        "channel " + c + ", sample " + i);
            }
        }
    }

    @Test
    void decodingToANarrowerTypeScalesEveryChannel() {
        JxlChannels image = JxlChannels.builder(WIDTH, HEIGHT).gray(uint16Plane(0)).add("a", uint16Plane(1)).build();
        byte[] data = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless());

        JxlChannels.Uint8 narrow = (JxlChannels.Uint8) JxlDecoder.decodeChannels(data, JxlSampleType.UINT8);

        assertEquals(8, narrow.bitsPerSample());
        for (int c = 0; c < 2; c++) {
            short[] expected = ((JxlChannels.Uint16) image).planes().get(c);
            for (int i = 0; i < SAMPLES; i++) {
                double scaled = Short.toUnsignedInt(expected[i]) * 255.0 / 65535.0;
                assertEquals(scaled, narrow.planes().get(c)[i] & 0xFF, 1.0, "channel " + c + ", sample " + i);
            }
        }
    }

    @Test
    void twelveBitSamplesKeepTheirValues() throws IOException {
        JxlChannels image = JxlChannels.builder(WIDTH, HEIGHT).bitsPerSample(12).gray(twelveBitPlane(0))
                .add("GFP", twelveBitPlane(1)).build();
        assertEquals(12, image.bitsPerSample());

        byte[] data = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless());
        save("channels-12bit.jxl", data);

        assertEquals(12, JxlDecoder.readInfo(data).bitsPerSample());
        assertEquals(JxlSampleType.UINT16, JxlDecoder.readInfo(data).sampleType());
        assertEquals(List.of(new JxlExtraChannelInfo(JxlChannelType.OPTIONAL, "GFP", 12, 0)),
                JxlDecoder.readExtraChannels(data));
        assertSameChannels(image, JxlDecoder.decodeChannels(data, JxlSampleType.UINT16));
        // A JxlImage uses the full range of its type: 4095 becomes 65535.
        short[] gray = ((JxlImage.Uint16) JxlDecoder.decode(data, 1, JxlSampleType.UINT16)).pixels();
        short[] expected = ((JxlChannels.Uint16) image).planes().get(0);
        for (int i = 0; i < SAMPLES; i++) {
            assertEquals(Short.toUnsignedInt(expected[i]) * 65535.0 / 4095.0, Short.toUnsignedInt(gray[i]), 0.51,
                    "sample " + i);
        }
        // Floating point samples are always scaled.
        JxlChannels.Float32 floats = (JxlChannels.Float32) JxlDecoder.decodeChannels(data, JxlSampleType.FLOAT32);
        assertEquals(32, floats.bitsPerSample());
        assertEquals(Short.toUnsignedInt(expected[5]) / 4095.0, floats.planes().get(0)[5], 1e-6);
    }

    @Test
    void twelveBitStackRoundTrips() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<JxlChannels> slices = new ArrayList<>();
        for (int z = 0; z < 3; z++) {
            slices.add(JxlChannels.builder(WIDTH, HEIGHT).bitsPerSample(12).gray(twelveBitPlane(z * 2))
                    .add("a", twelveBitPlane(z * 2 + 1)).build());
        }
        try (JxlFrameEncoder frames = JxlFrameEncoder.open(out, new JxlAnimationHeader(1, 1, 0),
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE)) {
            for (JxlChannels slice : slices) {
                frames.add(slice, 1);
            }
            assertThrows(IllegalArgumentException.class, () -> frames.add(JxlChannels.builder(WIDTH, HEIGHT)
                    .gray(twelveBitPlane(0)).add("a", twelveBitPlane(1)).build(), 1));
            frames.finish();
        }
        try (JxlFrameDecoder frames = JxlFrameDecoder.openChannels(out.toByteArray(), JxlSampleType.UINT16)) {
            for (JxlChannels slice : slices) {
                assertSameChannels(slice, frames.nextChannels().channels());
            }
            assertNull(frames.nextChannels());
        }
    }

    @Test
    void invalidBitsPerSampleAreRejected() {
        List<JxlExtraChannel> none = List.of();
        assertThrows(IllegalArgumentException.class,
                () -> new JxlChannels.Uint8(WIDTH, HEIGHT, 1, List.of(new byte[SAMPLES]), none, 9, null));
        assertThrows(IllegalArgumentException.class,
                () -> new JxlChannels.Uint16(WIDTH, HEIGHT, 1, List.of(new short[SAMPLES]), none, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new JxlChannels.Uint16(WIDTH, HEIGHT, 1, List.of(new short[SAMPLES]), none, 17, null));
        assertThrows(IllegalArgumentException.class,
                () -> new JxlChannels.Float32(WIDTH, HEIGHT, 1, List.of(new float[SAMPLES]), none, 16, null));
        assertThrows(IllegalArgumentException.class,
                () -> JxlChannels.builder(WIDTH, HEIGHT).bitsPerSample(12).gray(new byte[SAMPLES]).build());
        assertEquals(1, new JxlChannels.Uint8(WIDTH, HEIGHT, 1, List.of(new byte[SAMPLES]), none, 1, null)
                .bitsPerSample());
        assertEquals(32, JxlChannels.builder(WIDTH, HEIGHT).gray(new float[SAMPLES]).build().bitsPerSample());
    }

    @Test
    void streamsAndChannelsReceiveTheSameFile() throws IOException {
        JxlChannels image = JxlChannels.builder(WIDTH, HEIGHT).gray(uint8Plane(0)).add("a", uint8Plane(1)).build();
        JxlMetadata metadata = new JxlMetadata(null, "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".getBytes(
                StandardCharsets.UTF_8));
        byte[] expected = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless(), metadata);

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        assertEquals(expected.length, JxlEncoder.encode(image, JxlEncodeOptions.ofLossless(), metadata, stream));
        ByteArrayOutputStream channel = new ByteArrayOutputStream();
        assertEquals(expected.length, JxlEncoder.encode(image, JxlEncodeOptions.ofLossless(), metadata,
                Channels.newChannel(channel)));

        assertArrayEquals(expected, stream.toByteArray());
        assertArrayEquals(expected, channel.toByteArray());
        assertArrayEquals(metadata.xmp(), JxlDecoder.readMetadata(expected).xmp());
        assertSameChannels(image, JxlDecoder.decodeChannels(expected, JxlSampleType.UINT8));
    }

    @Test
    void extraChannelsCountTowardsThePixelLimit() {
        JxlChannels.Builder builder = JxlChannels.builder(WIDTH, HEIGHT).gray(uint8Plane(0));
        for (int i = 0; i < 4; i++) {
            builder.add("c" + i, uint8Plane(i + 1));
        }
        byte[] data = JxlEncoder.encode(builder.build(), JxlEncodeOptions.ofLossless());

        // 5 channels count twice.
        JxlDecodeOptions tooSmall = TestImages.maxPixels(2L * SAMPLES - 1);
        assertThrows(JxlLimitException.class, () -> JxlDecoder.decodeChannels(data, JxlSampleType.UINT8, tooSmall));
        assertEquals(5, JxlDecoder.decodeChannels(data, JxlSampleType.UINT8,
                TestImages.maxPixels(2L * SAMPLES)).channels());
    }

    @Test
    void imagesWithoutExtraChannelsHaveNone() {
        byte[] data = JxlEncoder.encode(TestImages.gradientChannels(1), JxlEncodeOptions.ofLossless());

        assertEquals(List.of(), JxlDecoder.readExtraChannels(data));
        JxlChannels decoded = JxlDecoder.decodeChannels(data, JxlSampleType.UINT8);
        assertEquals(1, decoded.channels());
        assertNull(decoded.iccProfile());
    }

    @Test
    void invalidDataIsRejected() {
        assertThrows(JxlException.class, () -> JxlDecoder.readExtraChannels(new byte[] {1, 2, 3}));
        assertThrows(JxlException.class, () -> JxlDecoder.decodeChannels(new byte[] {1, 2, 3}, JxlSampleType.UINT8));
    }

    @Test
    void stackOfFramesRoundTripsBitExact() throws IOException {
        // A stack of 5 slices with 3 channels each, also with more than 4 extra channels (codestream level 10).
        for (int extra : new int[] {2, 6}) {
            List<JxlChannels> slices = new ArrayList<>();
            for (int z = 0; z < 5; z++) {
                JxlChannels.Builder builder = JxlChannels.builder(WIDTH, HEIGHT).gray(uint16Plane(z * 10));
                for (int i = 0; i < extra; i++) {
                    builder.add("channel " + i, uint16Plane(z * 10 + 1 + i));
                }
                slices.add(builder.build());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (JxlFrameEncoder frames = JxlFrameEncoder.open(out, new JxlAnimationHeader(1, 1, 0),
                    JxlEncodeOptions.ofLossless().withEffort(3), JxlMetadata.NONE)) {
                for (int z = 0; z < slices.size(); z++) {
                    frames.add(slices.get(z), 1, "z" + z);
                }
                frames.finish();
            }
            byte[] data = out.toByteArray();
            save("channels-stack-" + extra + ".jxl", data);

            assertEquals(5, JxlDecoder.readAnimationInfo(data).frames().size());
            try (JxlFrameDecoder frames = JxlFrameDecoder.openChannels(data, JxlSampleType.UINT16)) {
                for (int z = 0; z < slices.size(); z++) {
                    JxlChannelsFrame frame = frames.nextChannels();
                    assertEquals("z" + z, frame.info().name());
                    assertEquals(1, frame.info().durationTicks());
                    assertSameChannels(slices.get(z), frame.channels());
                }
                assertNull(frames.nextChannels());
                assertEquals(5, frames.nextIndex());
            }
            // An ordinary decoder sees the gray channel of every slice.
            List<JxlFrame> gray = JxlDecoder.decodeFrames(data, 1, JxlSampleType.UINT16);
            for (int z = 0; z < slices.size(); z++) {
                assertSamePlane(planes(slices.get(z)).get(0), interleaved(gray.get(z).image()), "slice " + z);
            }
        }
    }

    @Test
    void decodedChannelFramesCanBeEncodedAgain() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JxlFrameEncoder frames = JxlFrameEncoder.open(out, new JxlAnimationHeader(10, 1, 0),
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE)) {
            frames.add(JxlChannels.builder(WIDTH, HEIGHT).rgb(uint8Plane(0), uint8Plane(1), uint8Plane(2))
                    .add(JxlExtraChannel.alpha(), uint8Plane(3)).add("depth", uint8Plane(4)).build(), 3);
            frames.add(JxlChannels.builder(WIDTH, HEIGHT).rgb(uint8Plane(5), uint8Plane(6), uint8Plane(7))
                    .add(JxlExtraChannel.alpha(), uint8Plane(8)).add("depth", uint8Plane(9)).build(), 4, "last");
            frames.finish();
        }
        byte[] data = out.toByteArray();

        List<JxlChannelsFrame> decoded = new ArrayList<>();
        try (JxlFrameDecoder frames = JxlFrameDecoder.openChannels(data, JxlSampleType.UINT8)) {
            for (JxlChannelsFrame frame = frames.nextChannels(); frame != null; frame = frames.nextChannels()) {
                decoded.add(frame);
            }
        }
        ByteArrayOutputStream again = new ByteArrayOutputStream();
        try (JxlFrameEncoder frames = JxlFrameEncoder.open(again, new JxlAnimationHeader(10, 1, 0),
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE)) {
            for (JxlChannelsFrame frame : decoded) {
                frames.add(frame);
            }
            frames.finish();
        }

        try (JxlFrameDecoder frames = JxlFrameDecoder.openChannels(again.toByteArray(), JxlSampleType.UINT8)) {
            for (JxlChannelsFrame expected : decoded) {
                JxlChannelsFrame actual = frames.nextChannels();
                assertEquals(expected.info(), actual.info());
                assertSameChannels(expected.channels(), actual.channels());
            }
            assertNull(frames.nextChannels());
        }
        assertEquals(List.of(new JxlFrameInfo(3, 300, ""), new JxlFrameInfo(4, 400, "last")),
                decoded.stream().map(JxlChannelsFrame::info).toList());
    }

    @Test
    void framesMustMatchTheFirstFrame() throws IOException {
        JxlChannels first = JxlChannels.builder(WIDTH, HEIGHT).gray(uint8Plane(0)).add("a", uint8Plane(1)).build();
        try (JxlFrameEncoder frames = JxlFrameEncoder.open(new ByteArrayOutputStream(),
                new JxlAnimationHeader(10, 1, 0), JxlEncodeOptions.ofLossless(), JxlMetadata.NONE)) {
            frames.add(first, 1);
            assertThrows(IllegalArgumentException.class, () -> frames.add(
                    JxlChannels.builder(WIDTH, HEIGHT).gray(uint8Plane(0)).add("b", uint8Plane(1)).build(), 1));
            assertThrows(IllegalArgumentException.class, () -> frames.add(
                    JxlChannels.builder(WIDTH, HEIGHT).gray(uint8Plane(0)).build(), 1));
            assertThrows(IllegalArgumentException.class, () -> frames.add(
                    JxlChannels.builder(WIDTH, HEIGHT).gray(uint16Plane(0)).add("a", uint16Plane(1)).build(), 1));
            assertThrows(IllegalArgumentException.class,
                    () -> frames.add(new JxlImage.Uint8(WIDTH, HEIGHT, 2, new byte[SAMPLES * 2]), 1));
            assertThrows(NullPointerException.class, () -> frames.add((JxlChannels) null, 1));
            assertThrows(NullPointerException.class, () -> frames.add((JxlChannelsFrame) null));
            // The invalid frames leave the encoder usable.
            frames.add(first, 1);
            frames.finish();
        }
        try (JxlFrameEncoder frames = JxlFrameEncoder.open(new ByteArrayOutputStream(),
                new JxlAnimationHeader(10, 1, 0), JxlEncodeOptions.ofLossless(), JxlMetadata.NONE)) {
            frames.add(TestImages.gradientRgba(), 1);
            assertThrows(IllegalArgumentException.class, () -> frames.add(first, 1));
            assertThrows(IllegalArgumentException.class, () -> frames.add(JxlChannels.builder(WIDTH, HEIGHT)
                    .gray(uint8Plane(0)).add(new JxlExtraChannel(JxlChannelType.BLACK, ""), uint8Plane(1)).build(), 1));
        }
        try (JxlFrameEncoder frames = JxlFrameEncoder.open(new ByteArrayOutputStream(),
                new JxlAnimationHeader(10, 1, 0), JxlEncodeOptions.ofLossless(), JxlMetadata.NONE)) {
            assertThrows(IllegalArgumentException.class, () -> frames.add(JxlChannels.builder(WIDTH, HEIGHT)
                    .gray(uint8Plane(0)).add(new JxlExtraChannel(JxlChannelType.BLACK, ""), uint8Plane(1)).build(), 1));
        }
    }

    @Test
    void frameDecoderModesAreSeparate() {
        byte[] data = JxlEncoder.encode(TestImages.gradientRgba(), JxlEncodeOptions.ofLossless());

        try (JxlFrameDecoder frames = JxlFrameDecoder.openChannels(data, JxlSampleType.UINT8)) {
            assertThrows(IllegalStateException.class, frames::next);
            assertEquals(4, frames.nextChannels().channels().channels());
        }
        try (JxlFrameDecoder frames = JxlFrameDecoder.open(data, 4, JxlSampleType.UINT8)) {
            assertThrows(IllegalStateException.class, frames::nextChannels);
        }
        assertThrows(IllegalArgumentException.class, () -> JxlFrameDecoder.open(data, 0, JxlSampleType.UINT8));
        assertThrows(IllegalArgumentException.class, () -> JxlDecoder.decodeFrames(data, 0, JxlSampleType.UINT8));
    }

    @Test
    void decodedChannelNamesAreValid() {
        assertEquals("a\uFFFDb", JxlDecoder.channelName(new byte[] {'a', 0, 'b'}));
        byte[] invalid = new byte[JxlExtraChannel.MAX_NAME_BYTES];
        java.util.Arrays.fill(invalid, (byte) 0xFF);
        String name = JxlDecoder.channelName(invalid);
        assertTrue(name.getBytes(StandardCharsets.UTF_8).length <= JxlExtraChannel.MAX_NAME_BYTES);
        JxlExtraChannel.of(name);
    }

    private static JxlChannels.Uint16 assertInstanceOfUint16(JxlChannels image) {
        assertTrue(image instanceof JxlChannels.Uint16, image.getClass().getName());
        return (JxlChannels.Uint16) image;
    }

    private static void assertSameChannels(JxlChannels expected, JxlChannels actual) {
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.height(), actual.height());
        assertEquals(expected.sampleType(), actual.sampleType());
        assertEquals(expected.colorChannels(), actual.colorChannels());
        assertEquals(expected.bitsPerSample(), actual.bitsPerSample());
        assertEquals(expected.extraChannels(), actual.extraChannels());
        List<Object> expectedPlanes = planes(expected);
        List<Object> actualPlanes = planes(actual);
        for (int c = 0; c < expectedPlanes.size(); c++) {
            assertSamePlane(expectedPlanes.get(c), actualPlanes.get(c), "plane " + c);
        }
    }

    private static void assertSamePlane(Object expected, Object actual, String message) {
        switch (expected) {
            case byte[] bytes -> assertArrayEquals(bytes, (byte[]) actual, message);
            case short[] shorts -> assertArrayEquals(shorts, (short[]) actual, message);
            case float[] floats -> assertArrayEquals(floats, (float[]) actual, message);
            default -> throw new AssertionError(expected);
        }
    }

    private static List<Object> planes(JxlChannels image) {
        return switch (image) {
            case JxlChannels.Uint8 img -> new ArrayList<>(img.planes());
            case JxlChannels.Uint16 img -> new ArrayList<>(img.planes());
            case JxlChannels.Float32 img -> new ArrayList<>(img.planes());
        };
    }

    private static Object interleaved(JxlImage image) {
        return switch (image) {
            case JxlImage.Uint8 img -> img.pixels();
            case JxlImage.Uint16 img -> img.pixels();
            case JxlImage.Float32 img -> img.pixels();
        };
    }

    /** Interleaves the planes of an 8-bit image. */
    private static Object interleaved(JxlChannels image) {
        List<byte[]> planes = ((JxlChannels.Uint8) image).planes();
        byte[] pixels = new byte[image.width() * image.height() * planes.size()];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = planes.get(i % planes.size())[i / planes.size()];
        }
        return pixels;
    }

    private static byte[] uint8Plane(int seed) {
        byte[] plane = new byte[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            plane[i] = (byte) (i * (seed + 3) + seed * 41);
        }
        return plane;
    }

    private static short[] uint16Plane(int seed) {
        short[] plane = new short[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            plane[i] = (short) (i * (977 + seed) + seed * 13_001);
        }
        return plane;
    }

    private static short[] twelveBitPlane(int seed) {
        short[] plane = new short[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            plane[i] = (short) ((i * (977 + seed) + seed * 13_001) & 0xFFF);
        }
        return plane;
    }

    private static float[] float32Plane(int seed) {
        float[] plane = new float[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            // Includes values outside 0.0 to 1.0, which lossless encoding keeps.
            plane[i] = (i % 37) / 36.0f + seed * 0.25f - 0.125f;
        }
        return plane;
    }

    /** A smooth plane of the size of the lossy test, which compresses well. */
    private static float[] smoothPlane(int seed) {
        int width = WIDTH * 4;
        int height = HEIGHT * 4;
        float[] plane = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                plane[y * width + x] = 0.5f + 0.4f * (float) Math.sin((x + seed * 7) / 9.0 + y / 13.0);
            }
        }
        return plane;
    }

    private static void save(String name, byte[] data) {
        try {
            Files.createDirectories(OUTPUT_DIR);
            Files.write(OUTPUT_DIR.resolve(name), data);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}

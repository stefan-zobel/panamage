package panamage.jxl;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import panamage.jxl.ffi.Jxl;
import panamage.jxl.ffi.JxlAnimationHeader;
import panamage.jxl.ffi.JxlBasicInfo;
import panamage.jxl.ffi.JxlColorEncoding;
import panamage.jxl.ffi.JxlFrameHeader;
import panamage.jxl.ffi.JxlLayerInfo;
import panamage.jxl.ffi.JxlPixelFormat;

class AnimationTest {

    /** The width and height of the animations built by {@link #encode}. */
    private static final int SIZE = 16;

    private static final int RED = 0xFF0000;
    private static final int GREEN = 0x00FF00;
    private static final int BLUE = 0x0000FF;

    private static final int FRAME_PIXELS = TestImages.ANIMATION_WIDTH * TestImages.ANIMATION_HEIGHT;

    @Test
    void readsTheFramesOfAnAnimation() {
        JxlAnimationInfo animation = JxlDecoder.readAnimationInfo(TestImages.animationJxl());

        assertEquals(3, animation.frameCount());
        assertEquals(0, animation.loops());
        // cjxl takes the APNG delays in milliseconds as ticks.
        assertEquals(1000, animation.ticksPerSecondNumerator());
        assertEquals(1, animation.ticksPerSecondDenominator());
        for (int i = 0; i < 3; i++) {
            double millis = TestImages.ANIMATION_DURATIONS[i];
            assertEquals(new JxlFrameInfo((long) millis, millis, ""), animation.frames().get(i));
        }
    }

    @Test
    void readsTicksNamesAndLoops() {
        byte[] data = encode(10, 1, 3, full(2, "first", RED), full(5, "", GREEN), full(1, "third", BLUE));

        JxlAnimationInfo animation = JxlDecoder.readAnimationInfo(data);

        assertEquals(new JxlAnimationInfo(10, 1, 3, List.of(new JxlFrameInfo(2, 200, "first"),
                new JxlFrameInfo(5, 500, ""), new JxlFrameInfo(1, 100, "third"))), animation);
        List<JxlFrame> frames = JxlDecoder.decodeFrames(data, 3, JxlSampleType.UINT8);
        assertEquals(animation.frames(), frames.stream().map(JxlFrame::info).toList());
    }

    @Test
    void describesAStillImageAsOneFrame() {
        byte[] data = TestImages.gradientJxl();

        assertEquals(new JxlAnimationInfo(0, 0, 0, List.of(new JxlFrameInfo(0, 0, ""))),
                JxlDecoder.readAnimationInfo(data));
        List<JxlFrame> frames = JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8);
        assertEquals(1, frames.size());
        assertArrayEquals(TestImages.gradientRgbaPixels(), ((JxlImage.Uint8) frames.get(0).image()).pixels());
    }

    @Test
    void decodesAllFrames() {
        byte[] data = TestImages.animationJxl();

        List<JxlFrame> frames = JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8);

        assertEquals(3, frames.size());
        for (int i = 0; i < 3; i++) {
            JxlImage.Uint8 image = (JxlImage.Uint8) frames.get(i).image();
            assertEquals(TestImages.ANIMATION_WIDTH, image.width());
            assertEquals(TestImages.ANIMATION_HEIGHT, image.height());
            assertArrayEquals(TestImages.animationRgbaPixels(i), image.pixels(), "frame " + i);
            assertEquals(TestImages.ANIMATION_DURATIONS[i], frames.get(i).info().durationMillis());
        }
    }

    @Test
    void decodesAllFramesWithHigherPrecision() {
        byte[] data = TestImages.animationJxl();

        List<JxlFrame> shorts = JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT16);
        List<JxlFrame> floats = JxlDecoder.decodeFrames(data, 4, JxlSampleType.FLOAT32);

        for (int i = 0; i < 3; i++) {
            byte[] expected = TestImages.animationRgbaPixels(i);
            short[] actualShorts = ((JxlImage.Uint16) shorts.get(i).image()).pixels();
            float[] actualFloats = ((JxlImage.Float32) floats.get(i).image()).pixels();
            for (int s = 0; s < expected.length; s++) {
                int value = expected[s] & 0xFF;
                assertEquals(value * 257, actualShorts[s] & 0xFFFF, "frame " + i + ", sample " + s);
                assertEquals(value / 255f, actualFloats[s], 1e-5f, "frame " + i + ", sample " + s);
            }
        }
    }

    @Test
    void decodeReturnsTheFirstFrame() {
        assertArrayEquals(TestImages.animationRgbaPixels(0), JxlDecoder.decode(TestImages.animationJxl()).pixels());
    }

    @Test
    void decodesOneFrameAtATime() {
        try (JxlFrameDecoder frames = JxlFrameDecoder.open(TestImages.animationJxl(), 4, JxlSampleType.UINT8)) {
            for (int i = 0; i < 3; i++) {
                assertEquals(i, frames.nextIndex());
                JxlFrame frame = frames.next();
                assertArrayEquals(TestImages.animationRgbaPixels(i), ((JxlImage.Uint8) frame.image()).pixels(),
                        "frame " + i);
                assertEquals(TestImages.ANIMATION_DURATIONS[i], frame.info().durationMillis());
            }
            assertEquals(3, frames.nextIndex());
            assertNull(frames.next());
            assertNull(frames.next());
        }
    }

    @Test
    void skipsFrames() {
        byte[] data = TestImages.animationJxl();
        try (JxlFrameDecoder frames = JxlFrameDecoder.open(data, 4, JxlSampleType.UINT8)) {
            frames.skip(0);
            frames.skip(1);
            assertEquals(1, frames.nextIndex());
            assertArrayEquals(TestImages.animationRgbaPixels(1), pixels(frames.next()));
            assertEquals(2, frames.nextIndex());
            frames.skip(5);
            assertNull(frames.next());
            assertThrows(IllegalArgumentException.class, () -> frames.skip(-1));
        }
        try (JxlFrameDecoder frames = JxlFrameDecoder.open(data, 4, JxlSampleType.UINT8)) {
            assertArrayEquals(TestImages.animationRgbaPixels(0), pixels(frames.next()));
            frames.skip(1);
            assertArrayEquals(TestImages.animationRgbaPixels(2), pixels(frames.next()));
        }
    }

    @Test
    void rejectsUseAfterClose() {
        JxlFrameDecoder frames = JxlFrameDecoder.open(TestImages.animationJxl(), 4, JxlSampleType.UINT8);
        frames.close();
        frames.close();
        assertThrows(IllegalStateException.class, frames::next);
        assertThrows(IllegalStateException.class, () -> frames.skip(1));
    }

    @Test
    void combinesFramesWithoutDurationWithTheFollowingFrame() {
        // A red layer without duration, a green patch shown for 5 ticks, then a blue frame.
        byte[] data = encode(10, 1, 0, full(0, "", RED), new Layer(2, 3, 4, 4, 5, "patch", GREEN),
                full(7, "", BLUE));

        assertEquals(List.of(new JxlFrameInfo(5, 500, "patch"), new JxlFrameInfo(7, 700, "")),
                JxlDecoder.readAnimationInfo(data).frames());
        List<JxlFrame> frames = JxlDecoder.decodeFrames(data, 3, JxlSampleType.UINT8);
        assertEquals(2, frames.size());
        byte[] first = pixels(frames.get(0));
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                boolean patch = x >= 2 && x < 6 && y >= 3 && y < 7;
                assertArrayEquals(rgb(patch ? GREEN : RED), Arrays.copyOfRange(first, (y * SIZE + x) * 3,
                        (y * SIZE + x) * 3 + 3), "pixel " + x + "," + y);
            }
        }
        try (JxlFrameDecoder decoder = JxlFrameDecoder.open(data, 3, JxlSampleType.UINT8)) {
            decoder.skip(1);
            assertArrayEquals(rgb(BLUE), Arrays.copyOf(pixels(decoder.next()), 3));
        }
    }

    @Test
    void appliesThePixelLimitToAllFramesTogether() {
        byte[] data = TestImages.animationJxl();
        JxlLimitException e = assertThrows(JxlLimitException.class, () -> JxlDecoder.decodeFrames(data, 4,
                JxlSampleType.UINT8, JxlLimits.defaults().withMaxPixels(3 * FRAME_PIXELS - 1)));
        assertEquals("Animation of 3 frames of 16 x 12 pixels exceeds the limit of 575 pixels", e.getMessage());
        assertEquals(3, JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8,
                JxlLimits.defaults().withMaxPixels(3 * FRAME_PIXELS)).size());

        // The frame decoder holds one frame at a time.
        try (JxlFrameDecoder frames = JxlFrameDecoder.open(data, 4, JxlSampleType.UINT8,
                JxlLimits.defaults().withMaxPixels(FRAME_PIXELS))) {
            frames.skip(2);
            assertArrayEquals(TestImages.animationRgbaPixels(2), pixels(frames.next()));
        }
        assertThrows(JxlLimitException.class, () -> JxlFrameDecoder.open(data, 4, JxlSampleType.UINT8,
                JxlLimits.defaults().withMaxPixels(FRAME_PIXELS - 1)));
    }

    @Test
    void checksTheLayersOfAllFrames() {
        byte[] data = encode(10, 1, 0, full(1, "", RED), full(1, "", GREEN), new Layer(0, 0, 1024, 1024, 1, "", BLUE));
        JxlLimits limits = JxlLimits.defaults().withMaxPixels(100_000);

        JxlLimitException e = assertThrows(JxlLimitException.class,
                () -> JxlDecoder.decodeFrames(data, 3, JxlSampleType.UINT8, limits));
        assertEquals("Frame layer of 1024 x 1024 pixels exceeds the limit of 100000 pixels", e.getMessage());
        assertThrows(JxlLimitException.class, () -> JxlFrameDecoder.open(data, 3, JxlSampleType.UINT8, limits));
        // The first frame alone is within the limit.
        assertEquals(SIZE, JxlDecoder.decode(data, 3, JxlSampleType.UINT8, limits).width());
        assertEquals(3, JxlDecoder.decodeFrames(data, 3, JxlSampleType.UINT8).size());
        assertEquals(3, JxlDecoder.decodeFrames(data, 3, JxlSampleType.UINT8, JxlLimits.unlimited()).size());
    }

    @Test
    void reportsInvalidData() {
        byte[] data = TestImages.animationJxl();
        byte[] truncated = Arrays.copyOf(data, data.length / 2);
        assertThrows(JxlException.class, () -> JxlDecoder.readAnimationInfo(truncated));
        assertThrows(JxlException.class, () -> JxlDecoder.decodeFrames(truncated, 4, JxlSampleType.UINT8));
        assertThrows(JxlException.class, () -> JxlDecoder.readAnimationInfo(new byte[] {1, 2, 3}));
        assertThrows(JxlException.class, () -> JxlFrameDecoder.open(new byte[] {1, 2, 3}, 4, JxlSampleType.UINT8));
        assertThrows(IllegalArgumentException.class, () -> JxlFrameDecoder.open(data, 5, JxlSampleType.UINT8));
    }

    private static byte[] pixels(JxlFrame frame) {
        return ((JxlImage.Uint8) frame.image()).pixels();
    }

    private static byte[] rgb(int color) {
        return new byte[] {(byte) (color >> 16), (byte) (color >> 8), (byte) color};
    }

    /** A layer of an animation built by {@link #encode}, filled with one color. */
    private record Layer(int x0, int y0, int width, int height, int duration, String name, int color) {
    }

    private static Layer full(int duration, String name, int color) {
        return new Layer(0, 0, SIZE, SIZE, duration, name, color);
    }

    /**
     * Encodes a lossless sRGB animation of {@value #SIZE} x {@value #SIZE}
     * pixels with the given tick rate, loop count and layers.
     */
    private static byte[] encode(int tpsNumerator, int tpsDenominator, int loops, Layer... layers) {
        try (Arena arena = Arena.ofConfined(); NativeEncoder encoder = NativeEncoder.create()) {
            MemorySegment handle = encoder.handle();
            MemorySegment info = arena.allocate(JxlBasicInfo.layout());
            Jxl.JxlEncoderInitBasicInfo(info);
            JxlBasicInfo.xsize(info, SIZE);
            JxlBasicInfo.ysize(info, SIZE);
            JxlBasicInfo.bits_per_sample(info, 8);
            JxlBasicInfo.num_color_channels(info, 3);
            JxlBasicInfo.uses_original_profile(info, Jxl.JXL_TRUE());
            JxlBasicInfo.have_animation(info, Jxl.JXL_TRUE());
            MemorySegment animation = JxlBasicInfo.animation(info);
            JxlAnimationHeader.tps_numerator(animation, tpsNumerator);
            JxlAnimationHeader.tps_denominator(animation, tpsDenominator);
            JxlAnimationHeader.num_loops(animation, loops);
            encoder.check(Jxl.JxlEncoderSetBasicInfo(handle, info), "JxlEncoderSetBasicInfo");

            MemorySegment color = arena.allocate(JxlColorEncoding.layout());
            Jxl.JxlColorEncodingSetToSRGB(color, Jxl.JXL_FALSE());
            encoder.check(Jxl.JxlEncoderSetColorEncoding(handle, color), "JxlEncoderSetColorEncoding");

            MemorySegment settings = encoder.createFrameSettings(1);
            encoder.check(Jxl.JxlEncoderSetFrameLossless(settings, Jxl.JXL_TRUE()), "JxlEncoderSetFrameLossless");
            MemorySegment header = arena.allocate(JxlFrameHeader.layout());
            for (Layer layer : layers) {
                Jxl.JxlEncoderInitFrameHeader(header);
                JxlFrameHeader.duration(header, layer.duration());
                if (layer.x0() != 0 || layer.y0() != 0 || layer.width() != SIZE || layer.height() != SIZE) {
                    MemorySegment layerInfo = JxlFrameHeader.layer_info(header);
                    JxlLayerInfo.have_crop(layerInfo, Jxl.JXL_TRUE());
                    JxlLayerInfo.crop_x0(layerInfo, layer.x0());
                    JxlLayerInfo.crop_y0(layerInfo, layer.y0());
                    JxlLayerInfo.xsize(layerInfo, layer.width());
                    JxlLayerInfo.ysize(layerInfo, layer.height());
                }
                encoder.check(Jxl.JxlEncoderSetFrameHeader(settings, header), "JxlEncoderSetFrameHeader");
                encoder.check(Jxl.JxlEncoderSetFrameName(settings, arena.allocateFrom(layer.name())),
                        "JxlEncoderSetFrameName");

                byte[] rgb = rgb(layer.color());
                MemorySegment pixels = arena.allocate((long) layer.width() * layer.height() * 3);
                for (long p = 0; p < pixels.byteSize(); p += 3) {
                    MemorySegment.copy(rgb, 0, pixels, JAVA_BYTE, p, 3);
                }
                encoder.check(Jxl.JxlEncoderAddImageFrame(settings, format(arena), pixels, pixels.byteSize()),
                        "JxlEncoderAddImageFrame");
            }
            Jxl.JxlEncoderCloseInput(handle);
            return encoder.collectOutput(arena);
        }
    }

    private static MemorySegment format(Arena arena) {
        MemorySegment format = arena.allocate(JxlPixelFormat.layout());
        JxlPixelFormat.num_channels(format, 3);
        JxlPixelFormat.data_type(format, Jxl.JXL_TYPE_UINT8());
        JxlPixelFormat.endianness(format, Jxl.JXL_NATIVE_ENDIAN());
        JxlPixelFormat.align(format, 0L);
        return format;
    }
}

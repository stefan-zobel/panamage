package panamage.jxl;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import panamage.jxl.ffi.Jxl;
import panamage.jxl.ffi.JxlBasicInfo;
import panamage.jxl.ffi.JxlFrameHeader;
import panamage.jxl.ffi.JxlLayerInfo;

/**
 * The frame encoder encodes only the area that differs from the previous
 * frame; decoded with coalescing, the frames are the same as the input.
 */
class FrameCroppingTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;

    /** A layer as stored in the file: x, y, width, height. */
    private record Layer(int x, int y, int width, int height) {
    }

    @Test
    void movingSquareIsStoredAsCroppedFrames() throws IOException {
        List<JxlImage> frames = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            frames.add(square(background(1), 10 + i * 6, 12, 8, 0xFF00FF00));
        }

        byte[] data = encode(frames, JxlEncodeOptions.ofLossless());

        assertSameFrames(frames, data);
        List<Layer> layers = layers(data);
        assertEquals(new Layer(0, 0, WIDTH, HEIGHT), layers.get(0));
        for (int i = 1; i < 4; i++) {
            // The old position of the square is restored, the new one drawn.
            assertEquals(new Layer(10 + (i - 1) * 6, 12, 14, 8), layers.get(i), "frame " + i);
        }
    }

    @Test
    void changesAtTheEdgesAndCorners() throws IOException {
        int[][] pixels = {{0, 0}, {WIDTH - 1, 0}, {0, HEIGHT - 1}, {WIDTH - 1, HEIGHT - 1}, {WIDTH / 2, 0},
            {0, HEIGHT / 2}, {WIDTH - 1, HEIGHT / 2}, {WIDTH / 2, HEIGHT - 1}};
        List<JxlImage> frames = new ArrayList<>();
        JxlImage.Uint8 image = background(2);
        frames.add(image);
        for (int[] pixel : pixels) {
            image = square(image, pixel[0], pixel[1], 1, 0xFFFF00FF);
            frames.add(image);
        }

        byte[] data = encode(frames, JxlEncodeOptions.ofLossless());

        assertSameFrames(frames, data);
        List<Layer> layers = layers(data);
        for (int i = 0; i < pixels.length; i++) {
            assertEquals(new Layer(pixels[i][0], pixels[i][1], 1, 1), layers.get(i + 1), "change " + i);
        }
    }

    @Test
    void identicalAndCompletelyDifferentFrames() throws IOException {
        List<JxlImage> frames = List.of(background(3), background(3), background(3), background(4), background(3));

        byte[] data = encode(frames, JxlEncodeOptions.ofLossless());

        assertSameFrames(frames, data);
        List<Layer> layers = layers(data);
        assertEquals(new Layer(0, 0, 1, 1), layers.get(1));
        assertEquals(new Layer(0, 0, 1, 1), layers.get(2));
        assertEquals(new Layer(0, 0, WIDTH, HEIGHT), layers.get(3));
        assertEquals(new Layer(0, 0, WIDTH, HEIGHT), layers.get(4));
    }

    @Test
    void alphaOnlyChange() throws IOException {
        JxlImage.Uint8 first = background(5);
        byte[] pixels = first.pixels().clone();
        int i = (20 * WIDTH + 30) * 4 + 3;
        pixels[i] = (byte) 17;
        List<JxlImage> frames = List.of(first, new JxlImage.Uint8(WIDTH, HEIGHT, 4, pixels));

        byte[] data = encode(frames, JxlEncodeOptions.ofLossless());

        assertSameFrames(frames, data);
        assertEquals(new Layer(30, 20, 1, 1), layers(data).get(1));
    }

    @Test
    void highBitDepthAndFloatSamples() throws IOException {
        Random random = new Random(6);
        short[] shorts = new short[WIDTH * HEIGHT * 3];
        float[] floats = new float[WIDTH * HEIGHT * 3];
        for (int i = 0; i < shorts.length; i++) {
            shorts[i] = (short) random.nextInt(65536);
            floats[i] = random.nextFloat();
        }
        short[] shorts2 = shorts.clone();
        float[] floats2 = floats.clone();
        for (int c = 0; c < 3; c++) {
            shorts2[(5 * WIDTH + 7) * 3 + c] ^= 0x5555;
            floats2[(40 * WIDTH + 60) * 3 + c] = 0.25f;
        }
        List<JxlImage> uint16 = List.of(new JxlImage.Uint16(WIDTH, HEIGHT, 3, shorts),
                new JxlImage.Uint16(WIDTH, HEIGHT, 3, shorts2));
        List<JxlImage> float32 = List.of(new JxlImage.Float32(WIDTH, HEIGHT, 3, floats),
                new JxlImage.Float32(WIDTH, HEIGHT, 3, floats2));

        byte[] data16 = encode(uint16, JxlEncodeOptions.ofLossless());
        byte[] data32 = encode(float32, JxlEncodeOptions.ofLossless());

        List<JxlFrame> decoded16 = JxlDecoder.decodeFrames(data16, 3, JxlSampleType.UINT16);
        List<JxlFrame> decoded32 = JxlDecoder.decodeFrames(data32, 3, JxlSampleType.FLOAT32);
        for (int i = 0; i < 2; i++) {
            assertArrayEquals(((JxlImage.Uint16) uint16.get(i)).pixels(),
                    ((JxlImage.Uint16) decoded16.get(i).image()).pixels(), "uint16 frame " + i);
            assertArrayEquals(((JxlImage.Float32) float32.get(i)).pixels(),
                    ((JxlImage.Float32) decoded32.get(i).image()).pixels(), "float32 frame " + i);
        }
        assertEquals(new Layer(7, 5, 1, 1), layers(data16).get(1));
        assertEquals(new Layer(60, 40, 1, 1), layers(data32).get(1));
    }

    @Test
    void extraChannelOnlyChange() throws IOException {
        Random random = new Random(7);
        short[] gray = new short[WIDTH * HEIGHT];
        short[] gfp = new short[WIDTH * HEIGHT];
        for (int i = 0; i < gray.length; i++) {
            gray[i] = (short) random.nextInt(4096);
            gfp[i] = (short) random.nextInt(4096);
        }
        short[] gfp2 = gfp.clone();
        gfp2[33 * WIDTH + 44] = 4095;
        gfp2[34 * WIDTH + 46] = 0;
        List<JxlChannels> frames = List.of(
                JxlChannels.builder(WIDTH, HEIGHT).gray(gray).add("GFP", gfp).bitsPerSample(12).build(),
                JxlChannels.builder(WIDTH, HEIGHT).gray(gray).add("GFP", gfp2).bitsPerSample(12).build());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JxlFrameEncoder encoder = JxlFrameEncoder.open(out, JxlAnimationHeader.millis(0),
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE)) {
            for (JxlChannels frame : frames) {
                encoder.add(frame, 100);
            }
            encoder.finish();
        }
        byte[] data = out.toByteArray();

        try (JxlFrameDecoder decoder = JxlFrameDecoder.openChannels(data, JxlSampleType.UINT16)) {
            for (int i = 0; i < 2; i++) {
                JxlChannels.Uint16 decoded = (JxlChannels.Uint16) decoder.nextChannels().channels();
                JxlChannels.Uint16 expected = (JxlChannels.Uint16) frames.get(i);
                for (int plane = 0; plane < 2; plane++) {
                    assertArrayEquals(expected.planes().get(plane), decoded.planes().get(plane),
                            "frame " + i + ", plane " + plane);
                }
            }
        }
        assertEquals(new Layer(44, 33, 3, 2), layers(data).get(1));
    }

    @Test
    void lastFrameWithoutDurationAndEncodeAnimation() throws IOException {
        List<JxlImage> images = List.of(background(8), square(background(8), 3, 4, 5, 0xFF0000FF),
                square(background(8), 40, 30, 5, 0xFF0000FF));
        List<JxlFrame> frames = List.of(new JxlFrame(images.get(0), new JxlFrameInfo(10, 10, "")),
                new JxlFrame(images.get(1), new JxlFrameInfo(10, 10, "")),
                new JxlFrame(images.get(2), new JxlFrameInfo(0, 0, "")));

        byte[] data = JxlEncoder.encodeAnimation(frames, JxlAnimationHeader.millis(0), JxlEncodeOptions.ofLossless(),
                JxlMetadata.NONE);

        assertSameFrames(images, data);
        assertEquals(new Layer(3, 4, 5, 5), layers(data).get(1));
        assertEquals(new Layer(3, 4, 42, 31), layers(data).get(2));
    }

    @Test
    void smallChangesKeepTheFileSmall() throws IOException {
        JxlImage.Uint8 background = background(9, 256, 256);
        byte[] single = JxlEncoder.encode(background, JxlEncodeOptions.ofLossless());
        List<JxlImage> frames = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            frames.add(square(background, 20 + i * 7, 100, 12, 0xFFFFFF00));
        }

        byte[] data = encode(frames, JxlEncodeOptions.ofLossless());

        assertTrue(data.length < 2 * single.length, data.length + " bytes for 30 frames, " + single.length
                + " bytes for one");
        assertSameFrames(frames, data);
    }

    @Test
    void lossyFramesKeepTheUnchangedArea() throws IOException {
        JxlImage.Uint8 first = background(10);
        List<JxlImage> frames = List.of(first, square(first, 20, 20, 6, 0xFF808080));

        byte[] data = encode(frames, JxlEncodeOptions.ofDistance(2.0f));

        List<JxlFrame> decoded = JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8);
        byte[] a = ((JxlImage.Uint8) decoded.get(0).image()).pixels();
        byte[] b = ((JxlImage.Uint8) decoded.get(1).image()).pixels();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                boolean inSquare = x >= 20 && x < 26 && y >= 20 && y < 26;
                int i = (y * WIDTH + x) * 4;
                if (!inSquare) {
                    assertArrayEquals(Arrays.copyOfRange(a, i, i + 4), Arrays.copyOfRange(b, i, i + 4),
                            "pixel " + x + "," + y);
                }
            }
        }
        assertEquals(new Layer(20, 20, 6, 6), layers(data).get(1));
    }

    private static byte[] encode(List<JxlImage> frames, JxlEncodeOptions options) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JxlFrameEncoder encoder = JxlFrameEncoder.open(out, JxlAnimationHeader.millis(0), options,
                JxlMetadata.NONE)) {
            for (JxlImage frame : frames) {
                encoder.add(frame, 100);
            }
            encoder.finish();
        }
        return out.toByteArray();
    }

    private static void assertSameFrames(List<JxlImage> expected, byte[] data) {
        List<JxlFrame> decoded = JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8);
        assertEquals(expected.size(), decoded.size());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(((JxlImage.Uint8) expected.get(i)).pixels(),
                    ((JxlImage.Uint8) decoded.get(i).image()).pixels(), "frame " + i);
        }
    }

    /** The layers as stored, read without coalescing. */
    private static List<Layer> layers(byte[] data) {
        List<Layer> layers = new ArrayList<>();
        try (Arena arena = Arena.ofConfined(); NativeDecoder decoder = NativeDecoder.createWithoutThreads()) {
            MemorySegment handle = decoder.handle();
            NativeDecoder.check(Jxl.JxlDecoderSetCoalescing(handle, Jxl.JXL_FALSE()), "JxlDecoderSetCoalescing");
            decoder.start(Jxl.JXL_DEC_BASIC_INFO() | Jxl.JXL_DEC_FRAME(), arena.allocateFrom(JAVA_BYTE, data));
            MemorySegment header = arena.allocate(JxlFrameHeader.layout());
            MemorySegment info = null;
            while (true) {
                int status = Jxl.JxlDecoderProcessInput(handle);
                if (status == Jxl.JXL_DEC_BASIC_INFO()) {
                    info = JxlDecoder.basicInfo(handle, arena);
                } else if (status == Jxl.JXL_DEC_FRAME()) {
                    NativeDecoder.check(Jxl.JxlDecoderGetFrameHeader(handle, header), "JxlDecoderGetFrameHeader");
                    MemorySegment layer = JxlFrameHeader.layer_info(header);
                    layers.add(JxlLayerInfo.have_crop(layer) != 0
                            ? new Layer(JxlLayerInfo.crop_x0(layer), JxlLayerInfo.crop_y0(layer),
                                    JxlLayerInfo.xsize(layer), JxlLayerInfo.ysize(layer))
                            : new Layer(0, 0, JxlBasicInfo.xsize(info), JxlBasicInfo.ysize(info)));
                } else if (status == Jxl.JXL_DEC_SUCCESS()) {
                    return layers;
                } else {
                    throw NativeDecoder.failure(status);
                }
            }
        }
    }

    private static JxlImage.Uint8 background(long seed) {
        return background(seed, WIDTH, HEIGHT);
    }

    /** Opaque RGBA noise. */
    private static JxlImage.Uint8 background(long seed, int width, int height) {
        Random random = new Random(seed);
        byte[] pixels = new byte[width * height * 4];
        random.nextBytes(pixels);
        for (int i = 3; i < pixels.length; i += 4) {
            pixels[i] = (byte) 255;
        }
        return new JxlImage.Uint8(width, height, 4, pixels);
    }

    /** A copy with a filled square, clipped to the image. */
    private static JxlImage.Uint8 square(JxlImage.Uint8 image, int x0, int y0, int size, int argb) {
        byte[] pixels = image.pixels().clone();
        for (int y = y0; y < Math.min(y0 + size, image.height()); y++) {
            for (int x = x0; x < Math.min(x0 + size, image.width()); x++) {
                int i = (y * image.width() + x) * 4;
                pixels[i] = (byte) (argb >> 16);
                pixels[i + 1] = (byte) (argb >> 8);
                pixels[i + 2] = (byte) argb;
                pixels[i + 3] = (byte) (argb >>> 24);
            }
        }
        return new JxlImage.Uint8(image.width(), image.height(), 4, pixels);
    }
}

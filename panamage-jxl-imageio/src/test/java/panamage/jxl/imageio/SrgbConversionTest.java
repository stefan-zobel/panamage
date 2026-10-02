package panamage.jxl.imageio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import panamage.jxl.JxlAnimationHeader;
import panamage.jxl.JxlChannels;
import panamage.jxl.JxlChannelsFrame;
import panamage.jxl.JxlDecodeOptions;
import panamage.jxl.JxlDecoder;
import panamage.jxl.JxlEncodeOptions;
import panamage.jxl.JxlEncoder;
import panamage.jxl.JxlFrame;
import panamage.jxl.JxlFrameDecoder;
import panamage.jxl.JxlFrameInfo;
import panamage.jxl.JxlImage;
import panamage.jxl.JxlMetadata;
import panamage.jxl.JxlSampleType;

/**
 * Decoding to sRGB with {@link JxlDecodeOptions#withSrgb(boolean)}, checked
 * against the sRGB transfer curve and against Java's own color conversion,
 * also with images of the JPEG XL conformance corpus in other color spaces
 * (skipped without the corpus, see {@code scripts/fetch_tools.py}).
 */
class SrgbConversionTest {

    private static final String CONFORMANCE_DIR_PROPERTY = "panamage.jxl.conformance.dir";

    private static final JxlDecodeOptions ORIGINAL = JxlDecodeOptions.defaults();
    private static final JxlDecodeOptions SRGB = JxlDecodeOptions.defaults().withSrgb(true);

    /** One column for every 8-bit value. */
    private static final int WIDTH = 256;
    private static final int HEIGHT = 2;

    /**
     * The colorants of Display P3, adapted to the D50 white of the profile
     * connection space, as in Apple's Display P3 profile.
     */
    private static final double[][] DISPLAY_P3_COLORANTS = {
        {0.5151, 0.2412, -0.0011}, {0.2920, 0.6922, 0.0419}, {0.1571, 0.0666, 0.7841}};

    @Test
    void linearRgbIsConvertedToSrgb() {
        JxlImage.Uint8 linear = ramp(3, profile(ColorSpace.CS_LINEAR_RGB));
        byte[] encoded = JxlEncoder.encode(linear, JxlEncodeOptions.ofLossless());

        JxlImage original = JxlDecoder.decode(encoded, 3, JxlSampleType.UINT8, ORIGINAL);
        JxlImage.Uint8 srgb = (JxlImage.Uint8) JxlDecoder.decode(encoded, 3, JxlSampleType.UINT8, SRGB);

        assertFalse(original.isSrgb());
        assertArrayEquals(linear.pixels(), ((JxlImage.Uint8) original).pixels());
        assertTrue(srgb.isSrgb());
        assertClose(srgbRamp(3), srgb.pixels(), 1);
    }

    @Test
    void sixteenBitSamplesAreConverted() {
        int width = 1024;
        short[] pixels = new short[width * 3];
        double[] expected = new double[pixels.length];
        for (int x = 0; x < width; x++) {
            int value = x * 64;
            for (int c = 0; c < 3; c++) {
                pixels[x * 3 + c] = (short) value;
                expected[x * 3 + c] = srgbFromLinear(value / 65535.0) * 65535.0;
            }
        }
        JxlImage.Uint16 linear = new JxlImage.Uint16(width, 1, 3, pixels, profile(ColorSpace.CS_LINEAR_RGB));
        byte[] encoded = JxlEncoder.encode(linear, JxlEncodeOptions.ofLossless());

        JxlImage.Uint16 srgb = (JxlImage.Uint16) JxlDecoder.decode(encoded, 3, JxlSampleType.UINT16, SRGB);

        assertTrue(srgb.isSrgb());
        for (int i = 0; i < expected.length; i++) {
            double actual = Short.toUnsignedInt(srgb.pixels()[i]);
            assertTrue(Math.abs(actual - expected[i]) <= 64, "sample " + i + ": " + actual + " instead of "
                    + expected[i]);
        }
    }

    @Test
    void grayWithItsOwnProfileBecomesSrgbGray() {
        JxlImage.Uint8 linearGray = ramp(1, profile(ColorSpace.CS_GRAY));
        byte[] encoded = JxlEncoder.encode(linearGray, JxlEncodeOptions.ofLossless());

        JxlImage.Uint8 gray = (JxlImage.Uint8) JxlDecoder.decode(encoded, 1, JxlSampleType.UINT8, SRGB);
        JxlImage.Uint8 rgb = (JxlImage.Uint8) JxlDecoder.decode(encoded, 3, JxlSampleType.UINT8, SRGB);

        assertTrue(gray.isSrgb());
        assertClose(srgbRamp(1), gray.pixels(), 1);
        assertTrue(rgb.isSrgb());
        assertClose(srgbRamp(3), rgb.pixels(), 1);
    }

    @Test
    void wideGamutColorsMatchJavaColorConversion() {
        byte[] p3 = displayP3Profile();
        byte[] pixels = colorCube();
        int width = pixels.length / 3;
        byte[] encoded = JxlEncoder.encode(new JxlImage.Uint8(width, 1, 3, pixels, p3), JxlEncodeOptions.ofLossless());

        JxlImage.Uint8 srgb = (JxlImage.Uint8) JxlDecoder.decode(encoded, 3, JxlSampleType.UINT8, SRGB);

        assertTrue(srgb.isSrgb());
        assertClose(javaToSrgb(pixels, width, 1, 3, p3), srgb.pixels(), 3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bench_oriented_brg", "grayscale_jpeg"})
    void conformanceImagesMatchJavaColorConversion(String testCase) throws IOException {
        String directory = System.getProperty(CONFORMANCE_DIR_PROPERTY, "");
        Path input = Path.of(directory, testCase, "input.jxl");
        Assumptions.assumeTrue(!directory.isEmpty() && Files.isRegularFile(input),
                "Conformance test case not found: " + input);
        byte[] encoded = Files.readAllBytes(input);
        int channels = JxlDecoder.readInfo(encoded).channels() >= 3 ? 3 : 1;

        JxlImage.Uint8 original = (JxlImage.Uint8) JxlDecoder.decode(encoded, channels, JxlSampleType.UINT8,
                ORIGINAL);
        JxlImage.Uint8 srgb = (JxlImage.Uint8) JxlDecoder.decode(encoded, 3, JxlSampleType.UINT8, SRGB);

        assertFalse(original.isSrgb());
        assertTrue(srgb.isSrgb());
        assertClose(javaToSrgb(original.pixels(), original.width(), original.height(), channels,
                original.iccProfile()), srgb.pixels(), 3);
    }

    @Test
    void lossyImagesAreConverted() {
        byte[] encoded = JxlEncoder.encode(ramp(3, profile(ColorSpace.CS_LINEAR_RGB)), JxlEncodeOptions.ofDistance(1.0f));

        JxlImage.Uint8 srgb = (JxlImage.Uint8) JxlDecoder.decode(encoded, 3, JxlSampleType.UINT8, SRGB);

        assertTrue(srgb.isSrgb());
        assertClose(srgbRamp(3), srgb.pixels(), 6);
    }

    @Test
    void srgbImagesAreUnchanged() {
        JxlImage.Uint8 image = ramp(3, null);
        byte[] encoded = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless());

        JxlImage.Uint8 srgb = (JxlImage.Uint8) JxlDecoder.decode(encoded, 3, JxlSampleType.UINT8, SRGB);

        assertTrue(srgb.isSrgb());
        assertArrayEquals(image.pixels(), srgb.pixels());
    }

    @Test
    void framesAndChannelsAreConvertedLikeAStillImage() {
        byte[] profile = profile(ColorSpace.CS_LINEAR_RGB);
        JxlImage.Uint8 first = ramp(3, profile);
        JxlImage.Uint8 second = ramp(3, profile, 255);
        byte[] still = JxlEncoder.encode(first, JxlEncodeOptions.ofLossless());
        byte[] animation = JxlEncoder.encodeAnimation(
                List.of(new JxlFrame(first, new JxlFrameInfo(100, 100.0, "")),
                        new JxlFrame(second, new JxlFrameInfo(100, 100.0, ""))),
                JxlAnimationHeader.millis(0), JxlEncodeOptions.ofLossless(), JxlMetadata.NONE);
        byte[] expected = ((JxlImage.Uint8) JxlDecoder.decode(still, 3, JxlSampleType.UINT8, SRGB)).pixels();
        byte[] expectedSecond = ((JxlImage.Uint8) JxlDecoder.decode(
                JxlEncoder.encode(second, JxlEncodeOptions.ofLossless()), 3, JxlSampleType.UINT8, SRGB)).pixels();

        List<JxlFrame> frames = JxlDecoder.decodeFrames(animation, 3, JxlSampleType.UINT8, SRGB);
        assertEquals(2, frames.size());
        assertConverted(expected, frames.get(0).image());
        assertConverted(expectedSecond, frames.get(1).image());

        try (JxlFrameDecoder decoder = JxlFrameDecoder.open(animation, 3, JxlSampleType.UINT8, SRGB)) {
            assertConverted(expected, decoder.next().image());
            assertConverted(expectedSecond, decoder.next().image());
        }
        try (JxlFrameDecoder decoder = JxlFrameDecoder.openChannels(animation, JxlSampleType.UINT8, SRGB)) {
            JxlChannelsFrame frame = decoder.nextChannels();
            assertConverted(expected, frame.channels());
        }
        assertConverted(expected, JxlDecoder.decodeChannels(still, JxlSampleType.UINT8, SRGB));
    }

    @Test
    void optionsReplaceTheLimits() {
        assertFalse(ORIGINAL.srgb());
        assertEquals(ORIGINAL.limits(), SRGB.limits());
        assertTrue(SRGB.withLimits(ORIGINAL.limits().withMaxPixels(100)).srgb());
        assertNull(JxlDecoder.decode(JxlEncoder.encode(ramp(3, null), JxlEncodeOptions.ofLossless()), 3,
                JxlSampleType.UINT8, SRGB).iccProfile());
    }

    private static void assertConverted(byte[] expected, JxlImage image) {
        assertTrue(image.isSrgb());
        assertArrayEquals(expected, ((JxlImage.Uint8) image).pixels());
    }

    private static void assertConverted(byte[] expected, JxlChannels channels) {
        assertNull(channels.iccProfile());
        List<byte[]> planes = ((JxlChannels.Uint8) channels).planes();
        assertEquals(3, planes.size());
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < planes.get(c).length; i++) {
                assertEquals(expected[i * 3 + c], planes.get(c)[i], "channel " + c + ", pixel " + i);
            }
        }
    }

    /** Checks that all samples differ by at most {@code tolerance}. */
    private static void assertClose(byte[] expected, byte[] actual, int tolerance) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            int e = expected[i] & 0xFF;
            int a = actual[i] & 0xFF;
            assertTrue(Math.abs(e - a) <= tolerance, "sample " + i + ": " + a + " instead of " + e);
        }
    }

    /** A ramp with every 8-bit value, from left to right. */
    private static JxlImage.Uint8 ramp(int channels, byte[] profile) {
        return ramp(channels, profile, 0);
    }

    /** A ramp with every 8-bit value, starting at {@code start} and wrapping around. */
    private static JxlImage.Uint8 ramp(int channels, byte[] profile, int start) {
        byte[] pixels = new byte[WIDTH * HEIGHT * channels];
        for (int i = 0; i < WIDTH * HEIGHT; i++) {
            for (int c = 0; c < channels; c++) {
                pixels[i * channels + c] = (byte) (start + i);
            }
        }
        return new JxlImage.Uint8(WIDTH, HEIGHT, channels, pixels, profile);
    }

    /** The linear ramp of {@link #ramp(int, byte[])} with the sRGB transfer curve applied. */
    private static byte[] srgbRamp(int channels) {
        byte[] pixels = new byte[WIDTH * HEIGHT * channels];
        for (int i = 0; i < WIDTH * HEIGHT; i++) {
            long value = Math.round(srgbFromLinear((i % WIDTH) / 255.0) * 255.0);
            for (int c = 0; c < channels; c++) {
                pixels[i * channels + c] = (byte) value;
            }
        }
        return pixels;
    }

    private static double srgbFromLinear(double value) {
        return value <= 0.0031308 ? value * 12.92 : 1.055 * Math.pow(value, 1 / 2.4) - 0.055;
    }

    /** RGB colors with every component in steps of 51, inside and outside the sRGB gamut. */
    private static byte[] colorCube() {
        byte[] pixels = new byte[6 * 6 * 6 * 3];
        int i = 0;
        for (int r = 0; r <= 255; r += 51) {
            for (int g = 0; g <= 255; g += 51) {
                for (int b = 0; b <= 255; b += 51) {
                    pixels[i++] = (byte) r;
                    pixels[i++] = (byte) g;
                    pixels[i++] = (byte) b;
                }
            }
        }
        return pixels;
    }

    /**
     * Converts gray or RGB pixels in the given color space to RGB pixels in
     * sRGB with Java 2D.
     */
    private static byte[] javaToSrgb(byte[] pixels, int width, int height, int channels, byte[] profile) {
        ComponentColorModel model = new ComponentColorModel(new ICC_ColorSpace(ICC_Profile.getInstance(profile)),
                false, false, ComponentColorModel.OPAQUE, DataBuffer.TYPE_BYTE);
        int[] offsets = channels == 1 ? new int[] {0} : new int[] {0, 1, 2};
        WritableRaster raster = Raster.createInterleavedRaster(new DataBufferByte(pixels.clone(), pixels.length),
                width, height, width * channels, channels, offsets, null);
        BufferedImage source = new BufferedImage(model, raster, false, null);
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        new ColorConvertOp(null).filter(source, target);
        byte[] bgr = ((DataBufferByte) target.getRaster().getDataBuffer()).getData();
        byte[] rgb = new byte[bgr.length];
        for (int p = 0; p < width * height; p++) {
            rgb[p * 3] = bgr[p * 3 + 2];
            rgb[p * 3 + 1] = bgr[p * 3 + 1];
            rgb[p * 3 + 2] = bgr[p * 3];
        }
        return rgb;
    }

    private static byte[] profile(int colorSpace) {
        return ICC_Profile.getInstance(colorSpace).getData();
    }

    /** Java's sRGB profile with the colorants of Display P3, which has a wider gamut. */
    private static byte[] displayP3Profile() {
        ICC_Profile profile = ICC_Profile.getInstance(profile(ColorSpace.CS_sRGB));
        int[] tags = {ICC_Profile.icSigRedColorantTag, ICC_Profile.icSigGreenColorantTag,
            ICC_Profile.icSigBlueColorantTag};
        for (int i = 0; i < tags.length; i++) {
            ByteBuffer xyz = ByteBuffer.allocate(20);
            xyz.putInt(ICC_Profile.icSigXYZData).putInt(0);
            for (double component : DISPLAY_P3_COLORANTS[i]) {
                xyz.putInt((int) Math.round(component * 65536.0));
            }
            profile.setData(tags[i], xyz.array());
        }
        return profile.getData();
    }
}

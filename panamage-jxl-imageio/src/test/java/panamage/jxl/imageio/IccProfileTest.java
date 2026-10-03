package panamage.jxl.imageio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageTypeSpecifier;

import org.junit.jupiter.api.Test;

import panamage.jxl.JxlAnimationHeader;
import panamage.jxl.JxlDecoder;
import panamage.jxl.JxlEncodeOptions;
import panamage.jxl.JxlEncoder;
import panamage.jxl.JxlFrame;
import panamage.jxl.JxlFrameInfo;
import panamage.jxl.JxlImage;
import panamage.jxl.JxlMetadata;
import panamage.jxl.JxlSampleType;

/**
 * Images whose pixels are not sRGB, using Java's built-in linear RGB profile:
 * the reader converts them to sRGB unless asked to keep their color space.
 */
class IccProfileTest {

    private static final Path OUTPUT_DIR = Path.of("target", "test-output");

    private static final int WIDTH = 16;
    private static final int HEIGHT = 4;

    @Test
    void losslessImagesKeepTheirColorSpace() throws IOException {
        JxlImage.Uint8 linear = linearRgbImage();
        byte[] encoded = JxlEncoder.encode(linear, JxlEncodeOptions.ofLossless());
        save("linear-rgb-lossless.jxl", encoded);

        JxlImage.Uint8 decoded = (JxlImage.Uint8) JxlDecoder.decode(encoded, 3, JxlSampleType.UINT8);

        assertFalse(decoded.isSrgb());
        assertArrayEquals(linear.pixels(), decoded.pixels());
        assertEquals(ColorSpace.TYPE_RGB, ICC_Profile.getInstance(decoded.iccProfile()).getColorSpaceType());
        assertFalse(JxlDecoder.readInfo(encoded).isSrgb());
    }

    @Test
    void imageReaderConvertsToSrgbByDefault() throws IOException {
        byte[] encoded = JxlEncoder.encode(linearRgbImage(), JxlEncodeOptions.ofLossless());

        BufferedImage image = JxlImageReaderTest.read(encoded);

        assertEquals(BufferedImage.TYPE_3BYTE_BGR, image.getType());
        assertSrgbRamp(image);
    }

    @Test
    void imageReaderKeepsTheProfileOnRequest() throws IOException {
        JxlImage.Uint8 linear = linearRgbImage();
        byte[] encoded = JxlEncoder.encode(linear, JxlEncodeOptions.ofLossless());

        BufferedImage image = JxlImageReaderTest.readOriginal(encoded);

        ColorSpace space = image.getColorModel().getColorSpace();
        assertInstanceOf(ICC_ColorSpace.class, space);
        assertFalse(space.isCS_sRGB());
        assertArrayEquals(linear.pixels(), (byte[]) image.getRaster().getDataElements(0, 0, WIDTH, HEIGHT, null));

        // Linear 128/255 is about 0.502, which is 0.735 in sRGB, or 188.
        int x = 8;
        int srgb = image.getRGB(x, 0) & 0xFF;
        assertEquals(128, linear.pixels()[x * 3] & 0xFF);
        assertTrue(Math.abs(srgb - 188) <= 2, "sRGB value " + srgb);
    }

    @Test
    void lossyImagesDecodeToPixelsThatMatchTheirProfile() {
        byte[] encoded = JxlEncoder.encode(linearRgbImage(), JxlEncodeOptions.ofDistance(1.0f));

        JxlImage.Uint8 decoded = (JxlImage.Uint8) JxlDecoder.decode(encoded, 3, JxlSampleType.UINT8);

        // libjxl may return lossy images in sRGB or in their original color space;
        // either way, pixels and profile together must describe linear 128 (sRGB 188).
        float value = (decoded.pixels()[8 * 3] & 0xFF) / 255.0f;
        float srgb = decoded.isSrgb() ? value
                : new ICC_ColorSpace(ICC_Profile.getInstance(decoded.iccProfile()))
                        .toRGB(new float[] {value, value, value})[0];
        assertTrue(Math.abs(srgb * 255.0f - 188.0f) <= 4.0f, "sRGB value " + srgb * 255.0f);
    }

    @Test
    void imageTypesMatchTheDecodedImage() throws IOException {
        byte[] encoded = JxlEncoder.encode(linearRgbImage(), JxlEncodeOptions.ofLossless());
        for (boolean convert : new boolean[] {true, false}) {
            JxlImageReader reader = reader(encoded);
            reader.setConvertToSrgb(convert);

            ImageTypeSpecifier type = reader.getImageTypes(0).next();
            BufferedImage image = reader.read(0);

            assertEquals(convert, type.getColorModel().getColorSpace().isCS_sRGB());
            assertEquals(type.getColorModel().getColorSpace(), image.getColorModel().getColorSpace());
            assertEquals(type.getNumBands(), image.getRaster().getNumBands());
        }
    }

    @Test
    void imageTypesListTheSrgbTypesAndTheTypesOfTheImage() throws IOException {
        byte[] encoded = JxlEncoder.encode(linearRgbImage(), JxlEncodeOptions.ofLossless());
        JxlImageReader reader = reader(encoded);

        List<ImageTypeSpecifier> converted = types(reader);
        reader.setConvertToSrgb(false);
        List<ImageTypeSpecifier> original = types(reader);

        // Three sample types, each in sRGB and in the color space of the image.
        assertEquals(6, converted.size());
        assertEquals(BufferedImage.TYPE_3BYTE_BGR, converted.get(0).getBufferedImageType());
        for (int i = 0; i < 6; i++) {
            assertEquals(i < 3, converted.get(i).getColorModel().getColorSpace().isCS_sRGB(), "type " + i);
            assertEquals(i >= 3, original.get(i).getColorModel().getColorSpace().isCS_sRGB(), "type " + i);
        }
        assertEquals(converted.get(3).getColorModel(), original.get(0).getColorModel());
    }

    @Test
    void destinationTypesSelectTheColorSpace() throws IOException {
        JxlImage.Uint8 linear = linearRgbImage();
        byte[] encoded = JxlEncoder.encode(linear, JxlEncodeOptions.ofLossless());
        JxlImageReader reader = reader(encoded);
        ImageReadParam param = reader.getDefaultReadParam();

        param.setDestinationType(types(reader).get(3));
        BufferedImage original = reader.read(0, param);
        reader.setConvertToSrgb(false);
        param.setDestinationType(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_3BYTE_BGR));
        BufferedImage srgb = reader.read(0, param);

        assertFalse(original.getColorModel().getColorSpace().isCS_sRGB());
        assertArrayEquals(linear.pixels(), (byte[]) original.getRaster().getDataElements(0, 0, WIDTH, HEIGHT, null));
        assertSrgbRamp(srgb);
    }

    @Test
    void resetRestoresTheConversion() throws IOException {
        JxlImageReader reader = reader(JxlEncoder.encode(linearRgbImage(), JxlEncodeOptions.ofLossless()));
        reader.setConvertToSrgb(false);

        reader.reset();

        assertTrue(reader.isConvertToSrgb());
    }

    @Test
    void metadataKeepsTheProfileOfTheImage() throws IOException {
        byte[] encoded = JxlEncoder.encode(linearRgbImage(), JxlEncodeOptions.ofLossless());
        JxlImageReader reader = reader(encoded);

        BufferedImage image = reader.read(0);
        JxlImageMetadata metadata = (JxlImageMetadata) reader.getImageMetadata(0);

        assertTrue(image.getColorModel().getColorSpace().isCS_sRGB());
        assertNotNull(metadata.getIccProfile());
        assertEquals(ColorSpace.TYPE_RGB, ICC_Profile.getInstance(metadata.getIccProfile()).getColorSpaceType());
    }

    @Test
    void framesSwitchBetweenTheColorSpaces() throws IOException {
        JxlImage.Uint8 linear = linearRgbImage();
        JxlFrameInfo info = new JxlFrameInfo(100, 100.0, "");
        byte[] encoded = JxlEncoder.encodeAnimation(List.of(new JxlFrame(linear, info), new JxlFrame(linear, info)),
                JxlAnimationHeader.millis(0), JxlEncodeOptions.ofLossless(), JxlMetadata.NONE);
        JxlImageReader reader = reader(encoded);
        ImageReadParam param = reader.getDefaultReadParam();
        param.setDestinationType(types(reader).get(3));

        BufferedImage first = reader.read(0);
        BufferedImage second = reader.read(1, param);
        BufferedImage again = reader.read(1);

        assertSrgbRamp(first);
        assertFalse(second.getColorModel().getColorSpace().isCS_sRGB());
        assertArrayEquals(linear.pixels(), (byte[]) second.getRaster().getDataElements(0, 0, WIDTH, HEIGHT, null));
        assertSrgbRamp(again);
    }

    /** Checks the sRGB values of the linear ramp: linear 128 is 188 in sRGB. */
    private static void assertSrgbRamp(BufferedImage image) {
        assertTrue(image.getColorModel().getColorSpace().isCS_sRGB());
        int x = 8;
        int rgb = image.getRGB(x, 0);
        for (int shift : new int[] {0, 8, 16}) {
            int value = (rgb >> shift) & 0xFF;
            assertTrue(Math.abs(value - 188) <= 1, "sRGB value " + value);
        }
    }

    private static JxlImageReader reader(byte[] encoded) throws IOException {
        JxlImageReader reader = new JxlImageReader(new JxlImageReaderSpi());
        reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(encoded)));
        return reader;
    }

    private static List<ImageTypeSpecifier> types(JxlImageReader reader) throws IOException {
        List<ImageTypeSpecifier> types = new ArrayList<>();
        reader.getImageTypes(0).forEachRemaining(types::add);
        return types;
    }

    /** A gray ramp from 0 to 240 in linear RGB, as RGB samples. */
    private static JxlImage.Uint8 linearRgbImage() {
        byte[] pixels = new byte[WIDTH * HEIGHT * 3];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int value = x * 16;
                int i = (y * WIDTH + x) * 3;
                pixels[i] = (byte) value;
                pixels[i + 1] = (byte) value;
                pixels[i + 2] = (byte) value;
            }
        }
        byte[] profile = ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).getData();
        return new JxlImage.Uint8(WIDTH, HEIGHT, 3, pixels, profile);
    }

    private static void save(String name, byte[] data) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Files.write(OUTPUT_DIR.resolve(name), data);
    }
}

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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import panamage.jxl.JxlDecoder;
import panamage.jxl.JxlEncodeOptions;
import panamage.jxl.JxlEncoder;
import panamage.jxl.JxlImage;

/**
 * Images whose pixels are not sRGB, using Java's built-in linear RGB profile.
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

        JxlImage.Uint8 decoded = JxlDecoder.decode(encoded, 3);

        assertFalse(decoded.isSrgb());
        assertArrayEquals(linear.pixels(), decoded.pixels());
        assertEquals(ColorSpace.TYPE_RGB, ICC_Profile.getInstance(decoded.iccProfile()).getColorSpaceType());
        assertFalse(JxlDecoder.readInfo(encoded).isSrgb());
    }

    @Test
    void imageReaderAttachesTheProfile() throws IOException {
        JxlImage.Uint8 linear = linearRgbImage();
        byte[] encoded = JxlEncoder.encode(linear, JxlEncodeOptions.ofLossless());

        BufferedImage image = JxlImageReaderTest.read(encoded);

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

        JxlImage.Uint8 decoded = JxlDecoder.decode(encoded, 3);

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
        JxlImageReader reader = new JxlImageReader(new JxlImageReaderSpi());
        reader.setInput(javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(encoded)));

        var type = reader.getImageTypes(0).next();
        BufferedImage image = reader.read(0);

        assertNotNull(type.getColorModel());
        assertEquals(type.getColorModel().getColorSpace().getType(), image.getColorModel().getColorSpace().getType());
        assertEquals(type.getNumBands(), image.getRaster().getNumBands());
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

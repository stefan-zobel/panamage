package panamage.jxl.imageio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;

import org.junit.jupiter.api.Test;

import panamage.jxl.JxlEncodeOptions;
import panamage.jxl.JxlEncoder;
import panamage.jxl.JxlImage;

class JxlImageReaderTest {

    @Test
    void isRegisteredByFormatNameSuffixAndMimeType() {
        assertInstanceOf(JxlImageReader.class, ImageIO.getImageReadersByFormatName("jxl").next());
        assertInstanceOf(JxlImageReader.class, ImageIO.getImageReadersByFormatName("JPEG XL").next());
        assertInstanceOf(JxlImageReader.class, ImageIO.getImageReadersBySuffix("jxl").next());
        assertInstanceOf(JxlImageReader.class, ImageIO.getImageReadersByMIMEType("image/jxl").next());
    }

    @Test
    void readsRgbaImagesBitExact() throws IOException {
        BufferedImage image = read(Resources.bytes("gradient.jxl"));

        assertEquals(BufferedImage.TYPE_4BYTE_ABGR, image.getType());
        assertEquals(Resources.GRADIENT_WIDTH, image.getWidth());
        assertEquals(Resources.GRADIENT_HEIGHT, image.getHeight());
        assertArrayEquals(Resources.gradientArgb(), argb(image));
    }

    @Test
    void readsTranscodedJpegsAsRgb() throws IOException {
        BufferedImage image = read(Resources.bytes("photo-420-exif.jxl"));

        assertEquals(BufferedImage.TYPE_3BYTE_BGR, image.getType());
        assertEquals(256, image.getWidth());
        assertEquals(192, image.getHeight());
    }

    @Test
    void readsGrayImagesAsByteGray() throws IOException {
        byte[] gray = new byte[16 * 8];
        for (int i = 0; i < gray.length; i++) {
            gray[i] = (byte) (i * 2);
        }
        byte[] encoded = JxlEncoder.encode(new JxlImage.Uint8(16, 8, 1, gray), JxlEncodeOptions.ofLossless());

        BufferedImage image = read(encoded);

        assertEquals(BufferedImage.TYPE_BYTE_GRAY, image.getType());
        assertArrayEquals(gray, (byte[]) image.getRaster().getDataElements(0, 0, 16, 8, null));
    }

    @Test
    void readsGrayAlphaImagesAsAbgr() throws IOException {
        byte[] grayAlpha = new byte[4 * 2 * 2];
        for (int p = 0; p < 8; p++) {
            grayAlpha[p * 2] = (byte) (p * 30);
            grayAlpha[p * 2 + 1] = (byte) (255 - p * 20);
        }
        byte[] encoded = JxlEncoder.encode(new JxlImage.Uint8(4, 2, 2, grayAlpha), JxlEncodeOptions.ofLossless());

        BufferedImage image = read(encoded);

        assertEquals(BufferedImage.TYPE_4BYTE_ABGR, image.getType());
        for (int p = 0; p < 8; p++) {
            int gray = p * 30;
            int expected = (255 - p * 20) << 24 | gray << 16 | gray << 8 | gray;
            assertEquals(expected, image.getRGB(p % 4, p / 4), "pixel " + p);
        }
    }

    @Test
    void doesNotClaimOtherFormats() throws IOException {
        JxlImageReaderSpi spi = new JxlImageReaderSpi();
        assertFalse(spi.canDecodeInput(stream(Resources.bytes("gradient.png"))));
        assertFalse(spi.canDecodeInput(stream(Resources.bytes("photo-420-exif.jpg"))));
        assertFalse(spi.canDecodeInput(stream(new byte[0])));
        assertTrue(spi.canDecodeInput(stream(Resources.bytes("gradient.jxl"))));
        assertTrue(spi.canDecodeInput(stream(Resources.bytes("photo-420-exif.jxl"))));

        BufferedImage jpeg = ImageIO.read(new ByteArrayInputStream(Resources.bytes("photo-420-exif.jpg")));
        assertEquals(256, jpeg.getWidth());
    }

    @Test
    void canDecodeInputKeepsTheStreamPosition() throws IOException {
        try (ImageInputStream stream = stream(Resources.bytes("gradient.jxl"))) {
            new JxlImageReaderSpi().canDecodeInput(stream);
            assertEquals(0, stream.getStreamPosition());
        }
    }

    @Test
    void reportsSizeAndTypeWithoutReading() throws IOException {
        ImageReader reader = reader(Resources.bytes("gradient.jxl"));

        assertEquals(1, reader.getNumImages(true));
        assertEquals(Resources.GRADIENT_WIDTH, reader.getWidth(0));
        assertEquals(Resources.GRADIENT_HEIGHT, reader.getHeight(0));
        Iterator<ImageTypeSpecifier> types = reader.getImageTypes(0);
        assertEquals(BufferedImage.TYPE_4BYTE_ABGR, types.next().getBufferedImageType());
        assertEquals(DataBuffer.TYPE_USHORT, types.next().getSampleModel().getDataType());
        assertEquals(DataBuffer.TYPE_FLOAT, types.next().getSampleModel().getDataType());
        assertFalse(types.hasNext());
        assertThrows(IndexOutOfBoundsException.class, () -> reader.getWidth(1));
        assertThrows(IndexOutOfBoundsException.class, () -> reader.read(1));
    }

    @Test
    void appliesSourceRegionAndSubsampling() throws IOException {
        ImageReader reader = reader(Resources.bytes("gradient.jxl"));
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceRegion(new Rectangle(10, 5, 20, 15));
        param.setSourceSubsampling(2, 3, 0, 0);

        BufferedImage image = reader.read(0, param);

        assertEquals(10, image.getWidth());
        assertEquals(5, image.getHeight());
        int[] reference = Resources.gradientArgb();
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 10; x++) {
                int expected = reference[(5 + y * 3) * Resources.GRADIENT_WIDTH + 10 + x * 2];
                assertEquals(expected, image.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void reportsInvalidDataAsIioException() throws IOException {
        byte[] data = Resources.bytes("gradient.jxl");
        byte[] damaged = Arrays.copyOf(data, data.length);
        Arrays.fill(damaged, 2, damaged.length, (byte) 0x5A);

        ImageReader reader = reader(damaged);

        assertThrows(IIOException.class, () -> reader.read(0));
    }

    @Test
    void requiresInput() {
        ImageReader reader = new JxlImageReader(new JxlImageReaderSpi());
        assertThrows(IllegalStateException.class, () -> reader.getNumImages(true));
    }

    static BufferedImage read(byte[] data) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        assertNotNull(image, "no reader found");
        return image;
    }

    static int[] argb(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static ImageReader reader(byte[] data) throws IOException {
        ImageReader reader = new JxlImageReader(new JxlImageReaderSpi());
        reader.setInput(stream(data));
        return reader;
    }

    private static ImageInputStream stream(byte[] data) throws IOException {
        return ImageIO.createImageInputStream(new ByteArrayInputStream(data));
    }
}

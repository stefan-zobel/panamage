package panamage.jxl.imageio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.Test;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import panamage.jxl.JxlEncodeOptions;
import panamage.jxl.JxlEncoder;
import panamage.jxl.JxlFrameInfo;
import panamage.jxl.JxlImage;
import panamage.jxl.JxlLimitException;
import panamage.jxl.JxlLimits;

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
    void rejectsImagesBeyondThePixelLimit() throws IOException {
        JxlImageReader reader = reader(Resources.bytes("gradient.jxl"));
        long pixels = (long) Resources.GRADIENT_WIDTH * Resources.GRADIENT_HEIGHT;
        reader.setLimits(JxlLimits.defaults().withMaxPixels(pixels - 1));

        IIOException e = assertThrows(IIOException.class, () -> reader.read(0));
        assertInstanceOf(JxlLimitException.class, e.getCause());
        // The header is not limited, so the size can still be queried.
        assertEquals(Resources.GRADIENT_WIDTH, reader.getWidth(0));
        assertEquals(Resources.GRADIENT_HEIGHT, reader.getHeight(0));

        reader.setLimits(JxlLimits.defaults().withMaxPixels(pixels));
        assertEquals(Resources.GRADIENT_WIDTH, reader.read(0).getWidth());
    }

    @Test
    void rejectsMetadataBeyondTheMetadataLimit() throws IOException {
        JxlImageReader reader = reader(Resources.bytes("photo-420-exif.jxl"));
        reader.setLimits(JxlLimits.defaults().withMaxMetadataBytes(16));

        IIOException e = assertThrows(IIOException.class, () -> reader.getImageMetadata(0));
        assertInstanceOf(JxlLimitException.class, e.getCause());
    }

    @Test
    void keepsTheLimitsForNewInputUntilReset() throws IOException {
        JxlImageReader reader = reader(Resources.bytes("gradient.jxl"));
        assertEquals(JxlLimits.defaults(), reader.getLimits());
        JxlLimits limits = JxlLimits.defaults().withMaxPixels(1000);
        reader.setLimits(limits);

        reader.setInput(stream(Resources.bytes("photo-420-exif.jxl")));
        assertEquals(limits, reader.getLimits());

        reader.reset();
        assertEquals(JxlLimits.defaults(), reader.getLimits());
        assertThrows(NullPointerException.class, () -> reader.setLimits(null));
    }

    @Test
    void countsTheFramesOfAnAnimation() throws IOException {
        JxlImageReader reader = reader(Resources.bytes("animation.jxl"));

        assertEquals(-1, reader.getNumImages(false));
        assertEquals(3, reader.getNumImages(true));
        assertEquals(3, reader.getNumImages(false));
        assertEquals(Resources.ANIMATION_WIDTH, reader.getWidth(2));
        assertEquals(Resources.ANIMATION_HEIGHT, reader.getHeight(2));
        assertThrows(IndexOutOfBoundsException.class, () -> reader.read(3));
        assertThrows(IndexOutOfBoundsException.class, () -> reader.getWidth(-1));
        assertEquals(1, reader(Resources.bytes("gradient.jxl")).getNumImages(false));
    }

    @Test
    void readsTheFramesOfAnAnimationInAnyOrder() throws IOException {
        JxlImageReader reader = reader(Resources.bytes("animation.jxl"));
        for (int index : new int[] {0, 1, 2, 2, 0, 1, 1, 0}) {
            BufferedImage image = reader.read(index);
            assertEquals(BufferedImage.TYPE_4BYTE_ABGR, image.getType());
            assertArrayEquals(Resources.animationArgb(index), argb(image), "frame " + index);
        }
        // ImageIO.read returns the first frame.
        assertArrayEquals(Resources.animationArgb(0), argb(read(Resources.bytes("animation.jxl"))));
    }

    @Test
    void switchesTheSampleTypeBetweenFrames() throws IOException {
        JxlImageReader reader = reader(Resources.bytes("animation.jxl"));
        assertArrayEquals(Resources.animationArgb(0), argb(reader.read(0)));

        ImageReadParam param = reader.getDefaultReadParam();
        Iterator<ImageTypeSpecifier> types = reader.getImageTypes(1);
        types.next();
        param.setDestinationType(types.next());
        BufferedImage image = reader.read(1, param);
        assertEquals(DataBuffer.TYPE_USHORT, image.getRaster().getDataBuffer().getDataType());
        int[] expected = Resources.animationArgb(1);
        for (int p = 0; p < expected.length; p++) {
            int x = p % Resources.ANIMATION_WIDTH;
            int y = p / Resources.ANIMATION_WIDTH;
            assertEquals((expected[p] >> 16 & 0xFF) * 257, image.getRaster().getSample(x, y, 0), "pixel " + p);
            assertEquals((expected[p] >>> 24) * 257, image.getRaster().getSample(x, y, 3), "pixel " + p);
        }

        assertArrayEquals(Resources.animationArgb(2), argb(reader.read(2)));
    }

    @Test
    void describesEachFrameInTheMetadata() throws IOException {
        JxlImageReader reader = reader(Resources.bytes("animation.jxl"));

        JxlImageMetadata metadata = (JxlImageMetadata) reader.getImageMetadata(1);

        assertEquals(new JxlFrameInfo(200, 200.0, ""), metadata.getFrameInfo());
        Node animation = child(metadata.getAsTree(JxlImageMetadataFormat.NAME), "Animation");
        NamedNodeMap attributes = animation.getAttributes();
        assertEquals("1", attributes.getNamedItem("frameIndex").getNodeValue());
        assertEquals("200.0", attributes.getNamedItem("durationMillis").getNodeValue());
        assertEquals("200", attributes.getNamedItem("durationTicks").getNodeValue());
        assertEquals("1000", attributes.getNamedItem("ticksPerSecondNumerator").getNodeValue());
        assertEquals("1", attributes.getNamedItem("ticksPerSecondDenominator").getNodeValue());
        assertEquals("0", attributes.getNamedItem("loops").getNodeValue());
        assertEquals("", attributes.getNamedItem("name").getNodeValue());

        JxlImageMetadata still = (JxlImageMetadata) reader(Resources.bytes("gradient.jxl")).getImageMetadata(0);
        assertNull(still.getFrameInfo());
        assertNull(child(still.getAsTree(JxlImageMetadataFormat.NAME), "Animation"));
    }

    @Test
    void writesAFrameWithItsMetadataAsAStillImage() throws IOException {
        IIOImage frame = reader(Resources.bytes("animation.jxl")).readAll(1, null);
        JxlImageWriteParam param = new JxlImageWriteParam(null);
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionType(JxlImageWriteParam.LOSSLESS);

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jxl").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, frame, param);
        } finally {
            writer.dispose();
        }

        JxlImageReader copy = reader(out.toByteArray());
        assertEquals(1, copy.getNumImages(true));
        assertArrayEquals(Resources.animationArgb(1), argb(copy.read(0)));
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

    /** Reads an image in its own color space, without the conversion to sRGB. */
    static BufferedImage readOriginal(byte[] data) throws IOException {
        JxlImageReader reader = reader(data);
        reader.setConvertToSrgb(false);
        try {
            return reader.read(0);
        } finally {
            reader.dispose();
        }
    }

    static int[] argb(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static Node child(Node parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (name.equals(child.getNodeName())) {
                return child;
            }
        }
        return null;
    }

    private static JxlImageReader reader(byte[] data) throws IOException {
        JxlImageReader reader = new JxlImageReader(new JxlImageReaderSpi());
        reader.setInput(stream(data));
        return reader;
    }

    private static ImageInputStream stream(byte[] data) throws IOException {
        return ImageIO.createImageInputStream(new ByteArrayInputStream(data));
    }
}

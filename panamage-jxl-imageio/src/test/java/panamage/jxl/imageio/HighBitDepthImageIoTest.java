package panamage.jxl.imageio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;

import org.junit.jupiter.api.Test;

import panamage.jxl.JxlDecoder;
import panamage.jxl.JxlEncodeOptions;
import panamage.jxl.JxlEncoder;
import panamage.jxl.JxlImage;
import panamage.jxl.JxlImageInfo;
import panamage.jxl.JxlSampleType;

/**
 * Reading and writing images with 16-bit and floating point samples.
 */
class HighBitDepthImageIoTest {

    private static final Path OUTPUT_DIR = Path.of("target", "test-output");

    private static final int WIDTH = Resources.GRADIENT_WIDTH;
    private static final int HEIGHT = Resources.GRADIENT_HEIGHT;

    @Test
    void reads16BitImagesWithFullPrecision() throws IOException {
        BufferedImage image = JxlImageReaderTest.read(Resources.bytes("gradient16.jxl"));

        assertEquals(DataBuffer.TYPE_USHORT, image.getSampleModel().getDataType());
        assertTrue(image.getColorModel().getColorSpace().isCS_sRGB());
        assertTrue(image.getColorModel().hasAlpha());
        assertArrayEquals(gradient16Samples(), samples(image.getRaster()));
    }

    @Test
    void reads16BitGrayImagesAsUShortGray() throws IOException {
        short[] gray = new short[16 * 8];
        for (int i = 0; i < gray.length; i++) {
            gray[i] = (short) (i * 509);
        }
        byte[] encoded = JxlEncoder.encode(new JxlImage.Uint16(16, 8, 1, gray), JxlEncodeOptions.ofLossless());

        BufferedImage image = JxlImageReaderTest.read(encoded);

        assertEquals(BufferedImage.TYPE_USHORT_GRAY, image.getType());
        assertArrayEquals(gray, (short[]) image.getRaster().getDataElements(0, 0, 16, 8, null));
    }

    @Test
    void readsFloatImagesWithFullPrecision() throws IOException {
        BufferedImage image = JxlImageReaderTest.read(Resources.bytes("gradient-float.jxl"));

        assertEquals(DataBuffer.TYPE_FLOAT, image.getSampleModel().getDataType());
        assertEquals(3, image.getSampleModel().getNumBands());
        float[] samples = image.getRaster().getPixels(0, 0, WIDTH, HEIGHT, (float[]) null);
        assertArrayEquals(gradientFloatSamples(), samples);
    }

    @Test
    void offersTheImagePrecisionFirst() throws IOException {
        ImageReader reader = reader(Resources.bytes("gradient16.jxl"));

        Iterator<ImageTypeSpecifier> types = reader.getImageTypes(0);
        assertEquals(DataBuffer.TYPE_USHORT, types.next().getSampleModel().getDataType());
        assertEquals(BufferedImage.TYPE_4BYTE_ABGR, types.next().getBufferedImageType());
        assertEquals(DataBuffer.TYPE_FLOAT, types.next().getSampleModel().getDataType());
        assertFalse(types.hasNext());
    }

    @Test
    void readsA16BitImageWith8BitsOnRequest() throws IOException {
        ImageReader reader = reader(Resources.bytes("gradient16.jxl"));
        ImageReadParam param = reader.getDefaultReadParam();
        param.setDestinationType(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_4BYTE_ABGR));

        BufferedImage image = reader.read(0, param);

        assertEquals(BufferedImage.TYPE_4BYTE_ABGR, image.getType());
        int[] reference = gradient16Samples();
        int[] actual = samples(image.getRaster());
        for (int i = 0; i < reference.length; i++) {
            assertEquals(reference[i] * 255.0 / 65535.0, actual[i], 1.0, "sample " + i);
        }
    }

    @Test
    void rejectsUnsupportedDestinationTypes() throws IOException {
        ImageReader reader = reader(Resources.bytes("gradient16.jxl"));
        ImageReadParam param = reader.getDefaultReadParam();
        param.setDestinationType(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB));

        assertThrows(IIOException.class, () -> reader.read(0, param));
    }

    @Test
    void describesTheSampleFormatInTheStandardMetadata() throws IOException {
        assertEquals("16 16 16 16", standardData("gradient16.jxl", "BitsPerSample"));
        assertEquals("UnsignedIntegral", standardData("gradient16.jxl", "SampleFormat"));
        assertEquals("32 32 32", standardData("gradient-float.jxl", "BitsPerSample"));
        assertEquals("Real", standardData("gradient-float.jxl", "SampleFormat"));
    }

    @Test
    void floatAnd8BitReadsOfALossyImageShowTheSameColors() throws IOException {
        float[] samples = gradientFloatSamples();
        for (int i = 0; i < samples.length; i++) {
            samples[i] = Math.clamp(samples[i], 0.0f, 1.0f);
        }
        byte[] encoded = JxlEncoder.encode(new JxlImage.Float32(WIDTH, HEIGHT, 3, samples),
                JxlEncodeOptions.ofDistance(1.0f));
        save("imageio-float-lossy.jxl", encoded);

        BufferedImage floats = JxlImageReaderTest.read(encoded);
        ImageReader reader = reader(encoded);
        ImageReadParam param = reader.getDefaultReadParam();
        param.setDestinationType(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_3BYTE_BGR));
        BufferedImage bytes = reader.read(0, param);

        assertEquals(DataBuffer.TYPE_FLOAT, floats.getSampleModel().getDataType());
        // Both are sRGB, so the samples can be compared directly. getRGB is not used because
        // Java 2D does not clip float samples slightly below 0.0 that lossy encoding produces.
        assertTrue(floats.getColorModel().getColorSpace().isCS_sRGB());
        assertTrue(bytes.getColorModel().getColorSpace().isCS_sRGB());
        int[] expected = samples(bytes.getRaster());
        float[] actual = floats.getRaster().getPixels(0, 0, WIDTH, HEIGHT, (float[]) null);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], Math.clamp(actual[i], 0.0f, 1.0f) * 255.0f, 1.0f, "sample " + i);
        }
    }

    @Test
    void writes16BitPngImagesLosslessly() throws IOException {
        BufferedImage png = ImageIO.read(new ByteArrayInputStream(Resources.bytes("gradient16.png")));
        assertEquals(DataBuffer.TYPE_USHORT, png.getSampleModel().getDataType());

        byte[] encoded = JxlImageWriterTest.write(png, lossless());
        save("imageio-png16-lossless.jxl", encoded);

        JxlImageInfo info = JxlDecoder.readInfo(encoded);
        assertEquals(16, info.bitsPerSample());
        assertEquals(4, info.channels());
        assertArrayEquals(gradient16Samples(), samples(JxlImageReaderTest.read(encoded).getRaster()));
    }

    @Test
    void writesUShortGrayImagesLosslessly() throws IOException {
        BufferedImage source = new BufferedImage(32, 16, BufferedImage.TYPE_USHORT_GRAY);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 32; x++) {
                source.getRaster().setSample(x, y, 0, x * 2039 + y * 31);
            }
        }

        byte[] encoded = JxlImageWriterTest.write(source, lossless());

        JxlImageInfo info = JxlDecoder.readInfo(encoded);
        assertEquals(1, info.channels());
        assertEquals(JxlSampleType.UINT16, info.sampleType());
        BufferedImage decoded = JxlImageReaderTest.read(encoded);
        assertEquals(BufferedImage.TYPE_USHORT_GRAY, decoded.getType());
        assertArrayEquals(samples(source.getRaster()), samples(decoded.getRaster()));
    }

    @Test
    void writesFloatImagesLosslessly() throws IOException {
        ColorSpace srgb = ColorSpace.getInstance(ColorSpace.CS_sRGB);
        BufferedImage source = ImageTypeSpecifier.createInterleaved(srgb, new int[] {0, 1, 2}, DataBuffer.TYPE_FLOAT,
                false, false).createBufferedImage(WIDTH, HEIGHT);
        float[] reference = gradientFloatSamples();
        source.getRaster().setPixels(0, 0, WIDTH, HEIGHT, reference);

        byte[] encoded = JxlImageWriterTest.write(source, lossless());

        JxlImageInfo info = JxlDecoder.readInfo(encoded);
        assertEquals(JxlSampleType.FLOAT32, info.sampleType());
        assertTrue(info.isSrgb());
        BufferedImage decoded = JxlImageReaderTest.read(encoded);
        assertArrayEquals(reference, decoded.getRaster().getPixels(0, 0, WIDTH, HEIGHT, (float[]) null));
    }

    @Test
    void keepsTheColorSpaceOfImagesThatAreNotSrgb() throws IOException {
        ColorSpace linear = ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB);
        BufferedImage source = ImageTypeSpecifier.createInterleaved(linear, new int[] {0, 1, 2}, DataBuffer.TYPE_BYTE,
                false, false).createBufferedImage(WIDTH, HEIGHT);
        int[] reference = new int[WIDTH * HEIGHT * 3];
        for (int i = 0; i < reference.length; i++) {
            reference[i] = (i * 7) & 0xFF;
        }
        source.getRaster().setPixels(0, 0, WIDTH, HEIGHT, reference);

        byte[] encoded = JxlImageWriterTest.write(source, lossless());

        assertNotNull(JxlDecoder.readInfo(encoded).iccProfile());
        BufferedImage decoded = JxlImageReaderTest.readOriginal(encoded);
        assertInstanceOf(ICC_ColorSpace.class, decoded.getColorModel().getColorSpace());
        assertFalse(decoded.getColorModel().getColorSpace().isCS_sRGB());
        assertArrayEquals(reference, samples(decoded.getRaster()));
    }

    @Test
    void keeps16BitSamplesOfImagesThatAreNotSrgb() throws IOException {
        ColorSpace linear = ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB);
        BufferedImage source = ImageTypeSpecifier.createInterleaved(linear, new int[] {0, 1, 2, 3},
                DataBuffer.TYPE_USHORT, true, false).createBufferedImage(WIDTH, HEIGHT);
        source.getRaster().setPixels(0, 0, WIDTH, HEIGHT, gradient16Samples());

        byte[] encoded = JxlImageWriterTest.write(source, lossless());

        JxlImageInfo info = JxlDecoder.readInfo(encoded);
        assertEquals(JxlSampleType.UINT16, info.sampleType());
        assertNotNull(info.iccProfile());
        assertArrayEquals(gradient16Samples(), samples(JxlImageReaderTest.readOriginal(encoded).getRaster()));
    }

    @Test
    void convertsPremultipliedAlphaToStraightAlpha() throws IOException {
        BufferedImage source = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_4BYTE_ABGR_PRE);
        source.setRGB(0, 0, WIDTH, HEIGHT, Resources.gradientArgb(), 0, WIDTH);

        byte[] encoded = JxlImageWriterTest.write(source, lossless());

        assertTrue(JxlDecoder.readInfo(encoded).hasAlpha());
        int[] expected = JxlImageReaderTest.argb(source);
        int[] actual = JxlImageReaderTest.argb(JxlImageReaderTest.read(encoded));
        for (int p = 0; p < expected.length; p++) {
            for (int shift = 0; shift <= 24; shift += 8) {
                assertEquals(expected[p] >> shift & 0xFF, actual[p] >> shift & 0xFF, 1, "pixel " + p);
            }
        }
    }

    @Test
    void writesPackedIntImagesAs8BitSrgb() throws IOException {
        BufferedImage source = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, WIDTH, HEIGHT, Resources.gradientArgb(), 0, WIDTH);

        byte[] encoded = JxlImageWriterTest.write(source, lossless());

        JxlImageInfo info = JxlDecoder.readInfo(encoded);
        assertEquals(8, info.bitsPerSample());
        assertTrue(info.isSrgb());
        assertArrayEquals(Resources.gradientArgb(), JxlImageReaderTest.argb(JxlImageReaderTest.read(encoded)));
    }

    /** The samples of gradient16.jxl as unsigned values, interleaved RGBA. */
    private static int[] gradient16Samples() {
        byte[] raw = Resources.bytes("gradient16.rgba16");
        short[] shorts = new short[raw.length / 2];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);
        int[] samples = new int[shorts.length];
        for (int i = 0; i < shorts.length; i++) {
            samples[i] = Short.toUnsignedInt(shorts[i]);
        }
        return samples;
    }

    /** The samples of gradient-float.jxl, interleaved RGB. */
    private static float[] gradientFloatSamples() {
        byte[] raw = Resources.bytes("gradient-float.rgbf32");
        float[] samples = new float[raw.length / 4];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        return samples;
    }

    private static int[] samples(Raster raster) {
        return raster.getPixels(0, 0, raster.getWidth(), raster.getHeight(), (int[]) null);
    }

    private static String standardData(String resource, String element) throws IOException {
        IIOMetadataNode root = (IIOMetadataNode) reader(Resources.bytes(resource)).getImageMetadata(0)
                .getAsTree(IIOMetadataFormatImpl.standardMetadataFormatName);
        IIOMetadataNode node = (IIOMetadataNode) root.getElementsByTagName(element).item(0);
        assertNotNull(node, element);
        return node.getAttribute("value");
    }

    private static ImageReader reader(byte[] data) throws IOException {
        ImageReader reader = new JxlImageReader(new JxlImageReaderSpi());
        reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(data)));
        return reader;
    }

    private static JxlImageWriteParam lossless() {
        JxlImageWriteParam param = new JxlImageWriteParam(null);
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionType(JxlImageWriteParam.LOSSLESS);
        return param;
    }

    private static void save(String name, byte[] data) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Files.write(OUTPUT_DIR.resolve(name), data);
    }
}

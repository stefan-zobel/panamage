package panamage.jxl.imageio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.Test;

import panamage.jxl.JxlDecoder;
import panamage.jxl.JxlEncodeOptions;
import panamage.jxl.JxlImageInfo;
import panamage.jxl.JxlThreads;

class JxlImageWriterTest {

    /** Encoded files are kept here for cross-checks with the libjxl command line tools. */
    private static final Path OUTPUT_DIR = Path.of("target", "test-output");

    @Test
    void isRegisteredByFormatNameSuffixAndMimeType() {
        assertInstanceOf(JxlImageWriter.class, ImageIO.getImageWritersByFormatName("jxl").next());
        assertInstanceOf(JxlImageWriter.class, ImageIO.getImageWritersBySuffix("jxl").next());
        assertInstanceOf(JxlImageWriter.class, ImageIO.getImageWritersByMIMEType("image/jxl").next());
    }

    @Test
    void writesArgbImagesLosslessly() throws IOException {
        BufferedImage source = gradient(BufferedImage.TYPE_INT_ARGB);

        byte[] encoded = write(source, lossless());
        save("imageio-argb-lossless.jxl", encoded);

        assertArrayEquals(JxlImageReaderTest.argb(source), JxlImageReaderTest.argb(JxlImageReaderTest.read(encoded)));
        assertTrue(JxlDecoder.readInfo(encoded).hasAlpha());
    }

    @Test
    void writesOpaqueImagesWithoutAlpha() throws IOException {
        BufferedImage source = gradient(BufferedImage.TYPE_INT_RGB);

        byte[] encoded = write(source, lossless());

        JxlImageInfo info = JxlDecoder.readInfo(encoded);
        assertFalse(info.hasAlpha());
        assertEquals(3, info.colorChannels());
        assertArrayEquals(JxlImageReaderTest.argb(source), JxlImageReaderTest.argb(JxlImageReaderTest.read(encoded)));
    }

    @Test
    void writesGrayImagesAsGray() throws IOException {
        BufferedImage source = new BufferedImage(32, 16, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 32; x++) {
                source.getRaster().setSample(x, y, 0, x * 8 + y);
            }
        }

        byte[] encoded = write(source, lossless());

        assertEquals(1, JxlDecoder.readInfo(encoded).channels());
        BufferedImage decoded = JxlImageReaderTest.read(encoded);
        assertEquals(BufferedImage.TYPE_BYTE_GRAY, decoded.getType());
        assertArrayEquals((byte[]) source.getRaster().getDataElements(0, 0, 32, 16, null),
                (byte[]) decoded.getRaster().getDataElements(0, 0, 32, 16, null));
    }

    @Test
    void imageIoWriteUsesVisuallyLosslessDefaults() throws IOException {
        BufferedImage source = JxlImageReaderTest.read(Resources.bytes("photo-420-exif.jxl"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertTrue(ImageIO.write(source, "jxl", out));

        BufferedImage decoded = JxlImageReaderTest.read(out.toByteArray());
        double psnr = psnr(JxlImageReaderTest.argb(source), JxlImageReaderTest.argb(decoded));
        // The photo is mostly synthetic noise, which distance 1.0 does not preserve exactly;
        // libjxl 0.12.0 reaches about 35 dB here.
        assertTrue(psnr > 32.0, "PSNR too low: " + psnr);
    }

    @Test
    void lowerQualityProducesSmallerFiles() throws IOException {
        BufferedImage source = JxlImageReaderTest.read(Resources.bytes("photo-420-exif.jxl"));

        byte[] low = write(source, quality(0.5f));
        byte[] high = write(source, quality(0.95f));

        assertTrue(low.length < high.length, low.length + " >= " + high.length);
    }

    @Test
    void qualityOneIsLossless() throws IOException {
        BufferedImage source = gradient(BufferedImage.TYPE_INT_ARGB);
        JxlImageWriteParam param = quality(1.0f);

        assertTrue(param.isCompressionLossless());
        assertArrayEquals(JxlImageReaderTest.argb(source),
                JxlImageReaderTest.argb(JxlImageReaderTest.read(write(source, param))));
    }

    @Test
    void writeParamDescribesTheCompressionTypes() {
        JxlImageWriteParam param = new JxlImageWriteParam(null);
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);

        assertArrayEquals(new String[] {"Lossy", "Lossless"}, param.getCompressionTypes());
        assertEquals("Lossy", param.getCompressionType());
        assertFalse(param.isCompressionLossless());
        param.setCompressionType(JxlImageWriteParam.LOSSLESS);
        assertTrue(param.isCompressionLossless());
    }

    @Test
    void rejectsInvalidEffort() {
        JxlImageWriteParam param = new JxlImageWriteParam(null);
        assertThrows(IllegalArgumentException.class, () -> param.setEffort(0));
        assertThrows(IllegalArgumentException.class, () -> param.setEffort(11));
        param.setEffort(3);
        assertEquals(3, param.getEffort());
    }

    @Test
    void writesTheSourceRegion() throws IOException {
        BufferedImage source = gradient(BufferedImage.TYPE_INT_ARGB);
        JxlImageWriteParam param = lossless();
        param.setSourceRegion(new java.awt.Rectangle(8, 4, 16, 12));

        BufferedImage decoded = JxlImageReaderTest.read(write(source, param));

        assertEquals(16, decoded.getWidth());
        assertEquals(12, decoded.getHeight());
        assertEquals(source.getRGB(8, 4), decoded.getRGB(0, 0));
        assertEquals(source.getRGB(23, 15), decoded.getRGB(15, 11));
    }

    @Test
    void writesTheSameFileWithEveryThreadSetting() throws IOException {
        BufferedImage source = gradient(BufferedImage.TYPE_INT_ARGB);
        byte[] expected = write(source, lossless());

        JxlImageWriteParam param = lossless();
        assertEquals(JxlThreads.auto(), param.getThreads());
        for (JxlThreads threads : new JxlThreads[] {JxlThreads.none(), JxlThreads.fixed(2)}) {
            param.setThreads(threads);
            assertEquals(threads, JxlImageWriteParam.toOptions(param).threads());
            assertArrayEquals(expected, write(source, param), threads.toString());
        }
        assertThrows(NullPointerException.class, () -> param.setThreads(null));
    }

    @Test
    void otherParametersUseAutomaticThreads() {
        assertEquals(JxlThreads.auto(), JxlImageWriteParam.toOptions(null).threads());
        assertEquals(JxlEncodeOptions.defaults(), JxlImageWriteParam.toOptions(null));
        assertEquals(JxlThreads.auto(), JxlImageWriteParam.toOptions(new ImageWriteParam(null)).threads());
    }

    @Test
    void paletteImagesAreWrittenLosslesslyByDefault() throws IOException {
        BufferedImage indexed = new BufferedImage(Resources.GRADIENT_WIDTH, Resources.GRADIENT_HEIGHT,
                BufferedImage.TYPE_BYTE_INDEXED);
        indexed.getGraphics().drawImage(gradient(BufferedImage.TYPE_INT_RGB), 0, 0, null);
        byte[] lossless = write(indexed, lossless());

        assertArrayEquals(lossless, write(indexed, null));
        ImageWriteParam copy = new JxlImageWriteParam(null);
        copy.setCompressionMode(ImageWriteParam.MODE_COPY_FROM_METADATA);
        assertArrayEquals(lossless, write(indexed, copy));
        assertArrayEquals(JxlImageReaderTest.argb(indexed), JxlImageReaderTest.argb(JxlImageReaderTest.read(
                write(indexed, null))));
        // An explicit setting wins, and other images stay lossy by default.
        assertFalse(Arrays.equals(lossless, write(indexed, quality(0.9f))));
        BufferedImage rgb = gradient(BufferedImage.TYPE_INT_RGB);
        assertFalse(Arrays.equals(write(rgb, lossless()), write(rgb, null)));
    }

    @Test
    void paletteDefaultKeepsEffortAndThreads() {
        JxlImageWriteParam param = new JxlImageWriteParam(null);
        param.setEffort(3);
        param.setThreads(JxlThreads.none());
        assertEquals(JxlEncodeOptions.ofLossless().withEffort(3).withThreads(JxlThreads.none()),
                JxlImageWriteParam.toOptions(param, true));
        assertEquals(JxlEncodeOptions.defaults().withEffort(3).withThreads(JxlThreads.none()),
                JxlImageWriteParam.toOptions(param, false));
        assertEquals(JxlEncodeOptions.ofLossless(), JxlImageWriteParam.toOptions(null, true));
        assertEquals(JxlEncodeOptions.ofLossless(), JxlImageWriteParam.toOptions(new ImageWriteParam(null), true));
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionType(JxlImageWriteParam.LOSSY);
        assertFalse(JxlImageWriteParam.toOptions(param, true).lossless());
    }

    /** A 64x48 image with the pixels of gradient.rgba (alpha dropped for opaque types). */
    private static BufferedImage gradient(int type) {
        BufferedImage image = new BufferedImage(Resources.GRADIENT_WIDTH, Resources.GRADIENT_HEIGHT, type);
        int[] argb = Resources.gradientArgb();
        image.setRGB(0, 0, Resources.GRADIENT_WIDTH, Resources.GRADIENT_HEIGHT, argb, 0, Resources.GRADIENT_WIDTH);
        return image;
    }

    private static JxlImageWriteParam lossless() {
        JxlImageWriteParam param = new JxlImageWriteParam(null);
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionType(JxlImageWriteParam.LOSSLESS);
        return param;
    }

    private static JxlImageWriteParam quality(float quality) {
        JxlImageWriteParam param = new JxlImageWriteParam(null);
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionType(JxlImageWriteParam.LOSSY);
        param.setCompressionQuality(quality);
        return param;
    }

    @Test
    void writesAfterTheBytesAlreadyInTheStream() throws IOException {
        BufferedImage source = new BufferedImage(5, 3, BufferedImage.TYPE_INT_RGB);
        byte[] alone = write(source, lossless());
        byte[] prefix = {1, 2, 3};

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jxl").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            stream.write(prefix);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(source, null, null), lossless());
        } finally {
            writer.dispose();
        }

        byte[] written = out.toByteArray();
        assertEquals(prefix.length + alone.length, written.length);
        assertArrayEquals(prefix, Arrays.copyOf(written, prefix.length));
        assertArrayEquals(alone, Arrays.copyOfRange(written, prefix.length, written.length));
    }

    static byte[] write(BufferedImage image, ImageWriteParam param) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jxl").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static double psnr(int[] expected, int[] actual) {
        double squaredError = 0;
        for (int i = 0; i < expected.length; i++) {
            for (int shift = 0; shift <= 16; shift += 8) {
                int diff = (expected[i] >> shift & 0xFF) - (actual[i] >> shift & 0xFF);
                squaredError += diff * diff;
            }
        }
        double mse = squaredError / (expected.length * 3.0);
        return mse == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 / mse);
    }

    private static void save(String name, byte[] data) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Files.write(OUTPUT_DIR.resolve(name), data);
    }
}

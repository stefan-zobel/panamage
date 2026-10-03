package panamage.jxl.imageio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.Test;

import panamage.jxl.JxlAnimationHeader;
import panamage.jxl.JxlFrameInfo;

/**
 * GIF animations read with the JDK's GIF reader and written as a JPEG XL
 * sequence keep their timing and are composed as browsers show them.
 */
class GifConversionTest {

    private static final int TRANSPARENT = 0;
    private static final int RED = 1;
    private static final int BLUE = 2;
    private static final int GREEN = 3;
    private static final int WHITE = 4;

    /** Index 0 is transparent. */
    private static final IndexColorModel PALETTE = new IndexColorModel(8, 5,
            new byte[] {0, (byte) 255, 0, 0, (byte) 255}, new byte[] {0, 0, 0, (byte) 255, (byte) 255},
            new byte[] {0, 0, (byte) 255, 0, (byte) 255}, TRANSPARENT);

    private static final int ARGB_RED = 0xFFFF0000;
    private static final int ARGB_BLUE = 0xFF0000FF;
    private static final int ARGB_GREEN = 0xFF00FF00;
    private static final int ARGB_WHITE = 0xFFFFFFFF;
    private static final int ARGB_CLEAR = 0;

    private static final int WIDTH = 8;
    private static final int HEIGHT = 6;

    private record GifFrame(BufferedImage image, int left, int top, String disposal, int delay) {
    }

    @Test
    void optimizedAnimationsAreComposedAndKeepTheirTiming() throws IOException {
        BufferedImage blue = fill(3, 3, BLUE);
        blue.getRaster().setSample(1, 1, 0, TRANSPARENT);
        byte[] gif = gif(WIDTH, HEIGHT, 3,
                new GifFrame(fill(WIDTH, HEIGHT, RED), 0, 0, "none", 10),
                new GifFrame(blue, 1, 1, "restoreToBackgroundColor", 20),
                new GifFrame(fill(2, 2, GREEN), 4, 2, "restoreToPrevious", 0),
                new GifFrame(fill(1, 1, WHITE), 0, 0, "none", 5));

        byte[] jxl = convert(gif, true);

        int[] first = canvas(ARGB_RED);
        int[] second = first.clone();
        paint(second, 1, 1, 3, 3, ARGB_BLUE);
        second[2 * WIDTH + 2] = ARGB_RED;
        int[] cleared = second.clone();
        paint(cleared, 1, 1, 3, 3, ARGB_CLEAR);
        int[] third = cleared.clone();
        paint(third, 4, 2, 2, 2, ARGB_GREEN);
        int[] fourth = cleared.clone();
        fourth[0] = ARGB_WHITE;

        JxlImageReader reader = reader(jxl);
        assertEquals(4, reader.getNumImages(true));
        assertFrame(first, reader.read(0));
        assertFrame(second, reader.read(1));
        assertFrame(third, reader.read(2));
        assertFrame(fourth, reader.read(3));
        assertEquals(new JxlAnimationHeader(100, 1, 3), metadata(reader, 0).getAnimationHeader());
        double[] millis = new double[4];
        for (int i = 0; i < 4; i++) {
            millis[i] = metadata(reader, i).getFrameInfo().durationMillis();
        }
        assertEquals(Arrays.toString(new double[] {100, 200, 100, 50}), Arrays.toString(millis));
    }

    @Test
    void withoutStreamMetadataTheFirstFrameSetsTheSize() throws IOException {
        byte[] gif = gif(4, 4, -1,
                new GifFrame(fill(4, 4, RED), 0, 0, "none", 0),
                new GifFrame(fill(2, 2, BLUE), 2, 2, "none", 1));

        JxlImageReader reader = reader(convert(gif, false));

        assertEquals(4, reader.getWidth(0));
        BufferedImage second = reader.read(1);
        assertEquals(ARGB_BLUE, second.getRGB(3, 3));
        assertEquals(ARGB_RED, second.getRGB(1, 1));
        // Without a loop count, a GIF is played once; short delays last 100 milliseconds.
        assertEquals(1, metadata(reader, 0).getAnimationHeader().loops());
        assertEquals(100.0, metadata(reader, 0).getFrameInfo().durationMillis());
        assertEquals(100.0, metadata(reader, 1).getFrameInfo().durationMillis());
    }

    @Test
    void convertsGifMetadata() throws IOException {
        byte[] gif = gif(WIDTH, HEIGHT, 0, new GifFrame(fill(WIDTH, HEIGHT, RED), 0, 0, "none", 25));
        ImageReader gifReader = gifReader(gif);

        IIOMetadata converted = new JxlImageWriter(new JxlImageWriterSpi())
                .convertImageMetadata(gifReader.getImageMetadata(0), null, null);

        JxlImageMetadata metadata = assertInstanceOf(JxlImageMetadata.class, converted);
        assertEquals(new JxlAnimationHeader(100, 1, 0), metadata.getAnimationHeader());
        assertEquals(new JxlFrameInfo(25, 250.0, ""), metadata.getFrameInfo());
    }

    /** Reads every frame with the JDK's GIF reader and writes them as a lossless JPEG XL sequence. */
    private static byte[] convert(byte[] gif, boolean withStreamMetadata) throws IOException {
        ImageReader gifReader = gifReader(gif);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = new JxlImageWriter(new JxlImageWriterSpi());
        JxlImageWriteParam lossless = new JxlImageWriteParam(null);
        lossless.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        lossless.setCompressionType(JxlImageWriteParam.LOSSLESS);
        try (ImageOutputStream output = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(output);
            writer.prepareWriteSequence(withStreamMetadata ? gifReader.getStreamMetadata() : null);
            for (int i = 0; i < gifReader.getNumImages(true); i++) {
                writer.writeToSequence(gifReader.readAll(i, null), lossless);
            }
            writer.endWriteSequence();
        }
        return out.toByteArray();
    }

    /** Writes an animated GIF with the JDK's GIF writer. */
    private static byte[] gif(int width, int height, int loops, GifFrame... frames) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(output);
            IIOMetadata stream = writer.getDefaultStreamMetadata(null);
            IIOMetadataNode streamRoot = new IIOMetadataNode(GifMetadata.STREAM_FORMAT);
            IIOMetadataNode screen = new IIOMetadataNode("LogicalScreenDescriptor");
            screen.setAttribute("logicalScreenWidth", Integer.toString(width));
            screen.setAttribute("logicalScreenHeight", Integer.toString(height));
            screen.setAttribute("colorResolution", "8");
            screen.setAttribute("pixelAspectRatio", "0");
            streamRoot.appendChild(screen);
            stream.mergeTree(GifMetadata.STREAM_FORMAT, streamRoot);
            writer.prepareWriteSequence(stream);
            for (int i = 0; i < frames.length; i++) {
                GifFrame frame = frames[i];
                IIOMetadata metadata = writer.getDefaultImageMetadata(
                        ImageTypeSpecifier.createFromRenderedImage(frame.image()), null);
                IIOMetadataNode root = new IIOMetadataNode(GifMetadata.FORMAT);
                IIOMetadataNode descriptor = new IIOMetadataNode("ImageDescriptor");
                descriptor.setAttribute("imageLeftPosition", Integer.toString(frame.left()));
                descriptor.setAttribute("imageTopPosition", Integer.toString(frame.top()));
                descriptor.setAttribute("imageWidth", Integer.toString(frame.image().getWidth()));
                descriptor.setAttribute("imageHeight", Integer.toString(frame.image().getHeight()));
                descriptor.setAttribute("interlaceFlag", "FALSE");
                root.appendChild(descriptor);
                root.appendChild(colorTable());
                IIOMetadataNode control = new IIOMetadataNode("GraphicControlExtension");
                control.setAttribute("disposalMethod", frame.disposal());
                control.setAttribute("userInputFlag", "FALSE");
                control.setAttribute("transparentColorFlag", "TRUE");
                control.setAttribute("delayTime", Integer.toString(frame.delay()));
                control.setAttribute("transparentColorIndex", Integer.toString(TRANSPARENT));
                root.appendChild(control);
                if (i == 0 && loops >= 0) {
                    IIOMetadataNode extensions = new IIOMetadataNode("ApplicationExtensions");
                    IIOMetadataNode netscape = new IIOMetadataNode("ApplicationExtension");
                    netscape.setAttribute("applicationID", "NETSCAPE");
                    netscape.setAttribute("authenticationCode", "2.0");
                    netscape.setUserObject(new byte[] {1, (byte) loops, (byte) (loops >> 8)});
                    extensions.appendChild(netscape);
                    root.appendChild(extensions);
                }
                metadata.mergeTree(GifMetadata.FORMAT, root);
                writer.writeToSequence(new IIOImage(frame.image(), null, metadata), null);
            }
            writer.endWriteSequence();
        }
        return out.toByteArray();
    }

    /**
     * The palette as local color table. Without it, the default metadata of
     * the JDK 21 GIF writer brings its own color table, which replaces the
     * palette of the image.
     */
    private static IIOMetadataNode colorTable() {
        IIOMetadataNode table = new IIOMetadataNode("LocalColorTable");
        int size = 8;
        table.setAttribute("sizeOfLocalColorTable", Integer.toString(size));
        table.setAttribute("sortFlag", "FALSE");
        for (int i = 0; i < size; i++) {
            IIOMetadataNode entry = new IIOMetadataNode("ColorTableEntry");
            boolean used = i < PALETTE.getMapSize();
            entry.setAttribute("index", Integer.toString(i));
            entry.setAttribute("red", Integer.toString(used ? PALETTE.getRed(i) : 0));
            entry.setAttribute("green", Integer.toString(used ? PALETTE.getGreen(i) : 0));
            entry.setAttribute("blue", Integer.toString(used ? PALETTE.getBlue(i) : 0));
            table.appendChild(entry);
        }
        return table;
    }

    private static ImageReader gifReader(byte[] gif) throws IOException {
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(gif));
        reader.setInput(input);
        return reader;
    }

    private static JxlImageReader reader(byte[] jxl) throws IOException {
        JxlImageReader reader = new JxlImageReader(new JxlImageReaderSpi());
        reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(jxl)));
        return reader;
    }

    private static JxlImageMetadata metadata(JxlImageReader reader, int index) throws IOException {
        return (JxlImageMetadata) reader.getImageMetadata(index);
    }

    private static BufferedImage fill(int width, int height, int index) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, PALETTE);
        int[] samples = new int[width * height];
        Arrays.fill(samples, index);
        image.getRaster().setSamples(0, 0, width, height, 0, samples);
        return image;
    }

    private static int[] canvas(int argb) {
        int[] pixels = new int[WIDTH * HEIGHT];
        Arrays.fill(pixels, argb);
        return pixels;
    }

    private static void paint(int[] pixels, int left, int top, int width, int height, int argb) {
        for (int y = top; y < top + height; y++) {
            Arrays.fill(pixels, y * WIDTH + left, y * WIDTH + left + width, argb);
        }
    }

    /** Compares the pixels; fully transparent pixels compare only by their alpha. */
    private static void assertFrame(int[] expected, BufferedImage image) {
        assertEquals(WIDTH, image.getWidth());
        assertEquals(HEIGHT, image.getHeight());
        int[] actual = image.getRGB(0, 0, WIDTH, HEIGHT, null, 0, WIDTH);
        List<String> differences = new ArrayList<>();
        for (int i = 0; i < expected.length; i++) {
            boolean same = expected[i] == ARGB_CLEAR ? actual[i] >>> 24 == 0 : expected[i] == actual[i];
            if (!same) {
                differences.add(String.format("(%d,%d): %08x instead of %08x", i % WIDTH, i / WIDTH, actual[i],
                        expected[i]));
            }
        }
        assertEquals(List.of(), differences);
    }
}

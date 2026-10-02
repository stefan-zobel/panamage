package panamage.jxl.imageio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormat;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Node;

import panamage.jxl.JxlBox;
import panamage.jxl.JxlDecoder;
import panamage.jxl.JxlImageInfo;
import panamage.jxl.JxlMetadata;

class MetadataTest {

    private static final Path OUTPUT_DIR = Path.of("target", "test-output");

    private static final String NATIVE = JxlImageMetadataFormat.NAME;
    private static final String STANDARD = IIOMetadataFormatImpl.standardMetadataFormatName;

    @Test
    void readerProvidesExifInTheNativeTree() throws IOException {
        IIOImage image = readAll(Resources.bytes("photo-420-exif.jxl"), "jxl");

        JxlImageMetadata metadata = assertInstanceOf(JxlImageMetadata.class, image.getMetadata());
        assertTrue(metadata.isReadOnly());
        assertTrue(new String(metadata.getExif(), StandardCharsets.ISO_8859_1).contains("Panamage"));
        assertNull(metadata.getXmp());
        assertNull(metadata.getIccProfile());

        Node root = metadata.getAsTree(NATIVE);
        IIOMetadataNode exif = (IIOMetadataNode) root.getFirstChild();
        assertEquals("Exif", exif.getNodeName());
        assertArrayEquals(metadata.getExif(), (byte[]) exif.getUserObject());
        assertNull(exif.getNextSibling());
    }

    @Test
    void readerProvidesTheStandardTree() throws IOException {
        IIOMetadata metadata = readAll(Resources.bytes("gradient.jxl"), "jxl").getMetadata();

        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(STANDARD);
        assertEquals("RGB", attribute(root, "ColorSpaceType", "name"));
        assertEquals("4", attribute(root, "NumChannels", "value"));
        assertEquals("8 8 8 8", attribute(root, "BitsPerSample", "value"));
        assertEquals("nonpremultiplied", attribute(root, "Alpha", "value"));
        assertEquals("JPEG XL", attribute(root, "CompressionTypeName", "value"));
    }

    @Test
    void describesTheNativeFormat() throws IOException {
        IIOMetadata metadata = readAll(Resources.bytes("gradient.jxl"), "jxl").getMetadata();

        IIOMetadataFormat format = metadata.getMetadataFormat(NATIVE);

        assertNotNull(format);
        assertEquals(NATIVE, format.getRootName());
        assertEquals(IIOMetadataFormat.VALUE_LIST, format.getObjectValueType("Exif"));
    }

    @Test
    void readerMetadataIsReadOnly() throws IOException {
        JxlImageMetadata metadata = (JxlImageMetadata) readAll(Resources.bytes("gradient.jxl"), "jxl").getMetadata();

        assertThrows(IllegalStateException.class, () -> metadata.setXmp(new byte[1]));
        assertThrows(IllegalStateException.class, metadata::reset);
        assertThrows(IllegalStateException.class, () -> metadata.mergeTree(NATIVE, new IIOMetadataNode(NATIVE)));
    }

    @Test
    void jpegToJxlKeepsExifAndXmpAndAppliesTheOrientation() throws IOException {
        byte[] jpeg = Resources.bytes("photo-orient6-xmp.jpg");
        IIOImage image = readAll(jpeg, "jpeg");
        BufferedImage stored = (BufferedImage) image.getRenderedImage();

        byte[] jxl = write(image, lossless());
        save("imageio-orient6-xmp.jxl", jxl);

        JxlImageInfo info = JxlDecoder.readInfo(jxl);
        assertEquals(stored.getHeight(), info.width());
        assertEquals(stored.getWidth(), info.height());
        JxlMetadata metadata = JxlDecoder.readMetadata(jxl);
        assertArrayEquals(Resources.bytes("photo-orient6-xmp.xmp"), metadata.xmp());
        assertEquals(1, metadata.orientation());
        assertTrue(new String(metadata.exif(), StandardCharsets.ISO_8859_1).contains("Panamage"));

        // Rotated 90 degrees clockwise: displayed (x, y) comes from stored (y, height - 1 - x).
        BufferedImage upright = ImageIO.read(new ByteArrayInputStream(jxl));
        for (int[] p : new int[][] {{0, 0}, {10, 20}, {191, 255}, {100, 5}}) {
            int x = p[0];
            int y = p[1];
            assertEquals(stored.getRGB(y, stored.getHeight() - 1 - x), upright.getRGB(x, y), "pixel " + x + "," + y);
        }
    }

    @Test
    void jxlToJxlKeepsTheMetadataWithoutRotatingAgain() throws IOException {
        byte[] first = write(readAll(Resources.bytes("photo-orient6-xmp.jpg"), "jpeg"), lossless());

        byte[] second = write(readAll(first, "jxl"), lossless());

        assertEquals(JxlDecoder.readInfo(first).width(), JxlDecoder.readInfo(second).width());
        assertArrayEquals(JxlDecoder.readMetadata(first).exif(), JxlDecoder.readMetadata(second).exif());
        assertArrayEquals(JxlDecoder.readMetadata(first).xmp(), JxlDecoder.readMetadata(second).xmp());
        assertArrayEquals(JxlImageReaderTest.argb(ImageIO.read(new ByteArrayInputStream(first))),
                JxlImageReaderTest.argb(ImageIO.read(new ByteArrayInputStream(second))));
    }

    @Test
    void writesModifiedDefaultMetadata() throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jxl").next();
        JxlImageMetadata metadata = (JxlImageMetadata) writer.getDefaultImageMetadata(null, null);
        assertFalse(metadata.isReadOnly());
        byte[] xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".getBytes(StandardCharsets.UTF_8);
        metadata.setXmp(xmp);
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);

        byte[] jxl = write(new IIOImage(image, null, metadata), null);

        assertArrayEquals(xmp, JxlDecoder.readMetadata(jxl).xmp());
        assertNull(JxlDecoder.readMetadata(jxl).exif());
    }

    @Test
    void mergesTheNativeTree() throws Exception {
        JxlImageMetadata metadata = new JxlImageMetadata();
        IIOMetadataNode root = new IIOMetadataNode(NATIVE);
        IIOMetadataNode xmp = new IIOMetadataNode("XMP");
        xmp.setUserObject(new byte[] {'<', 'x', '/', '>'});
        root.appendChild(xmp);

        metadata.mergeTree(NATIVE, root);

        assertArrayEquals(new byte[] {'<', 'x', '/', '>'}, metadata.getXmp());
        metadata.reset();
        assertTrue(metadata.toJxlMetadata().isEmpty());
    }

    @Test
    void boxesSurviveReadingAndWriting() throws IOException {
        List<JxlBox> boxes = List.of(JxlBox.of("myCo", "{\"unit\": \"micron\"}".getBytes(StandardCharsets.UTF_8)),
                new JxlBox("jumb", new byte[] {1, 2, 3}, false));
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jxl").next();
        JxlImageMetadata metadata = (JxlImageMetadata) writer.getDefaultImageMetadata(null, null);
        writer.dispose();
        metadata.setBoxes(boxes);
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);

        byte[] first = write(new IIOImage(image, null, metadata), lossless());
        assertSameBoxes(boxes, JxlDecoder.readMetadata(first).boxes());

        JxlImageMetadata read = (JxlImageMetadata) readAll(first, "jxl").getMetadata();
        assertSameBoxes(boxes, read.getBoxes());
        assertThrows(IllegalStateException.class, () -> read.setBoxes(List.of()));
        IIOMetadataNode tree = (IIOMetadataNode) read.getAsTree(NATIVE);
        IIOMetadataNode box = (IIOMetadataNode) tree.getElementsByTagName("Box").item(1);
        assertEquals("jumb", box.getAttribute("type"));
        assertEquals("false", box.getAttribute("compressed"));
        assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) box.getUserObject());

        // Reading and writing again keeps the boxes.
        byte[] second = write(readAll(first, "jxl"), lossless());
        assertSameBoxes(boxes, JxlDecoder.readMetadata(second).boxes());
    }

    @Test
    void mergesBoxesFromTheNativeTree() throws Exception {
        JxlImageMetadata metadata = new JxlImageMetadata();
        metadata.setBoxes(List.of(JxlBox.of("old ", new byte[] {9})));
        IIOMetadataNode root = new IIOMetadataNode(NATIVE);
        IIOMetadataNode boxes = new IIOMetadataNode("Boxes");
        IIOMetadataNode compressed = new IIOMetadataNode("Box");
        compressed.setAttribute("type", "myCo");
        compressed.setUserObject(new byte[] {1});
        IIOMetadataNode plain = new IIOMetadataNode("Box");
        plain.setAttribute("type", "abcd");
        plain.setAttribute("compressed", "false");
        plain.setUserObject(new byte[] {2});
        boxes.appendChild(compressed);
        boxes.appendChild(plain);
        root.appendChild(boxes);

        metadata.mergeTree(NATIVE, root);

        assertSameBoxes(List.of(JxlBox.of("myCo", new byte[] {1}), new JxlBox("abcd", new byte[] {2}, false)),
                metadata.getBoxes());
        assertEquals(2, metadata.toJxlMetadata().boxes().size());
        metadata.reset();
        assertEquals(List.of(), metadata.getBoxes());
    }

    @Test
    void invalidBoxesInTheNativeTreeAreRejected() {
        for (String[] attributes : new String[][] {{"jxlc", "true"}, {"abc", "true"}, {"abcd", "yes"}, {null, null}}) {
            JxlImageMetadata metadata = new JxlImageMetadata();
            IIOMetadataNode root = new IIOMetadataNode(NATIVE);
            IIOMetadataNode boxes = new IIOMetadataNode("Boxes");
            IIOMetadataNode box = new IIOMetadataNode("Box");
            if (attributes[0] != null) {
                box.setAttribute("type", attributes[0]);
                box.setAttribute("compressed", attributes[1]);
            }
            box.setUserObject(new byte[] {1});
            boxes.appendChild(box);
            root.appendChild(boxes);
            assertThrows(IIOInvalidTreeException.class, () -> metadata.mergeTree(NATIVE, root),
                    String.valueOf(attributes[0]));
            assertEquals(List.of(), metadata.getBoxes());
        }
    }

    private static void assertSameBoxes(List<JxlBox> expected, List<JxlBox> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).type(), actual.get(i).type(), "box " + i);
            assertEquals(expected.get(i).compressed(), actual.get(i).compressed(), "box " + i);
            assertArrayEquals(expected.get(i).content(), actual.get(i).content(), "box " + i);
        }
    }

    private static IIOImage readAll(byte[] data, String format) throws IOException {
        ImageReader reader = ImageIO.getImageReadersByFormatName(format).next();
        try {
            reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(data)));
            return reader.readAll(0, null);
        } finally {
            reader.dispose();
        }
    }

    private static byte[] write(IIOImage image, ImageWriteParam param) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jxl").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, image, param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static JxlImageWriteParam lossless() {
        JxlImageWriteParam param = new JxlImageWriteParam(null);
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionType(JxlImageWriteParam.LOSSLESS);
        return param;
    }

    private static String attribute(IIOMetadataNode root, String element, String attribute) {
        IIOMetadataNode node = (IIOMetadataNode) root.getElementsByTagName(element).item(0);
        assertNotNull(node, element);
        return node.getAttribute(attribute);
    }

    private static void save(String name, byte[] data) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Files.write(OUTPUT_DIR.resolve(name), data);
    }
}

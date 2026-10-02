package panamage.jxl.imageio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.ArrayList;
import java.util.List;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.event.IIOWriteProgressListener;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.Test;

import panamage.jxl.JxlAnimationHeader;
import panamage.jxl.JxlAnimationInfo;
import panamage.jxl.JxlDecoder;
import panamage.jxl.JxlFrameInfo;

class SequenceWriterTest {

    /** Encoded files are kept here for cross-checks with the libjxl command line tools. */
    private static final Path OUTPUT_DIR = Path.of("target", "test-output");

    private static final String NATIVE = JxlImageMetadataFormat.NAME;

    private static final int WIDTH = 16;
    private static final int HEIGHT = 12;

    @Test
    void canWriteSequences() {
        assertTrue(writer().canWriteSequence());
    }

    @Test
    void readAllAndWriteToSequenceKeepTheFramesAndTheTiming() throws IOException {
        byte[] original = Resources.bytes("animation.jxl");
        List<IIOImage> frames = new ArrayList<>();
        ImageReader reader = ImageIO.getImageReadersByFormatName("jxl").next();
        try {
            reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(original)));
            for (int i = 0; i < reader.getNumImages(true); i++) {
                frames.add(reader.readAll(i, null));
            }
        } finally {
            reader.dispose();
        }

        byte[] data = writeSequence(frames, lossless());
        save("imageio-animation.jxl", data);

        assertEquals(JxlDecoder.readAnimationInfo(original), JxlDecoder.readAnimationInfo(data));
        ImageReader copy = ImageIO.getImageReadersByFormatName("jxl").next();
        try {
            copy.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(data)));
            for (int i = 0; i < 3; i++) {
                assertArrayEquals(Resources.animationArgb(i), JxlImageReaderTest.argb(copy.read(i)), "frame " + i);
            }
        } finally {
            copy.dispose();
        }
    }

    @Test
    void framesWithoutMetadataLastOneHundredMilliseconds() throws IOException {
        List<IIOImage> frames = List.of(frame(0xFFFF0000, null), frame(0xFF00FF00, null), frame(0x800000FF, null));

        byte[] data = writeSequence(frames, lossless());

        JxlFrameInfo hundred = new JxlFrameInfo(100, 100.0, "");
        assertEquals(new JxlAnimationInfo(1000, 1, 0, List.of(hundred, hundred, hundred)),
                JxlDecoder.readAnimationInfo(data));
        assertEquals(0x800000FF, argbOfFrame(data, 2));
    }

    @Test
    void takesTheHeaderFromTheFirstFrameAndTheDurationsFromEveryFrame() throws IOException {
        JxlImageMetadata first = new JxlImageMetadata();
        first.setAnimationHeader(new JxlAnimationHeader(10, 1, 2));
        first.setFrameInfo(new JxlFrameInfo(3, 0.0, "first"));
        first.setXmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".getBytes(StandardCharsets.UTF_8));

        JxlImageMetadata second = new JxlImageMetadata();
        IIOMetadataNode root = new IIOMetadataNode(NATIVE);
        IIOMetadataNode animation = new IIOMetadataNode("Animation");
        animation.setAttribute("durationTicks", "7");
        animation.setAttribute("name", "second");
        // The header of later frames is ignored.
        animation.setAttribute("ticksPerSecondNumerator", "50");
        root.appendChild(animation);
        second.mergeTree(NATIVE, root);

        byte[] data = writeSequence(List.of(frame(0xFFFF0000, first), frame(0xFF00FF00, second),
                frame(0xFF0000FF, null)), lossless());

        assertEquals(new JxlAnimationInfo(10, 1, 2, List.of(new JxlFrameInfo(3, 300, "first"),
                new JxlFrameInfo(7, 700, "second"), new JxlFrameInfo(1, 100, ""))), JxlDecoder.readAnimationInfo(data));
        assertArrayEquals(first.getXmp(), JxlDecoder.readMetadata(data).xmp());
    }

    @Test
    void defaultDurationFollowsTheTickRate() {
        assertEquals(100, JxlImageWriter.defaultTicks(JxlAnimationHeader.millis(0)));
        assertEquals(3, JxlImageWriter.defaultTicks(new JxlAnimationHeader(30000, 1001, 0)));
        assertEquals(1, JxlImageWriter.defaultTicks(new JxlAnimationHeader(1, 1, 0)));
    }

    @Test
    void rejectsAFrameOfAnotherSizeAndContinues() throws IOException {
        ImageWriter writer = writer();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            writer.writeToSequence(frame(0xFFFF0000, null), lossless());
            BufferedImage small = new BufferedImage(WIDTH / 2, HEIGHT, BufferedImage.TYPE_INT_ARGB);
            assertThrows(IllegalArgumentException.class,
                    () -> writer.writeToSequence(new IIOImage(small, null, null), lossless()));
            writer.writeToSequence(frame(0xFF00FF00, null), lossless());
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        assertEquals(2, JxlDecoder.readAnimationInfo(out.toByteArray()).frameCount());
    }

    @Test
    void enforcesTheOrderOfCalls() throws IOException {
        ImageWriter writer = writer();
        assertThrows(IllegalStateException.class, () -> writer.prepareWriteSequence(null));
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(new ByteArrayOutputStream())) {
            writer.setOutput(stream);
            assertThrows(IllegalStateException.class, () -> writer.writeToSequence(frame(0xFFFF0000, null), null));
            assertThrows(IllegalStateException.class, writer::endWriteSequence);

            writer.prepareWriteSequence(null);
            assertThrows(IllegalStateException.class, () -> writer.prepareWriteSequence(null));
            assertThrows(IllegalStateException.class, () -> writer.write(frame(0xFFFF0000, null)));
            // Ending a sequence without images fails and ends the sequence.
            assertThrows(IIOException.class, writer::endWriteSequence);
            assertThrows(IllegalStateException.class, writer::endWriteSequence);

            writer.prepareWriteSequence(null);
            writer.writeToSequence(frame(0xFFFF0000, null), null);
            // A new output abandons the sequence.
            writer.setOutput(stream);
            assertThrows(IllegalStateException.class, () -> writer.writeToSequence(frame(0xFFFF0000, null), null));
        } finally {
            writer.dispose();
        }
    }

    @Test
    void anAbortedFrameIsSkipped() throws IOException {
        ImageWriter writer = writer();
        List<String> events = new ArrayList<>();
        writer.addIIOWriteProgressListener(new IIOWriteProgressListener() {
            @Override
            public void imageStarted(ImageWriter source, int imageIndex) {
                events.add("started " + imageIndex);
                // Aborts the second image once; the next image gets the same index.
                if (imageIndex == 1 && !events.contains("aborted")) {
                    source.abort();
                }
            }

            @Override
            public void writeAborted(ImageWriter source) {
                events.add("aborted");
            }

            @Override
            public void imageProgress(ImageWriter source, float percentageDone) {
            }

            @Override
            public void imageComplete(ImageWriter source) {
                events.add("complete");
            }

            @Override
            public void thumbnailStarted(ImageWriter source, int imageIndex, int thumbnailIndex) {
            }

            @Override
            public void thumbnailProgress(ImageWriter source, float percentageDone) {
            }

            @Override
            public void thumbnailComplete(ImageWriter source) {
            }
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            writer.writeToSequence(frame(0xFFFF0000, null), lossless());
            writer.writeToSequence(frame(0xFF00FF00, null), lossless());
            writer.writeToSequence(frame(0xFF0000FF, null), lossless());
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }

        assertEquals(List.of("started 0", "complete", "started 1", "aborted", "started 1", "complete"), events);
        assertEquals(2, JxlDecoder.readAnimationInfo(out.toByteArray()).frameCount());
        assertEquals(0xFF0000FF, argbOfFrame(out.toByteArray(), 1));
    }

    @Test
    void writeIgnoresTheAnimationMetadata() throws IOException {
        JxlImageMetadata metadata = new JxlImageMetadata();
        metadata.setAnimationHeader(new JxlAnimationHeader(10, 1, 0));
        metadata.setFrameInfo(new JxlFrameInfo(5, 0.0, "frame"));

        ImageWriter writer = writer();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, frame(0xFFFF0000, metadata), lossless());
        } finally {
            writer.dispose();
        }

        assertFalse(JxlDecoder.readInfo(out.toByteArray()).animated());
    }

    @Test
    void metadataForWritingDescribesTheAnimationInTheNativeTree() throws IIOInvalidTreeException {
        JxlImageMetadata metadata = new JxlImageMetadata();
        assertNull(metadata.getFrameInfo());
        assertNull(metadata.getAnimationHeader());

        metadata.setAnimationHeader(new JxlAnimationHeader(20, 1, 4));
        metadata.setFrameInfo(new JxlFrameInfo(5, 0.0, "name"));

        assertEquals(new JxlFrameInfo(5, 250.0, "name"), metadata.getFrameInfo());
        IIOMetadataNode animation = (IIOMetadataNode) ((IIOMetadataNode) metadata.getAsTree(NATIVE))
                .getElementsByTagName("Animation").item(0);
        assertEquals("5", animation.getAttribute("durationTicks"));
        assertEquals("250.0", animation.getAttribute("durationMillis"));
        assertEquals("20", animation.getAttribute("ticksPerSecondNumerator"));
        assertEquals("1", animation.getAttribute("ticksPerSecondDenominator"));
        assertEquals("4", animation.getAttribute("loops"));
        assertEquals("name", animation.getAttribute("name"));
        assertFalse(animation.hasAttribute("frameIndex"));

        // A tree with only XMP replaces everything.
        IIOMetadataNode root = new IIOMetadataNode(NATIVE);
        root.appendChild(new IIOMetadataNode("XMP"));
        metadata.setFromTree(NATIVE, root);
        assertNull(metadata.getFrameInfo());
        assertNull(metadata.getAnimationHeader());

        metadata.setFrameInfo(new JxlFrameInfo(5, 0.0, ""));
        metadata.setFrameInfo(null);
        assertNull(metadata.getFrameInfo());
    }

    @Test
    void rejectsInvalidAnimationAttributesWithoutChangingAnything() {
        JxlImageMetadata metadata = new JxlImageMetadata();
        metadata.setFrameInfo(new JxlFrameInfo(5, 0.0, "kept"));
        for (String[] attribute : new String[][] {{"ticksPerSecondNumerator", "0"},
                {"ticksPerSecondDenominator", "1025"}, {"loops", "-1"}, {"durationTicks", "4294967296"},
                {"durationTicks", "soon"}, {"name", "a\0b"}}) {
            IIOMetadataNode root = new IIOMetadataNode(NATIVE);
            IIOMetadataNode animation = new IIOMetadataNode("Animation");
            animation.setAttribute("durationTicks", "9");
            animation.setAttribute(attribute[0], attribute[1]);
            root.appendChild(animation);
            assertThrows(IIOInvalidTreeException.class, () -> metadata.mergeTree(NATIVE, root), attribute[0]);
        }
        assertEquals(new JxlFrameInfo(5, 5.0, "kept"), metadata.getFrameInfo());
        assertNull(metadata.getAnimationHeader());
        assertThrows(IllegalArgumentException.class,
                () -> metadata.setFrameInfo(new JxlFrameInfo(1L << 32, 0.0, "")));
        assertThrows(IllegalArgumentException.class,
                () -> metadata.setFrameInfo(new JxlFrameInfo(1, 0.0, "x".repeat(1072))));
    }

    @Test
    void metadataOfAReadFrameIsReadOnlyAndConvertsForWriting() throws IOException {
        ImageReader reader = ImageIO.getImageReadersByFormatName("jxl").next();
        JxlImageMetadata read;
        try {
            reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(Resources.bytes("animation.jxl"))));
            read = (JxlImageMetadata) reader.getImageMetadata(2);
        } finally {
            reader.dispose();
        }
        assertEquals(new JxlAnimationHeader(1000, 1, 0), read.getAnimationHeader());
        assertThrows(IllegalStateException.class, () -> read.setFrameInfo(null));
        assertThrows(IllegalStateException.class, () -> read.setAnimationHeader(null));

        JxlImageMetadata converted = (JxlImageMetadata) writer().convertImageMetadata(read, null, null);

        assertFalse(converted.isReadOnly());
        assertEquals(read.getAnimationHeader(), converted.getAnimationHeader());
        assertEquals(read.getFrameInfo(), converted.getFrameInfo());
        assertNull(((IIOMetadataNode) converted.getAsTree(NATIVE)).getElementsByTagName("Animation").item(0)
                .getAttributes().getNamedItem("frameIndex"));
    }

    private static byte[] writeSequence(List<IIOImage> frames, ImageWriteParam param) throws IOException {
        ImageWriter writer = writer();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            for (IIOImage frame : frames) {
                writer.writeToSequence(frame, param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** A frame filled with one ARGB color. */
    private static IIOImage frame(int argb, JxlImageMetadata metadata) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return new IIOImage(image, null, metadata);
    }

    /** The ARGB value of the top left pixel of a frame. */
    private static int argbOfFrame(byte[] data, int index) throws IOException {
        ImageReader reader = ImageIO.getImageReadersByFormatName("jxl").next();
        try {
            reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(data)));
            return reader.read(index).getRGB(0, 0);
        } finally {
            reader.dispose();
        }
    }

    private static ImageWriter writer() {
        return ImageIO.getImageWritersByFormatName("jxl").next();
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

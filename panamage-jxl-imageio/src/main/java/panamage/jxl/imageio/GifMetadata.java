package panamage.jxl.imageio;

import java.awt.Dimension;
import java.util.Arrays;

import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;

import org.w3c.dom.Node;

import panamage.jxl.JxlAnimationHeader;
import panamage.jxl.JxlFrameInfo;

/**
 * Reads the position, disposal and timing of a frame from the metadata of
 * the JDK's GIF reader, and the size of the animation from its stream
 * metadata.
 */
final class GifMetadata {

    static final String FORMAT = "javax_imageio_gif_image_1.0";
    static final String STREAM_FORMAT = "javax_imageio_gif_stream_1.0";

    /** GIF delays are given in hundredths of a second. */
    static final long TICKS_PER_SECOND = 100;

    /**
     * The delay of frames with a delay of 0 or 1 hundredths of a second,
     * which browsers show for 100 milliseconds.
     */
    static final long SHORT_DELAY_TICKS = 10;

    /** What happens to the area of a frame before the next frame is drawn. */
    enum Disposal {
        /** The frame stays. */
        KEEP,
        /** The area of the frame becomes transparent. */
        CLEAR,
        /** The area returns to the state before the frame was drawn. */
        RESTORE
    }

    /**
     * The position, disposal and timing of a frame.
     *
     * @param loops the loop count of the NETSCAPE application extension (0
     *              for an endless animation), or -1 without one
     */
    record Frame(int left, int top, Disposal disposal, long delayTicks, int loops) {

        /** Converts the timing to metadata for the JPEG XL writer. */
        JxlImageMetadata toJxlMetadata() {
            JxlImageMetadata metadata = new JxlImageMetadata();
            // Without the extension, a GIF animation is played once.
            metadata.setAnimationHeader(new JxlAnimationHeader(TICKS_PER_SECOND, 1, loops < 0 ? 1 : loops));
            metadata.setFrameInfo(new JxlFrameInfo(delayTicks, delayTicks * 1000.0 / TICKS_PER_SECOND, ""));
            return metadata;
        }
    }

    private GifMetadata() {
    }

    static boolean isSupported(IIOMetadata metadata) {
        return hasFormat(metadata, FORMAT);
    }

    static boolean isSupportedStream(IIOMetadata metadata) {
        return hasFormat(metadata, STREAM_FORMAT);
    }

    private static boolean hasFormat(IIOMetadata metadata, String format) {
        String[] names = metadata.getMetadataFormatNames();
        return names != null && Arrays.asList(names).contains(format);
    }

    /** Reads the frame description from image metadata of the GIF reader. */
    static Frame read(IIOMetadata metadata) {
        Node root = metadata.getAsTree(FORMAT);
        int left = 0;
        int top = 0;
        Disposal disposal = Disposal.KEEP;
        long delay = 0;
        int loops = -1;
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            switch (child.getNodeName()) {
                case "ImageDescriptor" -> {
                    left = intAttribute(child, "imageLeftPosition");
                    top = intAttribute(child, "imageTopPosition");
                }
                case "GraphicControlExtension" -> {
                    disposal = switch (attribute(child, "disposalMethod")) {
                        case "restoreToBackgroundColor" -> Disposal.CLEAR;
                        case "restoreToPrevious" -> Disposal.RESTORE;
                        default -> Disposal.KEEP;
                    };
                    delay = intAttribute(child, "delayTime");
                }
                case "ApplicationExtensions" -> loops = loops(child);
                default -> {
                    // Color tables, comments and plain text do not affect the frames.
                }
            }
        }
        return new Frame(left, top, disposal, delay <= 1 ? SHORT_DELAY_TICKS : delay, loops);
    }

    /**
     * Returns the logical screen size from stream metadata of the GIF
     * reader, or {@code null} if it is not given.
     */
    static Dimension screenSize(IIOMetadata streamMetadata) {
        Node root = streamMetadata.getAsTree(STREAM_FORMAT);
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if ("LogicalScreenDescriptor".equals(child.getNodeName())) {
                int width = intAttribute(child, "logicalScreenWidth");
                int height = intAttribute(child, "logicalScreenHeight");
                return width > 0 && height > 0 ? new Dimension(width, height) : null;
            }
        }
        return null;
    }

    /** Returns the loop count of a NETSCAPE (or ANIMEXTS) extension, or -1. */
    private static int loops(Node extensions) {
        for (Node child = extensions.getFirstChild(); child != null; child = child.getNextSibling()) {
            String id = attribute(child, "applicationID");
            if (!"NETSCAPE".equals(id) && !"ANIMEXTS".equals(id)) {
                continue;
            }
            // Sub-block 1 holds the loop count as an unsigned 16-bit little-endian number.
            if (child instanceof IIOMetadataNode node && node.getUserObject() instanceof byte[] data
                    && data.length >= 3 && data[0] == 1) {
                return (data[1] & 0xFF) | (data[2] & 0xFF) << 8;
            }
        }
        return -1;
    }

    private static String attribute(Node node, String name) {
        Node attribute = node.getAttributes().getNamedItem(name);
        return attribute == null ? "" : attribute.getNodeValue();
    }

    private static int intAttribute(Node node, String name) {
        try {
            return Integer.parseInt(attribute(node, name));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

package panamage.jxl.imageio;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;

import org.w3c.dom.Node;

import panamage.jxl.JxlMetadata;

/**
 * Extracts EXIF and XMP from the native metadata of the JDK's JPEG reader,
 * where APP1 segments appear as {@code unknown} markers.
 */
final class JpegMetadata {

    static final String FORMAT = "javax_imageio_jpeg_image_1.0";

    private static final int APP1 = 0xE1;
    private static final byte[] EXIF_HEADER = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] XMP_HEADER = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1);

    private JpegMetadata() {
    }

    static boolean isSupported(IIOMetadata metadata) {
        String[] names = metadata.getMetadataFormatNames();
        return names != null && Arrays.asList(names).contains(FORMAT);
    }

    /** Returns the first EXIF and XMP segments, or {@link JxlMetadata#NONE}. */
    static JxlMetadata extract(IIOMetadata metadata) {
        byte[] exif = null;
        byte[] xmp = null;
        Node root = metadata.getAsTree(FORMAT);
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!"markerSequence".equals(child.getNodeName())) {
                continue;
            }
            for (Node marker = child.getFirstChild(); marker != null; marker = marker.getNextSibling()) {
                byte[] data = app1Data(marker);
                if (data == null) {
                    continue;
                }
                if (exif == null && startsWith(data, EXIF_HEADER)) {
                    exif = Arrays.copyOfRange(data, EXIF_HEADER.length, data.length);
                } else if (xmp == null && startsWith(data, XMP_HEADER)) {
                    xmp = Arrays.copyOfRange(data, XMP_HEADER.length, data.length);
                }
            }
        }
        return exif == null && xmp == null ? JxlMetadata.NONE : new JxlMetadata(exif, xmp);
    }

    private static byte[] app1Data(Node marker) {
        if (!"unknown".equals(marker.getNodeName()) || !(marker instanceof IIOMetadataNode node)) {
            return null;
        }
        Node tag = node.getAttributes().getNamedItem("MarkerTag");
        if (tag == null || !Integer.toString(APP1).equals(tag.getNodeValue())) {
            return null;
        }
        return node.getUserObject() instanceof byte[] data ? data : null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }
}

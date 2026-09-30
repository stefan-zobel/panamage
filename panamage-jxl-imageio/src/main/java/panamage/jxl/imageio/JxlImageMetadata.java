package panamage.jxl.imageio;

import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;

import org.w3c.dom.Node;

import panamage.jxl.JxlImageInfo;
import panamage.jxl.JxlMetadata;

/**
 * Image metadata of a JPEG XL image: EXIF, XMP and, for images that are not
 * sRGB, the ICC profile.
 * <p>
 * Supports the native format {@value JxlImageMetadataFormat#NAME} and, for
 * reading, the standard format {@code javax_imageio_1.0}. Metadata returned
 * by {@link JxlImageReader} is read-only; its EXIF orientation is 1 because
 * the reader returns upright images. Metadata from
 * {@link JxlImageWriter#getDefaultImageMetadata} or the public constructor can
 * be changed and is written by {@link JxlImageWriter}.
 */
public final class JxlImageMetadata extends IIOMetadata {

    private final boolean readOnly;
    private final JxlImageInfo info;
    private byte[] exif;
    private byte[] xmp;

    /**
     * Creates empty, modifiable metadata for writing.
     */
    public JxlImageMetadata() {
        this(null, JxlMetadata.NONE, false);
    }

    JxlImageMetadata(JxlImageInfo info, JxlMetadata metadata, boolean readOnly) {
        super(true, JxlImageMetadataFormat.NAME, JxlImageMetadataFormat.class.getName(), null, null);
        this.info = info;
        this.readOnly = readOnly;
        this.exif = metadata.exif();
        this.xmp = metadata.xmp();
    }

    /**
     * Returns the EXIF data, starting with the TIFF header.
     *
     * @return a copy of the EXIF data, or {@code null}
     */
    public byte[] getExif() {
        return exif == null ? null : exif.clone();
    }

    /**
     * Sets the EXIF data. An orientation other than 1 is stored as the image
     * orientation when writing, so the pixels are taken as stored.
     *
     * @param exif the EXIF data starting with the TIFF header, or {@code null}
     * @throws IllegalStateException if the metadata is read-only
     */
    public void setExif(byte[] exif) {
        checkWritable();
        this.exif = exif == null ? null : exif.clone();
    }

    /**
     * Returns the XMP packet.
     *
     * @return a copy of the XMP bytes, or {@code null}
     */
    public byte[] getXmp() {
        return xmp == null ? null : xmp.clone();
    }

    /**
     * Sets the XMP packet.
     *
     * @param xmp the XMP bytes, or {@code null}
     * @throws IllegalStateException if the metadata is read-only
     */
    public void setXmp(byte[] xmp) {
        checkWritable();
        this.xmp = xmp == null ? null : xmp.clone();
    }

    /**
     * Returns the ICC profile of the decoded pixels if they are not sRGB.
     *
     * @return a copy of the profile, or {@code null} for sRGB or metadata for writing
     */
    public byte[] getIccProfile() {
        return info == null || info.iccProfile() == null ? null : info.iccProfile().clone();
    }

    /**
     * Returns the EXIF and XMP data for {@link panamage.jxl.JxlEncoder}.
     *
     * @return the metadata
     */
    public JxlMetadata toJxlMetadata() {
        return exif == null && xmp == null ? JxlMetadata.NONE : new JxlMetadata(getExif(), getXmp());
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public Node getAsTree(String formatName) {
        if (JxlImageMetadataFormat.NAME.equals(formatName)) {
            return nativeTree();
        } else if (IIOMetadataFormatImpl.standardMetadataFormatName.equals(formatName)) {
            return getStandardTree();
        }
        throw new IllegalArgumentException("Unsupported metadata format: " + formatName);
    }

    @Override
    public void mergeTree(String formatName, Node root) throws IIOInvalidTreeException {
        checkWritable();
        if (IIOMetadataFormatImpl.standardMetadataFormatName.equals(formatName)) {
            // The standard format carries nothing that is written.
            return;
        }
        if (!JxlImageMetadataFormat.NAME.equals(formatName)) {
            throw new IllegalArgumentException("Unsupported metadata format: " + formatName);
        }
        if (root == null || !formatName.equals(root.getNodeName())) {
            throw new IIOInvalidTreeException("Root must be " + formatName, root);
        }
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            switch (child.getNodeName()) {
                case JxlImageMetadataFormat.EXIF -> exif = bytes(child);
                case JxlImageMetadataFormat.XMP -> xmp = bytes(child);
                case JxlImageMetadataFormat.ICC_PROFILE -> {
                    // Derived from the pixels when reading; not written.
                }
                default -> throw new IIOInvalidTreeException("Unknown element " + child.getNodeName(), child);
            }
        }
    }

    @Override
    public void reset() {
        checkWritable();
        exif = null;
        xmp = null;
    }

    private IIOMetadataNode nativeTree() {
        IIOMetadataNode root = new IIOMetadataNode(JxlImageMetadataFormat.NAME);
        addBytes(root, JxlImageMetadataFormat.EXIF, getExif());
        addBytes(root, JxlImageMetadataFormat.XMP, getXmp());
        addBytes(root, JxlImageMetadataFormat.ICC_PROFILE, getIccProfile());
        return root;
    }

    private static void addBytes(IIOMetadataNode parent, String name, byte[] value) {
        if (value != null) {
            IIOMetadataNode node = new IIOMetadataNode(name);
            node.setUserObject(value);
            parent.appendChild(node);
        }
    }

    private static byte[] bytes(Node node) throws IIOInvalidTreeException {
        Object value = node instanceof IIOMetadataNode metadataNode ? metadataNode.getUserObject() : null;
        if (value == null) {
            return null;
        }
        if (!(value instanceof byte[] data)) {
            throw new IIOInvalidTreeException(node.getNodeName() + " must hold a byte[]", node);
        }
        return data.clone();
    }

    private void checkWritable() {
        if (readOnly) {
            throw new IllegalStateException("The metadata is read-only");
        }
    }

    @Override
    protected IIOMetadataNode getStandardChromaNode() {
        if (info == null) {
            return null;
        }
        IIOMetadataNode chroma = new IIOMetadataNode("Chroma");
        chroma.appendChild(node("ColorSpaceType", "name", info.colorChannels() == 1 ? "GRAY" : "RGB"));
        chroma.appendChild(node("NumChannels", "value", Integer.toString(info.channels())));
        return chroma;
    }

    @Override
    protected IIOMetadataNode getStandardCompressionNode() {
        IIOMetadataNode compression = new IIOMetadataNode("Compression");
        compression.appendChild(node("CompressionTypeName", "value", "JPEG XL"));
        return compression;
    }

    @Override
    protected IIOMetadataNode getStandardDataNode() {
        if (info == null) {
            return null;
        }
        IIOMetadataNode data = new IIOMetadataNode("Data");
        data.appendChild(node("SampleFormat", "value", "UnsignedIntegral"));
        String bits = (info.bitsPerSample() + " ").repeat(info.channels()).strip();
        data.appendChild(node("BitsPerSample", "value", bits));
        return data;
    }

    @Override
    protected IIOMetadataNode getStandardDimensionNode() {
        IIOMetadataNode dimension = new IIOMetadataNode("Dimension");
        dimension.appendChild(node("ImageOrientation", "value", "Normal"));
        return dimension;
    }

    @Override
    protected IIOMetadataNode getStandardTransparencyNode() {
        if (info == null) {
            return null;
        }
        IIOMetadataNode transparency = new IIOMetadataNode("Transparency");
        transparency.appendChild(node("Alpha", "value", info.hasAlpha() ? "nonpremultiplied" : "none"));
        return transparency;
    }

    private static IIOMetadataNode node(String name, String attribute, String value) {
        IIOMetadataNode node = new IIOMetadataNode(name);
        node.setAttribute(attribute, value);
        return node;
    }
}

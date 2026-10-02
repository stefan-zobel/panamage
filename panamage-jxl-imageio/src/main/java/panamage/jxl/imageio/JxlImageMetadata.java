package panamage.jxl.imageio;

import java.nio.charset.StandardCharsets;

import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import panamage.jxl.JxlAnimationHeader;
import panamage.jxl.JxlAnimationInfo;
import panamage.jxl.JxlFrameInfo;
import panamage.jxl.JxlImageInfo;
import panamage.jxl.JxlMetadata;

/**
 * Image metadata of a JPEG XL image: EXIF, XMP and, for images that are not
 * sRGB, the ICC profile. For a frame of an animation, it also holds the
 * duration and name of the frame, the tick rate and the loop count.
 * <p>
 * Supports the native format {@value JxlImageMetadataFormat#NAME} and, for
 * reading, the standard format {@code javax_imageio_1.0}. Metadata returned
 * by {@link JxlImageReader} is read-only; its EXIF orientation is 1 because
 * the reader returns upright images. Metadata from
 * {@link JxlImageWriter#getDefaultImageMetadata} or the public constructor can
 * be changed and is written by {@link JxlImageWriter}; the animation header
 * and the frame information only apply when writing a sequence.
 */
public final class JxlImageMetadata extends IIOMetadata {

    /** The longest frame duration that JPEG XL stores, in ticks. */
    private static final long MAX_DURATION_TICKS = 0xFFFF_FFFFL;

    /** The longest frame name that libjxl accepts, in UTF-8 bytes. */
    private static final int MAX_NAME_BYTES = 1071;

    private final boolean readOnly;
    private final JxlImageInfo info;
    private final JxlAnimationInfo animation;
    private final int frameIndex;
    private byte[] exif;
    private byte[] xmp;
    private JxlAnimationHeader animationHeader;
    private long durationTicks = -1;
    private String frameName = "";

    /**
     * Creates empty, modifiable metadata for writing.
     */
    public JxlImageMetadata() {
        this(null, JxlMetadata.NONE, false);
    }

    JxlImageMetadata(JxlImageInfo info, JxlMetadata metadata, boolean readOnly) {
        this(info, metadata, readOnly, null, 0);
    }

    /**
     * Creates metadata for a frame of an animation.
     *
     * @param animation  the frames of the animation, or {@code null} for a
     *                   still image
     * @param frameIndex the index of the frame in {@code animation}
     */
    JxlImageMetadata(JxlImageInfo info, JxlMetadata metadata, boolean readOnly, JxlAnimationInfo animation,
            int frameIndex) {
        super(true, JxlImageMetadataFormat.NAME, JxlImageMetadataFormat.class.getName(), null, null);
        this.info = info;
        this.readOnly = readOnly;
        this.animation = animation;
        this.frameIndex = frameIndex;
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
     * Returns the duration and name of the frame: for metadata read by
     * {@link JxlImageReader}, those of the frame of the animation; for
     * metadata for writing, those set with {@link #setFrameInfo} or
     * {@link #mergeTree}, with the duration in milliseconds computed from the
     * {@linkplain #getAnimationHeader() animation header} (or 1000 ticks per
     * second without one).
     *
     * @return the frame information, or {@code null} for a still image or if
     *         no duration was set
     */
    public JxlFrameInfo getFrameInfo() {
        if (animation != null) {
            return animation.frames().get(frameIndex);
        }
        if (durationTicks < 0) {
            return null;
        }
        JxlAnimationHeader header = animationHeader == null ? JxlAnimationHeader.millis(0) : animationHeader;
        double millis = durationTicks * 1000.0 * header.ticksPerSecondDenominator()
                / header.ticksPerSecondNumerator();
        return new JxlFrameInfo(durationTicks, millis, frameName);
    }

    /**
     * Sets the duration in ticks and the name of the frame, for writing a
     * sequence with {@link JxlImageWriter}; the duration in milliseconds is
     * ignored.
     *
     * @param frame the duration in ticks and the name, or {@code null} to
     *              use the default duration of 100 milliseconds and no name
     * @throws IllegalArgumentException if the duration is above
     *                                  4294967295 ticks or the name is longer
     *                                  than 1071 bytes in UTF-8 or contains
     *                                  U+0000
     * @throws IllegalStateException    if the metadata is read-only
     */
    public void setFrameInfo(JxlFrameInfo frame) {
        checkWritable();
        if (frame == null) {
            durationTicks = -1;
            frameName = "";
        } else {
            checkDuration(frame.durationTicks());
            checkName(frame.name());
            durationTicks = frame.durationTicks();
            frameName = frame.name();
        }
    }

    /**
     * Returns the tick rate and the loop count: for metadata read by
     * {@link JxlImageReader}, those of the animation; for metadata for
     * writing, the header set with {@link #setAnimationHeader} or
     * {@link #mergeTree}.
     *
     * @return the animation header, or {@code null} for a still image or if
     *         none was set
     */
    public JxlAnimationHeader getAnimationHeader() {
        if (animation != null) {
            return new JxlAnimationHeader(animation.ticksPerSecondNumerator(),
                    animation.ticksPerSecondDenominator(), animation.loops());
        }
        return animationHeader;
    }

    /**
     * Sets the tick rate and the loop count, for writing a sequence with
     * {@link JxlImageWriter}; the header of the first image of the sequence
     * applies to the whole animation.
     *
     * @param header the animation header, or {@code null} for 1000 ticks per
     *               second and an animation played forever
     * @throws IllegalStateException if the metadata is read-only
     */
    public void setAnimationHeader(JxlAnimationHeader header) {
        checkWritable();
        animationHeader = header;
    }

    /** Returns the frame name for writing, also if no duration was set. */
    String frameName() {
        JxlFrameInfo frame = getFrameInfo();
        return frame == null ? frameName : frame.name();
    }

    /**
     * Returns modifiable metadata with the same EXIF, XMP, animation header
     * and frame information.
     */
    JxlImageMetadata copyForWriting() {
        JxlImageMetadata copy = new JxlImageMetadata(null, toJxlMetadata(), false);
        copy.animationHeader = getAnimationHeader();
        JxlFrameInfo frame = getFrameInfo();
        if (frame != null) {
            copy.durationTicks = frame.durationTicks();
        }
        copy.frameName = frameName();
        return copy;
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
                case JxlImageMetadataFormat.ANIMATION -> mergeAnimation(child);
                case JxlImageMetadataFormat.ICC_PROFILE -> {
                    // Derived from the image when reading; not written.
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
        animationHeader = null;
        durationTicks = -1;
        frameName = "";
    }

    /**
     * Takes the writable attributes of an {@code Animation} element; the
     * frame index and the duration in milliseconds are ignored.
     */
    private void mergeAnimation(Node node) throws IIOInvalidTreeException {
        NamedNodeMap attributes = node.getAttributes();
        Long numerator = longAttribute(node, attributes, JxlImageMetadataFormat.TICKS_PER_SECOND_NUMERATOR);
        Long denominator = longAttribute(node, attributes, JxlImageMetadataFormat.TICKS_PER_SECOND_DENOMINATOR);
        Long loops = longAttribute(node, attributes, JxlImageMetadataFormat.LOOPS);
        Long duration = longAttribute(node, attributes, JxlImageMetadataFormat.DURATION_TICKS);
        Node name = attributes.getNamedItem(JxlImageMetadataFormat.FRAME_NAME);
        // Validate everything before changing anything.
        JxlAnimationHeader header = animationHeader;
        String newName = name == null ? frameName : name.getNodeValue();
        try {
            if (numerator != null || denominator != null || loops != null) {
                JxlAnimationHeader base = header == null ? JxlAnimationHeader.millis(0) : header;
                header = new JxlAnimationHeader(numerator == null ? base.ticksPerSecondNumerator() : numerator,
                        denominator == null ? base.ticksPerSecondDenominator() : denominator,
                        loops == null ? base.loops() : loops);
            }
            if (duration != null) {
                checkDuration(duration);
            }
            checkName(newName);
        } catch (IllegalArgumentException e) {
            IIOInvalidTreeException invalid = new IIOInvalidTreeException(e.getMessage(), node);
            invalid.initCause(e);
            throw invalid;
        }
        animationHeader = header;
        if (duration != null) {
            durationTicks = duration;
        }
        frameName = newName;
    }

    private static Long longAttribute(Node node, NamedNodeMap attributes, String name)
            throws IIOInvalidTreeException {
        Node attribute = attributes.getNamedItem(name);
        if (attribute == null) {
            return null;
        }
        try {
            return Long.valueOf(attribute.getNodeValue().strip());
        } catch (NumberFormatException e) {
            IIOInvalidTreeException invalid = new IIOInvalidTreeException(name + " is not an integer: "
                    + attribute.getNodeValue(), node);
            invalid.initCause(e);
            throw invalid;
        }
    }

    private static void checkDuration(long ticks) {
        if (ticks < 0 || ticks > MAX_DURATION_TICKS) {
            throw new IllegalArgumentException("durationTicks must be 0 to " + MAX_DURATION_TICKS + ": " + ticks);
        }
    }

    private static void checkName(String name) {
        if (name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("The frame name must not contain U+0000");
        }
        int length = name.getBytes(StandardCharsets.UTF_8).length;
        if (length > MAX_NAME_BYTES) {
            throw new IllegalArgumentException("The frame name has " + length + " bytes in UTF-8, at most "
                    + MAX_NAME_BYTES + " are allowed");
        }
    }

    private IIOMetadataNode nativeTree() {
        IIOMetadataNode root = new IIOMetadataNode(JxlImageMetadataFormat.NAME);
        addBytes(root, JxlImageMetadataFormat.EXIF, getExif());
        addBytes(root, JxlImageMetadataFormat.XMP, getXmp());
        addBytes(root, JxlImageMetadataFormat.ICC_PROFILE, getIccProfile());
        JxlFrameInfo frame = getFrameInfo();
        JxlAnimationHeader header = getAnimationHeader();
        if (frame != null || header != null) {
            IIOMetadataNode node = new IIOMetadataNode(JxlImageMetadataFormat.ANIMATION);
            if (animation != null) {
                node.setAttribute(JxlImageMetadataFormat.FRAME_INDEX, Integer.toString(frameIndex));
            }
            if (frame != null) {
                node.setAttribute(JxlImageMetadataFormat.DURATION_MILLIS, Double.toString(frame.durationMillis()));
                node.setAttribute(JxlImageMetadataFormat.DURATION_TICKS, Long.toString(frame.durationTicks()));
            }
            if (header != null) {
                node.setAttribute(JxlImageMetadataFormat.TICKS_PER_SECOND_NUMERATOR,
                        Long.toString(header.ticksPerSecondNumerator()));
                node.setAttribute(JxlImageMetadataFormat.TICKS_PER_SECOND_DENOMINATOR,
                        Long.toString(header.ticksPerSecondDenominator()));
                node.setAttribute(JxlImageMetadataFormat.LOOPS, Long.toString(header.loops()));
            }
            node.setAttribute(JxlImageMetadataFormat.FRAME_NAME, frameName());
            root.appendChild(node);
        }
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
        data.appendChild(node("SampleFormat", "value", info.exponentBitsPerSample() > 0 ? "Real" : "UnsignedIntegral"));
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

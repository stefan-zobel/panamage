package panamage.jxl.imageio;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataFormat;
import javax.imageio.metadata.IIOMetadataFormatImpl;

/**
 * Describes the native image metadata format {@value #NAME}.
 * <p>
 * The root element has up to three children without attributes, each holding
 * a {@code byte[]} as user object: {@code Exif} (TIFF data), {@code XMP} (XML
 * bytes) and {@code ICCProfile} (read only; present if the decoded pixels are
 * not sRGB).
 */
public final class JxlImageMetadataFormat extends IIOMetadataFormatImpl {

    /** Name of the native image metadata format. */
    public static final String NAME = "panamage_jxl_image_1.0";

    static final String EXIF = "Exif";
    static final String XMP = "XMP";
    static final String ICC_PROFILE = "ICCProfile";

    private static final JxlImageMetadataFormat INSTANCE = new JxlImageMetadataFormat();

    private JxlImageMetadataFormat() {
        super(NAME, CHILD_POLICY_SOME);
        for (String element : new String[] {EXIF, XMP, ICC_PROFILE}) {
            addElement(element, NAME, CHILD_POLICY_EMPTY);
            addObjectValue(element, byte.class, 0, Integer.MAX_VALUE);
        }
    }

    /**
     * Returns the shared instance; called by {@link javax.imageio.metadata.IIOMetadata#getMetadataFormat}.
     *
     * @return the format description
     */
    public static IIOMetadataFormat getInstance() {
        return INSTANCE;
    }

    @Override
    public boolean canNodeAppear(String elementName, ImageTypeSpecifier imageType) {
        return true;
    }
}

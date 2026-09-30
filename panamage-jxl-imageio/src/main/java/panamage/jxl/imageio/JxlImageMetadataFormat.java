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
 * <p>
 * For a frame of an animation, the root also has an {@code Animation} element
 * (read only) with the attributes {@code frameIndex}, {@code durationMillis},
 * {@code durationTicks}, {@code ticksPerSecondNumerator},
 * {@code ticksPerSecondDenominator}, {@code loops} (0 plays the animation
 * forever) and {@code name} (empty if the frame has no name).
 */
public final class JxlImageMetadataFormat extends IIOMetadataFormatImpl {

    /** Name of the native image metadata format. */
    public static final String NAME = "panamage_jxl_image_1.0";

    static final String EXIF = "Exif";
    static final String XMP = "XMP";
    static final String ICC_PROFILE = "ICCProfile";
    static final String ANIMATION = "Animation";

    static final String FRAME_INDEX = "frameIndex";
    static final String DURATION_MILLIS = "durationMillis";
    static final String DURATION_TICKS = "durationTicks";
    static final String TICKS_PER_SECOND_NUMERATOR = "ticksPerSecondNumerator";
    static final String TICKS_PER_SECOND_DENOMINATOR = "ticksPerSecondDenominator";
    static final String LOOPS = "loops";
    static final String FRAME_NAME = "name";

    private static final JxlImageMetadataFormat INSTANCE = new JxlImageMetadataFormat();

    private JxlImageMetadataFormat() {
        super(NAME, CHILD_POLICY_SOME);
        for (String element : new String[] {EXIF, XMP, ICC_PROFILE}) {
            addElement(element, NAME, CHILD_POLICY_EMPTY);
            addObjectValue(element, byte.class, 0, Integer.MAX_VALUE);
        }
        addElement(ANIMATION, NAME, CHILD_POLICY_EMPTY);
        addAttribute(ANIMATION, FRAME_INDEX, DATATYPE_INTEGER, true, null);
        addAttribute(ANIMATION, DURATION_MILLIS, DATATYPE_DOUBLE, true, null);
        for (String attribute : new String[] {DURATION_TICKS, TICKS_PER_SECOND_NUMERATOR,
                TICKS_PER_SECOND_DENOMINATOR, LOOPS}) {
            addAttribute(ANIMATION, attribute, DATATYPE_INTEGER, true, null);
        }
        addAttribute(ANIMATION, FRAME_NAME, DATATYPE_STRING, true, "");
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

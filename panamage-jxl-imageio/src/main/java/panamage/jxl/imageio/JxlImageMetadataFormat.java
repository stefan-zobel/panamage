package panamage.jxl.imageio;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataFormat;
import javax.imageio.metadata.IIOMetadataFormatImpl;

/**
 * Describes the native image metadata format {@value #NAME}.
 * <p>
 * The root element has up to three children without attributes, each holding
 * a {@code byte[]} as user object: {@code Exif} (TIFF data), {@code XMP} (XML
 * bytes) and {@code ICCProfile} (read only; present if the image is not
 * sRGB, also if the reader converts its pixels to sRGB).
 * <p>
 * For a frame of an animation, the root also has an {@code Animation} element
 * with the attributes {@code frameIndex}, {@code durationMillis},
 * {@code durationTicks}, {@code ticksPerSecondNumerator},
 * {@code ticksPerSecondDenominator}, {@code loops} (0 plays the animation
 * forever) and {@code name} (empty if the frame has no name). When writing a
 * sequence, {@code durationTicks}, the tick rate, {@code loops} and
 * {@code name} can be set, each on its own; {@code frameIndex} and
 * {@code durationMillis} are derived and ignored.
 * <p>
 * Application-specific boxes are {@code Box} children of a {@code Boxes}
 * element, in the order of the file, each with the attributes {@code type}
 * (4 characters) and {@code compressed} ({@code true} or {@code false}) and a
 * {@code byte[]} as user object. When writing, a {@code Boxes} element
 * replaces all boxes.
 */
public final class JxlImageMetadataFormat extends IIOMetadataFormatImpl {

    /** Name of the native image metadata format. */
    public static final String NAME = "panamage_jxl_image_1.0";

    static final String EXIF = "Exif";
    static final String XMP = "XMP";
    static final String ICC_PROFILE = "ICCProfile";
    static final String ANIMATION = "Animation";
    static final String BOXES = "Boxes";
    static final String BOX = "Box";

    static final String FRAME_INDEX = "frameIndex";
    static final String DURATION_MILLIS = "durationMillis";
    static final String DURATION_TICKS = "durationTicks";
    static final String TICKS_PER_SECOND_NUMERATOR = "ticksPerSecondNumerator";
    static final String TICKS_PER_SECOND_DENOMINATOR = "ticksPerSecondDenominator";
    static final String LOOPS = "loops";
    static final String FRAME_NAME = "name";
    static final String BOX_TYPE = "type";
    static final String BOX_COMPRESSED = "compressed";

    private static final JxlImageMetadataFormat INSTANCE = new JxlImageMetadataFormat();

    private JxlImageMetadataFormat() {
        super(NAME, CHILD_POLICY_SOME);
        for (String element : new String[] {EXIF, XMP, ICC_PROFILE}) {
            addElement(element, NAME, CHILD_POLICY_EMPTY);
            addObjectValue(element, byte.class, 0, Integer.MAX_VALUE);
        }
        addElement(ANIMATION, NAME, CHILD_POLICY_EMPTY);
        addAttribute(ANIMATION, FRAME_INDEX, DATATYPE_INTEGER, false, null);
        addAttribute(ANIMATION, DURATION_MILLIS, DATATYPE_DOUBLE, false, null);
        for (String attribute : new String[] {DURATION_TICKS, TICKS_PER_SECOND_NUMERATOR,
                TICKS_PER_SECOND_DENOMINATOR, LOOPS}) {
            addAttribute(ANIMATION, attribute, DATATYPE_INTEGER, false, null);
        }
        addAttribute(ANIMATION, FRAME_NAME, DATATYPE_STRING, false, "");
        addElement(BOXES, NAME, 0, Integer.MAX_VALUE);
        addElement(BOX, BOXES, CHILD_POLICY_EMPTY);
        addAttribute(BOX, BOX_TYPE, DATATYPE_STRING, true, null);
        addBooleanAttribute(BOX, BOX_COMPRESSED, true, true);
        addObjectValue(BOX, byte.class, 0, Integer.MAX_VALUE);
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

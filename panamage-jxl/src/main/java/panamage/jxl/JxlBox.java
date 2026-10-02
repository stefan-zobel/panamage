package panamage.jxl;

import java.util.Objects;
import java.util.Set;

/**
 * An application-specific metadata box of a JPEG XL file, such as the
 * calibration of a microscope image or a JUMBF superbox ({@code jumb}).
 * <p>
 * A box has a type of 4 characters and any content. JPEG XL files that carry
 * boxes use the container format. The content can be stored Brotli-compressed
 * in a {@code brob} box; readers without Brotli support then cannot read it.
 * EXIF and XMP are not boxes in this sense: they are the fields of
 * {@link JxlMetadata}.
 *
 * @param type       the box type, 4 printable ASCII characters (U+0020 to
 *                   U+007E), for example {@code "jumb"}; types of the JPEG
 *                   XL container, types starting with {@code jxl} or
 *                   {@code JXL}, {@code Exif} and {@code xml } are not
 *                   allowed
 * @param content    the content of the box, without the box header
 * @param compressed whether the content is stored Brotli-compressed; when
 *                   read, whether it was stored so
 */
public record JxlBox(String type, byte[] content, boolean compressed) {

    /** Box types that the JPEG XL container or {@link JxlMetadata} use. */
    private static final Set<String> RESERVED = Set.of("ftyp", "brob", "jbrd", "Exif", "xml ");

    /**
     * Validates the box.
     *
     * @throws NullPointerException     if the type or the content is
     *                                  {@code null}
     * @throws IllegalArgumentException if the type is invalid or reserved
     */
    public JxlBox {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(content, "content");
        if (!isValidType(type)) {
            throw new IllegalArgumentException("The box type must have 4 printable ASCII characters: '" + type + "'");
        }
        if (isReserved(type)) {
            throw new IllegalArgumentException("The box type '" + type + "' is reserved"
                    + ("Exif".equals(type) || "xml ".equals(type) ? "; use the EXIF or XMP of JxlMetadata" : ""));
        }
    }

    /**
     * Returns a box whose content is stored Brotli-compressed.
     *
     * @param type    the box type
     * @param content the content of the box
     * @return the box
     * @throws IllegalArgumentException if the type is invalid or reserved
     */
    public static JxlBox of(String type, byte[] content) {
        return new JxlBox(type, content, true);
    }

    /** Returns whether the type has 4 printable ASCII characters. */
    static boolean isValidType(String type) {
        if (type.length() != 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            char c = type.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /** Returns whether the type belongs to the JPEG XL container or to {@link JxlMetadata}. */
    static boolean isReserved(String type) {
        return RESERVED.contains(type) || type.startsWith("jxl") || type.startsWith("JXL");
    }
}

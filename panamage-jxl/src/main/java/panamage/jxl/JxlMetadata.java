package panamage.jxl;

import java.util.List;
import java.util.Objects;

/**
 * EXIF, XMP and application-specific metadata of a JPEG XL image.
 * <p>
 * The orientation in the EXIF data always matches the pixels it travels
 * with: {@link JxlDecoder#readMetadata(byte[])} returns orientation 1 because
 * decoded images are upright, and {@link JxlEncoder} stores an EXIF
 * orientation as the image orientation, so pixels are passed as stored (as in
 * a JPEG file).
 * <p>
 * Further metadata, such as the calibration of a microscope image, can be
 * stored in boxes of their own type:
 * {@snippet :
 * JxlMetadata metadata = JxlMetadata.NONE.withBoxes(List.of(
 *         JxlBox.of("myCo", json.getBytes(StandardCharsets.UTF_8))));
 * }
 *
 * @param exif  the EXIF data, starting with the TIFF header ({@code II*\0} or
 *              {@code MM\0*}), or {@code null}
 * @param xmp   the XMP packet as XML bytes, or {@code null}
 * @param boxes the application-specific boxes in the order of the file
 */
public record JxlMetadata(byte[] exif, byte[] xmp, List<JxlBox> boxes) {

    /** No metadata. */
    public static final JxlMetadata NONE = new JxlMetadata(null, null);

    /**
     * Copies the list of boxes.
     *
     * @throws NullPointerException if the list or a box is {@code null}
     */
    public JxlMetadata {
        boxes = List.copyOf(boxes);
    }

    /**
     * Creates metadata without application-specific boxes.
     *
     * @param exif the EXIF data, starting with the TIFF header ({@code II*\0} or
     *             {@code MM\0*}), or {@code null}
     * @param xmp  the XMP packet as XML bytes, or {@code null}
     */
    public JxlMetadata(byte[] exif, byte[] xmp) {
        this(exif, xmp, List.of());
    }

    /**
     * Returns whether there is neither EXIF nor XMP data nor a box.
     *
     * @return {@code true} if all are absent
     */
    public boolean isEmpty() {
        return exif == null && xmp == null && boxes.isEmpty();
    }

    /**
     * Returns a copy of this metadata with other application-specific boxes.
     *
     * @param boxes the boxes
     * @return the new metadata
     * @throws NullPointerException if the list or a box is {@code null}
     */
    public JxlMetadata withBoxes(List<JxlBox> boxes) {
        return new JxlMetadata(exif, xmp, boxes);
    }

    /**
     * Returns the first box of the given type.
     *
     * @param type the box type
     * @return the box, or {@code null} if there is none of this type
     */
    public JxlBox box(String type) {
        Objects.requireNonNull(type, "type");
        for (JxlBox box : boxes) {
            if (box.type().equals(type)) {
                return box;
            }
        }
        return null;
    }

    /**
     * Returns the EXIF orientation from 1 to 8, or 1 if there is no EXIF data
     * or it contains no valid orientation.
     *
     * @return the orientation as defined by EXIF and JPEG XL
     */
    public int orientation() {
        return Exif.orientation(exif);
    }
}

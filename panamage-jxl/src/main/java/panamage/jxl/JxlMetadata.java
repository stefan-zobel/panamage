package panamage.jxl;

/**
 * EXIF and XMP metadata of a JPEG XL image.
 * <p>
 * The orientation in the EXIF data always matches the pixels it travels
 * with: {@link JxlDecoder#readMetadata(byte[])} returns orientation 1 because
 * decoded images are upright, and {@link JxlEncoder} stores an EXIF
 * orientation as the image orientation, so pixels are passed as stored (as in
 * a JPEG file).
 *
 * @param exif the EXIF data, starting with the TIFF header ({@code II*\0} or
 *             {@code MM\0*}), or {@code null}
 * @param xmp  the XMP packet as XML bytes, or {@code null}
 */
public record JxlMetadata(byte[] exif, byte[] xmp) {

    /** No metadata. */
    public static final JxlMetadata NONE = new JxlMetadata(null, null);

    /**
     * Returns whether there is neither EXIF nor XMP data.
     *
     * @return {@code true} if both are absent
     */
    public boolean isEmpty() {
        return exif == null && xmp == null;
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

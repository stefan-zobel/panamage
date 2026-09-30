package panamage.jxl.imageio;

/**
 * Names and identifiers shared by the reader and writer service providers.
 */
final class JxlFormat {

    static final String VENDOR = "panamage";

    static final String[] NAMES = {"jxl", "JXL", "jpeg xl", "JPEG XL", "jpeg-xl", "JPEG-XL"};

    static final String[] SUFFIXES = {"jxl"};

    static final String[] MIME_TYPES = {"image/jxl"};

    private JxlFormat() {
    }

    /** The version from the JAR manifest, or a placeholder when running from classes. */
    static String version() {
        String version = JxlFormat.class.getPackage().getImplementationVersion();
        return version != null ? version : "development";
    }
}

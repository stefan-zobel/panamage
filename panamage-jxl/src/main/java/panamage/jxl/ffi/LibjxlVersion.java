package panamage.jxl.ffi;

/**
 * The libjxl version whose headers these bindings were created from. Struct
 * layouts may differ between minor versions, so the loaded library must match
 * {@link #MAJOR} and {@link #MINOR}.
 */
public final class LibjxlVersion {

    /** Major version of libjxl. */
    public static final int MAJOR = 0;

    /** Minor version of libjxl. */
    public static final int MINOR = 12;

    /** Patch version of libjxl. */
    public static final int PATCH = 0;

    private LibjxlVersion() {
    }
}

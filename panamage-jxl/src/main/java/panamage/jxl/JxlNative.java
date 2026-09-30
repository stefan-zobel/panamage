package panamage.jxl;

import panamage.jxl.ffi.Jxl;
import panamage.jxl.internal.NativeLibraries;

/**
 * Information about the native libjxl library in use.
 * <p>
 * The library is loaded on first use from the first of these sources:
 * <ol>
 * <li>the directory named by the system property
 *     {@code panamage.jxl.library.path}, if set (then no other source is tried);</li>
 * <li>the platform artifact on the class path or module path, for example
 *     {@code panamage-jxl-natives-windows-x86_64}; its libraries are extracted
 *     to the directory named by {@code panamage.jxl.cache.dir}, by default
 *     {@code panamage-jxl} in {@code java.io.tmpdir};</li>
 * <li>libjxl installed on the system.</li>
 * </ol>
 * The library must have the major and minor version the bindings were created
 * for. Calling native code requires {@code --enable-native-access=panamage.jxl}
 * (or {@code ALL-UNNAMED} on the class path).
 */
public final class JxlNative {

    private JxlNative() {
    }

    /**
     * Returns the version of the loaded libjxl decoder as
     * {@code "major.minor.patch"}.
     *
     * @return the libjxl version string
     * @throws UnsatisfiedLinkError if no suitable libjxl can be loaded
     */
    public static String version() {
        // libjxl encodes its version as major * 1000000 + minor * 1000 + patch
        int version = Jxl.JxlDecoderVersion();
        return (version / 1_000_000) + "." + (version / 1_000 % 1_000) + "." + (version % 1_000);
    }

    /**
     * Describes where the loaded libjxl comes from, for diagnostics, for
     * example {@code bundled windows-x86_64 at <directory>}.
     *
     * @return the description of the library source
     * @throws UnsatisfiedLinkError if no suitable libjxl can be loaded
     */
    public static String librarySource() {
        return NativeLibraries.source();
    }
}

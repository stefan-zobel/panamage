package panamage.jxl.spi;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * A set of native libjxl libraries for one platform, packaged as resources.
 * <p>
 * Implementations are discovered with {@link java.util.ServiceLoader}. On the
 * module path, an implementing module declares
 * {@code provides panamage.jxl.spi.JxlNativeBundle with ...}; on the class
 * path, it lists the implementation in
 * {@code META-INF/services/panamage.jxl.spi.JxlNativeBundle}.
 */
public interface JxlNativeBundle {

    /**
     * Returns the platform the libraries are built for, as
     * {@code <os>-<arch>}, for example {@code windows-x86_64},
     * {@code linux-aarch64} or {@code macos-aarch64}.
     *
     * @return the platform identifier
     */
    String platform();

    /**
     * Returns the version of the bundled libjxl, for example {@code 0.12.0}.
     *
     * @return the libjxl version
     */
    String libjxlVersion();

    /**
     * Returns the file names of the bundled libraries in the order in which
     * they must be loaded: every library comes after the libraries it
     * depends on.
     *
     * @return the library file names, in load order
     */
    List<String> libraries();

    /**
     * Opens one of the bundled libraries.
     *
     * @param fileName a file name from {@link #libraries()}
     * @return a stream with the content of the library; the caller closes it
     * @throws IOException if the library cannot be read or is not part of this bundle
     */
    InputStream open(String fileName) throws IOException;
}

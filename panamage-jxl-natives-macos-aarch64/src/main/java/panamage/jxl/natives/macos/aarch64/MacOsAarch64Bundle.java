package panamage.jxl.natives.macos.aarch64;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;

import panamage.jxl.spi.JxlNativeBundle;

/**
 * The libjxl shared libraries for macOS on Apple silicon (arm64), stored as
 * resources next to this class under their install names.
 * <p>
 * libjxl publishes no macOS binaries; these libraries are built from the
 * libjxl sources by this project (see {@code natives/README.md} in the
 * repository). They need macOS 11 or newer and find each other through
 * {@code @rpath} relative to their own directory.
 */
public final class MacOsAarch64Bundle implements JxlNativeBundle {

    /** Every library comes after the libraries it needs. */
    private static final List<String> LIBRARIES = List.of(
            "libbrotlicommon.1.dylib",
            "libbrotlidec.1.dylib",
            "libbrotlienc.1.dylib",
            "libjxl_cms.0.12.dylib",
            "libjxl.0.12.dylib",
            "libjxl_threads.0.12.dylib");

    private final String libjxlVersion;

    /**
     * Creates the bundle; called by {@link java.util.ServiceLoader}.
     */
    public MacOsAarch64Bundle() {
        Properties properties = new Properties();
        try (InputStream in = MacOsAarch64Bundle.class.getResourceAsStream("bundle.properties")) {
            if (in == null) {
                throw new IllegalStateException("bundle.properties is missing");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        libjxlVersion = properties.getProperty("libjxl.version");
    }

    @Override
    public String platform() {
        return "macos-aarch64";
    }

    @Override
    public String libjxlVersion() {
        return libjxlVersion;
    }

    @Override
    public List<String> libraries() {
        return LIBRARIES;
    }

    @Override
    public InputStream open(String fileName) throws IOException {
        if (!LIBRARIES.contains(fileName)) {
            throw new IOException("Not part of this bundle: " + fileName);
        }
        InputStream in = MacOsAarch64Bundle.class.getResourceAsStream(fileName);
        if (in == null) {
            throw new IOException("Missing bundled library: " + fileName);
        }
        return in;
    }
}

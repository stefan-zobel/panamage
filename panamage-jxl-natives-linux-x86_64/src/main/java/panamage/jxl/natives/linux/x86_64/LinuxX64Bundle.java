package panamage.jxl.natives.linux.x86_64;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;

import panamage.jxl.spi.JxlNativeBundle;

/**
 * The libjxl shared libraries for Linux x86_64, stored as resources next to
 * this class under their SONAMEs.
 * <p>
 * The libraries come from the Ubuntu 20.04 packages of libjxl and Brotli.
 * They need glibc 2.29 or newer and the system's C++ runtime
 * ({@code libstdc++.so.6}); they do not run on musl-based systems such as
 * Alpine Linux.
 */
public final class LinuxX64Bundle implements JxlNativeBundle {

    /** Every library comes after the libraries it needs. */
    private static final List<String> LIBRARIES = List.of(
            "libbrotlicommon.so.1",
            "libbrotlidec.so.1",
            "libbrotlienc.so.1",
            "libjxl_cms.so.0.12",
            "libjxl.so.0.12",
            "libjxl_threads.so.0.12");

    private final String libjxlVersion;

    /**
     * Creates the bundle; called by {@link java.util.ServiceLoader}.
     */
    public LinuxX64Bundle() {
        Properties properties = new Properties();
        try (InputStream in = LinuxX64Bundle.class.getResourceAsStream("bundle.properties")) {
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
        return "linux-x86_64";
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
        InputStream in = LinuxX64Bundle.class.getResourceAsStream(fileName);
        if (in == null) {
            throw new IOException("Missing bundled library: " + fileName);
        }
        return in;
    }
}

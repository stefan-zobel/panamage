package panamage.jxl.natives.linux.musl.aarch64;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;

import panamage.jxl.spi.JxlNativeBundle;

/**
 * The libjxl shared libraries for Linux aarch64 (64-bit ARM) with the musl C
 * library, such as Alpine Linux, stored as resources next to this class under
 * their SONAMEs.
 * <p>
 * libjxl publishes no binaries for musl-based systems; these libraries are
 * built from the libjxl sources by this project (see {@code natives/README.md}
 * in the repository). They need musl 1.2.4 or newer and nothing else: the C++
 * runtime is linked statically, so no {@code libstdc++} has to be installed.
 * They find each other through a RUNPATH of {@code $ORIGIN} and do not run on
 * glibc-based systems.
 */
public final class LinuxMuslAarch64Bundle implements JxlNativeBundle {

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
    public LinuxMuslAarch64Bundle() {
        Properties properties = new Properties();
        try (InputStream in = LinuxMuslAarch64Bundle.class.getResourceAsStream("bundle.properties")) {
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
        return "linux-musl-aarch64";
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
        InputStream in = LinuxMuslAarch64Bundle.class.getResourceAsStream(fileName);
        if (in == null) {
            throw new IOException("Missing bundled library: " + fileName);
        }
        return in;
    }
}

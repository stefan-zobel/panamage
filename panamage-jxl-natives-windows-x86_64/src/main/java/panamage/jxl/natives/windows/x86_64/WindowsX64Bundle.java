package panamage.jxl.natives.windows.x86_64;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;

import panamage.jxl.spi.JxlNativeBundle;

/**
 * The libjxl DLLs for Windows x86_64, stored as resources next to this class.
 * <p>
 * The DLLs are built with the static C and C++ runtime, so they need nothing
 * but {@code KERNEL32.dll} and each other, and work with the Microsoft Visual
 * C++ runtime of any JDK, whatever its version.
 */
public final class WindowsX64Bundle implements JxlNativeBundle {

    /** Every library comes after the libraries it imports. */
    private static final List<String> LIBRARIES = List.of(
            "brotlicommon.dll",
            "brotlidec.dll",
            "brotlienc.dll",
            "jxl_cms.dll",
            "jxl.dll",
            "jxl_threads.dll");

    private final String libjxlVersion;

    /**
     * Creates the bundle; called by {@link java.util.ServiceLoader}.
     */
    public WindowsX64Bundle() {
        Properties properties = new Properties();
        try (InputStream in = WindowsX64Bundle.class.getResourceAsStream("bundle.properties")) {
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
        return "windows-x86_64";
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
        InputStream in = WindowsX64Bundle.class.getResourceAsStream(fileName);
        if (in == null) {
            throw new IOException("Missing bundled library: " + fileName);
        }
        return in;
    }
}

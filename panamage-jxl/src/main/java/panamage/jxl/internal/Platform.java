package panamage.jxl.internal;

import java.util.List;
import java.util.Locale;

import panamage.jxl.ffi.LibjxlVersion;

/**
 * Identifies the current platform as {@code <os>-<arch>}, using the same
 * identifiers as {@link panamage.jxl.spi.JxlNativeBundle#platform()}.
 */
final class Platform {

    /** Base names of the libjxl runtime libraries, in load order. */
    private static final List<String> LIBRARY_NAMES = List.of(
            "brotlicommon", "brotlidec", "brotlienc", "jxl_cms", "jxl", "jxl_threads");

    /** Libraries that must be present when loading from a directory or the system. */
    static final List<String> REQUIRED_LIBRARY_NAMES = List.of("jxl", "jxl_threads");

    private Platform() {
    }

    /**
     * Returns the identifier of the running platform.
     *
     * @throws IllegalArgumentException if the operating system or architecture is not supported
     */
    static String current() {
        return identify(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /**
     * Maps the values of the {@code os.name} and {@code os.arch} system
     * properties to a platform identifier.
     *
     * @throws IllegalArgumentException if the operating system or architecture is not supported
     */
    static String identify(String osName, String osArch) {
        return os(osName) + "-" + arch(osArch);
    }

    /**
     * Returns the possible file names of a library on the given platform, most
     * specific first: {@code jxl.dll} on Windows; {@code libjxl.so.0.12}
     * (the SONAME) and {@code libjxl.so} on Linux; {@code libjxl.0.12.dylib}
     * and {@code libjxl.dylib} on macOS.
     */
    static List<String> fileNames(String platform, String libraryName) {
        String os = platform.substring(0, platform.indexOf('-'));
        String version = abiVersion(libraryName);
        return switch (os) {
            case "windows" -> List.of(libraryName + ".dll");
            case "macos" -> List.of("lib" + libraryName + "." + version + ".dylib", "lib" + libraryName + ".dylib");
            default -> List.of("lib" + libraryName + ".so." + version, "lib" + libraryName + ".so");
        };
    }

    /** The version in the SONAME: 0.12 for libjxl libraries, 1 for Brotli. */
    private static String abiVersion(String libraryName) {
        return libraryName.startsWith("brotli") ? "1" : LibjxlVersion.MAJOR + "." + LibjxlVersion.MINOR;
    }

    /** Base names of the libjxl runtime libraries, in load order. */
    static List<String> libraryNames() {
        return LIBRARY_NAMES;
    }

    private static String os(String osName) {
        String name = osName.toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) {
            return "windows";
        } else if (name.startsWith("linux")) {
            return "linux";
        } else if (name.startsWith("mac") || name.startsWith("darwin")) {
            return "macos";
        }
        throw new IllegalArgumentException("Unsupported operating system: " + osName);
    }

    private static String arch(String osArch) {
        return switch (osArch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new IllegalArgumentException("Unsupported architecture: " + osArch);
        };
    }
}

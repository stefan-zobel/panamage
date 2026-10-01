package panamage.jxl.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import panamage.jxl.ffi.LibjxlVersion;

/**
 * Identifies the current platform as {@code <os>-<arch>}, or as
 * {@code linux-musl-<arch>} on Linux with the musl C library, using the same
 * identifiers as {@link panamage.jxl.spi.JxlNativeBundle#platform()}.
 */
final class Platform {

    /** Base names of the libjxl runtime libraries, in load order. */
    private static final List<String> LIBRARY_NAMES = List.of(
            "brotlicommon", "brotlidec", "brotlienc", "jxl_cms", "jxl", "jxl_threads");

    /** Libraries that must be present when loading from a directory or the system. */
    static final List<String> REQUIRED_LIBRARY_NAMES = List.of("jxl", "jxl_threads");

    /** Whether the running JVM uses the musl C library, determined once. */
    private static final class Libc {
        static final boolean MUSL = runsOnMusl();
    }

    private Platform() {
    }

    /**
     * Returns the identifier of the running platform.
     *
     * @throws IllegalArgumentException if the operating system or architecture is not supported
     */
    static String current() {
        String osName = System.getProperty("os.name", "");
        return identify(osName, System.getProperty("os.arch", ""), isLinux(osName) && Libc.MUSL);
    }

    /**
     * Maps the values of the {@code os.name} and {@code os.arch} system
     * properties to a platform identifier, for Linux with glibc.
     *
     * @throws IllegalArgumentException if the operating system or architecture is not supported
     */
    static String identify(String osName, String osArch) {
        return identify(osName, osArch, false);
    }

    /**
     * Maps the values of the {@code os.name} and {@code os.arch} system
     * properties to a platform identifier; {@code musl} selects
     * {@code linux-musl-<arch>} on Linux and is ignored elsewhere.
     *
     * @throws IllegalArgumentException if the operating system or architecture is not supported
     */
    static String identify(String osName, String osArch, boolean musl) {
        String os = os(osName);
        return (os.equals("linux") && musl ? "linux-musl" : os) + "-" + arch(osArch);
    }

    /** Returns whether the platform identifier denotes Linux with the musl C library. */
    static boolean isMusl(String platform) {
        return platform.startsWith("linux-musl-");
    }

    /**
     * Returns whether the running JVM uses the musl C library, as on Alpine
     * Linux. The memory map of the process shows the C library the JVM was
     * started with, so a musl installed beside glibc does not count. If the
     * map cannot be read, glibc is assumed.
     */
    private static boolean runsOnMusl() {
        // ISO-8859-1 accepts every byte of a path name.
        try (Stream<String> lines = Files.lines(Path.of("/proc/self/maps"), StandardCharsets.ISO_8859_1)) {
            return mapsMusl(lines);
        } catch (IOException | UncheckedIOException | SecurityException e) {
            return false;
        }
    }

    /**
     * Returns whether lines in the format of {@code /proc/<pid>/maps} contain
     * the musl dynamic loader, which is also the musl C library
     * ({@code /lib/ld-musl-<arch>.so.1}).
     */
    static boolean mapsMusl(Stream<String> lines) {
        return lines.anyMatch(line -> {
            int slash = line.lastIndexOf('/');
            return slash >= 0 && line.startsWith("ld-musl-", slash + 1);
        });
    }

    private static boolean isLinux(String osName) {
        return osName.toLowerCase(Locale.ROOT).startsWith("linux");
    }

    /**
     * Returns the possible file names of a library on the given platform, most
     * specific first: {@code jxl.dll} on Windows; {@code libjxl.so.0.12}
     * (the SONAME) and {@code libjxl.so} on Linux, with glibc or musl;
     * {@code libjxl.0.12.dylib} and {@code libjxl.dylib} on macOS.
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
        } else if (isLinux(name)) {
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

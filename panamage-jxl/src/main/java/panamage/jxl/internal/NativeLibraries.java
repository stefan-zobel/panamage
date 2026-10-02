package panamage.jxl.internal;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

import panamage.jxl.ffi.LibjxlVersion;
import panamage.jxl.spi.JxlNativeBundle;

/**
 * Finds and loads the native libjxl libraries and provides the symbol lookup
 * for the generated bindings in {@code panamage.jxl.ffi}.
 * <p>
 * Sources, in this order:
 * <ol>
 * <li>The directory named by the system property
 *     {@value #LIBRARY_PATH_PROPERTY}. If the property is set, it is the only
 *     source that is tried, so a misconfiguration is not hidden.</li>
 * <li>A {@link JxlNativeBundle} for the current platform on the class path or
 *     module path, extracted to the directory named by
 *     {@value #CACHE_DIR_PROPERTY} (default: {@code panamage-jxl-<user>} in
 *     {@code java.io.tmpdir}). On POSIX file systems, the default directory
 *     must be private to the current user, otherwise a new temporary
 *     directory is used instead; a configured directory is used as it
 *     is.</li>
 * <li>libjxl installed on the system, found by the operating system's
 *     library search.</li>
 * </ol>
 * The loaded library must have the major and minor version the bindings were
 * created for. This class must not use the generated bindings, because it
 * runs while they are being initialized.
 */
public final class NativeLibraries {

    /** System property naming a directory that contains the libjxl libraries. */
    public static final String LIBRARY_PATH_PROPERTY = "panamage.jxl.library.path";

    /** System property naming the directory to extract bundled libraries to. */
    public static final String CACHE_DIR_PROPERTY = "panamage.jxl.cache.dir";

    /** Recognized platforms for which panamage has no natives artifact yet. */
    private static final Set<String> UNBUNDLED_PLATFORMS = Set.of("macos-x86_64", "windows-aarch64");

    private record Loaded(SymbolLookup lookup, String source) {
    }

    /** Loads the libraries on first use; initialization is thread-safe. */
    private static final class Holder {
        static final Loaded LOADED = load();
    }

    private NativeLibraries() {
    }

    /**
     * Returns the lookup for the symbols of libjxl and its thread pool,
     * loading the libraries on first use.
     *
     * @return the symbol lookup
     * @throws UnsatisfiedLinkError if no suitable libjxl can be loaded
     */
    public static SymbolLookup lookup() {
        return Holder.LOADED.lookup();
    }

    /**
     * Describes where the loaded libraries come from, for diagnostics.
     *
     * @return a description such as {@code bundled windows-x86_64 at <directory>}
     * @throws UnsatisfiedLinkError if no suitable libjxl can be loaded
     */
    public static String source() {
        return Holder.LOADED.source();
    }

    private static Loaded load() {
        String directory = System.getProperty(LIBRARY_PATH_PROPERTY, "");
        if (!directory.isBlank()) {
            try {
                return fromDirectory(Path.of(directory));
            } catch (IllegalArgumentException | IOException e) {
                throw failure(List.of(LIBRARY_PATH_PROPERTY + "=" + directory + ": " + e.getMessage()));
            }
        }

        List<String> attempts = new ArrayList<>();
        try {
            Optional<Loaded> bundled = fromBundle(Platform.current());
            if (bundled.isPresent()) {
                return bundled.get();
            }
            String platform = Platform.current();
            attempts.add("bundled: no " + JxlNativeBundle.class.getSimpleName() + " for " + platform
                    + " on the class path or module path"
                    + (UNBUNDLED_PLATFORMS.contains(platform) ? "" : " (add panamage-jxl-natives-" + platform + ")"));
        } catch (IllegalArgumentException | IOException | ServiceConfigurationError e) {
            attempts.add("bundled: " + e.getMessage());
        }

        try {
            return fromSystem();
        } catch (IllegalArgumentException e) {
            attempts.add("system: " + e.getMessage());
        }
        throw failure(attempts);
    }

    private static Loaded fromDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("not a directory");
        }
        String platform = Platform.current();
        List<Path> paths = new ArrayList<>();
        for (String name : Platform.libraryNames()) {
            List<String> candidates = Platform.fileNames(platform, name);
            Optional<Path> file = candidates.stream().map(directory::resolve).filter(Files::isRegularFile).findFirst();
            if (file.isPresent()) {
                paths.add(file.get());
            } else if (Platform.REQUIRED_LIBRARY_NAMES.contains(name)) {
                throw new IOException(String.join(" or ", candidates) + " not found");
            }
        }
        return new Loaded(loadAll(paths), "directory " + directory.toAbsolutePath());
    }

    private static Optional<Loaded> fromBundle(String platform) throws IOException {
        for (JxlNativeBundle bundle : ServiceLoader.load(JxlNativeBundle.class)) {
            if (!bundle.platform().equals(platform)) {
                continue;
            }
            String configured = System.getProperty(CACHE_DIR_PROPERTY, "");
            // Only the default directory has a name that other users can predict.
            List<Path> paths = configured.isBlank()
                    ? BundleExtractor.extractPrivately(bundle,
                            defaultCacheRoot(System.getProperty("java.io.tmpdir"), System.getProperty("user.name")))
                    : BundleExtractor.extractUnchecked(bundle, Path.of(configured));
            return Optional.of(new Loaded(loadAll(paths),
                    "bundled " + platform + " at " + paths.getFirst().getParent()));
        }
        return Optional.empty();
    }

    /**
     * Loads libjxl through the operating system's library search, trying the
     * versioned name (the SONAME on Linux) before the plain name.
     */
    private static Loaded fromSystem() {
        String platform = Platform.current();
        List<String> loaded = new ArrayList<>();
        SymbolLookup lookup = null;
        for (String name : Platform.REQUIRED_LIBRARY_NAMES) {
            List<String> candidates = Platform.fileNames(platform, name);
            SymbolLookup library = null;
            for (String candidate : candidates) {
                try {
                    library = SymbolLookup.libraryLookup(candidate, Arena.global());
                    loaded.add(candidate);
                    break;
                } catch (IllegalArgumentException e) {
                    // Try the next name.
                }
            }
            if (library == null) {
                throw new IllegalArgumentException("Cannot open library: " + String.join(" or ", candidates));
            }
            lookup = lookup == null ? library : lookup.or(library);
        }
        checkVersion(lookup);
        return new Loaded(lookup, "system " + String.join(", ", loaded));
    }

    /** Loads the libraries in the given order and checks the libjxl version. */
    private static SymbolLookup loadAll(List<Path> paths) {
        SymbolLookup lookup = null;
        for (Path path : paths) {
            // A library's dependencies resolve to the already loaded libraries.
            SymbolLookup library = SymbolLookup.libraryLookup(path, Arena.global());
            lookup = lookup == null ? library : lookup.or(library);
        }
        if (lookup == null) {
            throw new IllegalArgumentException("no libraries to load");
        }
        checkVersion(lookup);
        return lookup;
    }

    private static void checkVersion(SymbolLookup lookup) {
        int version = decoderVersion(lookup);
        int major = version / 1_000_000;
        int minor = version / 1_000 % 1_000;
        if (major != LibjxlVersion.MAJOR || minor != LibjxlVersion.MINOR) {
            throw new IllegalArgumentException("libjxl " + major + "." + minor + "." + (version % 1_000)
                    + " found, but the bindings require " + LibjxlVersion.MAJOR + "." + LibjxlVersion.MINOR + ".x");
        }
    }

    private static int decoderVersion(SymbolLookup lookup) {
        MethodHandle handle = Linker.nativeLinker().downcallHandle(
                lookup.find("JxlDecoderVersion").orElseThrow(
                        () -> new IllegalArgumentException("JxlDecoderVersion not found; not a libjxl library")),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        try {
            return (int) handle.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException("JxlDecoderVersion failed", t);
        }
    }

    /**
     * Returns the default cache directory: {@code panamage-jxl-<user>} in the
     * temporary directory, with the characters of the user name other than
     * letters, digits, '.', '_' and '-' replaced by '_', so that users do not
     * share a directory.
     */
    static Path defaultCacheRoot(String temporaryDirectory, String userName) {
        String user = userName == null ? "" : userName.replaceAll("[^A-Za-z0-9._-]", "_");
        return Path.of(temporaryDirectory, "panamage-jxl-" + (user.isEmpty() ? "user" : user));
    }

    private static UnsatisfiedLinkError failure(List<String> attempts) {
        StringBuilder message = new StringBuilder("Cannot load libjxl ")
                .append(LibjxlVersion.MAJOR).append('.').append(LibjxlVersion.MINOR).append(".x:");
        for (String attempt : attempts) {
            message.append(System.lineSeparator()).append("  ").append(attempt);
        }
        String note = note(System.getProperty("os.name", ""), currentPlatform());
        if (note != null) {
            message.append(System.lineSeparator()).append("  Note: ").append(note);
        }
        return new UnsatisfiedLinkError(message.toString());
    }

    /**
     * Returns the hint for a failed load on the given operating system and
     * platform (null if the platform is not supported), or null if there is
     * none.
     */
    static String note(String osName, String platform) {
        if (osName.startsWith("Windows")) {
            return "libjxl DLLs other than the bundled ones, such as those of the libjxl releases, need the"
                    + " Microsoft Visual C++ runtime (msvcp140.dll, vcruntime140.dll) 14.40 or newer;"
                    + " the copy in the bin directory of some JDKs is older.";
        } else if (platform != null && UNBUNDLED_PLATFORMS.contains(platform)) {
            return "there are no bundled libraries for " + platform + " yet; install libjxl "
                    + LibjxlVersion.MAJOR + "." + LibjxlVersion.MINOR + " on the system or set "
                    + LIBRARY_PATH_PROPERTY + " to a directory with libraries built for this platform.";
        } else if (platform != null && Platform.isMusl(platform)) {
            return "the bundled libraries for musl-based systems need musl 1.2.4 or newer"
                    + " (for example Alpine Linux 3.18 or newer).";
        } else if (osName.startsWith("Linux")) {
            return "the bundled libraries need glibc " + ("linux-aarch64".equals(platform) ? "2.28" : "2.29")
                    + " or newer and libstdc++.";
        }
        return null;
    }

    private static String currentPlatform() {
        try {
            return Platform.current();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

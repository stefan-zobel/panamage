package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class JxlNativeTest {

    @Test
    void reportsTheBundledLibjxlVersion() {
        assertEquals("0.12.0", JxlNative.version());
    }

    @Test
    @DisabledIfSystemProperty(named = "panamage.jxl.library.path", matches = ".+")
    void loadsTheBundledLibrariesFromTheConfiguredCache() throws IOException {
        String osName = System.getProperty("os.name");
        String platform;
        String libjxlFile;
        if (osName.startsWith("Windows")) {
            platform = "windows-x86_64";
            libjxlFile = "jxl.dll";
        } else if (osName.startsWith("Mac")) {
            platform = "macos-aarch64";
            libjxlFile = "libjxl.0.12.dylib";
        } else {
            // The JVM of a musl-based system such as Alpine Linux runs with the musl loader.
            boolean musl = Files.readString(Path.of("/proc/self/maps"), StandardCharsets.ISO_8859_1)
                    .contains("/ld-musl-");
            String arch = System.getProperty("os.arch").equals("aarch64") ? "aarch64" : "x86_64";
            platform = (musl ? "linux-musl-" : "linux-") + arch;
            libjxlFile = "libjxl.so.0.12";
        }
        String prefix = "bundled " + platform + " at ";
        String source = JxlNative.librarySource();
        assertTrue(source.startsWith(prefix), source);

        Path directory = Path.of(source.substring(prefix.length()));
        Path cacheRoot = Path.of(System.getProperty("panamage.jxl.cache.dir"));
        assertEquals(cacheRoot.toAbsolutePath().normalize(), directory.getParent().toAbsolutePath().normalize());
        assertTrue(directory.getFileName().toString().startsWith(platform + "-0.12.0-"), source);
        assertTrue(Files.isRegularFile(directory.resolve(libjxlFile)), source);
    }

    @Test
    @EnabledIfSystemProperty(named = "panamage.jxl.library.path", matches = ".+")
    void loadsFromTheConfiguredDirectory() {
        Path configured = Path.of(System.getProperty("panamage.jxl.library.path")).toAbsolutePath();
        assertEquals("directory " + configured, JxlNative.librarySource());
    }
}

package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void loadsTheBundledLibrariesFromTheConfiguredCache() {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String platform = windows ? "windows-x86_64" : "linux-x86_64";
        String prefix = "bundled " + platform + " at ";
        String source = JxlNative.librarySource();
        assertTrue(source.startsWith(prefix), source);

        Path directory = Path.of(source.substring(prefix.length()));
        Path cacheRoot = Path.of(System.getProperty("panamage.jxl.cache.dir"));
        assertEquals(cacheRoot.toAbsolutePath().normalize(), directory.getParent().toAbsolutePath().normalize());
        assertTrue(directory.getFileName().toString().startsWith(platform + "-0.12.0-"), source);
        assertTrue(Files.isRegularFile(directory.resolve(windows ? "jxl.dll" : "libjxl.so.0.12")), source);
    }

    @Test
    @EnabledIfSystemProperty(named = "panamage.jxl.library.path", matches = ".+")
    void loadsFromTheConfiguredDirectory() {
        Path configured = Path.of(System.getProperty("panamage.jxl.library.path")).toAbsolutePath();
        assertEquals("directory " + configured, JxlNative.librarySource());
    }
}

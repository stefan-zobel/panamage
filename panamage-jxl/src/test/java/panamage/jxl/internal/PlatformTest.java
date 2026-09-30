package panamage.jxl.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlatformTest {

    @ParameterizedTest
    @CsvSource({
            "Windows 11,        amd64,   windows-x86_64",
            "Windows Server 2025, aarch64, windows-aarch64",
            "Linux,             amd64,   linux-x86_64",
            "Linux,             aarch64, linux-aarch64",
            "Mac OS X,          aarch64, macos-aarch64",
            "Mac OS X,          x86_64,  macos-x86_64"
    })
    void identifiesSupportedPlatforms(String osName, String osArch, String expected) {
        assertEquals(expected, Platform.identify(osName, osArch));
    }

    @Test
    void rejectsUnsupportedPlatforms() {
        assertThrows(IllegalArgumentException.class, () -> Platform.identify("Linux", "riscv64"));
        assertThrows(IllegalArgumentException.class, () -> Platform.identify("SunOS", "amd64"));
    }

    @Test
    void usesThePlatformFileNamingConvention() {
        assertEquals(List.of("jxl.dll"), Platform.fileNames("windows-x86_64", "jxl"));
        assertEquals(List.of("libjxl.so.0.12", "libjxl.so"), Platform.fileNames("linux-aarch64", "jxl"));
        assertEquals(List.of("libbrotlidec.so.1", "libbrotlidec.so"), Platform.fileNames("linux-x86_64", "brotlidec"));
        assertEquals(List.of("libjxl_threads.0.12.dylib", "libjxl_threads.dylib"),
                Platform.fileNames("macos-aarch64", "jxl_threads"));
    }

    @Test
    void theCurrentPlatformIsSupported() {
        assertTrue(Set.of("windows-x86_64", "linux-x86_64").contains(Platform.current()), Platform.current());
    }
}

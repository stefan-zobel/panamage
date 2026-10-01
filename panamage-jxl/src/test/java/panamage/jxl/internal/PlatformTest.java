package panamage.jxl.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

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

    @ParameterizedTest
    @CsvSource({
            "Linux,     amd64,   linux-musl-x86_64",
            "Linux,     aarch64, linux-musl-aarch64",
            "Windows 11, amd64,  windows-x86_64",
            "Mac OS X,  aarch64, macos-aarch64"
    })
    void identifiesMuslOnLinuxOnly(String osName, String osArch, String expected) {
        assertEquals(expected, Platform.identify(osName, osArch, true));
    }

    @Test
    void recognizesMuslPlatformIdentifiers() {
        assertTrue(Platform.isMusl("linux-musl-x86_64"));
        assertFalse(Platform.isMusl("linux-x86_64"));
        assertFalse(Platform.isMusl("macos-aarch64"));
    }

    @Test
    void findsTheMuslLoaderInTheMemoryMap() {
        assertTrue(Platform.mapsMusl(Stream.of(
                "55d4c8a00000-55d4c8a01000 r--p 00000000 08:01 1234   /usr/lib/jvm/java-25/bin/java",
                "7f2b1c000000-7f2b1c014000 r--p 00000000 08:01 5678   /lib/ld-musl-x86_64.so.1")));
        assertTrue(Platform.mapsMusl(Stream.of(
                "ffff9a000000-ffff9a0a0000 r-xp 00000000 08:01 42     /lib/ld-musl-aarch64.so.1")));
    }

    @Test
    void doesNotTakeGlibcForMusl() {
        assertFalse(Platform.mapsMusl(Stream.of(
                "7f2b1c000000-7f2b1c028000 r--p 00000000 08:01 11     /usr/lib/x86_64-linux-gnu/libc.so.6",
                "7f2b1c200000-7f2b1c201000 r--p 00000000 08:01 12     /usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
                "7f2b1c300000-7f2b1c301000 rw-p 00000000 00:00 0",
                "7ffd1a000000-7ffd1a021000 rw-p 00000000 00:00 0      [stack]",
                "7f2b1c400000-7f2b1c401000 r--p 00000000 08:01 13     /opt/app/not-ld-musl-x86_64.so.1")));
        assertFalse(Platform.mapsMusl(Stream.empty()));
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
        assertEquals(List.of("libbrotlicommon.1.dylib", "libbrotlicommon.dylib"),
                Platform.fileNames("macos-aarch64", "brotlicommon"));
    }

    @Test
    void theCurrentPlatformIsSupported() {
        assertTrue(Set.of("windows-x86_64", "linux-x86_64", "linux-aarch64", "macos-aarch64",
                "linux-musl-x86_64", "linux-musl-aarch64").contains(Platform.current()), Platform.current());
    }
}

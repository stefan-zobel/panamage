package panamage.jxl.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class NativeLibrariesTest {

    @Test
    void defaultCacheRootContainsTheUserName() {
        assertEquals(Path.of("tmp", "panamage-jxl-alice"), NativeLibraries.defaultCacheRoot("tmp", "alice"));
        assertEquals(Path.of("tmp", "panamage-jxl-a.b_c-d"), NativeLibraries.defaultCacheRoot("tmp", "a.b_c-d"));
    }

    @Test
    void defaultCacheRootReplacesOtherCharacters() {
        assertEquals(Path.of("tmp", "panamage-jxl-Jane_Doe"), NativeLibraries.defaultCacheRoot("tmp", "Jane Doe"));
        assertEquals(Path.of("tmp", "panamage-jxl-.._a_b"), NativeLibraries.defaultCacheRoot("tmp", "../a\\b"));
        assertEquals(Path.of("tmp", "panamage-jxl-J_rg"), NativeLibraries.defaultCacheRoot("tmp", "J\u00f6rg"));
    }

    @Test
    void defaultCacheRootWithoutUserName() {
        assertEquals(Path.of("tmp", "panamage-jxl-user"), NativeLibraries.defaultCacheRoot("tmp", ""));
        assertEquals(Path.of("tmp", "panamage-jxl-user"), NativeLibraries.defaultCacheRoot("tmp", null));
    }

    @Test
    void noteNamesTheRequirementsOfThePlatform() {
        assertTrue(NativeLibraries.note("Windows 11", "windows-x86_64").contains("Visual C++ runtime"));
        assertTrue(NativeLibraries.note("Windows 11", "windows-aarch64").contains("Visual C++ runtime"));
        assertTrue(NativeLibraries.note("Linux", "linux-x86_64").contains("glibc 2.29"));
        assertTrue(NativeLibraries.note("Linux", "linux-aarch64").contains("glibc 2.28"));
        assertTrue(NativeLibraries.note("Linux", null).contains("glibc 2.29"));
        assertNull(NativeLibraries.note("Mac OS X", "macos-aarch64"));
    }

    @Test
    void noteForMuslNamesTheMuslVersionInsteadOfGlibc() {
        for (String platform : List.of("linux-musl-x86_64", "linux-musl-aarch64")) {
            String note = NativeLibraries.note("Linux", platform);
            assertTrue(note.contains("musl 1.2.4"), note);
            assertFalse(note.contains("glibc"), note);
        }
    }

    @Test
    void noteForAPlatformWithoutBundleNamesTheAlternatives() {
        String note = NativeLibraries.note("Mac OS X", "macos-x86_64");
        assertTrue(note.contains("no bundled libraries for macos-x86_64"), note);
        assertTrue(note.contains(NativeLibraries.LIBRARY_PATH_PROPERTY), note);
    }
}

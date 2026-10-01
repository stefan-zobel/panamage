package panamage.jxl.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

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
}

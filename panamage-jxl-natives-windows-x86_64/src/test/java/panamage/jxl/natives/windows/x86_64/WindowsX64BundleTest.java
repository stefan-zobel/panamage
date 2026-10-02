package panamage.jxl.natives.windows.x86_64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import panamage.jxl.spi.JxlNativeBundle;

class WindowsX64BundleTest {

    private final WindowsX64Bundle bundle = new WindowsX64Bundle();

    @Test
    void describesThePlatformAndVersion() {
        assertEquals("windows-x86_64", bundle.platform());
        assertEquals("0.12.0", bundle.libjxlVersion());
    }

    @Test
    void everyListedLibraryIsBundledAsADll() throws IOException {
        for (String name : bundle.libraries()) {
            try (InputStream in = bundle.open(name)) {
                byte[] header = in.readNBytes(2);
                // Every Windows PE file starts with the "MZ" DOS header.
                assertEquals("MZ", new String(header, StandardCharsets.US_ASCII), name);
            }
        }
    }

    @Test
    void dllsNeedNoVisualCppRuntime() throws IOException {
        // Imported DLL names are stored as ASCII strings in the PE file.
        for (String name : bundle.libraries()) {
            try (InputStream in = bundle.open(name)) {
                String content = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
                for (String runtime : new String[] {"msvcp140", "vcruntime140", "api-ms-win-crt-"}) {
                    assertFalse(content.contains(runtime), name + " imports " + runtime);
                }
            }
        }
    }

    @Test
    void loadOrderEndsWithTheLibjxlLibraries() {
        assertTrue(bundle.libraries().indexOf("brotlicommon.dll") < bundle.libraries().indexOf("jxl.dll"));
        assertTrue(bundle.libraries().indexOf("jxl_cms.dll") < bundle.libraries().indexOf("jxl.dll"));
        assertEquals("jxl_threads.dll", bundle.libraries().getLast());
    }

    @Test
    void refusesFilesOutsideTheBundle() {
        assertThrows(IOException.class, () -> bundle.open("kernel32.dll"));
        assertThrows(IOException.class, () -> bundle.open("bundle.properties"));
    }

    @Test
    void bundlesTheLicensesOfAllNativeComponents() {
        for (String name : new String[] {"LICENSE.libjxl", "LICENSE.brotli", "LICENSE.highway", "LICENSE.skcms"}) {
            assertTrue(WindowsX64Bundle.class.getResource("/META-INF/licenses/" + name) != null, name);
        }
    }

    @Test
    void isRegisteredForTheClassPath() throws IOException {
        String service = "/META-INF/services/" + JxlNativeBundle.class.getName();
        try (InputStream in = WindowsX64Bundle.class.getResourceAsStream(service)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals(WindowsX64Bundle.class.getName(), content);
        }
    }
}

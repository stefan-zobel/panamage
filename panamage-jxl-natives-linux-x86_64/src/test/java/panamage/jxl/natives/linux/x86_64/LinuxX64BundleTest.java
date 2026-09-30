package panamage.jxl.natives.linux.x86_64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import panamage.jxl.spi.JxlNativeBundle;

class LinuxX64BundleTest {

    private static final byte[] ELF_MAGIC = {0x7F, 'E', 'L', 'F'};

    private final LinuxX64Bundle bundle = new LinuxX64Bundle();

    @Test
    void describesThePlatformAndVersion() {
        assertEquals("linux-x86_64", bundle.platform());
        assertEquals("0.12.0", bundle.libjxlVersion());
    }

    @Test
    void everyListedLibraryIsBundledAsA64BitElfFile() throws IOException {
        for (String name : bundle.libraries()) {
            try (InputStream in = bundle.open(name)) {
                byte[] header = in.readNBytes(5);
                assertArrayEquals(ELF_MAGIC, Arrays.copyOf(header, 4), name);
                // EI_CLASS 2 means ELFCLASS64.
                assertEquals(2, header[4], name);
            }
        }
    }

    @Test
    void loadOrderPutsDependenciesFirst() {
        List<String> libraries = bundle.libraries();
        assertTrue(libraries.indexOf("libbrotlicommon.so.1") < libraries.indexOf("libbrotlidec.so.1"));
        assertTrue(libraries.indexOf("libbrotlienc.so.1") < libraries.indexOf("libjxl.so.0.12"));
        assertTrue(libraries.indexOf("libjxl_cms.so.0.12") < libraries.indexOf("libjxl.so.0.12"));
        assertEquals("libjxl_threads.so.0.12", libraries.getLast());
    }

    @Test
    void refusesFilesOutsideTheBundle() {
        assertThrows(IOException.class, () -> bundle.open("libc.so.6"));
        assertThrows(IOException.class, () -> bundle.open("bundle.properties"));
    }

    @Test
    void bundlesTheLicensesOfAllNativeComponents() {
        for (String name : List.of("libjxl.copyright", "brotli.copyright", "LICENSE.highway")) {
            assertTrue(LinuxX64Bundle.class.getResource("/META-INF/licenses/" + name) != null, name);
        }
    }

    @Test
    void isRegisteredForTheClassPath() throws IOException {
        String service = "/META-INF/services/" + JxlNativeBundle.class.getName();
        try (InputStream in = LinuxX64Bundle.class.getResourceAsStream(service)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals(LinuxX64Bundle.class.getName(), content);
        }
    }
}

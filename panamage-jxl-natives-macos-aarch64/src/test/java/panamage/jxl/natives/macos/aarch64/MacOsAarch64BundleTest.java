package panamage.jxl.natives.macos.aarch64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import panamage.jxl.spi.JxlNativeBundle;

class MacOsAarch64BundleTest {

    /** MH_MAGIC_64: a 64-bit Mach-O file in little-endian byte order. */
    private static final int MACH_O_64_MAGIC = 0xFEEDFACF;

    /** CPU_TYPE_ARM64. */
    private static final int CPU_TYPE_ARM64 = 0x0100000C;

    /** MH_DYLIB. */
    private static final int FILE_TYPE_DYLIB = 6;

    private final MacOsAarch64Bundle bundle = new MacOsAarch64Bundle();

    @Test
    void describesThePlatformAndVersion() {
        assertEquals("macos-aarch64", bundle.platform());
        assertEquals("0.12.0", bundle.libjxlVersion());
    }

    @Test
    void everyListedLibraryIsBundledAsAnArm64Dylib() throws IOException {
        for (String name : bundle.libraries()) {
            try (InputStream in = bundle.open(name)) {
                ByteBuffer header = ByteBuffer.wrap(in.readNBytes(16)).order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(MACH_O_64_MAGIC, header.getInt(0), name);
                assertEquals(CPU_TYPE_ARM64, header.getInt(4), name);
                assertEquals(FILE_TYPE_DYLIB, header.getInt(12), name);
            }
        }
    }

    @Test
    void loadOrderPutsDependenciesFirst() {
        List<String> libraries = bundle.libraries();
        assertTrue(libraries.indexOf("libbrotlicommon.1.dylib") < libraries.indexOf("libbrotlidec.1.dylib"));
        assertTrue(libraries.indexOf("libbrotlienc.1.dylib") < libraries.indexOf("libjxl.0.12.dylib"));
        assertTrue(libraries.indexOf("libjxl_cms.0.12.dylib") < libraries.indexOf("libjxl.0.12.dylib"));
        assertEquals("libjxl_threads.0.12.dylib", libraries.getLast());
    }

    @Test
    void refusesFilesOutsideTheBundle() {
        assertThrows(IOException.class, () -> bundle.open("libSystem.B.dylib"));
        assertThrows(IOException.class, () -> bundle.open("bundle.properties"));
    }

    @Test
    void bundlesTheLicensesOfAllNativeComponents() {
        for (String name : List.of("LICENSE.libjxl", "LICENSE.brotli", "LICENSE.highway", "LICENSE.skcms")) {
            assertTrue(MacOsAarch64Bundle.class.getResource("/META-INF/licenses/" + name) != null, name);
        }
    }

    @Test
    void isRegisteredForTheClassPath() throws IOException {
        String service = "/META-INF/services/" + JxlNativeBundle.class.getName();
        try (InputStream in = MacOsAarch64Bundle.class.getResourceAsStream(service)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals(MacOsAarch64Bundle.class.getName(), content);
        }
    }
}

package panamage.jxl.natives.linux.aarch64;

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

class LinuxAarch64BundleTest {

    /** The ELF magic number, read as a little-endian int. */
    private static final int ELF_MAGIC = 0x464C457F;

    /** EI_CLASS ELFCLASS64. */
    private static final byte ELF_CLASS_64 = 2;

    /** EI_DATA ELFDATA2LSB: little-endian. */
    private static final byte ELF_DATA_LITTLE_ENDIAN = 1;

    /** ET_DYN: a shared object. */
    private static final short ELF_TYPE_SHARED_OBJECT = 3;

    /** EM_AARCH64. */
    private static final short ELF_MACHINE_AARCH64 = 183;

    private final LinuxAarch64Bundle bundle = new LinuxAarch64Bundle();

    @Test
    void describesThePlatformAndVersion() {
        assertEquals("linux-aarch64", bundle.platform());
        assertEquals("0.12.0", bundle.libjxlVersion());
    }

    @Test
    void everyListedLibraryIsBundledAsAnAarch64SharedObject() throws IOException {
        for (String name : bundle.libraries()) {
            try (InputStream in = bundle.open(name)) {
                ByteBuffer header = ByteBuffer.wrap(in.readNBytes(20)).order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(ELF_MAGIC, header.getInt(0), name);
                assertEquals(ELF_CLASS_64, header.get(4), name);
                assertEquals(ELF_DATA_LITTLE_ENDIAN, header.get(5), name);
                assertEquals(ELF_TYPE_SHARED_OBJECT, header.getShort(16), name);
                assertEquals(ELF_MACHINE_AARCH64, header.getShort(18), name);
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
        for (String name : List.of("LICENSE.libjxl", "LICENSE.brotli", "LICENSE.highway", "LICENSE.skcms")) {
            assertTrue(LinuxAarch64Bundle.class.getResource("/META-INF/licenses/" + name) != null, name);
        }
    }

    @Test
    void isRegisteredForTheClassPath() throws IOException {
        String service = "/META-INF/services/" + JxlNativeBundle.class.getName();
        try (InputStream in = LinuxAarch64Bundle.class.getResourceAsStream(service)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals(LinuxAarch64Bundle.class.getName(), content);
        }
    }
}

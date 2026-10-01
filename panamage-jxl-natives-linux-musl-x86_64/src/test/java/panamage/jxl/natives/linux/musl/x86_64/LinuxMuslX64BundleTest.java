package panamage.jxl.natives.linux.musl.x86_64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class LinuxMuslX64BundleTest {

    /** The ELF magic number, read as a little-endian int. */
    private static final int ELF_MAGIC = 0x464C457F;

    /** EI_CLASS ELFCLASS64. */
    private static final byte ELF_CLASS_64 = 2;

    /** EI_DATA ELFDATA2LSB: little-endian. */
    private static final byte ELF_DATA_LITTLE_ENDIAN = 1;

    /** ET_DYN: a shared object. */
    private static final short ELF_TYPE_SHARED_OBJECT = 3;

    /** EM_X86_64. */
    private static final short ELF_MACHINE_X86_64 = 62;

    private final LinuxMuslX64Bundle bundle = new LinuxMuslX64Bundle();

    @Test
    void describesThePlatformAndVersion() {
        assertEquals("linux-musl-x86_64", bundle.platform());
        assertEquals("0.12.0", bundle.libjxlVersion());
    }

    @Test
    void everyListedLibraryIsBundledAsAnX64SharedObject() throws IOException {
        for (String name : bundle.libraries()) {
            try (InputStream in = bundle.open(name)) {
                ByteBuffer header = ByteBuffer.wrap(in.readNBytes(20)).order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(ELF_MAGIC, header.getInt(0), name);
                assertEquals(ELF_CLASS_64, header.get(4), name);
                assertEquals(ELF_DATA_LITTLE_ENDIAN, header.get(5), name);
                assertEquals(ELF_TYPE_SHARED_OBJECT, header.getShort(16), name);
                assertEquals(ELF_MACHINE_X86_64, header.getShort(18), name);
            }
        }
    }

    @Test
    void librariesNeedTheMuslCLibraryButNoCxxRuntime() throws IOException {
        for (String name : bundle.libraries()) {
            String content;
            try (InputStream in = bundle.open(name)) {
                // The names of the needed libraries are ASCII strings in the file.
                content = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
            assertTrue(content.contains("libc.musl-x86_64.so.1"), name);
            assertFalse(content.contains("libstdc++.so"), name);
            assertFalse(content.contains("libgcc_s.so"), name);
            assertFalse(content.contains("libc.so.6"), name);
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
        assertThrows(IOException.class, () -> bundle.open("libc.musl-x86_64.so.1"));
        assertThrows(IOException.class, () -> bundle.open("bundle.properties"));
    }

    @Test
    void bundlesTheLicensesOfAllNativeComponents() {
        for (String name : List.of("LICENSE.libjxl", "LICENSE.brotli", "LICENSE.highway", "LICENSE.skcms")) {
            assertTrue(LinuxMuslX64Bundle.class.getResource("/META-INF/licenses/" + name) != null, name);
        }
    }

    @Test
    void isRegisteredForTheClassPath() throws IOException {
        String service = "/META-INF/services/" + JxlNativeBundle.class.getName();
        try (InputStream in = LinuxMuslX64Bundle.class.getResourceAsStream(service)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals(LinuxMuslX64Bundle.class.getName(), content);
        }
    }
}

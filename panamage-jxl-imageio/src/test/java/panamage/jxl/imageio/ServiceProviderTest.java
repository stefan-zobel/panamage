package panamage.jxl.imageio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

/**
 * The service providers must be loadable by old JVMs, so that they can
 * deregister themselves there (see {@link RuntimeGuardTest}).
 */
class ServiceProviderTest {

    /** The class file version of Java 8. */
    private static final int JAVA_8 = 52;

    @Test
    void serviceProvidersAreCompiledForJava8() throws IOException {
        assertEquals(JAVA_8, majorVersion(JxlFormat.class));
        assertEquals(JAVA_8, majorVersion(JxlImageReaderSpi.class));
        assertEquals(JAVA_8, majorVersion(JxlImageWriterSpi.class));
    }

    @Test
    void otherClassesKeepTheirRelease() throws IOException {
        int release = majorVersion(JxlImageReader.class);
        assertTrue(release > JAVA_8, "JxlImageReader has class file version " + release);
        assertEquals(release, majorVersion(JxlImageWriter.class));
        assertEquals(release, majorVersion(JxlSignature.class));
    }

    @Test
    void classNamesMatchTheClasses() {
        assertEquals(JxlImageReader.class.getName(), JxlFormat.READER_CLASS);
        assertEquals(JxlImageWriter.class.getName(), JxlFormat.WRITER_CLASS);
        assertEquals(JxlImageReaderSpi.class.getName(), JxlFormat.READER_SPI_CLASS);
        assertEquals(JxlImageWriterSpi.class.getName(), JxlFormat.WRITER_SPI_CLASS);
        assertEquals(new JxlImageReaderSpi().getPluginClassName(), JxlFormat.READER_CLASS);
        assertEquals(new JxlImageWriterSpi().getPluginClassName(), JxlFormat.WRITER_CLASS);
    }

    @Test
    void currentRuntimeIsSupported() {
        assertNull(JxlFormat.unsupportedReason(JxlFormat.PROBE_CLASS, JxlFormat.class.getClassLoader()));
    }

    @Test
    void missingClassIsUnsupported() {
        String reason = JxlFormat.unsupportedReason("panamage.jxl.NoSuchClass", JxlFormat.class.getClassLoader());
        assertNotNull(reason);
        assertTrue(reason.contains("panamage.jxl.NoSuchClass"), reason);
    }

    private static int majorVersion(Class<?> type) throws IOException {
        String name = type.getSimpleName() + ".class";
        try (InputStream in = type.getResourceAsStream(name)) {
            assertNotNull(in, name);
            DataInputStream data = new DataInputStream(in);
            assertEquals(0xCAFEBABE, data.readInt(), name);
            data.readUnsignedShort();
            return data.readUnsignedShort();
        }
    }
}

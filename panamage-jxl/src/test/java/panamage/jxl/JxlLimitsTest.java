package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

class JxlLimitsTest {

    @Test
    void rejectsLimitsThatAreNotPositive() {
        assertThrows(IllegalArgumentException.class, () -> new JxlLimits(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new JxlLimits(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new JxlLimits(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> JxlLimits.defaults().withMaxPixels(0));
        assertThrows(IllegalArgumentException.class, () -> JxlLimits.defaults().withMaxMetadataBytes(-5));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void defaultsUseTheConstantsWithoutSystemProperties() {
        withProperties(null, null, () -> {
            JxlLimits limits = JxlLimits.defaults();
            assertEquals(268_435_456L, limits.maxPixels());
            assertEquals(16L * 1024 * 1024, limits.maxMetadataBytes());
        });
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void defaultsCanBeSetWithSystemProperties() {
        withProperties("1000", " 2048 ", () -> {
            assertEquals(new JxlLimits(1000, 2048), JxlLimits.defaults());
        });
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void rejectsInvalidSystemProperties() {
        withProperties("many", null, () -> {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, JxlLimits::defaults);
            assertTrue(e.getMessage().contains(JxlLimits.MAX_PIXELS_PROPERTY), e.getMessage());
        });
        withProperties(null, "0", () -> {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, JxlLimits::defaults);
            assertTrue(e.getMessage().contains(JxlLimits.MAX_METADATA_BYTES_PROPERTY), e.getMessage());
        });
    }

    @Test
    void copiesReplaceOneLimit() {
        JxlLimits limits = new JxlLimits(10, 20);
        assertEquals(new JxlLimits(30, 20), limits.withMaxPixels(30));
        assertEquals(new JxlLimits(10, 40), limits.withMaxMetadataBytes(40));
        assertEquals(new JxlLimits(Long.MAX_VALUE, Long.MAX_VALUE), JxlLimits.unlimited());
    }

    @Test
    void countsPixelsOncePerFourChannels() {
        JxlLimits limits = JxlLimits.defaults().withMaxPixels(100);
        assertDoesNotThrow(() -> limits.checkPixels("Image", 10, 10, 1));
        assertDoesNotThrow(() -> limits.checkPixels("Image", 10, 10, 4));
        assertThrows(JxlLimitException.class, () -> limits.checkPixels("Image", 10, 10, 5));
        assertThrows(JxlLimitException.class, () -> limits.checkPixels("Image", 101, 1, 3));
        assertDoesNotThrow(() -> limits.checkPixels("Image", 5, 10, 8));
        assertThrows(JxlLimitException.class, () -> limits.checkPixels("Image", 5, 10, 9));
    }

    @Test
    void reportsTheSizeAndTheLimit() {
        JxlLimitException e = assertThrows(JxlLimitException.class,
                () -> JxlLimits.defaults().checkPixels("Image", 100_000, 100_000, 3));
        assertEquals("Image of 100000 x 100000 pixels exceeds the limit of 268435456 pixels", e.getMessage());
    }

    @Test
    void treatsOverflowAsExceedingTheLimit() {
        long huge = 0xFFFF_FFFFL;
        assertThrows(JxlLimitException.class,
                () -> JxlLimits.defaults().withMaxPixels(Long.MAX_VALUE - 1).checkPixels("Image", huge, huge, 4000));
        // The largest image the JPEG XL format allows.
        long largest = 1L << 30;
        assertDoesNotThrow(() -> JxlLimits.unlimited().checkPixels("Image", largest, largest, 4));
    }

    private static void withProperties(String maxPixels, String maxMetadataBytes, Runnable action) {
        String oldPixels = System.getProperty(JxlLimits.MAX_PIXELS_PROPERTY);
        String oldMetadata = System.getProperty(JxlLimits.MAX_METADATA_BYTES_PROPERTY);
        try {
            set(JxlLimits.MAX_PIXELS_PROPERTY, maxPixels);
            set(JxlLimits.MAX_METADATA_BYTES_PROPERTY, maxMetadataBytes);
            action.run();
        } finally {
            set(JxlLimits.MAX_PIXELS_PROPERTY, oldPixels);
            set(JxlLimits.MAX_METADATA_BYTES_PROPERTY, oldMetadata);
        }
    }

    private static void set(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}

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
        assertThrows(IllegalArgumentException.class, () -> JxlLimits.unlimited().withMaxPixels(-1));
        assertThrows(IllegalArgumentException.class, () -> JxlLimits.defaults().withMaxPixels(0));
        assertThrows(IllegalArgumentException.class, () -> JxlLimits.defaults().withMaxMetadataBytes(-5));
        assertThrows(IllegalArgumentException.class, () -> JxlLimits.defaults().withMaxJpegBytes(-1));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void defaultsUseTheConstantsWithoutSystemProperties() {
        withProperties(null, null, null, () -> {
            JxlLimits limits = JxlLimits.defaults();
            assertEquals(268_435_456L, limits.maxPixels());
            assertEquals(16L * 1024 * 1024, limits.maxMetadataBytes());
            assertEquals(1024L * 1024 * 1024, limits.maxJpegBytes());
        });
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void defaultsCanBeSetWithSystemProperties() {
        withProperties("1000", " 2048 ", "4096", () -> {
            assertEquals(limits(1000, 2048, 4096), JxlLimits.defaults());
        });
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void rejectsInvalidSystemProperties() {
        withProperties("many", null, null, () -> {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, JxlLimits::defaults);
            assertTrue(e.getMessage().contains(JxlLimits.MAX_PIXELS_PROPERTY), e.getMessage());
        });
        withProperties(null, "0", null, () -> {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, JxlLimits::defaults);
            assertTrue(e.getMessage().contains(JxlLimits.MAX_METADATA_BYTES_PROPERTY), e.getMessage());
        });
        withProperties(null, null, "-1", () -> {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, JxlLimits::defaults);
            assertTrue(e.getMessage().contains(JxlLimits.MAX_JPEG_BYTES_PROPERTY), e.getMessage());
        });
    }

    @Test
    void copiesReplaceOneLimit() {
        JxlLimits limits = limits(10, 20, 30);
        assertEquals(10, limits.maxPixels());
        assertEquals(20, limits.maxMetadataBytes());
        assertEquals(30, limits.maxJpegBytes());
        assertEquals(limits(40, 20, 30), limits.withMaxPixels(40));
        assertEquals(limits(10, 40, 30), limits.withMaxMetadataBytes(40));
        assertEquals(limits(10, 20, 40), limits.withMaxJpegBytes(40));
        assertEquals(limits(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE), JxlLimits.unlimited());
    }

    private static JxlLimits limits(long maxPixels, long maxMetadataBytes, long maxJpegBytes) {
        return JxlLimits.unlimited().withMaxPixels(maxPixels).withMaxMetadataBytes(maxMetadataBytes)
                .withMaxJpegBytes(maxJpegBytes);
    }

    @Test
    void checksTheJpegSize() {
        JxlLimits limits = JxlLimits.defaults().withMaxJpegBytes(100);
        assertDoesNotThrow(() -> limits.checkJpeg(100));
        JxlLimitException e = assertThrows(JxlLimitException.class, () -> limits.checkJpeg(101));
        assertEquals("Reconstructed JPEG exceeds the limit of 100 bytes", e.getMessage());
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

    @Test
    void countsAllFramesOfAnAnimationTogether() {
        JxlLimits limits = JxlLimits.defaults().withMaxPixels(1000);
        assertDoesNotThrow(() -> limits.checkAnimation(10, 10, 10, 4));
        JxlLimitException e = assertThrows(JxlLimitException.class, () -> limits.checkAnimation(11, 10, 10, 4));
        assertEquals("Animation of 11 frames of 10 x 10 pixels exceeds the limit of 1000 pixels", e.getMessage());
        e = assertThrows(JxlLimitException.class, () -> limits.checkAnimation(6, 10, 10, 5));
        assertEquals("Animation of 6 frames of 10 x 10 pixels with 5 channels exceeds the limit of 1000 pixels",
                e.getMessage());
        assertDoesNotThrow(() -> limits.checkAnimation(5, 10, 10, 5));
        assertDoesNotThrow(() -> limits.checkAnimation(0, 10, 10, 4));
    }

    @Test
    void treatsOverflowOfAllFramesAsExceedingTheLimit() {
        JxlLimits limits = JxlLimits.defaults().withMaxPixels(Long.MAX_VALUE - 1);
        long huge = 0xFFFF_FFFFL;
        assertThrows(JxlLimitException.class, () -> limits.checkAnimation(huge, huge, huge, 4));
        assertThrows(JxlLimitException.class, () -> limits.checkAnimation(Long.MAX_VALUE, 2, 2, 4));
        assertDoesNotThrow(() -> JxlLimits.unlimited().checkAnimation(1000, 1L << 20, 1L << 20, 4));
    }

    private static void withProperties(String maxPixels, String maxMetadataBytes, String maxJpegBytes,
            Runnable action) {
        String oldPixels = System.getProperty(JxlLimits.MAX_PIXELS_PROPERTY);
        String oldMetadata = System.getProperty(JxlLimits.MAX_METADATA_BYTES_PROPERTY);
        String oldJpeg = System.getProperty(JxlLimits.MAX_JPEG_BYTES_PROPERTY);
        try {
            set(JxlLimits.MAX_PIXELS_PROPERTY, maxPixels);
            set(JxlLimits.MAX_METADATA_BYTES_PROPERTY, maxMetadataBytes);
            set(JxlLimits.MAX_JPEG_BYTES_PROPERTY, maxJpegBytes);
            action.run();
        } finally {
            set(JxlLimits.MAX_PIXELS_PROPERTY, oldPixels);
            set(JxlLimits.MAX_METADATA_BYTES_PROPERTY, oldMetadata);
            set(JxlLimits.MAX_JPEG_BYTES_PROPERTY, oldJpeg);
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

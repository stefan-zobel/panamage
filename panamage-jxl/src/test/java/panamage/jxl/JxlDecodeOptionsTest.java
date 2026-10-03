package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * The settings of {@link JxlDecodeOptions}; the conversion to sRGB is tested
 * in the Image I/O module, which compares it with Java's color management.
 */
class JxlDecodeOptionsTest {

    private static final JxlLimits SMALL = JxlLimits.defaults().withMaxPixels(100);

    @Test
    @ResourceLock(value = Resources.SYSTEM_PROPERTIES, mode = ResourceAccessMode.READ)
    void defaultsKeepTheColorSpaceAndUseTheDefaultLimits() {
        JxlDecodeOptions options = JxlDecodeOptions.defaults();

        assertFalse(options.srgb());
        assertEquals(JxlLimits.defaults(), options.limits());
    }

    @Test
    void withersReplaceOneSetting() {
        JxlDecodeOptions options = JxlDecodeOptions.defaults().withSrgb(true).withLimits(SMALL);

        assertTrue(options.srgb());
        assertEquals(SMALL, options.limits());
        assertFalse(options.withSrgb(false).srgb());
        assertEquals(SMALL, options.withSrgb(false).limits());
    }

    @Test
    void limitsAreRequired() {
        assertThrows(NullPointerException.class, () -> JxlDecodeOptions.defaults().withLimits(null));
        assertThrows(NullPointerException.class,
                () -> JxlDecoder.decode(TestImages.gradientJxl(), 4, JxlSampleType.UINT8, (JxlDecodeOptions) null));
    }

    @Test
    void theLimitsOfTheOptionsApply() {
        byte[] data = TestImages.gradientJxl();
        JxlDecodeOptions options = JxlDecodeOptions.defaults().withSrgb(true).withLimits(SMALL);

        assertThrows(JxlLimitException.class, () -> JxlDecoder.decode(data, 4, JxlSampleType.UINT8, options));
        assertThrows(JxlLimitException.class, () -> JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8, options));
        assertThrows(JxlLimitException.class, () -> JxlDecoder.decodeChannels(data, JxlSampleType.UINT8, options));
        assertThrows(JxlLimitException.class, () -> JxlFrameDecoder.open(data, 4, JxlSampleType.UINT8, options));
        assertThrows(JxlLimitException.class, () -> JxlFrameDecoder.openChannels(data, JxlSampleType.UINT8, options));
    }

    @Test
    void srgbImagesDecodeAsWithoutTheOption() {
        JxlImage.Uint8 image = (JxlImage.Uint8) JxlDecoder.decode(TestImages.gradientJxl(), 4, JxlSampleType.UINT8,
                JxlDecodeOptions.defaults().withSrgb(true));

        assertNull(image.iccProfile());
        assertArrayEquals(TestImages.gradientRgbaPixels(), image.pixels());
    }
}

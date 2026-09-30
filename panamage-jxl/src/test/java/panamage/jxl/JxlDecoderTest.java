package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class JxlDecoderTest {

    @Test
    void decodesLosslessImageBitExact() {
        JxlImage.Uint8 image = JxlDecoder.decode(TestImages.gradientJxl());

        assertEquals(TestImages.WIDTH, image.width());
        assertEquals(TestImages.HEIGHT, image.height());
        assertEquals(4, image.channels());
        assertArrayEquals(TestImages.gradientRgbaPixels(), image.pixels());
    }

    @Test
    void decodesFromNativeSegment() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocateFrom(ValueLayout.JAVA_BYTE, TestImages.gradientJxl());
            JxlImage.Uint8 image = JxlDecoder.decode(segment);
            assertArrayEquals(TestImages.gradientRgbaPixels(), image.pixels());
        }
    }

    @Test
    void decodesFromHeapSegment() {
        JxlImage.Uint8 image = JxlDecoder.decode(MemorySegment.ofArray(TestImages.gradientJxl()));
        assertArrayEquals(TestImages.gradientRgbaPixels(), image.pixels());
    }

    @Test
    void readsTheHeaderWithoutDecoding() {
        JxlImageInfo info = JxlDecoder.readInfo(TestImages.gradientJxl());

        assertEquals(TestImages.WIDTH, info.width());
        assertEquals(TestImages.HEIGHT, info.height());
        assertEquals(3, info.colorChannels());
        assertTrue(info.hasAlpha());
        assertEquals(4, info.channels());
        assertEquals(8, info.bitsPerSample());
        assertFalse(info.animated());
        assertTrue(info.isSrgb());
    }

    @Test
    void readsTheHeaderOfATranscodedJpeg() {
        JxlImageInfo info = JxlDecoder.readInfo(TestImages.resource(TestImages.PHOTO_CJXL_REFERENCE));

        assertEquals(TestImages.PHOTO_WIDTH, info.width());
        assertEquals(TestImages.PHOTO_HEIGHT, info.height());
        assertEquals(3, info.channels());
        assertTrue(info.isSrgb());
    }

    @Test
    void decodesToRgbWithoutAlpha() {
        JxlImage.Uint8 image = JxlDecoder.decode(TestImages.gradientJxl(), 3);

        assertEquals(3, image.channels());
        byte[] rgba = TestImages.gradientRgbaPixels();
        byte[] rgb = image.pixels();
        for (int p = 0; p < TestImages.WIDTH * TestImages.HEIGHT; p++) {
            for (int c = 0; c < 3; c++) {
                assertEquals(rgba[p * 4 + c], rgb[p * 3 + c], "pixel " + p + ", channel " + c);
            }
        }
    }

    @Test
    void decodesGrayImagesToTheirNaturalChannelCount() {
        JxlImage.Uint8 gray = TestImages.gradientChannels(1);
        byte[] encoded = JxlEncoder.encode(gray, JxlEncodeOptions.ofLossless());

        JxlImageInfo info = JxlDecoder.readInfo(encoded);
        JxlImage.Uint8 decoded = JxlDecoder.decode(encoded, info.channels());

        assertEquals(1, info.channels());
        assertTrue(decoded.isSrgb());
        assertArrayEquals(gray.pixels(), decoded.pixels());
    }

    @Test
    void rejectsInvalidChannelCounts() {
        assertThrows(IllegalArgumentException.class, () -> JxlDecoder.decode(TestImages.gradientJxl(), 0));
        assertThrows(IllegalArgumentException.class, () -> JxlDecoder.decode(TestImages.gradientJxl(), 5));
    }

    @Test
    void readInfoRejectsGarbage() {
        byte[] garbage = new byte[256];
        Arrays.fill(garbage, (byte) 0x5A);
        assertThrows(JxlException.class, () -> JxlDecoder.readInfo(garbage));
    }

    @Test
    void rejectsGarbage() {
        byte[] garbage = new byte[256];
        Arrays.fill(garbage, (byte) 0x5A);
        assertThrows(JxlException.class, () -> JxlDecoder.decode(garbage));
    }

    @Test
    void rejectsTruncatedData() {
        byte[] encoded = TestImages.gradientJxl();
        byte[] truncated = Arrays.copyOf(encoded, encoded.length / 2);
        assertThrows(JxlException.class, () -> JxlDecoder.decode(truncated));
    }
}

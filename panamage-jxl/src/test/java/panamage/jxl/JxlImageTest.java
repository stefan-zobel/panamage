package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JxlImageTest {

    private static final byte[] PROFILE = {1, 2, 3};

    @Test
    void eachImageTypeReportsItsSampleType() {
        assertEquals(JxlSampleType.UINT8, new JxlImage.Uint8(2, 1, 1, new byte[2]).sampleType());
        assertEquals(JxlSampleType.UINT16, new JxlImage.Uint16(2, 1, 1, new short[2]).sampleType());
        assertEquals(JxlSampleType.FLOAT32, new JxlImage.Float32(2, 1, 1, new float[2]).sampleType());
    }

    @Test
    void imagesWithoutProfileAreSrgb() {
        assertTrue(new JxlImage.Uint16(1, 1, 3, new short[3]).isSrgb());
        assertFalse(new JxlImage.Float32(1, 1, 3, new float[3], PROFILE).isSrgb());
    }

    @Test
    void rejectsAPixelArrayOfTheWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> new JxlImage.Uint8(2, 2, 3, new byte[11]));
        assertThrows(IllegalArgumentException.class, () -> new JxlImage.Uint16(2, 2, 3, new short[13]));
        assertThrows(IllegalArgumentException.class, () -> new JxlImage.Float32(2, 2, 3, new float[24]));
    }

    @Test
    void rejectsInvalidDimensionsAndEmptyProfiles() {
        assertThrows(IllegalArgumentException.class, () -> new JxlImage.Uint16(0, 2, 1, new short[0]));
        assertThrows(IllegalArgumentException.class, () -> new JxlImage.Float32(2, 2, 0, new float[0]));
        assertThrows(IllegalArgumentException.class, () -> new JxlImage.Uint8(1, 1, 1, new byte[1], new byte[0]));
        assertThrows(NullPointerException.class, () -> new JxlImage.Uint16(1, 1, 1, null));
    }

    @Test
    void aSwitchCoversEverySampleType() {
        JxlImage[] images = {
                new JxlImage.Uint8(2, 1, 1, new byte[2]),
                new JxlImage.Uint16(2, 1, 2, new short[4]),
                new JxlImage.Float32(2, 1, 3, new float[6])
        };
        for (JxlImage image : images) {
            int samples = switch (image) {
                case JxlImage.Uint8 img -> img.pixels().length;
                case JxlImage.Uint16 img -> img.pixels().length;
                case JxlImage.Float32 img -> img.pixels().length;
            };
            assertEquals(image.width() * image.height() * image.channels(), samples);
        }
    }
}

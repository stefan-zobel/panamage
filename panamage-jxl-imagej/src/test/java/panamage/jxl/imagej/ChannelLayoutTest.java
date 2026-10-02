package panamage.jxl.imagej;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import panamage.jxl.JxlChannels;
import panamage.jxl.JxlEncodeOptions;
import panamage.jxl.JxlEncoder;
import panamage.jxl.JxlExtraChannel;
import panamage.jxl.JxlSampleType;

class ChannelLayoutTest {

    private static final int WIDTH = TestPlanes.WIDTH;
    private static final int HEIGHT = TestPlanes.HEIGHT;

    @Test
    void onlyEightBitRedGreenAndBlueWithoutExtraChannelsIsRgb() {
        byte[] r = TestPlanes.uint8(1);
        byte[] g = TestPlanes.uint8(2);
        byte[] b = TestPlanes.uint8(3);
        assertTrue(ChannelLayout.isRgb(JxlChannels.builder(WIDTH, HEIGHT).rgb(r, g, b).build()));
        assertFalse(ChannelLayout.isRgb(
                JxlChannels.builder(WIDTH, HEIGHT).rgb(r, g, b).add(JxlExtraChannel.alpha(), r).build()));
        assertFalse(ChannelLayout.isRgb(JxlChannels.builder(WIDTH, HEIGHT).gray(r).build()));
        assertFalse(ChannelLayout.isRgb(JxlChannels.builder(WIDTH, HEIGHT)
                .rgb(TestPlanes.uint16(1), TestPlanes.uint16(2), TestPlanes.uint16(3)).build()));
    }

    @Test
    void imagesAreDecodedWithoutLoss() {
        assertDecodedLosslessly(JxlChannels.builder(WIDTH, HEIGHT).gray(TestPlanes.uint8(1))
                .add("A", TestPlanes.uint8(2)).build(), JxlSampleType.UINT8);
        assertDecodedLosslessly(JxlChannels.builder(WIDTH, HEIGHT).gray(TestPlanes.uint16(1))
                .add("A", TestPlanes.uint16(2)).build(), JxlSampleType.UINT16);
        assertDecodedLosslessly(JxlChannels.builder(WIDTH, HEIGHT).gray(TestPlanes.float32(1))
                .add("A", TestPlanes.float32(2)).build(), JxlSampleType.FLOAT32);
    }

    @Test
    void twelveBitImagesKeepTheirValues() {
        short[] plane = new short[TestPlanes.SAMPLES];
        for (int i = 0; i < plane.length; i++) {
            plane[i] = (short) (i * 11 % 4096);
        }
        JxlChannels image = JxlChannels.builder(WIDTH, HEIGHT).gray(plane).bitsPerSample(12).build();

        JxlChannels.Uint16 read = assertInstanceOf(JxlChannels.Uint16.class,
                ChannelLayout.decode(JxlEncoder.encode(image, JxlEncodeOptions.ofLossless())));

        assertEquals(12, read.bitsPerSample());
        assertArrayEquals(plane, read.planes().get(0));
    }

    private static void assertDecodedLosslessly(JxlChannels image, JxlSampleType type) {
        byte[] jxl = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless());

        assertEquals(type, ChannelLayout.losslessType(jxl));
        JxlChannels read = ChannelLayout.decode(jxl);
        assertEquals(type, read.sampleType());
        switch (read) {
            case JxlChannels.Uint8 img -> {
                for (int i = 0; i < img.channels(); i++) {
                    assertArrayEquals(((JxlChannels.Uint8) image).planes().get(i), img.planes().get(i));
                }
            }
            case JxlChannels.Uint16 img -> {
                for (int i = 0; i < img.channels(); i++) {
                    assertArrayEquals(((JxlChannels.Uint16) image).planes().get(i), img.planes().get(i));
                }
            }
            case JxlChannels.Float32 img -> {
                for (int i = 0; i < img.channels(); i++) {
                    assertArrayEquals(((JxlChannels.Float32) image).planes().get(i), img.planes().get(i));
                }
            }
        }
    }
}

package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Equality, hash codes and string forms of the option and info classes. */
class ValueTypesTest {

    @Test
    void limits() {
        JxlLimits limits = JxlLimits.unlimited().withMaxPixels(10).withMaxMetadataBytes(20).withMaxJpegBytes(30);

        assertEqualValues(limits,
                JxlLimits.unlimited().withMaxJpegBytes(30).withMaxMetadataBytes(20).withMaxPixels(10));
        assertNotEquals(limits, limits.withMaxPixels(11));
        assertNotEquals(limits, limits.withMaxMetadataBytes(21));
        assertNotEquals(limits, limits.withMaxJpegBytes(31));
        assertEquals("JxlLimits[maxPixels=10, maxMetadataBytes=20, maxJpegBytes=30]", limits.toString());
    }

    @Test
    void decodeOptions() {
        JxlLimits limits = JxlLimits.unlimited().withMaxPixels(10);
        JxlDecodeOptions options = JxlDecodeOptions.defaults().withLimits(limits).withSrgb(true);

        assertEqualValues(options, JxlDecodeOptions.defaults().withSrgb(true).withLimits(limits));
        assertNotEquals(options, options.withSrgb(false));
        assertNotEquals(options, options.withLimits(JxlLimits.unlimited()));
        assertEquals("JxlDecodeOptions[limits=" + limits + ", srgb=true]", options.toString());
    }

    @Test
    void encodeOptions() {
        JxlEncodeOptions lossless = JxlEncodeOptions.ofLossless();
        assertTrue(lossless.lossless());
        assertEquals(0.0f, lossless.distance());
        assertEquals(JxlEncodeOptions.DEFAULT_EFFORT, lossless.effort());

        JxlEncodeOptions lossy = JxlEncodeOptions.ofDistance(1.5f).withEffort(3);
        assertEqualValues(lossy, JxlEncodeOptions.ofDistance(1.5f).withEffort(3));
        assertEqualValues(JxlEncodeOptions.defaults(), JxlEncodeOptions.ofDistance(1.0f));
        assertEqualValues(lossless, JxlEncodeOptions.ofQuality(100));
        assertNotEquals(lossy, lossy.withEffort(4));
        assertNotEquals(lossy, JxlEncodeOptions.ofDistance(2.0f).withEffort(3));
        assertNotEquals(lossless, JxlEncodeOptions.ofDistance(0.1f));
        assertEquals("JxlEncodeOptions[lossless=false, distance=1.5, effort=3]", lossy.toString());
        assertEquals("JxlEncodeOptions[lossless=true, distance=0.0, effort=7]", lossless.toString());
    }

    @Test
    void imageInfoComparesTheIccProfileByContent() {
        JxlImageInfo info = new JxlImageInfo(4, 3, 3, true, 8, 0, false, new byte[] {1, 2, 3});

        assertEqualValues(info, new JxlImageInfo(4, 3, 3, true, 8, 0, false, new byte[] {1, 2, 3}));
        assertNotEquals(info, new JxlImageInfo(4, 3, 3, true, 8, 0, false, new byte[] {1, 2, 4}));
        assertNotEquals(info, new JxlImageInfo(4, 3, 3, true, 8, 0, false, null));
        assertNotEquals(info, new JxlImageInfo(4, 3, 3, true, 8, 0, true, new byte[] {1, 2, 3}));
        assertEquals("JxlImageInfo[width=4, height=3, colorChannels=3, hasAlpha=true, bitsPerSample=8,"
                + " exponentBitsPerSample=0, animated=false, iccProfile=3 bytes]", info.toString());
        assertTrue(new JxlImageInfo(4, 3, 3, true, 8, 0, false, null).toString().endsWith("iccProfile=null]"));
    }

    @Test
    void imageInfoOfADecodedFile() {
        JxlImageInfo info = JxlDecoder.readInfo(TestImages.gradientJxl());

        assertEqualValues(info, JxlDecoder.readInfo(TestImages.gradientJxl()));
        assertEquals(4, info.channels());
        assertEquals(JxlSampleType.UINT8, info.sampleType());
    }

    @Test
    void extraChannelInfo() {
        JxlExtraChannelInfo info = new JxlExtraChannelInfo(JxlChannelType.OPTIONAL, "GFP", 12, 0);

        assertEqualValues(info, new JxlExtraChannelInfo(JxlChannelType.OPTIONAL, "GFP", 12, 0));
        assertNotEquals(info, new JxlExtraChannelInfo(JxlChannelType.OPTIONAL, "RFP", 12, 0));
        assertNotEquals(info, new JxlExtraChannelInfo(JxlChannelType.ALPHA, "GFP", 12, 0));
        assertNotEquals(info, new JxlExtraChannelInfo(JxlChannelType.OPTIONAL, "GFP", 16, 0));
        assertEquals("JxlExtraChannelInfo[type=OPTIONAL, name=GFP, bitsPerSample=12, exponentBitsPerSample=0]",
                info.toString());
    }

    @Test
    void frameAndAnimationInfo() {
        JxlFrameInfo frame = new JxlFrameInfo(5, 50.0, "first");
        assertEqualValues(frame, new JxlFrameInfo(5, 50.0, "first"));
        assertNotEquals(frame, new JxlFrameInfo(6, 50.0, "first"));
        assertNotEquals(frame, new JxlFrameInfo(5, 60.0, "first"));
        assertNotEquals(frame, new JxlFrameInfo(5, 50.0, ""));
        assertEquals("JxlFrameInfo[durationTicks=5, durationMillis=50.0, name=first]", frame.toString());

        JxlAnimationInfo animation = new JxlAnimationInfo(100, 1, 2, List.of(frame));
        assertEqualValues(animation, new JxlAnimationInfo(100, 1, 2, List.of(new JxlFrameInfo(5, 50.0, "first"))));
        assertNotEquals(animation, new JxlAnimationInfo(100, 1, 3, List.of(frame)));
        assertNotEquals(animation, new JxlAnimationInfo(100, 1, 2, List.of(frame, frame)));
        assertEquals("JxlAnimationInfo[ticksPerSecondNumerator=100, ticksPerSecondDenominator=1, loops=2, frames=["
                + frame + "]]", animation.toString());
    }

    private static void assertEqualValues(Object expected, Object actual) {
        assertEquals(expected, actual);
        assertEquals(expected.hashCode(), actual.hashCode());
    }
}

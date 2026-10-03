package panamage.jxl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import panamage.jxl.ffi.Jxl;
import panamage.jxl.ffi.JxlBasicInfo;
import panamage.jxl.ffi.JxlColorEncoding;
import panamage.jxl.ffi.JxlExtraChannelInfo;
import panamage.jxl.ffi.JxlFrameHeader;
import panamage.jxl.ffi.JxlLayerInfo;
import panamage.jxl.ffi.JxlPixelFormat;

class DecompressionBombTest {

    private static final long GRADIENT_PIXELS = (long) TestImages.WIDTH * TestImages.HEIGHT;

    @Test
    void rejectsImagesBeyondThePixelLimit() {
        byte[] data = TestImages.gradientJxl();
        JxlDecodeOptions tooSmall = TestImages.maxPixels(GRADIENT_PIXELS - 1);
        JxlDecodeOptions justEnough = TestImages.maxPixels(GRADIENT_PIXELS);

        JxlLimitException e = assertThrows(JxlLimitException.class,
                () -> JxlDecoder.decode(data, 4, JxlSampleType.UINT8, tooSmall));
        assertEquals("Image of 64 x 48 pixels exceeds the limit of 3071 pixels", e.getMessage());
        assertArrayEquals(TestImages.gradientRgbaPixels(),
                ((JxlImage.Uint8) JxlDecoder.decode(data, 4, JxlSampleType.UINT8, justEnough)).pixels());
    }

    @Test
    void rejectsImagesInMemorySegmentsBeyondThePixelLimit() {
        MemorySegment data = MemorySegment.ofArray(TestImages.gradientJxl());
        assertThrows(JxlLimitException.class,
                () -> JxlDecoder.decode(data, 4, JxlSampleType.UINT8, TestImages.maxPixels(GRADIENT_PIXELS - 1)));
        assertEquals(TestImages.WIDTH,
                JxlDecoder.decode(data, 4, JxlSampleType.UINT8, TestImages.maxPixels(GRADIENT_PIXELS)).width());
    }

    @Test
    void rejectsJpegReconstructionBeyondThePixelLimit() {
        byte[] jxl = TestImages.resource(TestImages.PHOTO_CJXL_REFERENCE);
        long pixels = (long) TestImages.PHOTO_WIDTH * TestImages.PHOTO_HEIGHT;

        assertThrows(JxlLimitException.class,
                () -> JxlTranscoder.toJpeg(jxl, TestImages.maxPixels(pixels - 1)));
        assertArrayEquals(JxlTranscoder.toJpeg(jxl),
                JxlTranscoder.toJpeg(jxl, TestImages.maxPixels(pixels)));
    }

    @Test
    void rejectsJpegReconstructionBeyondTheJpegLimit() {
        byte[] jxl = TestImages.resource(TestImages.PHOTO_CJXL_REFERENCE);
        byte[] jpeg = JxlTranscoder.toJpeg(jxl);
        JxlDecodeOptions tooSmall = TestImages.maxJpegBytes(jpeg.length - 1);
        JxlDecodeOptions justEnough = TestImages.maxJpegBytes(jpeg.length);

        JxlLimitException e = assertThrows(JxlLimitException.class, () -> JxlTranscoder.toJpeg(jxl, tooSmall));
        assertEquals("Reconstructed JPEG exceeds the limit of " + (jpeg.length - 1) + " bytes", e.getMessage());
        assertArrayEquals(jpeg, JxlTranscoder.toJpeg(jxl, justEnough));
        // Small chunks make the buffer grow, which is checked against the limit, too.
        assertThrows(JxlLimitException.class, () -> JxlTranscoder.toJpeg(jxl, 1, tooSmall));
        assertThrows(JxlLimitException.class,
                () -> JxlTranscoder.toJpeg(jxl, 1, TestImages.maxJpegBytes(16)));
        assertArrayEquals(jpeg, JxlTranscoder.toJpeg(jxl, 1, justEnough));
    }

    @Test
    void countsExtraChannels() {
        // 3 color and 2 extra channels count twice.
        byte[] data = encode(16, 16, 16, 16, 2);
        assertThrows(JxlLimitException.class,
                () -> JxlDecoder.decode(data, 3, JxlSampleType.UINT8, TestImages.maxPixels(511)));
        assertEquals(16,
                JxlDecoder.decode(data, 3, JxlSampleType.UINT8, TestImages.maxPixels(512)).width());
    }

    @Test
    void rejectsLayersLargerThanTheImage() {
        byte[] data = encode(64, 64, 1024, 1024, 0);
        JxlImageInfo info = JxlDecoder.readInfo(data);
        assertEquals(64, info.width());
        assertEquals(64, info.height());

        JxlLimitException e = assertThrows(JxlLimitException.class,
                () -> JxlDecoder.decode(data, 3, JxlSampleType.UINT8, TestImages.maxPixels(100_000)));
        assertEquals("Frame layer of 1024 x 1024 pixels exceeds the limit of 100000 pixels", e.getMessage());
        assertEquals(64, JxlDecoder.decode(data, 3, JxlSampleType.UINT8, JxlDecodeOptions.defaults()).width());
    }

    @Test
    void rejectsHugeImagesBeforeTheirDataIsRead() {
        byte[] data = truncatedCodestream(100_000, 100_000);
        JxlImageInfo info = JxlDecoder.readInfo(data);
        assertEquals(100_000, info.width());
        assertEquals(100_000, info.height());

        JxlLimitException e = assertThrows(JxlLimitException.class, () -> JxlDecoder.decode(data));
        assertEquals("Image of 100000 x 100000 pixels exceeds the limit of 268435456 pixels", e.getMessage());
        assertThrows(JxlLimitException.class, () -> JxlTranscoder.toJpeg(data));
    }

    @Test
    void reportsDamagedDataAsWithoutTheLimit() {
        byte[] data = TestImages.gradientJxl();
        for (int length : new int[] {1, 10, data.length / 2}) {
            byte[] truncated = Arrays.copyOf(data, length);
            // Unlimited skips the frame check, which must not change the error.
            JxlException expected = assertThrows(JxlException.class,
                    () -> JxlDecoder.decode(truncated, 4, JxlSampleType.UINT8, TestImages.unlimited()));
            JxlException actual = assertThrows(JxlException.class, () -> JxlDecoder.decode(truncated));
            assertEquals(JxlException.class, actual.getClass(), "length " + length);
            assertEquals(expected.getMessage(), actual.getMessage(), "length " + length);
        }
    }

    @Test
    void rejectsMetadataBeyondTheMetadataLimit() {
        byte[] xmp = ("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" + " ".repeat(4 << 20) + "</x:xmpmeta>")
                .getBytes(StandardCharsets.UTF_8);
        byte[] encoded = JxlEncoder.encode(TestImages.gradientRgba(), JxlEncodeOptions.ofLossless(),
                new JxlMetadata(null, xmp));
        // The Brotli-compressed box is tiny compared to its content.
        assertTrue(encoded.length < 64 * 1024, "encoded size " + encoded.length);

        JxlLimitException e = assertThrows(JxlLimitException.class,
                () -> JxlDecoder.readMetadata(encoded, TestImages.maxMetadataBytes(64 * 1024)));
        assertEquals("Metadata box 'xml ' exceeds the limit of 65536 bytes", e.getMessage());
        assertThrows(JxlLimitException.class,
                () -> JxlDecoder.readMetadata(encoded, 1, TestImages.maxMetadataBytes(100)));
        assertArrayEquals(xmp, JxlDecoder.readMetadata(encoded).xmp());
        assertArrayEquals(xmp,
                JxlDecoder.readMetadata(encoded, TestImages.maxMetadataBytes(xmp.length)).xmp());
    }

    @Test
    void unlimitedDecodesLikeTheDefaults() {
        byte[] data = TestImages.gradientJxl();
        assertArrayEquals(JxlDecoder.decode(data).pixels(),
                ((JxlImage.Uint8) JxlDecoder.decode(data, 4, JxlSampleType.UINT8, TestImages.unlimited())).pixels());
    }

    /**
     * Encodes a black lossless sRGB image whose only frame is a layer of the
     * given size at the top left corner, with additional optional extra
     * channels.
     */
    private static byte[] encode(int width, int height, int layerWidth, int layerHeight, int extraChannels) {
        try (Arena arena = Arena.ofConfined(); NativeEncoder encoder = NativeEncoder.create()) {
            MemorySegment handle = encoder.handle();
            MemorySegment info = arena.allocate(JxlBasicInfo.layout());
            Jxl.JxlEncoderInitBasicInfo(info);
            JxlBasicInfo.xsize(info, width);
            JxlBasicInfo.ysize(info, height);
            JxlBasicInfo.bits_per_sample(info, 8);
            JxlBasicInfo.num_color_channels(info, 3);
            JxlBasicInfo.num_extra_channels(info, extraChannels);
            JxlBasicInfo.uses_original_profile(info, Jxl.JXL_TRUE());
            encoder.check(Jxl.JxlEncoderSetBasicInfo(handle, info), "JxlEncoderSetBasicInfo");

            MemorySegment color = arena.allocate(JxlColorEncoding.layout());
            Jxl.JxlColorEncodingSetToSRGB(color, Jxl.JXL_FALSE());
            encoder.check(Jxl.JxlEncoderSetColorEncoding(handle, color), "JxlEncoderSetColorEncoding");

            MemorySegment channelInfo = arena.allocate(JxlExtraChannelInfo.layout());
            for (int i = 0; i < extraChannels; i++) {
                Jxl.JxlEncoderInitExtraChannelInfo(Jxl.JXL_CHANNEL_OPTIONAL(), channelInfo);
                JxlExtraChannelInfo.bits_per_sample(channelInfo, 8);
                encoder.check(Jxl.JxlEncoderSetExtraChannelInfo(handle, i, channelInfo),
                        "JxlEncoderSetExtraChannelInfo");
            }

            MemorySegment settings = encoder.createFrameSettings(1);
            encoder.check(Jxl.JxlEncoderSetFrameLossless(settings, Jxl.JXL_TRUE()), "JxlEncoderSetFrameLossless");
            if (layerWidth != width || layerHeight != height) {
                MemorySegment header = arena.allocate(JxlFrameHeader.layout());
                Jxl.JxlEncoderInitFrameHeader(header);
                MemorySegment layer = JxlFrameHeader.layer_info(header);
                JxlLayerInfo.have_crop(layer, Jxl.JXL_TRUE());
                JxlLayerInfo.crop_x0(layer, 0);
                JxlLayerInfo.crop_y0(layer, 0);
                JxlLayerInfo.xsize(layer, layerWidth);
                JxlLayerInfo.ysize(layer, layerHeight);
                encoder.check(Jxl.JxlEncoderSetFrameHeader(settings, header), "JxlEncoderSetFrameHeader");
            }

            MemorySegment rgb = arena.allocate((long) layerWidth * layerHeight * 3);
            encoder.check(Jxl.JxlEncoderAddImageFrame(settings, format(3, arena), rgb, rgb.byteSize()),
                    "JxlEncoderAddImageFrame");
            MemorySegment extra = arena.allocate((long) layerWidth * layerHeight);
            for (int i = 0; i < extraChannels; i++) {
                encoder.check(Jxl.JxlEncoderSetExtraChannelBuffer(settings, format(1, arena), extra,
                        extra.byteSize(), i), "JxlEncoderSetExtraChannelBuffer");
            }
            Jxl.JxlEncoderCloseInput(handle);
            return encoder.collectOutput(arena);
        }
    }

    private static MemorySegment format(int channels, Arena arena) {
        MemorySegment format = arena.allocate(JxlPixelFormat.layout());
        JxlPixelFormat.num_channels(format, channels);
        JxlPixelFormat.data_type(format, Jxl.JXL_TYPE_UINT8());
        JxlPixelFormat.endianness(format, Jxl.JXL_NATIVE_ENDIAN());
        JxlPixelFormat.align(format, 0L);
        return format;
    }

    /**
     * Returns the start of a codestream that declares an image of the given
     * size with default metadata, followed by a few zero bytes instead of the
     * frame.
     */
    private static byte[] truncatedCodestream(int width, int height) {
        BitWriter bits = new BitWriter();
        bits.write(0xFF, 8);
        bits.write(0x0A, 8);
        // SizeHeader: not small, height, no ratio, width. Selector 2 of the size
        // distribution stores the size minus 1 in 18 bits.
        bits.write(0, 1);
        bits.write(2, 2);
        bits.write(height - 1, 18);
        bits.write(0, 3);
        bits.write(2, 2);
        bits.write(width - 1, 18);
        // ImageMetadata and CustomTransformData: all_default.
        bits.write(1, 1);
        bits.write(1, 1);
        return bits.toByteArray(8);
    }

    /** Writes bits in the order of the JPEG XL bit stream (least significant bit first). */
    private static final class BitWriter {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int used;

        void write(int value, int count) {
            for (int i = 0; i < count; i++) {
                current |= ((value >>> i) & 1) << used;
                if (++used == 8) {
                    out.write(current);
                    current = 0;
                    used = 0;
                }
            }
        }

        /** Returns the bits, padded to whole bytes and followed by the given number of zero bytes. */
        byte[] toByteArray(int zeroBytes) {
            if (used > 0) {
                out.write(current);
                current = 0;
                used = 0;
            }
            out.writeBytes(new byte[zeroBytes]);
            return out.toByteArray();
        }
    }
}

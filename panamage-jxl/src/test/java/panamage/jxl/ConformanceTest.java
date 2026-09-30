package panamage.jxl;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import panamage.jxl.ffi.Jxl;
import panamage.jxl.ffi.JxlBasicInfo;

/**
 * Compares the decoder output with the reference images of selected test
 * cases of the JPEG XL conformance corpus (github.com/libjxl/conformance),
 * downloaded by {@code scripts/fetch_tools.py}.
 * <p>
 * Each test case is checked like the corpus' own {@code conformance.py}: the
 * first frame is decoded to floating point samples and compared with the
 * reference in the same color space; the peak error and the largest RMSE of
 * a channel must not exceed the limits of the test case. Where the corpus has
 * a reconstructed JPEG file, {@link JxlTranscoder#toJpeg} must reproduce it.
 */
class ConformanceTest {

    private static final String DIRECTORY_PROPERTY = "panamage.jxl.conformance.dir";

    /** ICC tag signature and type 'desc'. */
    private static final int DESC_TAG = 0x64657363;

    /** ICC type 'mluc' (multi-localized Unicode). */
    private static final int MLUC_TYPE = 0x6D6C7563;

    @TestFactory
    Stream<DynamicTest> conformanceCorpus() throws IOException {
        String directory = System.getProperty(DIRECTORY_PROPERTY, "");
        Path corpus = Path.of(directory, "corpus.txt");
        if (directory.isEmpty() || !Files.isRegularFile(corpus)) {
            // A skipped test instead of an aborted factory, so that the build reports it.
            return Stream.of(DynamicTest.dynamicTest("corpus", () -> Assumptions.abort(
                    "Conformance test cases not found in '" + directory + "'; run python scripts/fetch_tools.py")));
        }
        return Files.readAllLines(corpus).stream()
                .filter(name -> !name.isBlank())
                .map(name -> DynamicTest.dynamicTest(name, () -> check(Path.of(directory, name))));
    }

    private static void check(Path testCase) throws IOException {
        String name = testCase.getFileName().toString();
        Map<?, ?> descriptor = (Map<?, ?>) Json.parse(Files.readString(testCase.resolve("test.json"),
                StandardCharsets.UTF_8));
        byte[] input = Files.readAllBytes(testCase.resolve("input.jxl"));

        List<?> frames = (List<?>) descriptor.get("frames");
        List<?> extraChannels = (List<?>) descriptor.get("extra_channel_type");
        int alphaIndex = extraChannels.indexOf("Alpha");
        JxlImageInfo info = JxlDecoder.readInfo(input);
        assertEquals(intAt(descriptor, "bits_per_sample"), info.bitsPerSample(), name + ": bits per sample");
        assertEquals(intAt(descriptor, "exp_bits_per_sample"), info.exponentBitsPerSample(),
                name + ": exponent bits per sample");
        assertEquals(alphaIndex >= 0, info.hasAlpha(), name + ": alpha channel");
        assertEquals(frames.size() > 1, info.animated(), name + ": animation");

        Npy reference = Npy.read(testCase.resolve("reference_image.npy"));
        int[] shape = reference.shape();
        assertEquals(4, shape.length, name + ": reference shape " + Arrays.toString(shape));
        JxlImage.Float32 image = (JxlImage.Float32) JxlDecoder.decode(input, info.channels(), JxlSampleType.FLOAT32,
                true);
        assertEquals(shape[1], image.height(), name + ": height");
        assertEquals(shape[2], image.width(), name + ": width");

        byte[] referenceProfile = Files.readAllBytes(testCase.resolve("reference.icc"));
        if (!Arrays.equals(referenceProfile, image.iccProfile())) {
            // Older libjxl versions created some reference profiles with other bytes. libjxl names
            // the color encoding in the description (for example RGB_D65_SRG_Rel_SRG), so equal
            // descriptions mean the same color space and the samples can be compared directly.
            assertEquals(colorEncoding(referenceProfile), colorEncoding(image.iccProfile()),
                    name + ": color profile of the decoded pixels");
        }

        // Our channels in the order of the reference: the color channels, then alpha among the extra channels.
        int colorChannels = info.colorChannels();
        int[] referenceChannel = new int[image.channels()];
        for (int c = 0; c < colorChannels; c++) {
            referenceChannel[c] = c;
        }
        if (info.hasAlpha()) {
            referenceChannel[colorChannels] = colorChannels + alphaIndex;
        }

        float[] samples = image.pixels();
        if (info.hasAlpha() && storesPremultipliedAlpha(input)) {
            // The reference keeps the stored premultiplied samples, while JxlImage has straight
            // alpha; multiplying by alpha must give the reference again (without the conversion
            // in the decoder, the samples would be multiplied twice and the comparison fails).
            samples = premultiplied(samples, image.channels());
        }

        Map<?, ?> frame = (Map<?, ?>) frames.get(0);
        double rmsLimit = ((Number) frame.get("rms_error")).doubleValue();
        double peakLimit = ((Number) frame.get("peak_error")).doubleValue();
        compare(name, reference, image, samples, referenceChannel, rmsLimit, peakLimit);

        Path jpeg = testCase.resolve("reconstructed.jpg");
        if (Files.exists(jpeg)) {
            assertArrayEquals(Files.readAllBytes(jpeg), JxlTranscoder.toJpeg(input), name + ": reconstructed JPEG");
        }
    }

    /** Checks the first frame like conformance.py: peak error and the largest RMSE of a channel. */
    private static void compare(String name, Npy reference, JxlImage.Float32 image, float[] actual,
            int[] referenceChannel, double rmsLimit, double peakLimit) {
        int referenceChannels = reference.shape()[3];
        int channels = image.channels();
        int pixels = image.width() * image.height();
        float[] expected = reference.data();
        double[] squaredErrors = new double[channels];
        double peak = 0;
        int peakChannel = 0;
        int peakPixel = 0;
        for (int p = 0; p < pixels; p++) {
            for (int c = 0; c < channels; c++) {
                float referenceSample = expected[p * referenceChannels + referenceChannel[c]];
                double error = Math.abs(referenceSample - actual[p * channels + c]);
                squaredErrors[c] += error * error;
                if (error > peak) {
                    peak = error;
                    peakChannel = c;
                    peakPixel = p;
                }
            }
        }
        double rms = 0;
        for (int c = 0; c < channels; c++) {
            rms = Math.max(rms, Math.sqrt(squaredErrors[c] / pixels));
        }
        String details = String.format("%s: RMSE %.3g (limit %.3g), peak error %.3g (limit %.3g) in channel %d at"
                + " pixel %d, expected %s, got %s", name, rms, rmsLimit, peak, peakLimit, peakChannel, peakPixel,
                expected[peakPixel * referenceChannels + referenceChannel[peakChannel]],
                actual[peakPixel * channels + peakChannel]);
        assertTrue(rms <= rmsLimit, details);
        assertTrue(peak <= peakLimit, details);
    }

    /** Reads whether the image stores its alpha channel premultiplied. */
    private static boolean storesPremultipliedAlpha(byte[] input) {
        try (Arena arena = Arena.ofConfined(); NativeDecoder decoder = NativeDecoder.create()) {
            decoder.start(Jxl.JXL_DEC_BASIC_INFO(), arena.allocateFrom(JAVA_BYTE, input));
            assertEquals(Jxl.JXL_DEC_BASIC_INFO(), Jxl.JxlDecoderProcessInput(decoder.handle()));
            MemorySegment info = arena.allocate(JxlBasicInfo.layout());
            NativeDecoder.check(Jxl.JxlDecoderGetBasicInfo(decoder.handle(), info), "JxlDecoderGetBasicInfo");
            return JxlBasicInfo.alpha_premultiplied(info) != 0;
        }
    }

    /** Multiplies the color samples of straight-alpha pixels by alpha. */
    private static float[] premultiplied(float[] straight, int channels) {
        float[] result = straight.clone();
        for (int p = 0; p < result.length; p += channels) {
            float alpha = result[p + channels - 1];
            for (int c = 0; c < channels - 1; c++) {
                result[p + c] *= alpha;
            }
        }
        return result;
    }

    /**
     * Returns the color encoding that libjxl names in the description of a
     * profile it created; newer versions use short names for common ones.
     */
    private static String colorEncoding(byte[] profile) {
        String description = iccDescription(profile);
        return switch (description) {
            case "sRGB" -> "RGB_D65_SRG_Rel_SRG";
            case "LinearSRGB" -> "RGB_D65_SRG_Rel_Lin";
            default -> description;
        };
    }

    /** Returns the text of the 'desc' tag of an ICC profile (ICC v2 'desc' or v4 'mluc' type). */
    static String iccDescription(byte[] profile) {
        ByteBuffer icc = ByteBuffer.wrap(profile);
        int tagCount = icc.getInt(128);
        for (int i = 0; i < tagCount; i++) {
            int entry = 132 + 12 * i;
            if (icc.getInt(entry) != DESC_TAG) {
                continue;
            }
            int offset = icc.getInt(entry + 4);
            int type = icc.getInt(offset);
            if (type == MLUC_TYPE) {
                // First record: language, country, length and offset relative to the tag.
                int length = icc.getInt(offset + 20);
                int start = offset + icc.getInt(offset + 24);
                return new String(profile, start, length, StandardCharsets.UTF_16BE);
            }
            if (type == DESC_TAG) {
                int length = icc.getInt(offset + 8);
                return new String(profile, offset + 12, length, StandardCharsets.ISO_8859_1).replace("\0", "");
            }
            throw new IllegalArgumentException("Unsupported description type " + Integer.toHexString(type));
        }
        throw new IllegalArgumentException("ICC profile without description");
    }

    private static int intAt(Map<?, ?> descriptor, String key) {
        return ((Number) ((List<?>) descriptor.get(key)).get(0)).intValue();
    }
}

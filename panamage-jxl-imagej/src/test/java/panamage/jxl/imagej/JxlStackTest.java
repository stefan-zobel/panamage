package panamage.jxl.imagej;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.scijava.Context;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ShortProcessor;
import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imglib2.display.ColorTable;
import net.imglib2.img.planar.PlanarImg;
import panamage.jxl.JxlAnimationHeader;
import panamage.jxl.JxlBox;
import panamage.jxl.JxlChannels;
import panamage.jxl.JxlDecoder;
import panamage.jxl.JxlEncodeOptions;
import panamage.jxl.JxlEncoder;
import panamage.jxl.JxlFrameEncoder;
import panamage.jxl.JxlLimits;
import panamage.jxl.JxlMetadata;

class JxlStackTest {

    /** Encoded files are kept here for cross-checks with the libjxl command line tools. */
    private static final Path OUTPUT_DIR = Path.of("target", "test-output");

    private static final int WIDTH = TestPlanes.WIDTH;
    private static final int HEIGHT = TestPlanes.HEIGHT;

    private static Context context;

    @BeforeAll
    static void createContext() {
        context = new Context(DatasetService.class);
    }

    @AfterAll
    static void disposeContext() {
        context.dispose();
    }

    @Test
    void hyperstackSurvivesLosslessRoundTrip() throws IOException {
        CompositeImage imp = StackMetadataTest.hyperstack(2, 3, 2);

        byte[] jxl = write(imp, false, "hyperstack.jxl");
        ImagePlus read = JxlStack.read(jxl, JxlLimits.defaults()).toImagePlus("read");

        assertTrue(JxlDecoder.readInfo(jxl).animated());
        assertEquals(1, JxlDecoder.readInfo(jxl).colorChannels());
        assertEquals(6, JxlDecoder.readAnimationInfo(jxl).frameCount());
        CompositeImage composite = assertInstanceOf(CompositeImage.class, read);
        assertEquals(2, read.getNChannels());
        assertEquals(3, read.getNSlices());
        assertEquals(2, read.getNFrames());
        assertSamePlanes(imp, read);
        assertEquals(IJ.COLOR, composite.getMode());
        assertEquals(Color.MAGENTA, new Color(composite.getChannelLut(2).getRGB(255)));
        assertEquals(4000, composite.getChannelLut(2).max);
        assertEquals(0.25, read.getCalibration().pixelWidth);
        assertEquals("min", read.getCalibration().getTimeUnit());
        assertTrue(read.getCalibration().isSigned16Bit());
        assertEquals("plane 7", read.getStack().getSliceLabel(7));
        assertNull(read.getStack().getSliceLabel(8));
        assertEquals("line 1\nline 2", read.getInfoProperty());
    }

    @Test
    void singleImageIsAStillImage() throws IOException {
        CompositeImage imp = StackMetadataTest.hyperstack(2, 1, 1);

        byte[] jxl = write(imp, false, "two-channels.jxl");
        ImagePlus read = JxlStack.read(jxl, JxlLimits.defaults()).toImagePlus("read");

        assertFalse(JxlDecoder.readInfo(jxl).animated());
        assertEquals(2, read.getNChannels());
        assertSamePlanes(imp, read);
        assertEquals(0.5, read.getCalibration().pixelHeight);
    }

    @Test
    void rgbStackStaysRgb() throws IOException {
        ImageStack stack = new ImageStack(WIDTH, HEIGHT);
        for (int z = 1; z <= 2; z++) {
            ColorProcessor processor = new ColorProcessor(WIDTH, HEIGHT);
            processor.setRGB(TestPlanes.uint8(z), TestPlanes.uint8(z + 1), TestPlanes.uint8(z + 2));
            stack.addSlice("z" + z, processor);
        }
        ImagePlus imp = new ImagePlus("rgb", stack);

        byte[] jxl = write(imp, false, "rgb-stack.jxl");
        ImagePlus read = JxlStack.read(jxl, JxlLimits.defaults()).toImagePlus("read");

        assertEquals(3, JxlDecoder.readInfo(jxl).colorChannels());
        assertEquals(ImagePlus.COLOR_RGB, read.getType());
        assertEquals(2, read.getNSlices());
        assertSamePlanes(imp, read);
        assertEquals("z2", read.getStack().getSliceLabel(2));
    }

    @Test
    void threeChannelsCanBeStoredAsRedGreenAndBlue() throws IOException {
        ImageStack stack = new ImageStack(WIDTH, HEIGHT);
        for (int c = 1; c <= 3; c++) {
            stack.addSlice(null, new ByteProcessor(WIDTH, HEIGHT, TestPlanes.uint8(c)));
        }
        ImagePlus channels = new ImagePlus("channels", stack);
        channels.setDimensions(3, 1, 1);
        CompositeImage imp = new CompositeImage(channels, IJ.COMPOSITE);

        byte[] jxl = write(imp, true, "photo-as-channels.jxl");
        ImagePlus read = JxlStack.read(jxl, JxlLimits.defaults()).toImagePlus("read");
        ImagePlus withoutBox = ImagePlusConverter.toImagePlus("plain", JxlDecoder.decodeChannels(jxl,
                ChannelLayout.losslessType(jxl)));

        assertEquals(3, JxlDecoder.readInfo(jxl).colorChannels());
        assertEquals(3, read.getNChannels());
        assertTrue(read.isComposite());
        assertSamePlanes(imp, read);
        assertEquals(ImagePlus.COLOR_RGB, withoutBox.getType());
    }

    @Test
    void framesOfAFileWithoutBoxAreTimePoints() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JxlFrameEncoder encoder = JxlFrameEncoder.open(out, JxlAnimationHeader.millis(0),
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE)) {
            for (int t = 1; t <= 4; t++) {
                encoder.add(JxlChannels.builder(WIDTH, HEIGHT).gray(TestPlanes.uint16(t))
                        .add("GFP", TestPlanes.uint16(t + 10)).build(), 40);
            }
            encoder.finish();
        }

        JxlStack stack = JxlStack.read(out.toByteArray(), JxlLimits.defaults());
        ImagePlus read = stack.toImagePlus("read");

        assertEquals(2, read.getNChannels());
        assertEquals(1, read.getNSlices());
        assertEquals(4, read.getNFrames());
        assertEquals(0.04, read.getCalibration().frameInterval, 1e-12);
        assertEquals("sec", read.getCalibration().getTimeUnit());
        assertArrayEquals(TestPlanes.uint16(13), (short[]) read.getStack().getPixels(read.getStackIndex(2, 1, 3)));
    }

    @Test
    void boxThatDoesNotMatchTheFramesIsIgnored() {
        StackMetadata wrong = StackMetadata.of(1, 5, 1, false);
        byte[] jxl = JxlEncoder.encode(JxlChannels.builder(WIDTH, HEIGHT).gray(TestPlanes.uint8(1))
                .build(), JxlEncodeOptions.ofLossless(),
                new JxlMetadata(null, null, List.of(JxlBox.of(StackMetadata.BOX_TYPE, wrong.toJson()))));
        byte[] invalid = JxlEncoder.encode(JxlChannels.builder(WIDTH, HEIGHT)
                .gray(TestPlanes.uint8(1)).build(), JxlEncodeOptions.ofLossless(),
                new JxlMetadata(null, null, List.of(JxlBox.of(StackMetadata.BOX_TYPE,
                        "{broken".getBytes(StandardCharsets.UTF_8)))));

        assertEquals(1, JxlStack.read(jxl, JxlLimits.defaults()).metadata().slices());
        assertEquals(1, JxlStack.read(invalid, JxlLimits.defaults()).metadata().slices());
    }

    @Test
    void sixteenBitImagesAreStoredWithTheBitsTheyNeed() throws IOException {
        for (int max : new int[] {885, 200, 4095, 65535}) {
            short[] plane = new short[TestPlanes.SAMPLES];
            for (int i = 0; i < plane.length; i++) {
                plane[i] = (short) (i * 37L % (max + 1));
            }
            plane[0] = (short) max;
            ImagePlus imp = new ImagePlus("bits", new ShortProcessor(WIDTH, HEIGHT, plane, null));

            byte[] jxl = write(imp, false, null);
            ImagePlus read = JxlStack.read(jxl, JxlLimits.defaults()).toImagePlus("read");

            int expected = Math.max(9, Integer.SIZE - Integer.numberOfLeadingZeros(max));
            assertEquals(expected, JxlStack.significantBits(imp), "max " + max);
            assertEquals(expected, JxlDecoder.readInfo(jxl).bitsPerSample(), "max " + max);
            assertEquals(ImagePlus.GRAY16, read.getType(), "max " + max);
            assertArrayEquals(plane, (short[]) read.getProcessor().getPixels(), "max " + max);
        }
        assertEquals(0, JxlStack.significantBits(new ImagePlus("8-bit", new ByteProcessor(WIDTH, HEIGHT))));
    }

    @Test
    void hyperstackBecomesDatasetWithCalibratedAxes() throws IOException {
        CompositeImage imp = StackMetadataTest.hyperstack(2, 3, 2);
        JxlStack stack = JxlStack.read(write(imp, false, null), JxlLimits.defaults());

        Dataset dataset = DatasetConverter.toDataset(context.service(DatasetService.class), "stack",
                stack.positions(), stack.metadata());

        ImgPlus<?> imgPlus = dataset.getImgPlus();
        assertEquals(5, imgPlus.numDimensions());
        assertEquals(List.of(Axes.X, Axes.Y, Axes.CHANNEL, Axes.Z, Axes.TIME),
                List.of(imgPlus.axis(0).type(), imgPlus.axis(1).type(), imgPlus.axis(2).type(),
                        imgPlus.axis(3).type(), imgPlus.axis(4).type()));
        assertEquals(0.25, imgPlus.averageScale(0));
        assertEquals("micron", imgPlus.axis(0).unit());
        assertEquals(1.5, imgPlus.averageScale(3));
        assertEquals(30, imgPlus.averageScale(4));
        assertEquals("min", imgPlus.axis(4).unit());
        assertEquals(2, dataset.getCompositeChannelCount());
        ColorTable magenta = imgPlus.getColorTable(1);
        assertEquals(255, magenta.get(ColorTable.RED, 255));
        assertEquals(0, magenta.get(ColorTable.GREEN, 255));
        assertEquals(100, imgPlus.getChannelMinimum(1));
        assertEquals(4000, imgPlus.getChannelMaximum(1));
        // The planes of the Dataset are those of the ImageJ stack, in the same order.
        PlanarImg<?, ?> img = (PlanarImg<?, ?>) imgPlus.getImg();
        assertArrayEquals((short[]) imp.getStack().getPixels(imp.getStackIndex(2, 2, 2)),
                (short[]) img.getPlane(imp.getStackIndex(2, 2, 2) - 1).getCurrentStorageArray());
    }

    private static byte[] write(ImagePlus imp, boolean rgb, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JxlStack.write(imp, out, JxlEncodeOptions.ofLossless(), rgb);
        byte[] jxl = out.toByteArray();
        if (name != null) {
            Files.createDirectories(OUTPUT_DIR);
            Files.write(OUTPUT_DIR.resolve(name), jxl);
        }
        return jxl;
    }

    private static void assertSamePlanes(ImagePlus expected, ImagePlus actual) {
        assertEquals(expected.getStackSize(), actual.getStackSize());
        for (int n = 1; n <= expected.getStackSize(); n++) {
            assertTrue(Objects.deepEquals(expected.getStack().getPixels(n), actual.getStack().getPixels(n)),
                    "plane " + n);
        }
    }
}

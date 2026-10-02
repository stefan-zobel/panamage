package panamage.jxl.imagej;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.Color;
import java.util.List;

import org.junit.jupiter.api.Test;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.LUT;
import ij.process.ShortProcessor;
import panamage.jxl.JxlChannels;
import panamage.jxl.JxlEncodeOptions;
import panamage.jxl.JxlEncoder;
import panamage.jxl.JxlExtraChannel;
import panamage.jxl.JxlSampleType;

class ImagePlusConverterTest {

    private static final int WIDTH = TestPlanes.WIDTH;
    private static final int HEIGHT = TestPlanes.HEIGHT;
    private static final int SAMPLES = TestPlanes.SAMPLES;

    @Test
    void grayImageBecomesOneChannel() {
        byte[] gray = TestPlanes.uint8(1);
        JxlChannels image = JxlChannels.builder(WIDTH, HEIGHT).gray(gray).build();

        ImagePlus imp = ImagePlusConverter.toImagePlus("gray", image);

        assertEquals("gray", imp.getTitle());
        assertEquals(ImagePlus.GRAY8, imp.getType());
        assertEquals(1, imp.getStackSize());
        assertSame(gray, imp.getProcessor().getPixels());

        JxlChannels back = ImagePlusConverter.toChannels(imp, 1, 1);
        JxlChannels.Uint8 uint8 = assertInstanceOf(JxlChannels.Uint8.class, back);
        assertEquals(1, back.colorChannels());
        assertEquals(List.of(), back.extraChannels());
        assertSame(gray, uint8.planes().get(0));
    }

    @Test
    void rgbImageBecomesColorImage() {
        byte[] red = TestPlanes.uint8(1);
        byte[] green = TestPlanes.uint8(2);
        byte[] blue = TestPlanes.uint8(3);
        JxlChannels image = JxlChannels.builder(WIDTH, HEIGHT).rgb(red, green, blue).build();

        ImagePlus imp = ImagePlusConverter.toImagePlus("rgb", image);

        assertEquals(ImagePlus.COLOR_RGB, imp.getType());
        assertEquals(1, imp.getNChannels());
        int[] pixel = imp.getPixel(5, 7);
        int i = 7 * WIDTH + 5;
        assertEquals(red[i] & 0xFF, pixel[0]);
        assertEquals(green[i] & 0xFF, pixel[1]);
        assertEquals(blue[i] & 0xFF, pixel[2]);

        JxlChannels.Uint8 back = assertInstanceOf(JxlChannels.Uint8.class, ImagePlusConverter.toChannels(imp, 1, 1));
        assertEquals(3, back.colorChannels());
        assertArrayEquals(red, back.planes().get(0));
        assertArrayEquals(green, back.planes().get(1));
        assertArrayEquals(blue, back.planes().get(2));
    }

    @Test
    void extraChannelsBecomeChannelsOfACompositeImage() {
        short[] dapi = TestPlanes.uint16(1);
        short[] gfp = TestPlanes.uint16(2);
        short[] mcherry = TestPlanes.uint16(3);
        JxlChannels image = JxlChannels.builder(WIDTH, HEIGHT).gray(dapi).add("GFP", gfp).add("mCherry", mcherry)
                .build();

        ImagePlus imp = ImagePlusConverter.toImagePlus("cells", image);

        CompositeImage composite = assertInstanceOf(CompositeImage.class, imp);
        assertEquals(ImagePlus.GRAY16, imp.getType());
        assertEquals(IJ.COMPOSITE, composite.getMode());
        assertEquals(3, imp.getNChannels());
        assertEquals(1, imp.getNSlices());
        assertEquals(1, imp.getNFrames());
        assertEquals(new ShortProcessor(WIDTH, HEIGHT, gfp, null).getMax(), composite.getChannelLut(2).max);
        assertSame(dapi, imp.getStack().getPixels(1));
        assertSame(gfp, imp.getStack().getPixels(2));
        assertSame(mcherry, imp.getStack().getPixels(3));

        JxlChannels.Uint16 back = assertInstanceOf(JxlChannels.Uint16.class,
                ImagePlusConverter.toChannels(imp, 1, 1));
        assertEquals(1, back.colorChannels());
        assertEquals(List.of(JxlExtraChannel.of(""), JxlExtraChannel.of("")), back.extraChannels());
        assertEquals(List.of(dapi, gfp, mcherry), back.planes());
    }

    @Test
    void colorChannelsOf16BitImagesAreRedGreenAndBlue() {
        short[] red = TestPlanes.uint16(1);
        JxlChannels image = JxlChannels.builder(WIDTH, HEIGHT)
                .rgb(red, TestPlanes.uint16(2), TestPlanes.uint16(3)).build();

        CompositeImage composite = assertInstanceOf(CompositeImage.class,
                ImagePlusConverter.toImagePlus("rgb16", image));

        assertEquals(3, composite.getNChannels());
        assertEquals(Color.RED, lutColor(composite.getChannelLut(1)));
        assertEquals(Color.GREEN, lutColor(composite.getChannelLut(2)));
        assertEquals(Color.BLUE, lutColor(composite.getChannelLut(3)));
        ShortProcessor processor = new ShortProcessor(WIDTH, HEIGHT, red, null);
        assertEquals(processor.getMin(), composite.getChannelLut(1).min);
        assertEquals(processor.getMax(), composite.getChannelLut(1).max);
    }

    @Test
    void floatImagesKeepTheirSamples() {
        float[] plane = TestPlanes.float32(1);
        float[] extra = TestPlanes.float32(2);
        JxlChannels image = JxlChannels.builder(WIDTH, HEIGHT).gray(plane).add("depth", extra).build();

        ImagePlus imp = ImagePlusConverter.toImagePlus("float", image);

        assertEquals(ImagePlus.GRAY32, imp.getType());
        assertEquals(2, imp.getNChannels());
        JxlChannels.Float32 back = assertInstanceOf(JxlChannels.Float32.class,
                ImagePlusConverter.toChannels(imp, 1, 1));
        assertEquals(List.of(plane, extra), back.planes());
    }

    @Test
    void channelsAreTakenFromTheGivenSliceAndFrame() {
        int channels = 2;
        int slices = 3;
        int frames = 2;
        ImageStack stack = new ImageStack(WIDTH, HEIGHT);
        for (int n = 1; n <= channels * slices * frames; n++) {
            stack.addSlice(null, new ShortProcessor(WIDTH, HEIGHT, TestPlanes.uint16(n), null));
        }
        ImagePlus imp = new ImagePlus("hyperstack", stack);
        imp.setDimensions(channels, slices, frames);

        JxlChannels.Uint16 image = assertInstanceOf(JxlChannels.Uint16.class,
                ImagePlusConverter.toChannels(imp, 2, 2));

        assertEquals(2, image.channels());
        assertSame(stack.getPixels(imp.getStackIndex(1, 2, 2)), image.planes().get(0));
        assertSame(stack.getPixels(imp.getStackIndex(2, 2, 2)), image.planes().get(1));
        assertThrows(IllegalArgumentException.class, () -> ImagePlusConverter.toChannels(imp, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> ImagePlusConverter.toChannels(imp, 4, 1));
        assertThrows(IllegalArgumentException.class, () -> ImagePlusConverter.toChannels(imp, 1, 3));
    }

    @Test
    void rgbImagesWithSeveralChannelsAreRejected() {
        ImageStack stack = new ImageStack(WIDTH, HEIGHT);
        stack.addSlice(null, new ColorProcessor(WIDTH, HEIGHT));
        stack.addSlice(null, new ColorProcessor(WIDTH, HEIGHT));
        ImagePlus imp = new ImagePlus("rgb", stack);
        imp.setDimensions(2, 1, 1);

        assertThrows(IllegalArgumentException.class, () -> ImagePlusConverter.toChannels(imp, 1, 1));
    }

    @Test
    void imagesWithALookupTableAreStoredAsGray() {
        ByteProcessor processor = new ByteProcessor(WIDTH, HEIGHT, TestPlanes.uint8(4));
        processor.setLut(LUT.createLutFromColor(Color.MAGENTA));
        ImagePlus imp = new ImagePlus("lut", processor);

        JxlChannels image = ImagePlusConverter.toChannels(imp, 1, 1);

        assertEquals(JxlSampleType.UINT8, image.sampleType());
        assertEquals(1, image.channels());
    }

    @Test
    void losslessRoundTripThroughJpegXl() {
        ImageStack stack = new ImageStack(WIDTH, HEIGHT);
        for (int c = 1; c <= 5; c++) {
            stack.addSlice(null, new ShortProcessor(WIDTH, HEIGHT, TestPlanes.uint16(c), null));
        }
        ImagePlus imp = new ImagePlus("five channels", stack);
        imp.setDimensions(5, 1, 1);

        byte[] jxl = JxlEncoder.encode(ImagePlusConverter.toChannels(imp, 1, 1), JxlEncodeOptions.ofLossless());
        ImagePlus read = ImagePlusConverter.toImagePlus("read", ChannelLayout.decode(jxl));

        assertEquals(ImagePlus.GRAY16, read.getType());
        assertEquals(5, read.getNChannels());
        for (int n = 1; n <= 5; n++) {
            assertArrayEquals((short[]) stack.getPixels(n), (short[]) read.getStack().getPixels(n));
        }
    }

    @Test
    void manyChannelsBecomeAHyperstack() {
        int channels = CompositeImage.MAX_CHANNELS + 1;
        JxlChannels.Builder builder = JxlChannels.builder(WIDTH, HEIGHT).gray(new byte[SAMPLES]);
        for (int c = 1; c < channels; c++) {
            builder.add("c" + c, new byte[SAMPLES]);
        }

        ImagePlus imp = ImagePlusConverter.toImagePlus("many", builder.build());

        assertEquals(channels, imp.getNChannels());
        assertEquals(false, imp.isComposite());
    }

    private static Color lutColor(LUT lut) {
        return new Color(lut.getRed(255), lut.getGreen(255), lut.getBlue(255));
    }
}

package panamage.jxl.imagej;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.scijava.Context;

import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.display.ColorTables;
import net.imglib2.RandomAccess;
import net.imglib2.img.planar.PlanarImg;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import panamage.jxl.JxlChannels;

class DatasetConverterTest {

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
    void grayImageHasTwoAxes() {
        byte[] gray = TestPlanes.uint8(1);
        JxlChannels image = JxlChannels.builder(WIDTH, HEIGHT).gray(gray).build();

        ImgPlus<? extends RealType<?>> imgPlus = DatasetConverter.toImgPlus("gray", image);

        assertEquals("gray", imgPlus.getName());
        assertEquals(2, imgPlus.numDimensions());
        assertEquals(Axes.X, imgPlus.axis(0).type());
        assertEquals(Axes.Y, imgPlus.axis(1).type());
        assertEquals(WIDTH, imgPlus.dimension(0));
        assertEquals(HEIGHT, imgPlus.dimension(1));
        assertInstanceOf(UnsignedByteType.class, imgPlus.firstElement());
        PlanarImg<?, ?> img = assertInstanceOf(PlanarImg.class, imgPlus.getImg());
        assertSame(gray, img.getPlane(0).getCurrentStorageArray());
        assertEquals(gray[3 * WIDTH + 4] & 0xFF, sample(imgPlus, 4, 3), 0);
    }

    @Test
    void channelsBecomeACompositeChannelAxis() {
        short[] dapi = TestPlanes.uint16(1);
        short[] gfp = TestPlanes.uint16(2);
        JxlChannels image = JxlChannels.builder(WIDTH, HEIGHT).gray(dapi).add("GFP", gfp).build();

        ImgPlus<? extends RealType<?>> imgPlus = DatasetConverter.toImgPlus("cells", image);

        assertEquals(3, imgPlus.numDimensions());
        assertEquals(Axes.CHANNEL, imgPlus.axis(2).type());
        assertEquals(2, imgPlus.dimension(2));
        assertEquals(2, imgPlus.getCompositeChannelCount());
        assertEquals(0, imgPlus.getColorTableCount());
        assertInstanceOf(UnsignedShortType.class, imgPlus.firstElement());
        assertEquals(gfp[5 * WIDTH + 6] & 0xFFFF, sample(imgPlus, 6, 5, 1), 0);
    }

    @Test
    void colorChannelsOf16BitImagesHaveRedGreenAndBlueTables() {
        JxlChannels image = JxlChannels.builder(WIDTH, HEIGHT)
                .rgb(TestPlanes.float32(1), TestPlanes.float32(2), TestPlanes.float32(3)).build();

        ImgPlus<? extends RealType<?>> imgPlus = DatasetConverter.toImgPlus("rgb", image);

        assertInstanceOf(FloatType.class, imgPlus.firstElement());
        assertEquals(3, imgPlus.getCompositeChannelCount());
        assertSame(ColorTables.RED, imgPlus.getColorTable(0));
        assertSame(ColorTables.GREEN, imgPlus.getColorTable(1));
        assertSame(ColorTables.BLUE, imgPlus.getColorTable(2));
    }

    @Test
    void rgbImageBecomesMergedDataset() {
        byte[] red = TestPlanes.uint8(1);
        JxlChannels image = JxlChannels.builder(WIDTH, HEIGHT)
                .rgb(red, TestPlanes.uint8(2), TestPlanes.uint8(3)).build();
        DatasetService datasets = context.service(DatasetService.class);

        Dataset dataset = DatasetConverter.toDataset(datasets, "photo", image);

        assertTrue(dataset.isRGBMerged());
        assertEquals("photo", dataset.getName());
        assertEquals(3, dataset.dimension(2));
        assertEquals(8, dataset.getType().getBitsPerPixel());
        assertNull(dataset.getImgPlus().getColorTable(0));
        assertEquals(red[2 * WIDTH + 1] & 0xFF, sample(dataset.getImgPlus(), 1, 2, 0), 0);
    }

    @Test
    void channelsBecomeUnmergedDataset() {
        JxlChannels image = JxlChannels.builder(WIDTH, HEIGHT)
                .gray(TestPlanes.uint8(1)).add("A", TestPlanes.uint8(2)).add("B", TestPlanes.uint8(3)).build();
        DatasetService datasets = context.service(DatasetService.class);

        Dataset dataset = DatasetConverter.toDataset(datasets, "channels", image);

        assertFalse(dataset.isRGBMerged());
        assertEquals(3, dataset.getCompositeChannelCount());
    }

    private static double sample(ImgPlus<? extends RealType<?>> imgPlus, long... position) {
        RandomAccess<? extends RealType<?>> access = imgPlus.randomAccess();
        access.setPosition(position);
        return access.get().getRealDouble();
    }
}

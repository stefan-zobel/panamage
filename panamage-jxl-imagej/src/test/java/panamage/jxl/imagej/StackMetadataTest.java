package panamage.jxl.imagej;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ColorProcessor;
import ij.process.LUT;
import ij.process.ShortProcessor;

class StackMetadataTest {

    private static final int WIDTH = TestPlanes.WIDTH;
    private static final int HEIGHT = TestPlanes.HEIGHT;

    @Test
    void metadataOfAHyperstackSurvivesJson() {
        CompositeImage imp = hyperstack(2, 3, 2);

        StackMetadata metadata = StackMetadata.fromJson(StackMetadata.of(imp).toJson());

        assertEquals(2, metadata.channels());
        assertEquals(3, metadata.slices());
        assertEquals(2, metadata.frames());
        assertFalse(metadata.rgb());
        assertEquals(new StackMetadata.Axis(0.25, 3, "micron"), metadata.x());
        assertEquals(new StackMetadata.Axis(0.5, -2, "nm"), metadata.y());
        assertEquals(new StackMetadata.Axis(1.5, 0, "mm"), metadata.z());
        assertEquals(30, metadata.frameInterval());
        assertEquals("min", metadata.timeUnit());
        assertEquals(Calibration.STRAIGHT_LINE, metadata.value().function());
        assertArrayEquals(new double[] {-32768, 1}, metadata.value().coefficients());
        assertEquals("Gray Value", metadata.value().unit());
        assertEquals(2, metadata.channelDisplays().size());
        assertEquals(100, metadata.channelDisplays().get(1).min());
        assertEquals(4000, metadata.channelDisplays().get(1).max());
        assertEquals("color", metadata.mode());
        assertEquals(12, metadata.labels().size());
        assertEquals("plane 5", metadata.labels().get(4));
        assertNull(metadata.labels().get(5));
        assertEquals("line 1\nline 2", metadata.info());
    }

    @Test
    void metadataIsAppliedToANewImage() {
        CompositeImage source = hyperstack(2, 3, 2);
        StackMetadata metadata = StackMetadata.fromJson(StackMetadata.of(source).toJson());
        CompositeImage target = hyperstack(2, 3, 2);
        target.setCalibration(new Calibration());
        target.setMode(IJ.COMPOSITE);
        target.setChannelLut(LUT.createLutFromColor(Color.WHITE), 2);
        for (int n = 1; n <= target.getStackSize(); n++) {
            target.getStack().setSliceLabel(null, n);
        }
        target.setProperty("Info", null);

        metadata.applyTo(target);

        Calibration cal = target.getCalibration();
        assertEquals(0.25, cal.pixelWidth);
        assertEquals(3, cal.xOrigin);
        assertEquals("micron", cal.getXUnit());
        assertEquals(0.5, cal.pixelHeight);
        assertEquals("nm", cal.getYUnit());
        assertEquals(1.5, cal.pixelDepth);
        assertEquals(30, cal.frameInterval);
        assertEquals("min", cal.getTimeUnit());
        assertTrue(cal.isSigned16Bit());
        assertEquals(IJ.COLOR, target.getMode());
        LUT lut = target.getChannelLut(2);
        assertEquals(Color.MAGENTA, new Color(lut.getRGB(255)));
        assertEquals(100, lut.min);
        assertEquals(4000, lut.max);
        assertEquals("plane 5", target.getStack().getSliceLabel(5));
        assertEquals("line 1\nline 2", target.getInfoProperty());
    }

    @Test
    void rgbImagesHaveNoLookupTables() {
        ImagePlus imp = new ImagePlus("rgb", new ColorProcessor(WIDTH, HEIGHT));

        StackMetadata metadata = StackMetadata.fromJson(StackMetadata.of(imp).toJson());

        assertTrue(metadata.rgb());
        assertEquals(1, metadata.channels());
        assertEquals(List.of(), metadata.channelDisplays());
        assertNull(metadata.value());
        assertNull(metadata.mode());
    }

    @Test
    void grayImageKeepsItsLookupTableAndRange() {
        ShortProcessor processor = new ShortProcessor(WIDTH, HEIGHT, TestPlanes.uint16(1), null);
        ImagePlus imp = new ImagePlus("gray", processor);
        imp.setLut(LUT.createLutFromColor(Color.GREEN));
        imp.setDisplayRange(10, 1000);
        StackMetadata metadata = StackMetadata.fromJson(StackMetadata.of(imp).toJson());
        ImagePlus target = new ImagePlus("target", new ShortProcessor(WIDTH, HEIGHT, TestPlanes.uint16(1), null));

        metadata.applyTo(target);

        assertEquals(Color.GREEN, new Color(target.getProcessor().getLut().getRGB(255)));
        assertEquals(10, target.getDisplayRangeMin());
        assertEquals(1000, target.getDisplayRangeMax());
    }

    @Test
    void unknownFieldsAreIgnored() {
        StackMetadata metadata = StackMetadata.fromJson(
                "{\"version\":2,\"channels\":1,\"slices\":4,\"frames\":1,\"future\":{\"a\":[1]}}"
                        .getBytes(StandardCharsets.UTF_8));

        assertEquals(4, metadata.slices());
        assertNull(metadata.x());
        assertEquals(List.of(), metadata.labels());
    }

    @Test
    void invalidMetadataIsRejected() {
        for (String json : List.of("[]", "{\"channels\":1,\"slices\":1}", "{\"channels\":0,\"slices\":1,\"frames\":1}",
                "{\"channels\":1.5,\"slices\":1,\"frames\":1}", "{\"channels\":\"1\",\"slices\":1,\"frames\":1}",
                "{\"channels\":1,\"slices\":1,\"frames\":1,\"mode\":\"other\"}",
                "{\"channels\":1,\"slices\":2,\"frames\":1,\"labels\":[\"a\"]}",
                "{\"channels\":1,\"slices\":1,\"frames\":1,\"channelDisplays\":[{\"lut\":\"00\",\"min\":0,\"max\":1}]}",
                "{\"channels\":1,\"slices\":1,\"frames\":1,\"x\":{\"scale\":1,\"origin\":0}}", "not json")) {
            assertThrows(IllegalArgumentException.class,
                    () -> StackMetadata.fromJson(json.getBytes(StandardCharsets.UTF_8)), json);
        }
    }

    /** A 16-bit hyperstack with calibration, lookup tables, display ranges, labels and info. */
    static CompositeImage hyperstack(int channels, int slices, int frames) {
        ImageStack stack = new ImageStack(WIDTH, HEIGHT);
        int planes = channels * slices * frames;
        for (int n = 1; n <= planes; n++) {
            stack.addSlice(n % 2 == 1 ? "plane " + n : null,
                    new ShortProcessor(WIDTH, HEIGHT, TestPlanes.uint16(n), null));
        }
        ImagePlus imp = new ImagePlus("hyperstack", stack);
        imp.setDimensions(channels, slices, frames);
        CompositeImage composite = new CompositeImage(imp, IJ.COLOR);
        composite.setChannelLut(lut(Color.CYAN, 0, 65535), 1);
        if (channels > 1) {
            composite.setChannelLut(lut(Color.MAGENTA, 100, 4000), 2);
        }
        Calibration cal = composite.getCalibration();
        cal.pixelWidth = 0.25;
        cal.xOrigin = 3;
        cal.setXUnit("micron");
        cal.pixelHeight = 0.5;
        cal.yOrigin = -2;
        cal.setYUnit("nm");
        cal.pixelDepth = 1.5;
        cal.setZUnit("mm");
        cal.frameInterval = 30;
        cal.setTimeUnit("min");
        cal.setSigned16BitCalibration();
        composite.setProperty("Info", "line 1\nline 2");
        return composite;
    }

    private static LUT lut(Color color, double min, double max) {
        LUT lut = LUT.createLutFromColor(color);
        lut.min = min;
        lut.max = max;
        return lut;
    }
}

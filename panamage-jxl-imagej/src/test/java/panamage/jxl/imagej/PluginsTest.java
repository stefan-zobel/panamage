package panamage.jxl.imagej;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scijava.Context;
import org.scijava.io.IOService;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.io.FileInfo;
import ij.process.ByteProcessor;
import net.imagej.Dataset;
import net.imagej.DatasetService;
import panamage.jxl.JxlDecoder;
import panamage.jxl.JxlEncodeOptions;

class PluginsTest {

    private static Context context;

    @TempDir
    Path dir;

    @BeforeAll
    static void createContext() {
        context = new Context(IOService.class, DatasetService.class);
    }

    @AfterAll
    static void disposeContext() {
        context.dispose();
    }

    @Test
    void runtimeCanRunPanamage() {
        assertNull(JxlRuntime.problem());
    }

    @Test
    void pluginIndexListsTheOpeners() throws IOException {
        List<String> index = new ArrayList<>();
        for (var url : Collections.list(
                getClass().getClassLoader().getResources("META-INF/json/org.scijava.plugin.Plugin"))) {
            try (InputStream in = url.openStream()) {
                index.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        String all = String.join("\n", index);
        assertTrue(all.contains(JxlIOPlugin.class.getName()), all);
        assertTrue(all.contains(JxlLegacyOpener.class.getName()), all);
    }

    @Test
    void pluginsConfigAddsTheSaveCommand() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("plugins.config")) {
            String config = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(config.contains("File>Save As, \"JPEG XL...\", " + SaveJxl.class.getName()), config);
        }
    }

    @Test
    void savedHyperstackOpensWithItsMetadata() throws IOException {
        CompositeImage imp = StackMetadataTest.hyperstack(2, 3, 2);
        imp.changes = true;
        Path file = dir.resolve("saved.jxl");

        JxlFiles.save(imp, file, JxlEncodeOptions.ofLossless().withEffort(3), false);

        assertEquals("saved.jxl", imp.getTitle());
        assertFalse(imp.changes);
        assertEquals(dir.toAbsolutePath() + File.separator, imp.getOriginalFileInfo().directory);
        try (var files = Files.list(dir)) {
            assertEquals(List.of(file.getFileName()), files.map(Path::getFileName).toList());
        }
        ImagePlus read = assertInstanceOf(ImagePlus.class,
                new JxlLegacyOpener().open(file.toString(), -1, false));
        assertEquals("saved.jxl", read.getTitle());
        assertEquals(12, read.getStackSize());
        assertEquals(2, read.getNChannels());
        assertEquals("line 1\nline 2", read.getInfoProperty());
        FileInfo info = read.getOriginalFileInfo();
        assertEquals("saved.jxl", info.fileName);
        for (int n = 1; n <= 12; n++) {
            assertArrayEquals((short[]) imp.getStack().getPixels(n), (short[]) read.getStack().getPixels(n));
        }
    }

    @Test
    void legacyOpenerReturnsOnePlaneOnRequest() throws IOException {
        CompositeImage imp = StackMetadataTest.hyperstack(2, 3, 1);
        Path file = dir.resolve("planes.jxl");
        JxlFiles.save(imp, file, JxlEncodeOptions.ofLossless(), false);

        ImagePlus plane = assertInstanceOf(ImagePlus.class, new JxlLegacyOpener().open(file.toString(), 4, false));

        assertEquals(1, plane.getStackSize());
        assertArrayEquals((short[]) imp.getStack().getPixels(4), (short[]) plane.getProcessor().getPixels());
        assertEquals(0.25, plane.getCalibration().pixelWidth);
    }

    @Test
    void legacyOpenerIgnoresOtherFiles() {
        JxlLegacyOpener opener = new JxlLegacyOpener();

        assertNull(opener.open(null, -1, true));
        assertNull(opener.open(dir.resolve("image.png").toString(), -1, false));
        assertNull(opener.open("https://example.org/image.jxl", -1, false));
    }

    @Test
    void legacyOpenerReportsInvalidFiles() throws IOException {
        Path file = dir.resolve("broken.jxl");
        Files.write(file, new byte[] {1, 2, 3});

        assertSame(Boolean.TRUE, new JxlLegacyOpener().open(file.toString(), -1, false));
        assertSame(Boolean.TRUE, new JxlLegacyOpener().open(dir.resolve("missing.jxl").toString(), -1, false));
    }

    @Test
    void ioServiceOpensADataset() throws IOException {
        Path file = dir.resolve("dataset.jxl");
        JxlFiles.save(StackMetadataTest.hyperstack(2, 3, 2), file, JxlEncodeOptions.ofLossless(), false);
        IOService io = context.service(IOService.class);

        assertInstanceOf(JxlIOPlugin.class, io.getOpener(file.toString()));
        Dataset dataset = assertInstanceOf(Dataset.class, io.open(file.toString()));

        assertEquals("dataset.jxl", dataset.getName());
        assertEquals(file.toString(), dataset.getSource());
        assertEquals(5, dataset.numDimensions());
        assertFalse(io.getOpener(dir.resolve("image.tif").toString()) instanceof JxlIOPlugin);
    }

    @Test
    void savedFileIsAStillImageForOnePlane() throws IOException {
        Path file = dir.resolve("plane.jxl");
        ImagePlus imp = new ImagePlus("plane", new ByteProcessor(TestPlanes.WIDTH, TestPlanes.HEIGHT,
                TestPlanes.uint8(1)));

        JxlFiles.save(imp, file, JxlEncodeOptions.ofDistance(1.5f), false);

        assertFalse(JxlDecoder.readInfo(Files.readAllBytes(file)).animated());
    }
}

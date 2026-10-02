package panamage.jxl.imagej;

import java.nio.file.Path;

import org.scijava.Priority;
import org.scijava.plugin.Plugin;

import ij.ImagePlus;
import net.imagej.legacy.plugin.LegacyOpener;

/**
 * Opens JPEG XL files in Fiji wherever ImageJ 1.x opens a file by its path:
 * {@code IJ.open} and the macro function {@code open}, {@code IJ.openImage},
 * drag and drop, File &gt; Open Recent and folders of images. The files
 * become ImageJ 1.x images with all their channels, slices, time points and
 * metadata, also in batch mode.
 * <p>
 * File &gt; Open asks for the file before any path is known; that case is
 * handled by {@link JxlIOPlugin}.
 */
@Plugin(type = LegacyOpener.class, priority = Priority.HIGH)
public class JxlLegacyOpener implements LegacyOpener {

    /** Creates the opener; it is created by Fiji. */
    public JxlLegacyOpener() {
    }

    @Override
    public Object open(String path, int planeIndex, boolean displayResult) {
        if (!JxlFiles.isJxl(path)) {
            return null;
        }
        ImagePlus imp = JxlFiles.openOrReport(Path.of(path));
        if (imp == null) {
            // The error has been reported; nothing else should try to open the file.
            return Boolean.TRUE;
        }
        if (planeIndex > 0 && planeIndex <= imp.getStackSize()) {
            // IJ.openImage(path, n) opens the n-th image of a stack.
            ImagePlus plane = new ImagePlus(imp.getTitle(), imp.getStack().getProcessor(planeIndex));
            plane.setCalibration(imp.getCalibration());
            plane.setFileInfo(imp.getOriginalFileInfo());
            imp = plane;
        }
        if (displayResult) {
            JxlFiles.show(imp, path);
        }
        return imp;
    }
}

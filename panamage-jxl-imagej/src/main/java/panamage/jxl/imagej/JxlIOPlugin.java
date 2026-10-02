package panamage.jxl.imagej;

import java.io.IOException;
import java.nio.file.Path;

import org.scijava.io.AbstractIOPlugin;
import org.scijava.io.IOPlugin;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;
import org.scijava.plugin.Attr;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import ij.ImagePlus;
import net.imagej.Dataset;
import net.imagej.DatasetService;

/**
 * Opens JPEG XL files through the SciJava I/O service.
 * <p>
 * The plugin is "eager", so Fiji asks it before ImageJ 1.x's own opener. Fiji
 * calls it this way after File &gt; Open has asked for the file; then it opens
 * the file as an ImageJ 1.x image with all its metadata and shows it, as
 * {@link JxlLegacyOpener} does for every other way of opening a file. Called
 * by other code, it returns an ImageJ2 {@link Dataset} with the channels,
 * slices, time points, calibration, color tables and display ranges.
 */
@Plugin(type = IOPlugin.class, attrs = @Attr(name = "eager"))
public class JxlIOPlugin extends AbstractIOPlugin<Object> {

    /** The opener of Fiji that asks the eager plugins after File &gt; Open. */
    private static final String LEGACY_OPENER = "net.imagej.legacy.plugin.DefaultLegacyOpener";

    @Parameter
    private DatasetService datasets;

    /** Creates the plugin; it is created by the SciJava plugin service. */
    public JxlIOPlugin() {
    }

    @Override
    public Class<Object> getDataType() {
        return Object.class;
    }

    @Override
    public boolean supportsOpen(Location source) {
        return source instanceof FileLocation file && JxlFiles.isJxl(file.getName());
    }

    @Override
    public Object open(Location source) throws IOException {
        Path path = ((FileLocation) source).getFile().toPath();
        if (calledByImageJ1Opener()) {
            ImagePlus imp = JxlFiles.openOrReport(path);
            if (imp == null) {
                // The error has been reported; a result other than a path stops ImageJ 1.x from trying again.
                return Boolean.TRUE;
            }
            JxlFiles.show(imp, path.toString());
            return imp;
        }
        String problem = JxlRuntime.problem();
        if (problem != null) {
            throw new IOException(problem);
        }
        JxlStack stack = JxlFiles.read(path);
        Dataset dataset = DatasetConverter.toDataset(datasets, path.getFileName().toString(), stack.positions(),
                stack.metadata());
        dataset.getImgPlus().setSource(path.toString());
        return dataset;
    }

    /** Whether Fiji's ImageJ 1.x opener calls this plugin, which shows what it returns. */
    private static boolean calledByImageJ1Opener() {
        return StackWalker.getInstance()
                .walk(frames -> frames.anyMatch(frame -> frame.getClassName().equals(LEGACY_OPENER)));
    }
}

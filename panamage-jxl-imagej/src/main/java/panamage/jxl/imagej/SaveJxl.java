package panamage.jxl.imagej;

import java.io.IOException;
import java.nio.file.Path;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.io.SaveDialog;
import ij.plugin.PlugIn;
import panamage.jxl.JxlEncodeOptions;

/**
 * File &gt; Save As &gt; JPEG XL...: saves the current image, with all its
 * channels, slices, time points and metadata, as a JPEG XL file.
 * <p>
 * The options are the compression (lossless, or lossy with a distance), the
 * effort and, for images with at least three channels, whether the first
 * three are stored as red, green and blue. In a macro:
 * <pre>
 * run("JPEG XL...", "compression=Lossless effort=7 save=/path/image.jxl");
 * run("JPEG XL...", "compression=Lossy distance=1.0 effort=7 store save=/path/photo.jxl");
 * </pre>
 */
public class SaveJxl implements PlugIn {

    private static final String DIALOG_TITLE = "Save as JPEG XL";
    private static final String LOSSLESS = "Lossless";
    private static final String LOSSY = "Lossy";
    private static final String PREF_COMPRESSION = "panamage.jxl.compression";
    private static final String PREF_DISTANCE = "panamage.jxl.distance";
    private static final String PREF_EFFORT = "panamage.jxl.effort";

    /** Creates the plugin; it is created by ImageJ. */
    public SaveJxl() {
    }

    @Override
    public void run(String arg) {
        ImagePlus imp = IJ.getImage();
        String problem = JxlRuntime.problem();
        if (problem != null) {
            IJ.error(JxlFiles.TITLE, problem);
            return;
        }
        boolean canStoreRgb = imp.getType() != ImagePlus.COLOR_RGB && imp.getNChannels() >= 3;
        GenericDialog dialog = new GenericDialog(DIALOG_TITLE);
        dialog.addChoice("Compression", new String[] {LOSSLESS, LOSSY}, Prefs.get(PREF_COMPRESSION, LOSSLESS));
        dialog.addNumericField("Distance (lossy)", Prefs.get(PREF_DISTANCE, 1.0), 1);
        dialog.addSlider("Effort", JxlEncodeOptions.MIN_EFFORT, JxlEncodeOptions.MAX_EFFORT,
                Prefs.get(PREF_EFFORT, JxlEncodeOptions.DEFAULT_EFFORT), 1);
        if (canStoreRgb) {
            dialog.addCheckbox("Store channels 1-3 as RGB color", false);
        }
        dialog.addMessage("Lossless keeps every sample value. Lossy files are much smaller,\n"
                + "but their samples differ from the original: not for measurements.");
        dialog.showDialog();
        if (dialog.wasCanceled()) {
            return;
        }
        boolean lossless = !LOSSY.equals(dialog.getNextChoice());
        double distance = dialog.getNextNumber();
        int effort = (int) dialog.getNextNumber();
        boolean rgb = canStoreRgb && dialog.getNextBoolean();
        JxlEncodeOptions options;
        try {
            options = (lossless ? JxlEncodeOptions.ofLossless() : JxlEncodeOptions.ofDistance((float) distance))
                    .withEffort(effort);
        } catch (IllegalArgumentException e) {
            IJ.error(JxlFiles.TITLE, "The distance must be greater than 0 and at most "
                    + JxlEncodeOptions.MAX_DISTANCE + ", the effort from " + JxlEncodeOptions.MIN_EFFORT + " to "
                    + JxlEncodeOptions.MAX_EFFORT + ".");
            return;
        }
        SaveDialog file = new SaveDialog(DIALOG_TITLE, imp.getTitle(), JxlFiles.EXTENSION);
        if (file.getFileName() == null) {
            return;
        }
        Path path = Path.of(file.getDirectory(), file.getFileName());
        Prefs.set(PREF_COMPRESSION, lossless ? LOSSLESS : LOSSY);
        Prefs.set(PREF_DISTANCE, distance);
        Prefs.set(PREF_EFFORT, effort);
        IJ.showStatus("Saving " + path + "...");
        try {
            JxlFiles.save(imp, path, options, rgb);
            IJ.showStatus("");
        } catch (IOException e) {
            IJ.showStatus("");
            IJ.error(JxlFiles.TITLE, "Cannot save " + path + ":\n" + JxlFiles.message(e));
        }
    }
}

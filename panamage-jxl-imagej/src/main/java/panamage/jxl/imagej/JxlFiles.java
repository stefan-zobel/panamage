package panamage.jxl.imagej;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import ij.IJ;
import ij.ImagePlus;
import ij.Menus;
import ij.io.FileInfo;
import panamage.jxl.JxlEncodeOptions;
import panamage.jxl.JxlLimits;

/** Opening and saving JPEG XL files as ImageJ 1.x images, shared by the plugins. */
final class JxlFiles {

    /** The file name extension of JPEG XL files. */
    static final String EXTENSION = ".jxl";

    /** The title of error messages. */
    static final String TITLE = "JPEG XL";

    private JxlFiles() {
    }

    /**
     * Returns whether a path names a local JPEG XL file, by its extension.
     *
     * @param path the path, or {@code null}
     * @return {@code true} for a local path that ends with {@value #EXTENSION}
     */
    static boolean isJxl(String path) {
        return path != null && !path.contains("://") && path.toLowerCase(Locale.ROOT).endsWith(EXTENSION);
    }

    /**
     * Reads a JPEG XL file as an ImageJ image with all its metadata.
     *
     * @param path the file
     * @return the image, titled with the file name
     * @throws IOException if reading fails or the file is not a valid JPEG XL
     *                     image
     */
    static ImagePlus open(Path path) throws IOException {
        JxlStack stack = read(path);
        ImagePlus imp = stack.toImagePlus(path.getFileName().toString());
        setFileInfo(imp, path);
        return imp;
    }

    /**
     * Reads a JPEG XL file.
     *
     * @param path the file
     * @return the content of the file
     * @throws IOException if reading fails or the file is not a valid JPEG XL
     *                     image
     */
    static JxlStack read(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        try {
            return JxlStack.read(data, JxlLimits.defaults());
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Reads a JPEG XL file as an ImageJ image, or shows why it cannot.
     *
     * @param path the file
     * @return the image, or {@code null} after an error message
     */
    static ImagePlus openOrReport(Path path) {
        String problem = JxlRuntime.problem();
        if (problem != null) {
            IJ.error(TITLE, problem);
            return null;
        }
        try {
            return open(path);
        } catch (IOException e) {
            IJ.error(TITLE, "Cannot open " + path + ":\n" + message(e));
            return null;
        }
    }

    /**
     * Shows an image that was opened from a file, as ImageJ does.
     *
     * @param imp  the image
     * @param path the file
     */
    static void show(ImagePlus imp, String path) {
        imp.show();
        Menus.addOpenRecentItem(path);
    }

    /**
     * Writes an ImageJ image with all its metadata to a file. The file is
     * written under a temporary name first, so an existing file stays intact
     * if writing fails.
     *
     * @param imp     the image
     * @param path    the file
     * @param options the encoder settings
     * @param rgb     whether the first three channels are stored as red, green
     *                and blue
     * @throws IOException if writing fails
     */
    static void save(ImagePlus imp, Path path, JxlEncodeOptions options, boolean rgb) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path temp = Files.createTempFile(absolute.getParent(), absolute.getFileName().toString(), ".tmp");
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                JxlStack.write(imp, out, options, rgb);
            } catch (RuntimeException e) {
                throw new IOException(e.getMessage(), e);
            }
            Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        imp.setTitle(absolute.getFileName().toString());
        setFileInfo(imp, absolute);
        imp.changes = false;
    }

    private static void setFileInfo(ImagePlus imp, Path path) {
        FileInfo info = new FileInfo();
        info.fileFormat = FileInfo.UNKNOWN;
        info.fileName = path.getFileName().toString();
        Path parent = path.toAbsolutePath().getParent();
        info.directory = parent == null ? "" : parent + File.separator;
        info.width = imp.getWidth();
        info.height = imp.getHeight();
        info.nImages = imp.getStackSize();
        imp.setFileInfo(info);
    }

    /**
     * Returns a message for the user about a failure.
     *
     * @param e the failure
     * @return the message
     */
    static String message(IOException e) {
        if (e instanceof NoSuchFileException) {
            return "The file does not exist.";
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}

package panamage.jxl.imageio;

import java.util.Locale;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;

/**
 * Service provider for {@link JxlImageWriter}, registered with Image I/O
 * automatically.
 */
public final class JxlImageWriterSpi extends ImageWriterSpi {

    /**
     * Creates the provider; called by the Image I/O registry.
     */
    public JxlImageWriterSpi() {
        super(JxlFormat.VENDOR, JxlFormat.version(), JxlFormat.NAMES, JxlFormat.SUFFIXES, JxlFormat.MIME_TYPES,
                JxlImageWriter.class.getName(), new Class<?>[] {ImageOutputStream.class},
                new String[] {JxlImageReaderSpi.class.getName()},
                false, null, null, null, null,
                false, null, null, null, null);
    }

    /**
     * Every image type can be written: images are converted to 8-bit gray,
     * RGB or RGBA in sRGB.
     */
    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        return type.getColorModel() != null;
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) {
        return new JxlImageWriter(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "JPEG XL image writer (libjxl)";
    }
}

package panamage.jxl.imageio;

import java.util.Locale;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageOutputStream;

/**
 * Service provider for {@link JxlImageWriter}, registered with Image I/O
 * automatically.
 * <p>
 * On a runtime that cannot run panamage, such as an older JVM or JDK 21
 * without {@code --enable-preview} for panamage-jxl-jdk21, the provider
 * removes itself from the registry, so the other formats keep working.
 */
public final class JxlImageWriterSpi extends ImageWriterSpi {

    /**
     * Creates the provider; called by the Image I/O registry.
     */
    public JxlImageWriterSpi() {
        super(JxlFormat.VENDOR, JxlFormat.version(), JxlFormat.NAMES, JxlFormat.SUFFIXES, JxlFormat.MIME_TYPES,
                JxlFormat.WRITER_CLASS, new Class<?>[] {ImageOutputStream.class},
                new String[] {JxlFormat.READER_SPI_CLASS},
                false, null, null, null, null,
                false, null, null, null, null);
    }

    /**
     * Removes the provider again if the runtime cannot run panamage.
     */
    @Override
    public void onRegistration(ServiceRegistry registry, Class<?> category) {
        JxlFormat.deregisterIfUnsupported(this, registry);
    }

    /**
     * Every image type can be written: gray and RGB images with a component
     * color model keep their precision and color space, all others are
     * converted to 8-bit RGB or RGBA in sRGB.
     */
    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        return type.getColorModel() != null;
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) {
        // Created in JxlImageWriter, so that verifying this class does not load it.
        return JxlImageWriter.create(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "JPEG XL image writer (libjxl)";
    }
}

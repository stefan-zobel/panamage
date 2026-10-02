package panamage.jxl.imageio;

import java.io.IOException;
import java.util.Locale;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageInputStream;

/**
 * Service provider for {@link JxlImageReader}, registered with Image I/O
 * automatically.
 * <p>
 * Recognizing JPEG XL input does not load libjxl, so the provider adds no
 * cost to reading other formats. On a runtime that cannot run panamage, such
 * as an older JVM or JDK 21 without {@code --enable-preview} for
 * panamage-jxl-jdk21, the provider removes itself from the registry, so the
 * other formats keep working.
 */
public final class JxlImageReaderSpi extends ImageReaderSpi {

    /**
     * Creates the provider; called by the Image I/O registry.
     */
    public JxlImageReaderSpi() {
        super(JxlFormat.VENDOR, JxlFormat.version(), JxlFormat.NAMES, JxlFormat.SUFFIXES, JxlFormat.MIME_TYPES,
                JxlFormat.READER_CLASS, new Class<?>[] {ImageInputStream.class},
                new String[] {JxlFormat.WRITER_SPI_CLASS},
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

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        return source instanceof ImageInputStream && JxlSignature.matches((ImageInputStream) source);
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        // Created in JxlImageReader, so that verifying this class does not load it.
        return JxlImageReader.create(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "JPEG XL image reader (libjxl)";
    }
}

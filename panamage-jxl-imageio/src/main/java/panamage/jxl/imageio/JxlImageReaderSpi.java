package panamage.jxl.imageio;

import java.io.IOException;
import java.util.Locale;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

/**
 * Service provider for {@link JxlImageReader}, registered with Image I/O
 * automatically.
 * <p>
 * Recognizing JPEG XL input does not load libjxl, so the provider adds no
 * cost to reading other formats.
 */
public final class JxlImageReaderSpi extends ImageReaderSpi {

    /**
     * Creates the provider; called by the Image I/O registry.
     */
    public JxlImageReaderSpi() {
        super(JxlFormat.VENDOR, JxlFormat.version(), JxlFormat.NAMES, JxlFormat.SUFFIXES, JxlFormat.MIME_TYPES,
                JxlImageReader.class.getName(), new Class<?>[] {ImageInputStream.class},
                new String[] {JxlImageWriterSpi.class.getName()},
                false, null, null, null, null,
                false, null, null, null, null);
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        return source instanceof ImageInputStream stream && JxlSignature.matches(stream);
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new JxlImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "JPEG XL image reader (libjxl)";
    }
}

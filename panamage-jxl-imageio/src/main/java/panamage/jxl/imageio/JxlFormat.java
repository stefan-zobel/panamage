package panamage.jxl.imageio;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.spi.IIOServiceProvider;
import javax.imageio.spi.ServiceRegistry;

/**
 * Names and identifiers shared by the reader and writer service providers,
 * and the check whether the runtime can run panamage.
 * <p>
 * This class and the service providers are compiled for Java 8 (see the
 * POM), so that the Image I/O registry of an older JVM can load them and
 * they can deregister themselves, instead of breaking Image I/O for all
 * formats. They must not refer to other classes of panamage while they are
 * created and registered.
 */
final class JxlFormat {

    static final String VENDOR = "panamage";

    static final String[] NAMES = {"jxl", "JXL", "jpeg xl", "JPEG XL", "jpeg-xl", "JPEG-XL"};

    static final String[] SUFFIXES = {"jxl"};

    static final String[] MIME_TYPES = {"image/jxl"};

    /** The name of {@link JxlImageReader}, as a string, so that the class is not loaded. */
    static final String READER_CLASS = "panamage.jxl.imageio.JxlImageReader";

    /** The name of {@link JxlImageWriter}, as a string, so that the class is not loaded. */
    static final String WRITER_CLASS = "panamage.jxl.imageio.JxlImageWriter";

    /** The name of {@link JxlImageReaderSpi}. */
    static final String READER_SPI_CLASS = "panamage.jxl.imageio.JxlImageReaderSpi";

    /** The name of {@link JxlImageWriterSpi}. */
    static final String WRITER_SPI_CLASS = "panamage.jxl.imageio.JxlImageWriterSpi";

    /**
     * A class of panamage-jxl that uses the Foreign Function and Memory API;
     * if it cannot be loaded, panamage cannot run.
     */
    static final String PROBE_CLASS = "panamage.jxl.JxlDecoder";

    private JxlFormat() {
    }

    /** The version from the JAR manifest, or a placeholder when running from classes. */
    static String version() {
        String version = JxlFormat.class.getPackage().getImplementationVersion();
        return version != null ? version : "development";
    }

    /**
     * Removes a service provider from the registry if the runtime cannot run
     * panamage: a JVM that is too old for the class files, the JDK 21 variant
     * without {@code --enable-preview}, or panamage-jxl missing.
     */
    static void deregisterIfUnsupported(IIOServiceProvider provider, ServiceRegistry registry) {
        String reason = unsupportedReason(PROBE_CLASS, JxlFormat.class.getClassLoader());
        if (reason != null) {
            registry.deregisterServiceProvider(provider);
            Logger.getLogger(JxlFormat.class.getName()).log(Level.FINE,
                    "JPEG XL Image I/O plugin disabled: {0}", reason);
        }
    }

    /**
     * Loads a class without initializing it.
     *
     * @return {@code null} if the class can be loaded, otherwise why not
     */
    static String unsupportedReason(String className, ClassLoader loader) {
        try {
            Class.forName(className, false, loader);
            return null;
        } catch (ClassNotFoundException e) {
            return className + " not found";
        } catch (LinkageError e) {
            return e.toString();
        }
    }
}

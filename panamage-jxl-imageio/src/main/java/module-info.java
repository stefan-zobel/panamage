/**
 * Java Image I/O plugin for JPEG XL.
 * <p>
 * With this module on the class path or module path, {@code ImageIO.read}
 * decodes JPEG XL images and {@code ImageIO.write(image, "jxl", output)}
 * encodes them.
 */
module panamage.jxl.imageio {
    requires java.desktop;
    requires panamage.jxl;

    exports panamage.jxl.imageio;

    provides javax.imageio.spi.ImageReaderSpi with panamage.jxl.imageio.JxlImageReaderSpi;
    provides javax.imageio.spi.ImageWriterSpi with panamage.jxl.imageio.JxlImageWriterSpi;
}

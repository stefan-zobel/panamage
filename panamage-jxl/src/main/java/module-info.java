/**
 * JPEG XL for Java, based on libjxl and the Foreign Function and Memory API.
 * <p>
 * The native libraries are provided by a platform artifact such as
 * {@code panamage-jxl-natives-windows-x86_64}, found through
 * {@link panamage.jxl.spi.JxlNativeBundle}.
 */
module panamage.jxl {
    requires panamage.jxl.spi;

    exports panamage.jxl;

    uses panamage.jxl.spi.JxlNativeBundle;
}

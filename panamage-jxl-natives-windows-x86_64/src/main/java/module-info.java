/**
 * Native libjxl libraries for Windows x86_64.
 * <p>
 * Put this module on the module path or the class path next to
 * {@code panamage.jxl}; it is found automatically through
 * {@link panamage.jxl.spi.JxlNativeBundle}.
 */
module panamage.jxl.natives.windows.x86_64 {
    requires panamage.jxl.spi;

    provides panamage.jxl.spi.JxlNativeBundle
            with panamage.jxl.natives.windows.x86_64.WindowsX64Bundle;
}

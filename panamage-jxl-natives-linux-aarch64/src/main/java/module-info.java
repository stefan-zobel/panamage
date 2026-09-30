/**
 * Native libjxl libraries for Linux aarch64 (glibc 2.28 or newer).
 * <p>
 * Put this module on the module path or the class path next to
 * {@code panamage.jxl}; it is found automatically through
 * {@link panamage.jxl.spi.JxlNativeBundle}.
 */
module panamage.jxl.natives.linux.aarch64 {
    requires panamage.jxl.spi;

    provides panamage.jxl.spi.JxlNativeBundle
            with panamage.jxl.natives.linux.aarch64.LinuxAarch64Bundle;
}

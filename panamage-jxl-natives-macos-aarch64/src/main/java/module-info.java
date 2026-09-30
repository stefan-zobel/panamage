/**
 * Native libjxl libraries for macOS 11 or newer on Apple silicon (arm64).
 * <p>
 * Put this module on the module path or the class path next to
 * {@code panamage.jxl}; it is found automatically through
 * {@link panamage.jxl.spi.JxlNativeBundle}.
 */
module panamage.jxl.natives.macos.aarch64 {
    requires panamage.jxl.spi;

    provides panamage.jxl.spi.JxlNativeBundle
            with panamage.jxl.natives.macos.aarch64.MacOsAarch64Bundle;
}

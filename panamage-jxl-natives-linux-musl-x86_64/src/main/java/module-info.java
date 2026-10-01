/**
 * Native libjxl libraries for musl-based Linux x86_64 (musl 1.2.4 or newer,
 * such as Alpine Linux 3.18 or newer).
 * <p>
 * Put this module on the module path or the class path next to
 * {@code panamage.jxl}; it is found automatically through
 * {@link panamage.jxl.spi.JxlNativeBundle}.
 */
module panamage.jxl.natives.linux.musl.x86_64 {
    requires panamage.jxl.spi;

    provides panamage.jxl.spi.JxlNativeBundle
            with panamage.jxl.natives.linux.musl.x86_64.LinuxMuslX64Bundle;
}

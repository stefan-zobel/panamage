package panamage.jxl.imagej;

import panamage.jxl.JxlNative;

/**
 * Checks once whether this JVM can run panamage: panamage-jxl-jdk21 needs
 * Java 21 started with {@code --enable-preview}, and a native libjxl for the
 * platform.
 * <p>
 * This class and the plugin classes use no preview feature themselves, so
 * they load on any Java 21 and can report the problem instead of failing.
 */
final class JxlRuntime {

    /** The Java release that panamage-jxl-jdk21 is compiled for. */
    static final int JAVA_RELEASE = 21;

    private static volatile String problem;
    private static volatile boolean checked;

    private JxlRuntime() {
    }

    /**
     * Returns why JPEG XL images cannot be read or written in this JVM.
     *
     * @return a message for the user, or {@code null} if everything works
     */
    static String problem() {
        if (!checked) {
            synchronized (JxlRuntime.class) {
                if (!checked) {
                    problem = check();
                    checked = true;
                }
            }
        }
        return problem;
    }

    private static String check() {
        int feature = Runtime.version().feature();
        if (feature != JAVA_RELEASE) {
            return "JPEG XL support needs Java " + JAVA_RELEASE + ", but Fiji runs on Java " + feature + ".";
        }
        try {
            JxlNative.version();
            return null;
        } catch (UnsupportedClassVersionError e) {
            return "JPEG XL support needs Java " + JAVA_RELEASE + " with preview features: start Fiji with "
                    + "--enable-preview. The panamage update site sets this option in "
                    + "config/jaunch/extra-panamage.toml; restart Fiji after installing it.";
        } catch (LinkageError e) {
            return "JPEG XL support cannot load the native library libjxl on " + System.getProperty("os.name")
                    + " (" + System.getProperty("os.arch") + "): " + e;
        }
    }
}

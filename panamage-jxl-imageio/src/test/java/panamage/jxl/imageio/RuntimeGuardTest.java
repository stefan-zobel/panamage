package panamage.jxl.imageio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Starts other JVMs with this plugin on the class path and checks that
 * Image I/O keeps working for other formats when the JVM cannot run
 * panamage. The JDKs come from the system properties {@value #JDK8_HOME}
 * and {@value #JDK21_HOME}; a case is skipped without its JDK.
 */
class RuntimeGuardTest {

    /** Home directory of a JDK 8, which cannot load any panamage class. */
    static final String JDK8_HOME = "jdk8.home";

    /**
     * Home directory of a JDK 21, which cannot load the classes of
     * panamage-jxl (release 22).
     */
    static final String JDK21_HOME = "jdk21.home";

    /** The Java version of the panamage-jxl-jdk21 variant, which needs --enable-preview. */
    private static final int PREVIEW_FEATURE = 21;

    /** Reads and writes a PNG and reports whether a JPEG XL reader and writer are registered. */
    private static final String PROBE = """
            import java.awt.image.BufferedImage;
            import java.io.File;
            import javax.imageio.ImageIO;

            public class Probe {
                public static void main(String[] args) throws Exception {
                    BufferedImage png = ImageIO.read(new File(args[0]));
                    System.out.println("png read: " + png.getWidth() + "x" + png.getHeight());
                    System.out.println("png written: " + ImageIO.write(png, "png", new File(args[1])));
                    System.out.println("jxl reader: " + ImageIO.getImageReadersByFormatName("jxl").hasNext());
                    System.out.println("jxl writer: " + ImageIO.getImageWritersByFormatName("jxl").hasNext());
                }
            }
            """;

    @TempDir
    Path temp;

    @Test
    void currentRuntimeRegistersThePlugin() throws Exception {
        List<String> options = new ArrayList<>();
        if (Runtime.version().feature() == PREVIEW_FEATURE) {
            options.add("--enable-preview");
        }
        assertEquals(expected(true), probe(Path.of(System.getProperty("java.home")), options));
    }

    @Test
    void java8KeepsImageIo() throws Exception {
        Path jdk = jdk(JDK8_HOME);
        assertEquals(expected(false), probe(jdk, List.of()));
    }

    @Test
    void runtimeWithoutPanamageSupportKeepsImageIo() throws Exception {
        // The JDK 21 variant runs on its own JDK without --enable-preview; the
        // other modules on a JDK 21, which is too old for their class files.
        Path jdk = Runtime.version().feature() == PREVIEW_FEATURE
                ? Path.of(System.getProperty("java.home"))
                : jdk(JDK21_HOME);
        assertEquals(expected(false), probe(jdk, List.of()));
    }

    private static Path jdk(String property) {
        String home = System.getProperty(property, "");
        assumeTrue(!home.isBlank(), "no JDK in the system property " + property);
        return Path.of(home);
    }

    private static List<String> expected(boolean registered) {
        return List.of("png read: " + Resources.GRADIENT_WIDTH + "x" + Resources.GRADIENT_HEIGHT,
                "png written: true", "jxl reader: " + registered, "jxl writer: " + registered);
    }

    /** Runs the probe on a JDK with this plugin and its dependencies on the class path. */
    private List<String> probe(Path jdk, List<String> options) throws Exception {
        Path probeClasses = Files.createDirectories(temp.resolve("probe"));
        Path source = probeClasses.resolve("Probe.java");
        Files.writeString(source, PROBE);
        // The javac of the running JDK compiles for Java 8, so every JDK can run the probe.
        Path javac = Path.of(System.getProperty("java.home"), "bin", "javac");
        run(List.of(javac.toString(), "--release", "8", "-Xlint:-options", "-d", probeClasses.toString(),
                source.toString()));

        Path png = temp.resolve("gradient.png");
        Files.write(png, Resources.bytes("gradient.png"));
        List<String> command = new ArrayList<>();
        command.add(jdk.resolve("bin").resolve("java").toString());
        command.addAll(options);
        command.add("-cp");
        command.add(probeClasses + File.pathSeparator + classPath());
        command.add("Probe");
        command.add(png.toString());
        command.add(temp.resolve("written.png").toString());
        // Only the lines of the probe; the JVM may print notes of its own.
        return run(command).stream()
                .filter(line -> line.startsWith("png ") || line.startsWith("jxl "))
                .toList();
    }

    /** The class path and module path of this test run: the plugin and its dependencies. */
    private static String classPath() {
        return Stream.of(System.getProperty("java.class.path", ""), System.getProperty("jdk.module.path", ""))
                .filter(path -> !path.isEmpty())
                .collect(Collectors.joining(File.pathSeparator));
    }

    private List<String> run(List<String> command) throws IOException, InterruptedException {
        Path output = Files.createTempFile(temp, "output", ".txt");
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("Timed out: " + command);
        }
        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), () -> command + "\n" + String.join("\n", lines));
        return lines;
    }
}

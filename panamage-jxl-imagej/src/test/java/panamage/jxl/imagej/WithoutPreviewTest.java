package panamage.jxl.imagej;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Starts a JVM without --enable-preview, as Fiji runs when the launcher
 * option is missing, and checks that the plugins load and report the problem
 * instead of failing.
 */
class WithoutPreviewTest {

    @TempDir
    Path dir;

    /** Runs in the other JVM: prints the problem and what the opener returns. */
    public static void main(String[] args) {
        System.out.println("problem: " + JxlRuntime.problem());
        System.out.println("opened: " + new JxlLegacyOpener().open(args[0], -1, false));
    }

    @Test
    void pluginsReportMissingPreview() throws IOException, InterruptedException {
        Path file = dir.resolve("image.jxl");
        Files.write(file, new byte[] {1, 2, 3});
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-Djava.awt.headless=true", "-cp",
                System.getProperty("java.class.path"), WithoutPreviewTest.class.getName(), file.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));

        assertEquals(0, process.exitValue(), output);
        List<String> lines = output.lines().filter(line -> line.startsWith("problem: ") || line.startsWith("opened: "))
                .toList();
        assertEquals(2, lines.size(), output);
        assertTrue(lines.get(0).contains("--enable-preview"), output);
        assertEquals("opened: true", lines.get(1), output);
        assertTrue(output.contains("--enable-preview"), output);
    }
}

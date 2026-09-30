import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import javax.imageio.ImageIO;

import panamage.jxl.JxlNative;
import panamage.jxl.JxlTranscoder;

/**
 * Converts an image to JPEG XL with Image I/O and, for a JPEG, also losslessly
 * with {@link JxlTranscoder}.
 *
 * <pre>java --enable-native-access=ALL-UNNAMED -cp "lib/*" Example.java photo.jpg</pre>
 */
public class Example {

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java ... Example.java <image file>");
            System.exit(2);
        }
        Path input = Path.of(args[0]);
        String name = input.getFileName().toString();
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;

        BufferedImage image = ImageIO.read(input.toFile());
        if (image == null) {
            System.err.println("Unsupported image format: " + input);
            System.exit(1);
        }
        System.out.printf("Read %s: %d x %d%n", input, image.getWidth(), image.getHeight());

        Path converted = input.resolveSibling(base + "-converted.jxl");
        ImageIO.write(image, "jxl", converted.toFile());
        report("Converted (visually lossless)", input, converted);

        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            byte[] jpeg = Files.readAllBytes(input);
            byte[] jxl = JxlTranscoder.fromJpeg(jpeg);
            Path lossless = input.resolveSibling(base + "-lossless.jxl");
            Files.write(lossless, jxl);
            report("Transcoded (lossless)", input, lossless);
            boolean identical = Arrays.equals(jpeg, JxlTranscoder.toJpeg(jxl));
            System.out.println("Restores the original JPEG bit for bit: " + identical);
        }
        System.out.println("libjxl " + JxlNative.version() + ", " + JxlNative.librarySource());
    }

    private static void report(String what, Path input, Path output) throws IOException {
        long before = Files.size(input);
        long after = Files.size(output);
        System.out.printf("%s: %s, %,d -> %,d bytes (%.1f%%)%n", what, output, before, after,
                100.0 * after / before);
    }
}

package panamage.jxl.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import panamage.jxl.spi.JxlNativeBundle;

/**
 * Copies the libraries of a {@link JxlNativeBundle} into a cache directory so
 * that the operating system can load them.
 * <p>
 * The directory name contains a hash of the bundle content, so different
 * versions never share a directory. Files that are already present with the
 * expected content are reused; missing, damaged or modified files are
 * replaced atomically. Several processes may extract the same bundle at the
 * same time.
 */
final class BundleExtractor {

    private BundleExtractor() {
    }

    /**
     * Extracts the bundle below {@code cacheRoot}.
     *
     * @return the paths of the extracted libraries, in load order
     * @throws IOException if a library cannot be read or written
     */
    static List<Path> extract(JxlNativeBundle bundle, Path cacheRoot) throws IOException {
        Map<String, byte[]> contents = readAll(bundle);
        Path dir = cacheRoot.resolve(bundle.platform() + "-" + bundle.libjxlVersion() + "-" + contentHash(contents));
        Files.createDirectories(dir);

        List<Path> paths = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
            Path target = dir.resolve(entry.getKey());
            byte[] content = entry.getValue();
            if (!hasContent(target, content)) {
                write(target, content);
            }
            paths.add(target);
        }
        return paths;
    }

    private static Map<String, byte[]> readAll(JxlNativeBundle bundle) throws IOException {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        for (String name : bundle.libraries()) {
            if (!isPlainFileName(name)) {
                throw new IOException("Invalid library name in bundle: " + name);
            }
            try (InputStream in = bundle.open(name)) {
                contents.put(name, in.readAllBytes());
            }
        }
        return contents;
    }

    private static boolean isPlainFileName(String name) {
        return !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf(':') < 0;
    }

    private static void write(Path target, byte[] content) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, content);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            // Another process may have written the same file in the meantime and
            // may hold it open (loaded libraries are locked on Windows).
            if (!hasContent(target, content)) {
                throw new IOException("Cannot write " + target, e);
            }
        }
    }

    private static boolean hasContent(Path file, byte[] expected) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) != expected.length) {
            return false;
        }
        return MessageDigest.isEqual(sha256(Files.readAllBytes(file)), sha256(expected));
    }

    /** First 12 hex digits of a hash over the names and contents of all files. */
    private static String contentHash(Map<String, byte[]> contents) {
        MessageDigest digest = newSha256();
        for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(sha256(entry.getValue()));
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 12);
    }

    private static byte[] sha256(byte[] data) {
        return newSha256().digest(data);
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}

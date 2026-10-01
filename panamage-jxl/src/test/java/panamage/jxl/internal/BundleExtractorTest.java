package panamage.jxl.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import panamage.jxl.spi.JxlNativeBundle;

class BundleExtractorTest {

    @TempDir
    Path cache;

    /** An in-memory bundle whose "libraries" are small text files. */
    private record FakeBundle(Map<String, byte[]> files) implements JxlNativeBundle {

        static FakeBundle of(String... namesAndContents) {
            Map<String, byte[]> files = new LinkedHashMap<>();
            for (int i = 0; i < namesAndContents.length; i += 2) {
                files.put(namesAndContents[i], namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
            }
            return new FakeBundle(files);
        }

        @Override
        public String platform() {
            return "test-x86_64";
        }

        @Override
        public String libjxlVersion() {
            return "0.12.0";
        }

        @Override
        public List<String> libraries() {
            return List.copyOf(files.keySet());
        }

        @Override
        public InputStream open(String fileName) {
            return new ByteArrayInputStream(files.get(fileName));
        }
    }

    @Test
    void extractsAllLibrariesInLoadOrder() throws IOException {
        FakeBundle bundle = FakeBundle.of("a.dll", "first", "b.dll", "second");

        List<Path> paths = BundleExtractor.extract(bundle, cache);

        assertEquals(List.of("a.dll", "b.dll"), paths.stream().map(p -> p.getFileName().toString()).toList());
        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(paths.get(0)));
        assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(paths.get(1)));
        Path directory = paths.getFirst().getParent();
        assertEquals(cache, directory.getParent());
        assertTrue(directory.getFileName().toString().matches("test-x86_64-0\\.12\\.0-[0-9a-f]{12}"),
                directory.toString());
    }

    @Test
    void reusesIntactFiles() throws IOException {
        FakeBundle bundle = FakeBundle.of("a.dll", "first");
        Path file = BundleExtractor.extract(bundle, cache).getFirst();
        FileTime marker = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(file, marker);

        BundleExtractor.extract(bundle, cache);

        assertEquals(marker, Files.getLastModifiedTime(file));
    }

    @Test
    void replacesDamagedFiles() throws IOException {
        FakeBundle bundle = FakeBundle.of("a.dll", "first");
        Path file = BundleExtractor.extract(bundle, cache).getFirst();
        Files.writeString(file, "fikst");

        BundleExtractor.extract(bundle, cache);

        assertEquals("first", Files.readString(file));
    }

    @Test
    void differentContentUsesADifferentDirectory() throws IOException {
        Path first = BundleExtractor.extract(FakeBundle.of("a.dll", "first"), cache).getFirst();
        Path second = BundleExtractor.extract(FakeBundle.of("a.dll", "other"), cache).getFirst();

        assertNotEquals(first.getParent(), second.getParent());
        assertEquals("first", Files.readString(first));
        assertEquals("other", Files.readString(second));
    }

    @Test
    void leavesNoTemporaryFilesBehind() throws IOException {
        Path directory = BundleExtractor.extract(FakeBundle.of("a.dll", "x", "b.dll", "y"), cache)
                .getFirst().getParent();

        try (var files = Files.list(directory)) {
            assertEquals(List.of("a.dll", "b.dll"), files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void createsDirectoriesOnlyTheOwnerCanAccess() throws IOException {
        assumeTrue(PrivateDirectory.isPosix(cache), "POSIX file system");
        Path root = cache.resolve("root");

        Path directory = BundleExtractor.extract(FakeBundle.of("a.so", "x"), root).getFirst().getParent();

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
    }

    @Test
    void rejectsADirectoryOthersCanWrite() throws IOException {
        assumeTrue(PrivateDirectory.isPosix(cache), "POSIX file system");
        FakeBundle bundle = FakeBundle.of("a.so", "x");
        Path directory = BundleExtractor.extract(bundle, cache).getFirst().getParent();
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));

        assertThrows(PrivateDirectory.UnsafeDirectoryException.class, () -> BundleExtractor.extract(bundle, cache));
    }

    @Test
    void rejectsASymbolicLink() throws IOException {
        assumeTrue(PrivateDirectory.isPosix(cache), "POSIX file system");
        Path target = Files.createDirectory(cache.resolve("target"));
        Path link = Files.createSymbolicLink(cache.resolve("root"), target);

        assertThrows(PrivateDirectory.UnsafeDirectoryException.class,
                () -> BundleExtractor.extract(FakeBundle.of("a.so", "x"), link));
    }

    @Test
    void rejectsADirectoryOfAnotherUser() throws IOException {
        assumeTrue(PrivateDirectory.isPosix(cache), "POSIX file system");
        UserPrincipal root = cache.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("root");
        assumeFalse(root.equals(PrivateDirectory.currentUser()), "not running as root");

        assertThrows(PrivateDirectory.UnsafeDirectoryException.class,
                () -> PrivateDirectory.create(cache.resolve("root"), root));
    }

    @Test
    void fallsBackToATemporaryDirectory() throws IOException {
        assumeTrue(PrivateDirectory.isPosix(cache), "POSIX file system");
        FakeBundle bundle = FakeBundle.of("a.so", "x");
        Path root = Files.createDirectory(cache.resolve("shared"));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxrwxrwx"));

        assertThrows(PrivateDirectory.UnsafeDirectoryException.class, () -> BundleExtractor.extract(bundle, root));
        Path file = BundleExtractor.extractPrivately(bundle, root).getFirst();

        assertFalse(file.startsWith(root), file.toString());
        assertEquals("x", Files.readString(file));
        assertEquals("rwx------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent().getParent())));
    }

    @Test
    void usesAChosenDirectoryAsItIs() throws IOException {
        assumeTrue(PrivateDirectory.isPosix(cache), "POSIX file system");
        FakeBundle bundle = FakeBundle.of("a.so", "x");
        Path root = Files.createDirectory(cache.resolve("chosen"));
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxrwxrwx"));

        Path file = BundleExtractor.extractUnchecked(bundle, root).getFirst();

        assertEquals(root, file.getParent().getParent());
        assertEquals("x", Files.readString(file));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
    }

    @Test
    void rejectsLibraryNamesWithPaths() {
        assertThrows(IOException.class, () -> BundleExtractor.extract(FakeBundle.of("../evil.dll", "x"), cache));
        assertThrows(IOException.class, () -> BundleExtractor.extract(FakeBundle.of("sub/evil.dll", "x"), cache));
        assertThrows(IOException.class, () -> BundleExtractor.extract(FakeBundle.of("C:evil.dll", "x"), cache));
    }
}

package panamage.jxl.internal;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

/**
 * Creates directories that only the current user can modify, for the
 * extracted native libraries.
 * <p>
 * A library is checked before it is loaded, so the directory must not let
 * other users replace it in between. On POSIX file systems, a new directory
 * gets the permissions {@code rwx------}; an existing directory must be a
 * real directory (not a symbolic link), must belong to the current user and
 * must not be writable by the group or by others. On other file systems
 * (Windows), the directory is created without checks: there, the temporary
 * directory belongs to a single user.
 */
final class PrivateDirectory {

    /** Thrown if an existing directory may be modified by other users. */
    static final class UnsafeDirectoryException extends IOException {

        private static final long serialVersionUID = 1L;

        UnsafeDirectoryException(String message) {
            super(message);
        }
    }

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");

    /** The current user, determined on first use. */
    private static volatile UserPrincipal currentUser;

    private PrivateDirectory() {
    }

    /**
     * Creates the directory, or checks an existing one. Its parent must exist.
     *
     * @return {@code dir}
     * @throws UnsafeDirectoryException if the directory may be modified by
     *                                  other users
     * @throws IOException              if the directory cannot be created
     */
    static Path create(Path dir) throws IOException {
        if (!isPosix(dir)) {
            Files.createDirectories(dir);
            return dir;
        }
        return create(dir, currentUser());
    }

    /**
     * Creates the directory like {@link #create(Path)}, but uses an existing
     * directory without checks, for a directory the user has chosen. Its
     * parent must exist.
     *
     * @return {@code dir}
     * @throws IOException if the directory cannot be created
     */
    static Path createUnchecked(Path dir) throws IOException {
        if (!isPosix(dir)) {
            Files.createDirectories(dir);
            return dir;
        }
        try {
            Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } catch (FileAlreadyExistsException e) {
            // Used as it is.
        }
        return dir;
    }

    /**
     * Like {@link #create(Path)} on a POSIX file system, with the expected
     * owner (tests pass another user).
     */
    static Path create(Path dir, UserPrincipal owner) throws IOException {
        try {
            Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } catch (FileAlreadyExistsException e) {
            // Created before, possibly by another process; checked below.
        }
        PosixFileAttributes attributes = Files.readAttributes(dir, PosixFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
            throw new UnsafeDirectoryException(dir + " is a symbolic link or not a directory");
        }
        if (!attributes.owner().equals(owner)) {
            throw new UnsafeDirectoryException(dir + " belongs to " + attributes.owner().getName()
                    + ", not to " + owner.getName());
        }
        Set<PosixFilePermission> permissions = attributes.permissions();
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new UnsafeDirectoryException(dir + " is writable by other users ("
                    + PosixFilePermissions.toString(permissions) + ")");
        }
        return dir;
    }

    static boolean isPosix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /**
     * Returns the owner of a new file, which is the current user also where
     * the user name is unknown (for example in containers).
     */
    static UserPrincipal currentUser() throws IOException {
        UserPrincipal user = currentUser;
        if (user == null) {
            Path probe = Files.createTempFile("panamage-jxl-", ".probe");
            try {
                user = Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS);
            } finally {
                Files.deleteIfExists(probe);
            }
            currentUser = user;
        }
        return user;
    }
}

package org.pathlab.forge.runtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class DataRootLock implements AutoCloseable {
    public static final class AlreadyOwnedException extends IOException {
        private static final long serialVersionUID = 1L;
        private AlreadyOwnedException(Throwable cause) {
            super("PathLab Forge data root is already owned by another process", cause);
        }
    }
    private final FileChannel channel;
    private final FileLock lock;
    private final Path root;

    private DataRootLock(FileChannel channel, FileLock lock, Path root) {
        this.channel = channel;
        this.lock = lock;
        this.root = root;
    }

    public static DataRootLock acquire(Path dataRoot) throws IOException {
        var root = dataRoot.toAbsolutePath().normalize();
        requireSafePath(root);
        Files.createDirectories(root);
        var channel = FileChannel.open(
                root.resolve("forge.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        try {
            var lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new AlreadyOwnedException(null);
            }
            return new DataRootLock(channel, lock, root);
        } catch (OverlappingFileLockException error) {
            channel.close();
            throw new AlreadyOwnedException(error);
        } catch (IOException error) {
            channel.close();
            throw error;
        }
    }

    /** Proves this still-live lock owns the exact data root before maintenance writes. */
    public void requireHeld(Path dataRoot) throws IOException {
        if (!lock.isValid() || !root.equals(dataRoot.toAbsolutePath().normalize()))
            throw new IOException("Maintenance requires the held lock for this Forge data root");
        requireSafePath(root);
    }

    public static void requireSafePath(Path path) throws IOException {
        for (var part = path.toAbsolutePath().normalize(); part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("Forge maintenance refuses symbolic links: " + part);
            if (Files.exists(part, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && !part.toRealPath().equals(part))
                throw new IOException("Forge maintenance refuses redirected paths: " + part);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}

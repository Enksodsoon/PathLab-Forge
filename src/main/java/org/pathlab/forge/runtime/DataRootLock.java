package org.pathlab.forge.runtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class DataRootLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private DataRootLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static DataRootLock acquire(Path dataRoot) throws IOException {
        var root = dataRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        var channel = FileChannel.open(
                root.resolve("forge.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        try {
            var lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new IOException("PathLab Forge data root is already owned by another process");
            }
            return new DataRootLock(channel, lock);
        } catch (OverlappingFileLockException error) {
            channel.close();
            throw new IOException("PathLab Forge data root is already owned by another process", error);
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

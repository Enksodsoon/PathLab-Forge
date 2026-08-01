package org.pathlab.forge.conversion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import org.pathlab.forge.packageformat.PackageEntryIndex;

/** Reuses one validated package index and channel across a bounded derivative scan. */
public final class DerivativeEntryReader implements AutoCloseable {
    private static final long MAXIMUM_ENTRY_BYTES = 32L * 1024 * 1024;
    private final LocalArtifacts artifacts;
    private final PackageEntryIndex index;
    private final FileChannel channel;

    DerivativeEntryReader(LocalArtifacts artifacts) throws IOException {
        this.artifacts = artifacts;
        var indexPath = artifacts.packagePath()
                .resolveSibling(artifacts.packagePath().getFileName() + ".index");
        index = PackageEntryIndex.read(indexPath);
        channel = FileChannel.open(artifacts.packagePath(), StandardOpenOption.READ);
    }

    public synchronized byte[] read(String relative) throws IOException {
        if (!relative.matches("slide\\.dzi|thumbnail\\.jpg|slide_files/\\d+/\\d+_\\d+\\.jpg")) {
            throw new IllegalArgumentException("Invalid derivative entry");
        }
        var loose = artifacts.derivativeRoot()
                .resolve(relative.replace('/', java.io.File.separatorChar))
                .normalize();
        if (loose.startsWith(artifacts.derivativeRoot())
                && Files.isRegularFile(loose, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(loose)) {
            return Files.readAllBytes(loose);
        }
        var entry = index.require("derivative/" + relative);
        if (entry.size() > MAXIMUM_ENTRY_BYTES) {
            throw new IOException("Derivative package entry exceeds the local serving limit");
        }
        var bytes = ByteBuffer.allocate(Math.toIntExact(entry.size()));
        channel.position(entry.offset());
        while (bytes.hasRemaining()) {
            if (channel.read(bytes) < 0) {
                throw new IOException("Derivative package entry is truncated");
            }
        }
        return bytes.array();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}

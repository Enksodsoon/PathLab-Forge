package org.pathlab.forge.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;

/** Logical bytes of managed regular files; never follows links or reads slide payloads. */
public record ManagedStorageUsage(long bytes, long files, boolean complete, long measuredAt) {
    public static ManagedStorageUsage measure(Path root) {
        return measure(root, 2_000_000, java.util.concurrent.TimeUnit.SECONDS.toNanos(10));
    }

    static ManagedStorageUsage measure(Path root, long maximumEntries, long maximumNanos) {
        var totals = new long[3];
        var complete = new boolean[] {true};
        var started = System.nanoTime();
        try {
            DataRootLock.requireSafePath(root);
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                private FileVisitResult bound() {
                    if (++totals[2] > maximumEntries || System.nanoTime() - started > maximumNanos
                            || Thread.currentThread().isInterrupted()) {
                        complete[0] = false; return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    if (bound() == FileVisitResult.TERMINATE) return FileVisitResult.TERMINATE;
                    try { DataRootLock.requireSafePath(directory); }
                    catch (IOException redirected) { complete[0] = false; return FileVisitResult.SKIP_SUBTREE; }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (bound() == FileVisitResult.TERMINATE) return FileVisitResult.TERMINATE;
                    if (attributes.isRegularFile() && !attributes.isSymbolicLink() && !attributes.isOther()) {
                        totals[0] = Math.addExact(totals[0], attributes.size()); totals[1]++;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    complete[0] = false; return bound();
                }
            });
        } catch (IOException | ArithmeticException failure) { complete[0] = false; }
        return new ManagedStorageUsage(totals[0], totals[1], complete[0], System.currentTimeMillis());
    }
}

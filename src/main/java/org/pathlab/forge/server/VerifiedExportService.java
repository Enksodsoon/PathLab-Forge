package org.pathlab.forge.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;

/** One bounded native export; existing destinations change only after verification. */
final class VerifiedExportService implements AutoCloseable {
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile State state = new State("", "IDLE", 0, 0, "", "");
    private boolean cancelled;
    record State(String id, String status, long completedBytes, long totalBytes, String destination, String detail) {}

    synchronized State submit(Path source, String expectedHash, Path destination) throws IOException {
        return submit(source, expectedHash, destination, false);
    }
    synchronized State submitBytes(byte[] bytes, Path destination) throws IOException {
        if (bytes.length > 16 * 1024 * 1024) throw new IOException("Result export exceeds bounded size; export a smaller selection");
        if (active()) throw new IllegalStateException("An export is active");
        var source = Files.createTempFile("forge-result-export-", ".partial");
        try {
            Files.write(source, bytes);
            var digest = MessageDigest.getInstance("SHA-256");
            return submit(source, HexFormat.of().formatHex(digest.digest(bytes)), destination, true);
        } catch (IOException | RuntimeException | NoSuchAlgorithmException error) {
            Files.deleteIfExists(source);
            if (error instanceof IOException io) throw io;
            throw new IOException("Result export could not be staged", error);
        }
    }
    private synchronized State submit(Path source, String expectedHash, Path destination, boolean temporary) throws IOException {
        if (state.status().equals("COPYING") || state.status().equals("VERIFYING")) throw new IllegalStateException("An export is active");
        if (!expectedHash.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("Verified artifact hash is required");
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) throw new IOException("Artifact is unavailable");
        var target = destination.toAbsolutePath().getParent().toRealPath().resolve(destination.getFileName());
        if (Files.isSymbolicLink(target) || Files.exists(target) && Files.isSameFile(source, target)) throw new IOException("Export cannot replace its source or a symlink");
        var size = Files.size(source);
        if (Files.getFileStore(target.getParent()).getUsableSpace() < size + 1024 * 1024) throw new IOException("Insufficient destination space");
        cancelled = false;
        state = new State(UUID.randomUUID().toString(), "COPYING", 0, size, target.toString(), "Copying verified artifact");
        worker.submit(() -> copy(source, expectedHash, target, temporary));
        return state;
    }
    State state() { return state; }
    boolean active() { return state.status().equals("COPYING") || state.status().equals("VERIFYING"); }
    synchronized State cancel(String expectedId) {
        if (expectedId.isBlank() || !expectedId.equals(state.id())) throw new IllegalStateException("Export changed; refresh before cancelling");
        if (active()) cancelled = true;
        return state;
    }
    private synchronized void checkCancelled() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Export cancelled");
    }
    private void copy(Path source, String expectedHash, Path target, boolean temporary) {
        Path partial = null;
        try {
            partial = Files.createTempFile(target.getParent(), ".forge-export-", ".partial");
            long completed = 0;
            try (var input = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 var output = FileChannel.open(partial, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.allocate(1024 * 1024);
                while (input.read(buffer) != -1) {
                    checkCancelled(); buffer.flip();
                    var count = buffer.remaining();
                    while (buffer.hasRemaining()) output.write(buffer);
                    completed += count; buffer.clear();
                    state = new State(state.id(), "COPYING", completed, state.totalBytes(), target.toString(), "Copying");
                }
                output.force(true);
            }
            state = new State(state.id(), "VERIFYING", completed, state.totalBytes(), target.toString(), "Verifying copied bytes");
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(partial, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = new byte[1024 * 1024]; int count;
                while ((count = input.read(buffer)) != -1) { checkCancelled(); digest.update(buffer, 0, count); }
            }
            if (completed != state.totalBytes() || !HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expectedHash)) throw new IOException("Export hash or size differs from verified artifact");
            synchronized (this) {
                checkCancelled();
                if (Files.isSymbolicLink(target)) throw new IOException("Destination changed to a symlink");
                // No non-atomic fallback: a failure must preserve any completed destination.
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                state = new State(state.id(), "COMPLETE", completed, completed, target.toString(), "Verified export complete");
            }
        } catch (IOException | NoSuchAlgorithmException error) {
            state = new State(state.id(), cancelled ? "CANCELLED" : "FAILED", state.completedBytes(), state.totalBytes(), target.toString(), error.getMessage());
        } finally {
            if (partial != null) try { Files.deleteIfExists(partial); } catch (IOException ignored) { /* Never delete destination on cleanup failure. */ }
            if (temporary) try { Files.deleteIfExists(source); } catch (IOException ignored) { /* Only this service's temporary source. */ }
        }
    }
    @Override public synchronized void close() { cancelled = true; worker.shutdownNow(); }
}

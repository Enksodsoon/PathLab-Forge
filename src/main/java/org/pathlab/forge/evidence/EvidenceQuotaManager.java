package org.pathlab.forge.evidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Hard, fail-closed quota accounting for newly acquired Evidence Mentor material. */
public final class EvidenceQuotaManager {
    private static final long GIB = 1024L * 1024 * 1024;
    private final Path root;

    public EvidenceQuotaManager(Path stateRoot) throws IOException {
        root = stateRoot.toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("quota"));
    }

    public synchronized void requireReservation(Bucket bucket, long additionalBytes) throws IOException {
        if (additionalBytes < 0) throw new IllegalArgumentException("Quota reservation is invalid");
        var used = directoryBytes(bucket.path(root));
        var reserved = reservedBytes(bucket);
        if (used > bucket.limitBytes || reserved > bucket.limitBytes - used
                || additionalBytes > bucket.limitBytes - used - reserved) {
            throw new IllegalArgumentException("Evidence " + bucket.wire + " quota is exhausted");
        }
    }

    public synchronized Reservation reserve(Bucket bucket, String id, long bytes) throws IOException {
        if (bucket == Bucket.RESERVE) throw new IllegalArgumentException("Evidence reserve is untouchable");
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalArgumentException("Quota reservation id is invalid");
        }
        requireReservation(bucket, bytes);
        var directory = root.resolve("quota/reservations").resolve(bucket.wire);
        Files.createDirectories(directory);
        var target = directory.resolve(id + ".reservation");
        if (Files.exists(target)) throw new IllegalArgumentException("Quota reservation already exists");
        var partial = target.resolveSibling(target.getFileName() + ".partial");
        Files.writeString(partial, Long.toString(bytes), java.nio.file.StandardOpenOption.CREATE_NEW);
        try {
            Files.move(partial, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(partial, target);
        }
        return new Reservation(target, bytes);
    }

    public Snapshot snapshot() throws IOException {
        var values = new java.util.LinkedHashMap<String, Usage>();
        for (var bucket : Bucket.values()) {
            values.put(bucket.wire, new Usage(directoryBytes(bucket.path(root)), reservedBytes(bucket),
                    bucket.limitBytes, bucket == Bucket.RESERVE));
        }
        return new Snapshot(java.util.Map.copyOf(values));
    }

    private static long directoryBytes(Path path) throws IOException {
        if (!Files.exists(path)) return 0;
        try (var paths = Files.walk(path)) {
            return paths.filter(Files::isRegularFile).mapToLong(item -> {
                try { return Files.size(item); } catch (IOException error) { return Long.MAX_VALUE; }
            }).sum();
        }
    }

    private long reservedBytes(Bucket bucket) throws IOException {
        var directory = root.resolve("quota/reservations").resolve(bucket.wire);
        if (!Files.isDirectory(directory)) return 0;
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".reservation"))
                    .mapToLong(path -> {
                        try { return Long.parseLong(Files.readString(path).trim()); }
                        catch (Exception error) { return Long.MAX_VALUE; }
                    }).sum();
        }
    }

    public enum Bucket {
        SOURCE("source", 45 * GIB, "sources"),
        DERIVED("derived", 25 * GIB, "derived"),
        MODELS("models", 10 * GIB, "models"),
        EVIDENCE_TEST("evidence-test", 10 * GIB, "artifacts"),
        RESERVE("reserve", 10 * GIB, "reserve");

        private final String wire;
        private final long limitBytes;
        private final String directory;
        Bucket(String wire, long limitBytes, String directory) {
            this.wire = wire; this.limitBytes = limitBytes; this.directory = directory;
        }
        Path path(Path root) { return root.resolve(directory); }
    }

    public record Usage(long usedBytes, long reservedBytes, long limitBytes, boolean untouchable) {}
    public record Snapshot(java.util.Map<String, Usage> buckets) {}

    public static final class Reservation implements AutoCloseable {
        private final Path path;
        private final long bytes;
        private Reservation(Path path, long bytes) { this.path = path; this.bytes = bytes; }
        public long bytes() { return bytes; }
        public void commit() throws IOException { Files.deleteIfExists(path); }
        @Override public void close() throws IOException { Files.deleteIfExists(path); }
    }
}

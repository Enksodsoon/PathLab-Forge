package org.pathlab.forge.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public record DatasetSourceInventory(
        long totalBytes, String fingerprint, String serialized, int fileCount) {
    private static final int MAX_COMPANION_DEPTH = 4;
    private static final long MAX_COMPANION_ENTRIES = 10_000;

    public DatasetSourceInventory {
        if (totalBytes < 0 || fileCount < 0) {
            throw new IllegalArgumentException("Inventory counts must not be negative");
        }
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        serialized = Objects.requireNonNull(serialized, "serialized");
    }

    public static DatasetSourceInventory singleFile(Path source) throws IOException {
        var normalized = source.toAbsolutePath().normalize();
        return create(normalized.getParent(), List.of(normalized));
    }

    public static DatasetSourceInventory forVsi(Path source)
            throws IOException, DatasetInspectionException {
        var normalized = source.toAbsolutePath().normalize();
        var parent = normalized.getParent();
        var stem = stripExtension(normalized.getFileName().toString());
        var matchingDirectories = new ArrayList<Path>();
        try (Stream<Path> children = Files.list(parent)) {
            children.filter(Files::isDirectory)
                    .filter(path -> normalizedStem(path.getFileName().toString())
                            .equals(normalizedStem(stem)))
                    .forEach(matchingDirectories::add);
        }
        if (matchingDirectories.size() > 1) {
            throw new DatasetInspectionException(
                    "AMBIGUOUS_COMPANIONS",
                    "Multiple CellSens companion directories match the selected VSI");
        }
        if (matchingDirectories.isEmpty()) {
            return new DatasetSourceInventory(Files.size(normalized), "", "", 1);
        }

        var companionRoot = matchingDirectories.get(0);
        var files = new ArrayList<Path>();
        files.add(normalized);
        try (Stream<Path> paths = Files.find(
                companionRoot,
                MAX_COMPANION_DEPTH,
                (path, attributes) -> attributes.isRegularFile())) {
            paths.limit(MAX_COMPANION_ENTRIES + 1).forEach(files::add);
        }
        if (files.size() > MAX_COMPANION_ENTRIES) {
            throw new DatasetInspectionException(
                    "TOO_MANY_COMPANIONS",
                    "The VSI companion set exceeds the bounded inventory limit");
        }
        var hasEts = files.stream().skip(1).anyMatch(DatasetSourceInventory::isEts);
        if (!hasEts) {
            return new DatasetSourceInventory(Files.size(normalized), "", "", 1);
        }
        return create(parent, files);
    }

    public static boolean matchesSnapshot(Path source, String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return false;
        }
        try {
            var normalized = source.toAbsolutePath().normalize();
            var snapshot = normalized.getFileName()
                            .toString()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(".vsi")
                    ? SourceSnapshot.forVsi(normalized)
                    : SourceSnapshot.singleFile(normalized);
            var lines = serialized.lines().filter(line -> !line.isBlank()).toList();
            if (lines.size() != snapshot.files().size()) {
                return false;
            }
            for (var index = 0; index < lines.size(); index++) {
                var fields = lines.get(index).split("\\|", 5);
                var entry = snapshot.files().get(index);
                if ((fields.length != 4 && fields.length != 5)
                        || !fields[0].equals(entry.relativePath())
                        || Long.parseLong(fields[1]) != entry.size()
                        || Long.parseLong(fields[2]) != entry.modifiedAt()) {
                    return false;
                }
                if (fields.length == 5) {
                    var fileId = new String(
                            java.util.Base64.getUrlDecoder().decode(fields[3]),
                            StandardCharsets.UTF_8);
                    if (!fileId.equals(entry.fileId())) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException | DatasetInspectionException | RuntimeException error) {
            return false;
        }
    }

    private static DatasetSourceInventory create(Path root, List<Path> files)
            throws IOException {
        var sorted = files.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .sorted(Comparator.comparing(path -> portable(root.relativize(path))))
                .toList();
        var identities = identities(root, sorted);
        var manifest = new StringBuilder();
        long total = 0;
        for (var identity : identities) {
            total = Math.addExact(total, identity.bytes());
            manifest.append(identity.relativePath())
                    .append('|')
                    .append(identity.bytes())
                    .append('|')
                    .append(identity.modifiedAt())
                    .append('|')
                    .append(identity.sha256())
                    .append('\n');
        }
        var serialized = manifest.toString();
        return new DatasetSourceInventory(
                total,
                sha256(serialized.getBytes(StandardCharsets.UTF_8)),
                serialized,
                sorted.size());
    }

    private static List<FileIdentity> identities(Path root, List<Path> files)
            throws IOException {
        if (files.size() == 1) {
            return List.of(identity(root, files.get(0)));
        }
        var workers = Math.min(4, files.size());
        var executor = Executors.newFixedThreadPool(workers, runnable -> {
            var thread = new Thread(runnable, "pathlab-source-fingerprint");
            thread.setDaemon(true);
            return thread;
        });
        try {
            var futures = files.stream()
                    .map(file -> executor.submit(() -> identity(root, file)))
                    .toList();
            var identities = new ArrayList<FileIdentity>(files.size());
            for (var future : futures) {
                try {
                    identities.add(future.get());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Source fingerprinting was interrupted", error);
                } catch (ExecutionException error) {
                    var cause = error.getCause();
                    if (cause instanceof IOException io) {
                        throw io;
                    }
                    throw new IOException("Source fingerprinting failed", cause);
                }
            }
            return List.copyOf(identities);
        } finally {
            executor.shutdownNow();
        }
    }

    private static FileIdentity identity(Path root, Path file) throws IOException {
        return new FileIdentity(
                portable(root.relativize(file)),
                Files.size(file),
                Files.getLastModifiedTime(file).toMillis(),
                sha256(file));
    }

    private static boolean isEts(Path path) {
        return path.getFileName()
                .toString()
                .toLowerCase(Locale.ROOT)
                .endsWith(".ets");
    }

    private static String stripExtension(String name) {
        var separator = name.lastIndexOf('.');
        return separator < 0 ? name : name.substring(0, separator);
    }

    private static String normalizedStem(String value) {
        var normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT);
        var start = 0;
        var end = normalized.length();
        while (start < end && normalized.charAt(start) == '_') {
            start++;
        }
        while (end > start && normalized.charAt(end - 1) == '_') {
            end--;
        }
        return normalized.substring(start, end);
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            var digest = digest();
            var buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static String sha256(byte[] bytes) {
        var digest = digest();
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private record FileIdentity(
            String relativePath, long bytes, long modifiedAt, String sha256) {}
}

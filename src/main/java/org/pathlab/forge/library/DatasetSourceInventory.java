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

    private static DatasetSourceInventory create(Path root, List<Path> files)
            throws IOException {
        var sorted = files.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .sorted(Comparator.comparing(path -> portable(root.relativize(path))))
                .toList();
        var manifest = new StringBuilder();
        long total = 0;
        for (var file : sorted) {
            var bytes = Files.size(file);
            total = Math.addExact(total, bytes);
            manifest.append(portable(root.relativize(file)))
                    .append('|')
                    .append(bytes)
                    .append('|')
                    .append(Files.getLastModifiedTime(file).toMillis())
                    .append('|')
                    .append(sha256(file))
                    .append('\n');
        }
        var serialized = manifest.toString();
        return new DatasetSourceInventory(
                total,
                sha256(serialized.getBytes(StandardCharsets.UTF_8)),
                serialized,
                sorted.size());
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
}

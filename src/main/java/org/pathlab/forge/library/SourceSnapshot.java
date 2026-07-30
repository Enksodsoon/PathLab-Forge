package org.pathlab.forge.library;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public record SourceSnapshot(
        Path canonicalPath,
        Path root,
        List<Entry> files,
        long totalBytes,
        String fingerprint,
        String serialized) {
    private static final int MAX_COMPANION_DEPTH = 4;
    private static final long MAX_COMPANION_ENTRIES = 10_000;

    public SourceSnapshot {
        canonicalPath = canonicalPath.toAbsolutePath().normalize();
        root = root.toAbsolutePath().normalize();
        files = List.copyOf(files);
        if (files.isEmpty() || totalBytes < 0 || fingerprint.length() != 64) {
            throw new IllegalArgumentException("Source snapshot is invalid");
        }
    }

    public static SourceSnapshot singleFile(Path source) throws IOException {
        var normalized = source.toAbsolutePath().normalize();
        return create(normalized, normalized.getParent(), List.of(normalized));
    }

    public static SourceSnapshot forVsi(Path source)
            throws IOException, DatasetInspectionException {
        var normalized = source.toAbsolutePath().normalize();
        var parent = normalized.getParent();
        var stem = stripExtension(normalized.getFileName().toString());
        var matchingDirectories = new ArrayList<Path>();
        try (var children = Files.list(parent)) {
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
        var files = new ArrayList<Path>();
        files.add(normalized);
        if (!matchingDirectories.isEmpty()) {
            try (var paths = Files.find(
                    matchingDirectories.get(0),
                    MAX_COMPANION_DEPTH,
                    (path, attributes) -> attributes.isRegularFile())) {
                paths.limit(MAX_COMPANION_ENTRIES + 1).forEach(files::add);
            }
        }
        if (files.size() > MAX_COMPANION_ENTRIES) {
            throw new DatasetInspectionException(
                    "TOO_MANY_COMPANIONS",
                    "The VSI companion set exceeds the bounded inventory limit");
        }
        return create(normalized, parent, files);
    }

    public boolean hasEtsCompanion() {
        return files.stream()
                .filter(entry -> !entry.path().equals(canonicalPath))
                .anyMatch(entry -> entry.path()
                        .getFileName()
                        .toString()
                        .toLowerCase(Locale.ROOT)
                        .endsWith(".ets"));
    }

    private static SourceSnapshot create(Path source, Path root, List<Path> paths)
            throws IOException {
        var entries = new ArrayList<Entry>();
        long total = 0;
        for (var path : paths.stream()
                .map(item -> item.toAbsolutePath().normalize())
                .sorted(Comparator.comparing(item -> portable(root.relativize(item))))
                .toList()) {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class);
            var relative = portable(root.relativize(path));
            var key = String.valueOf(attributes.fileKey());
            entries.add(new Entry(
                    path,
                    relative,
                    attributes.size(),
                    attributes.lastModifiedTime().toMillis(),
                    key));
            total = Math.addExact(total, attributes.size());
        }
        var serialized = entries.stream()
                .map(entry -> entry.relativePath() + "|" + entry.size() + "|"
                        + entry.modifiedAt() + "|"
                        + Base64.getUrlEncoder().withoutPadding().encodeToString(
                                entry.fileId().getBytes(StandardCharsets.UTF_8)))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        return new SourceSnapshot(
                source,
                root,
                entries,
                total,
                sha256(serialized.getBytes(StandardCharsets.UTF_8)),
                serialized);
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

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public record Entry(
            Path path, String relativePath, long size, long modifiedAt, String fileId) {}
}

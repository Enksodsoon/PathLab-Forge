package org.pathlab.forge.library;

import java.util.Objects;

public record LocalDataset(
        String id,
        String displayName,
        String sourcePath,
        long sourceBytes,
        DatasetFormat format,
        DatasetStatus status,
        String detail,
        String outputPath,
        String sha256) {
    public LocalDataset {
        id = requireText(id, "id");
        displayName = requireText(displayName, "displayName");
        sourcePath = requireText(sourcePath, "sourcePath");
        if (sourceBytes < 0) {
            throw new IllegalArgumentException("sourceBytes must not be negative");
        }
        format = Objects.requireNonNull(format, "format");
        status = Objects.requireNonNull(status, "status");
        detail = Objects.requireNonNull(detail, "detail");
        outputPath = Objects.requireNonNull(outputPath, "outputPath");
        sha256 = Objects.requireNonNull(sha256, "sha256");
    }

    public LocalDataset withPreparation(
            DatasetStatus nextStatus, String nextDetail, String nextOutputPath, String nextSha256) {
        return new LocalDataset(
                id,
                displayName,
                sourcePath,
                sourceBytes,
                format,
                nextStatus,
                nextDetail,
                nextOutputPath,
                nextSha256);
    }

    private static String requireText(String value, String name) {
        var normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}

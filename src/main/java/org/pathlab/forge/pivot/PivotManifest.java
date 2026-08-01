package org.pathlab.forge.pivot;

import java.util.List;
import java.util.Objects;

public record PivotManifest(
        String schema,
        String algorithmVersion,
        String id,
        String datasetId,
        String sourceFingerprint,
        String inputRevision,
        int selectedSeries,
        int previewWidth,
        int previewHeight,
        int sourceWidth,
        int sourceHeight,
        long seed,
        long createdAt,
        long generationMs,
        int inspectedCandidates,
        int rejectedBlank,
        int rejectedMissing,
        List<PivotTask> tasks) {
    public PivotManifest {
        schema = requireText(schema, "schema");
        algorithmVersion = requireText(algorithmVersion, "algorithmVersion");
        id = requireText(id, "id");
        datasetId = requireText(datasetId, "datasetId");
        sourceFingerprint = requireText(sourceFingerprint, "sourceFingerprint");
        inputRevision = requireText(inputRevision, "inputRevision");
        if (selectedSeries < 0 || previewWidth <= 0 || previewHeight <= 0
                || sourceWidth <= 0 || sourceHeight <= 0 || createdAt < 0
                || generationMs < 0 || inspectedCandidates < 0 || rejectedBlank < 0
                || rejectedMissing < 0) {
            throw new IllegalArgumentException("PIVOT manifest metadata is invalid");
        }
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("PIVOT manifest must contain at least one task");
        }
    }

    private static String requireText(String value, String name) {
        var normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}

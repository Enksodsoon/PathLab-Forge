package org.pathlab.forge.pivot;

import java.util.List;
import java.util.Objects;

public record PivotSession(
        String id,
        String datasetId,
        String manifestId,
        String sourceFingerprint,
        PivotSessionState state,
        long startedAt,
        long updatedAt,
        String currentTaskId,
        int completedTasks,
        int skippedTasks,
        int hintsUsed,
        List<String> skippedTaskIds,
        List<PivotAttempt> attempts) {
    public PivotSession {
        id = requireText(id, "id");
        datasetId = requireText(datasetId, "datasetId");
        manifestId = requireText(manifestId, "manifestId");
        sourceFingerprint = requireText(sourceFingerprint, "sourceFingerprint");
        state = Objects.requireNonNull(state, "state");
        currentTaskId = Objects.requireNonNull(currentTaskId, "currentTaskId");
        if (startedAt < 0 || updatedAt < startedAt || completedTasks < 0
                || skippedTasks < 0 || hintsUsed < 0) {
            throw new IllegalArgumentException("PIVOT session metadata is invalid");
        }
        skippedTaskIds = List.copyOf(Objects.requireNonNull(skippedTaskIds, "skippedTaskIds"));
        attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
        if (skippedTasks != skippedTaskIds.size() || completedTasks != attempts.size()) {
            throw new IllegalArgumentException("PIVOT session counters are inconsistent");
        }
        if (state == PivotSessionState.ACTIVE && currentTaskId.isBlank()) {
            throw new IllegalArgumentException("Active PIVOT session requires a current task");
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

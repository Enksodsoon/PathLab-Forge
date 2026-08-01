package org.pathlab.forge.pivot;

import java.util.Objects;

public record PivotAttempt(
        String taskId,
        double submittedX,
        double submittedY,
        long elapsedMs,
        double panDistance,
        int zoomReversals,
        int confidence,
        double normalizedError,
        String rating,
        long completedAt) {
    public PivotAttempt {
        taskId = Objects.requireNonNull(taskId, "taskId");
        rating = Objects.requireNonNull(rating, "rating");
        if (taskId.isBlank() || submittedX < 0 || submittedY < 0 || elapsedMs < 0
                || panDistance < 0 || zoomReversals < 0 || confidence < 1 || confidence > 4
                || normalizedError < 0 || !Double.isFinite(normalizedError)
                || completedAt < 0) {
            throw new IllegalArgumentException("PIVOT attempt metadata is invalid");
        }
    }
}

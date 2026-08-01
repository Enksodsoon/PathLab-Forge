package org.pathlab.forge.pivot;

import java.util.Objects;

public record PivotScore(
        double normalizedError,
        double distancePixels,
        String rating,
        PivotTask answeredTask,
        PivotSession session) {
    public PivotScore {
        if (normalizedError < 0 || distancePixels < 0) {
            throw new IllegalArgumentException("PIVOT score cannot be negative");
        }
        rating = Objects.requireNonNull(rating, "rating");
        answeredTask = Objects.requireNonNull(answeredTask, "answeredTask");
        session = Objects.requireNonNull(session, "session");
    }
}

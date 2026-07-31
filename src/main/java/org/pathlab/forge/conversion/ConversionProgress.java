package org.pathlab.forge.conversion;

import java.util.Objects;

public record ConversionProgress(
        String stage,
        long completedUnits,
        long totalUnits,
        long startedAt,
        long stageStartedAt,
        long peakWorkingSetBytes,
        String resourceProfile,
        String cacheHitReason) {
    public ConversionProgress(
            String stage,
            long completedUnits,
            long totalUnits,
            long startedAt,
            long peakWorkingSetBytes,
            String resourceProfile,
            String cacheHitReason) {
        this(
                stage,
                completedUnits,
                totalUnits,
                startedAt,
                startedAt,
                peakWorkingSetBytes,
                resourceProfile,
                cacheHitReason);
    }

    public ConversionProgress {
        stage = Objects.requireNonNull(stage, "stage");
        resourceProfile = Objects.requireNonNull(resourceProfile, "resourceProfile");
        cacheHitReason = Objects.requireNonNull(cacheHitReason, "cacheHitReason");
        if (completedUnits < 0
                || totalUnits < completedUnits
                || startedAt < 0
                || stageStartedAt < 0
                || peakWorkingSetBytes < 0) {
            throw new IllegalArgumentException("Conversion progress is invalid");
        }
    }

    public long elapsedMs() {
        return startedAt == 0 ? 0 : Math.max(0, System.currentTimeMillis() - startedAt);
    }

    public long estimatedRemainingMs() {
        if (stageStartedAt == 0
                || completedUnits <= 0
                || completedUnits >= totalUnits) {
            return 0;
        }
        var stageElapsed = Math.max(1, System.currentTimeMillis() - stageStartedAt);
        return Math.round(
                (double) stageElapsed * (totalUnits - completedUnits) / completedUnits);
    }

    public double unitsPerSecond() {
        if (stageStartedAt == 0 || completedUnits <= 0) {
            return 0;
        }
        var stageElapsed = Math.max(1, System.currentTimeMillis() - stageStartedAt);
        return completedUnits * 1_000.0 / stageElapsed;
    }
}

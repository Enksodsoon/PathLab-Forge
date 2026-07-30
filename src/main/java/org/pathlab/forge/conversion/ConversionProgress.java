package org.pathlab.forge.conversion;

import java.util.Objects;

public record ConversionProgress(
        String stage,
        long completedUnits,
        long totalUnits,
        long startedAt,
        long peakWorkingSetBytes,
        String resourceProfile,
        String cacheHitReason) {
    public ConversionProgress {
        stage = Objects.requireNonNull(stage, "stage");
        resourceProfile = Objects.requireNonNull(resourceProfile, "resourceProfile");
        cacheHitReason = Objects.requireNonNull(cacheHitReason, "cacheHitReason");
        if (completedUnits < 0
                || totalUnits < completedUnits
                || startedAt < 0
                || peakWorkingSetBytes < 0) {
            throw new IllegalArgumentException("Conversion progress is invalid");
        }
    }

    public long elapsedMs() {
        return startedAt == 0 ? 0 : Math.max(0, System.currentTimeMillis() - startedAt);
    }
}

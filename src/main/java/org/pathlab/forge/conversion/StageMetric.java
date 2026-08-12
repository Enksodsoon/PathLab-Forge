package org.pathlab.forge.conversion;

import java.util.Objects;

public record StageMetric(
        String stage,
        long wallTimeMs,
        long cpuTimeMs,
        long bytesRead,
        long bytesWritten,
        long peakWorkingSetBytes,
        long cacheHits,
        int workerCount,
        long completedBytes,
        String failureCode) {
    public StageMetric {
        stage = Objects.requireNonNull(stage, "stage");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        if (stage.isBlank()
                || wallTimeMs < 0
                || cpuTimeMs < 0
                || bytesRead < 0
                || bytesWritten < 0
                || peakWorkingSetBytes < 0
                || cacheHits < 0
                || workerCount < 0
                || completedBytes < 0) {
            throw new IllegalArgumentException("Stage metric values are invalid");
        }
    }
}

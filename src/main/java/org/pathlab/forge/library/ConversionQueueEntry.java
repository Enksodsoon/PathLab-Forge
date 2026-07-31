package org.pathlab.forge.library;

import java.util.Objects;

public record ConversionQueueEntry(
        String datasetId,
        long position,
        String configurationRevision,
        long createdAt,
        String waitReason) {
    public ConversionQueueEntry {
        datasetId = Objects.requireNonNull(datasetId, "datasetId");
        configurationRevision = Objects.requireNonNull(configurationRevision, "configurationRevision");
        waitReason = Objects.requireNonNull(waitReason, "waitReason");
    }

    public ConversionQueueEntry withWaitReason(String reason) {
        return new ConversionQueueEntry(
                datasetId, position, configurationRevision, createdAt, reason);
    }
}

package org.pathlab.forge.library;

import java.util.Objects;

public record ConversionQueueEntry(
        String datasetId,
        long position,
        String configurationRevision,
        long createdAt,
        String requestedFormat,
        String waitReason) {
    public ConversionQueueEntry {
        datasetId = Objects.requireNonNull(datasetId, "datasetId");
        configurationRevision = Objects.requireNonNull(configurationRevision, "configurationRevision");
        requestedFormat = Objects.requireNonNull(requestedFormat, "requestedFormat");
        waitReason = Objects.requireNonNull(waitReason, "waitReason");
    }

    public ConversionQueueEntry(
            String datasetId,
            long position,
            String configurationRevision,
            long createdAt,
            String waitReason) {
        this(
                datasetId,
                position,
                configurationRevision,
                createdAt,
                "PREPARED_DZI_V2",
                waitReason);
    }

    public ConversionQueueEntry withWaitReason(String reason) {
        return new ConversionQueueEntry(
                datasetId, position, configurationRevision, createdAt, requestedFormat, reason);
    }
}

package org.pathlab.forge.viewer;

import java.time.Instant;
import java.util.Objects;

public record ViewerSyncConflict(
        String slideId,
        String field,
        String localValue,
        String remoteValue,
        long baseRevision,
        long remoteRevision,
        Instant detectedAt,
        boolean unresolved) {
    public ViewerSyncConflict(String slideId, String field, String localValue, String remoteValue,
            long remoteRevision, Instant detectedAt) {
        this(slideId, field, localValue, remoteValue, 0, remoteRevision, detectedAt, true);
    }

    public ViewerSyncConflict(String slideId, String field, String localValue, String remoteValue,
            long remoteRevision, Instant detectedAt, boolean unresolved) {
        this(slideId, field, localValue, remoteValue, 0, remoteRevision, detectedAt, unresolved);
    }

    public ViewerSyncConflict {
        Objects.requireNonNull(slideId);
        Objects.requireNonNull(field);
        Objects.requireNonNull(localValue);
        Objects.requireNonNull(remoteValue);
        Objects.requireNonNull(detectedAt);
        if (baseRevision < 0 || remoteRevision < 0) {
            throw new IllegalArgumentException("Remote revision must be non-negative");
        }
    }
}

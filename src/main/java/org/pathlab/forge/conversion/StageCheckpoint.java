package org.pathlab.forge.conversion;

import java.util.Objects;

public record StageCheckpoint(
        String artifactRevisionId,
        String configurationRevision,
        String sourceFingerprint,
        Stage stage,
        long completedUnits,
        long totalUnits,
        long updatedAt) {
    public StageCheckpoint {
        artifactRevisionId = requireText(artifactRevisionId, "artifactRevisionId");
        configurationRevision = requireText(configurationRevision, "configurationRevision");
        sourceFingerprint = requireText(sourceFingerprint, "sourceFingerprint");
        stage = Objects.requireNonNull(stage, "stage");
        if (completedUnits < 0
                || totalUnits < completedUnits
                || updatedAt <= 0) {
            throw new IllegalArgumentException("Checkpoint progress is invalid");
        }
    }

    private static String requireText(String value, String name) {
        var normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    public enum Stage {
        SOURCE_VERIFIED,
        REGIONS_RENDERING,
        REGIONS_VERIFIED,
        OME_VERIFIED,
        DZI_LEDGER_VERIFIED,
        PACKAGE_COMMITTED
    }
}

package org.pathlab.forge.evidence;

import java.nio.file.Path;
import java.time.Instant;

public record EvidenceJob(
        String id,
        Path requestPath,
        EvidenceJobState state,
        String stage,
        double progress,
        int retryCount,
        boolean cancelRequested,
        String leaseOwner,
        Instant leaseExpiresAt,
        String detail,
        Instant createdAt,
        Instant updatedAt) {
    public EvidenceJob {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalArgumentException("Evidence job id is invalid");
        }
        requestPath = requestPath.toAbsolutePath().normalize();
        if (!Double.isFinite(progress) || progress < 0 || progress > 1) {
            throw new IllegalArgumentException("Evidence job progress is invalid");
        }
        if (retryCount < 0 || retryCount > 3) {
            throw new IllegalArgumentException("Evidence job retry count is invalid");
        }
        detail = detail == null ? "" : detail;
        stage = stage == null ? "" : stage;
        leaseOwner = leaseOwner == null ? "" : leaseOwner;
    }
}

package org.pathlab.forge.evidence;

import java.nio.file.Path;
import java.time.Instant;

/** Operations-only durable view. It deliberately excludes slide paths and scientific results. */
public record EvidenceJobSnapshot(
        String id, EvidenceJobState state, String stage, double progress,
        EvidenceExecutionLane lane, long completedUnits, long totalUnits,
        Path checkpointPath, String checkpointSha256, Instant lastHeartbeat,
        double throughput, Long etaSeconds, Instant nextRetryAt, int retryCount,
        boolean cancelRequested, String leaseOwner, Instant leaseExpiresAt,
        String failureClass, String failureCode, String requestSha256, String packSha256,
        String finalArtifactSha256,
        String detail, Instant createdAt, Instant updatedAt) { }

package org.pathlab.forge.evidence;

import java.time.Instant;
import java.util.List;

/** Operations-only campaign view; scientific measurements stay in signed reports. */
public record QualificationRunSnapshot(
        String id, String manifestSha256, String state,
        boolean campaignCompleted, boolean campaignTargetMet,
        boolean cancelRequested, List<Track> tracks,
        Instant createdAt, Instant updatedAt) {
    public record Track(
            String id, String candidateId, String capability, String scope,
            String state, String verdict, int attempt, String jobId,
            String artifactSha256, String failureCode, String detail,
            Instant updatedAt) { }
}

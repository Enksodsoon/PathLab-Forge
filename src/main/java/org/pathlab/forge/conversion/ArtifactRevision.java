package org.pathlab.forge.conversion;

import java.util.Objects;

public record ArtifactRevision(
        String id,
        String datasetId,
        String configurationRevision,
        String sourceFingerprint,
        long createdAt,
        ArtifactRevisionStatus status,
        ArtifactRevisionFormat format,
        String omePath,
        String derivativePath,
        String packagePath,
        String omeSha256,
        String packageSha256,
        int outputWidth,
        int outputHeight,
        long approvedAt,
        String failure) {
    public ArtifactRevision {
        id = requireText(id, "id");
        datasetId = requireText(datasetId, "datasetId");
        configurationRevision = requireText(configurationRevision, "configurationRevision");
        sourceFingerprint = Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
        status = Objects.requireNonNull(status, "status");
        format = Objects.requireNonNull(format, "format");
        omePath = requireText(omePath, "omePath");
        derivativePath = requireText(derivativePath, "derivativePath");
        packagePath = requireText(packagePath, "packagePath");
        omeSha256 = Objects.requireNonNull(omeSha256, "omeSha256");
        packageSha256 = Objects.requireNonNull(packageSha256, "packageSha256");
        failure = Objects.requireNonNull(failure, "failure");
        if (createdAt <= 0 || outputWidth <= 0 || outputHeight <= 0 || approvedAt < 0) {
            throw new IllegalArgumentException("Artifact revision metadata is invalid");
        }
    }

    public ArtifactRevision ready(String nextOmeSha256, String nextPackageSha256) {
        return withStatus(
                ArtifactRevisionStatus.READY,
                nextOmeSha256,
                nextPackageSha256,
                0,
                "");
    }

    public ArtifactRevision approved(long time) {
        if (status != ArtifactRevisionStatus.READY) {
            throw new IllegalStateException("Only a validated artifact can be approved");
        }
        return withStatus(
                ArtifactRevisionStatus.APPROVED,
                omeSha256,
                packageSha256,
                time,
                "");
    }

    public ArtifactRevision failed(String message) {
        return withStatus(
                ArtifactRevisionStatus.FAILED,
                omeSha256,
                packageSha256,
                0,
                Objects.requireNonNull(message, "message"));
    }

    private ArtifactRevision withStatus(
            ArtifactRevisionStatus nextStatus,
            String nextOmeSha256,
            String nextPackageSha256,
            long nextApprovedAt,
            String nextFailure) {
        return new ArtifactRevision(
                id,
                datasetId,
                configurationRevision,
                sourceFingerprint,
                createdAt,
                nextStatus,
                format,
                omePath,
                derivativePath,
                packagePath,
                nextOmeSha256,
                nextPackageSha256,
                outputWidth,
                outputHeight,
                nextApprovedAt,
                nextFailure);
    }

    private static String requireText(String value, String name) {
        var normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}

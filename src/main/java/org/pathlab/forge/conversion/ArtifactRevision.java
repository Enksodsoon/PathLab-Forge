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
        String omeProfile,
        int omeJpegQuality,
        long approvedAt,
        String name,
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
        omeProfile = Objects.requireNonNull(omeProfile, "omeProfile").trim();
        name = validateName(name);
        failure = Objects.requireNonNull(failure, "failure");
        if (createdAt <= 0
                || outputWidth <= 0
                || outputHeight <= 0
                || approvedAt < 0
                || (omeProfile.isEmpty() && omeJpegQuality != 0)
                || (!omeProfile.isEmpty() && (omeJpegQuality < 1 || omeJpegQuality > 100))) {
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
                omeProfile,
                omeJpegQuality,
                nextApprovedAt,
                name,
                nextFailure);
    }

    public ArtifactRevision renamed(String nextName) {
        return new ArtifactRevision(
                id,
                datasetId,
                configurationRevision,
                sourceFingerprint,
                createdAt,
                status,
                format,
                omePath,
                derivativePath,
                packagePath,
                omeSha256,
                packageSha256,
                outputWidth,
                outputHeight,
                omeProfile,
                omeJpegQuality,
                approvedAt,
                nextName,
                failure);
    }

    private static String validateName(String value) {
        var normalized = requireText(value, "name");
        if (normalized.length() > 80
                || normalized.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("Conversion name must be 1 to 80 visible characters");
        }
        return normalized;
    }

    private static String requireText(String value, String name) {
        var normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}

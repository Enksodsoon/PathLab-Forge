package org.pathlab.forge.packageformat;

import java.util.Objects;

public record PackageMetadata(
        String artifactRevisionId,
        String configurationRevision,
        String sourceFingerprint,
        int series,
        int cropX,
        int cropY,
        int cropWidth,
        int cropHeight,
        double downsample,
        double physicalSizeX,
        double physicalSizeY,
        String physicalUnit,
        String sizeReferenceKind,
        String producerVersion) {
    public PackageMetadata {
        artifactRevisionId = requireText(artifactRevisionId, "artifactRevisionId");
        configurationRevision = requireText(configurationRevision, "configurationRevision");
        sourceFingerprint = requireText(sourceFingerprint, "sourceFingerprint");
        physicalUnit = Objects.requireNonNullElse(physicalUnit, "");
        sizeReferenceKind = requireText(sizeReferenceKind, "sizeReferenceKind");
        producerVersion = requireText(producerVersion, "producerVersion");
        if (!sizeReferenceKind.equals("actual-staging-ome")
                && !sizeReferenceKind.equals("estimated-staging-ome")) {
            throw new IllegalArgumentException("Prepared-package size reference is invalid");
        }
        if (series < 0
                || cropX < 0
                || cropY < 0
                || cropWidth <= 0
                || cropHeight <= 0
                || !Double.isFinite(downsample)
                || downsample <= 0
                || !Double.isFinite(physicalSizeX)
                || !Double.isFinite(physicalSizeY)
                || physicalSizeX < 0
                || physicalSizeY < 0) {
            throw new IllegalArgumentException("Prepared-package provenance is invalid");
        }
    }

    private static String requireText(String value, String name) {
        var normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}

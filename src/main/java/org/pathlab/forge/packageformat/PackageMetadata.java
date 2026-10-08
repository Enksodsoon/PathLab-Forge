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
        String producerVersion,
        String readerEngine,
        String readerId,
        String formatName,
        String runtimeFingerprint,
        String viewDefinitionJson) {
    public PackageMetadata {
        artifactRevisionId = requireText(artifactRevisionId, "artifactRevisionId");
        configurationRevision = requireText(configurationRevision, "configurationRevision");
        sourceFingerprint = requireText(sourceFingerprint, "sourceFingerprint");
        physicalUnit = Objects.requireNonNullElse(physicalUnit, "");
        sizeReferenceKind = requireText(sizeReferenceKind, "sizeReferenceKind");
        producerVersion = requireText(producerVersion, "producerVersion");
        readerEngine = Objects.requireNonNullElse(readerEngine, "");
        readerId = Objects.requireNonNullElse(readerId, "");
        formatName = Objects.requireNonNullElse(formatName, "");
        runtimeFingerprint = Objects.requireNonNullElse(runtimeFingerprint, "");
        viewDefinitionJson = Objects.requireNonNullElse(viewDefinitionJson, "");
        if ((!runtimeFingerprint.isBlank() && !runtimeFingerprint.matches("[0-9a-f]{64}"))
                || viewDefinitionJson.length() > 65_536) {
            throw new IllegalArgumentException("Reader provenance is invalid");
        }
        if (!viewDefinitionJson.isBlank()) {
            try {
                if (!new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(viewDefinitionJson).isObject()) {
                    throw new IllegalArgumentException("View provenance must be a JSON object");
                }
            } catch (java.io.IOException error) {
                throw new IllegalArgumentException("View provenance is invalid", error);
            }
        }
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

    public PackageMetadata(
            String artifactRevisionId, String configurationRevision, String sourceFingerprint,
            int series, int cropX, int cropY, int cropWidth, int cropHeight, double downsample,
            double physicalSizeX, double physicalSizeY, String physicalUnit,
            String sizeReferenceKind, String producerVersion) {
        this(artifactRevisionId, configurationRevision, sourceFingerprint, series, cropX, cropY,
                cropWidth, cropHeight, downsample, physicalSizeX, physicalSizeY, physicalUnit,
                sizeReferenceKind, producerVersion, "", "", "", "", "");
    }

    private static String requireText(String value, String name) {
        var normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}

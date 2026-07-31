package org.pathlab.forge.library;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record LocalDataset(
        String id,
        String displayName,
        String sourcePath,
        long sourceBytes,
        DatasetFormat format,
        DatasetStatus status,
        String detail,
        String outputPath,
        String sha256,
        int selectedSeries,
        int width,
        int height,
        double downsample,
        long estimatedOutputBytes,
        int cropX,
        int cropY,
        int cropWidth,
        int cropHeight,
        String sourceFingerprint,
        String sourceInventory,
        String configurationRevision,
        String currentArtifactRevision,
        String approvedArtifactRevision) {
    public LocalDataset {
        id = requireText(id, "id");
        displayName = requireText(displayName, "displayName");
        sourcePath = requireText(sourcePath, "sourcePath");
        if (sourceBytes < 0) {
            throw new IllegalArgumentException("sourceBytes must not be negative");
        }
        format = Objects.requireNonNull(format, "format");
        status = Objects.requireNonNull(status, "status");
        detail = Objects.requireNonNull(detail, "detail");
        outputPath = Objects.requireNonNull(outputPath, "outputPath");
        sha256 = Objects.requireNonNull(sha256, "sha256");
        sourceFingerprint = Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
        sourceInventory = Objects.requireNonNull(sourceInventory, "sourceInventory");
        configurationRevision =
                Objects.requireNonNull(configurationRevision, "configurationRevision");
        currentArtifactRevision =
                Objects.requireNonNull(currentArtifactRevision, "currentArtifactRevision");
        approvedArtifactRevision =
                Objects.requireNonNull(approvedArtifactRevision, "approvedArtifactRevision");
        if (selectedSeries < -1 || width < 0 || height < 0 || downsample <= 0
                || estimatedOutputBytes < 0 || cropX < 0 || cropY < 0
                || cropWidth < 0 || cropHeight < 0
                || (width > 0 && (long) cropX + cropWidth > width)
                || (height > 0 && (long) cropY + cropHeight > height)) {
            throw new IllegalArgumentException("Conversion metadata is invalid");
        }
    }

    public LocalDataset(
            String id,
            String displayName,
            String sourcePath,
            long sourceBytes,
            DatasetFormat format,
            DatasetStatus status,
            String detail,
            String outputPath,
            String sha256) {
        this(
                id,
                displayName,
                sourcePath,
                sourceBytes,
                format,
                status,
                detail,
                outputPath,
                sha256,
                -1,
                0,
                0,
                1.0,
                0,
                0,
                0,
                0,
                0,
                "",
                "",
                "",
                "",
                "");
    }

    public LocalDataset withPreparation(
            DatasetStatus nextStatus, String nextDetail, String nextOutputPath, String nextSha256) {
        return new LocalDataset(
                id,
                displayName,
                sourcePath,
                sourceBytes,
                format,
                nextStatus,
                nextDetail,
                nextOutputPath,
                nextSha256,
                selectedSeries,
                width,
                height,
                downsample,
                estimatedOutputBytes,
                cropX,
                cropY,
                cropWidth,
                cropHeight,
                sourceFingerprint,
                sourceInventory,
                configurationRevision,
                currentArtifactRevision,
                approvedArtifactRevision);
    }

    public LocalDataset withSourceIdentity(
            DatasetStatus nextStatus,
            String nextDetail,
            String nextFingerprint,
            String nextInventory) {
        return new LocalDataset(
                id,
                displayName,
                sourcePath,
                sourceBytes,
                format,
                nextStatus,
                nextDetail,
                outputPath,
                sha256,
                selectedSeries,
                width,
                height,
                downsample,
                estimatedOutputBytes,
                cropX,
                cropY,
                cropWidth,
                cropHeight,
                nextFingerprint,
                nextInventory,
                configurationRevision,
                currentArtifactRevision,
                approvedArtifactRevision);
    }

    public LocalDataset withConversion(
            DatasetStatus nextStatus,
            String nextDetail,
            String nextOutputPath,
            String nextSha256,
            int nextSeries,
            int nextWidth,
            int nextHeight,
            double nextDownsample,
            long nextEstimatedBytes) {
        return new LocalDataset(
                id,
                displayName,
                sourcePath,
                sourceBytes,
                format,
                nextStatus,
                nextDetail,
                nextOutputPath,
                nextSha256,
                nextSeries,
                nextWidth,
                nextHeight,
                nextDownsample,
                nextEstimatedBytes,
                cropX,
                cropY,
                cropWidth,
                cropHeight,
                sourceFingerprint,
                sourceInventory,
                configurationRevision,
                currentArtifactRevision,
                approvedArtifactRevision);
    }

    public LocalDataset withExportConfiguration(
            DatasetStatus nextStatus,
            String nextDetail,
            int nextSeries,
            int nextWidth,
            int nextHeight,
            double nextDownsample,
            long nextEstimatedBytes,
            int nextCropX,
            int nextCropY,
            int nextCropWidth,
            int nextCropHeight) {
        var nextConfigurationRevision = configurationRevision(
                sourceFingerprint,
                sourcePath,
                sourceBytes,
                nextSeries,
                nextCropX,
                nextCropY,
                nextCropWidth,
                nextCropHeight,
                nextDownsample);
        var unchanged = nextConfigurationRevision.equals(configurationRevision);
        return new LocalDataset(
                id,
                displayName,
                sourcePath,
                sourceBytes,
                format,
                nextStatus,
                nextDetail,
                unchanged ? outputPath : "",
                unchanged ? sha256 : "",
                nextSeries,
                nextWidth,
                nextHeight,
                nextDownsample,
                nextEstimatedBytes,
                nextCropX,
                nextCropY,
                nextCropWidth,
                nextCropHeight,
                sourceFingerprint,
                sourceInventory,
                nextConfigurationRevision,
                unchanged ? currentArtifactRevision : "",
                unchanged ? approvedArtifactRevision : "");
    }

    public LocalDataset withArtifactRevision(
            DatasetStatus nextStatus,
            String nextDetail,
            String nextOutputPath,
            String nextSha256,
            String nextArtifactRevision) {
        return new LocalDataset(
                id,
                displayName,
                sourcePath,
                sourceBytes,
                format,
                nextStatus,
                nextDetail,
                nextOutputPath,
                nextSha256,
                selectedSeries,
                width,
                height,
                downsample,
                estimatedOutputBytes,
                cropX,
                cropY,
                cropWidth,
                cropHeight,
                sourceFingerprint,
                sourceInventory,
                configurationRevision,
                nextArtifactRevision,
                "");
    }

    public LocalDataset withApprovedArtifact(String revisionId) {
        if (!revisionId.equals(currentArtifactRevision)) {
            throw new IllegalArgumentException("Only the current artifact can be approved");
        }
        return new LocalDataset(
                id,
                displayName,
                sourcePath,
                sourceBytes,
                format,
                status,
                detail,
                outputPath,
                sha256,
                selectedSeries,
                width,
                height,
                downsample,
                estimatedOutputBytes,
                cropX,
                cropY,
                cropWidth,
                cropHeight,
                sourceFingerprint,
                sourceInventory,
                configurationRevision,
                currentArtifactRevision,
                revisionId);
    }

    public LocalDataset withArtifactPointers(
            DatasetStatus nextStatus,
            String nextDetail,
            String nextOutputPath,
            String nextSha256,
            String nextCurrentRevision,
            String nextApprovedRevision) {
        if (!nextApprovedRevision.isBlank()
                && !nextApprovedRevision.equals(nextCurrentRevision)) {
            throw new IllegalArgumentException("Approved artifact must be the current artifact");
        }
        return new LocalDataset(
                id,
                displayName,
                sourcePath,
                sourceBytes,
                format,
                nextStatus,
                nextDetail,
                nextOutputPath,
                nextSha256,
                selectedSeries,
                width,
                height,
                downsample,
                estimatedOutputBytes,
                cropX,
                cropY,
                cropWidth,
                cropHeight,
                sourceFingerprint,
                sourceInventory,
                configurationRevision,
                nextCurrentRevision,
                nextApprovedRevision);
    }

    private static String requireText(String value, String name) {
        var normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String configurationRevision(
            String sourceFingerprint,
            String sourcePath,
            long sourceBytes,
            int series,
            int cropX,
            int cropY,
            int cropWidth,
            int cropHeight,
            double downsample) {
        var identity = (sourceFingerprint.isBlank()
                        ? sourcePath + "|" + sourceBytes
                        : sourceFingerprint)
                + "|" + series
                + "|" + cropX
                + "|" + cropY
                + "|" + cropWidth
                + "|" + cropHeight
                + "|" + Double.toString(downsample);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}

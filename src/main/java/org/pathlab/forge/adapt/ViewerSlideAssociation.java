package org.pathlab.forge.adapt;

import org.pathlab.forge.conversion.ArtifactRevision;
import org.pathlab.forge.viewer.ViewerUploadStatus;

public record ViewerSlideAssociation(
        String datasetId,
        String viewerSlideId,
        String sha256,
        String displayName,
        String license,
        String artifactRevisionId,
        int cropX,
        int cropY,
        int cropWidth,
        int cropHeight,
        double downsample,
        int viewerWidth,
        int viewerHeight) {
    public ViewerSlideAssociation {
        datasetId = required(datasetId, "datasetId");
        viewerSlideId = required(viewerSlideId, "viewerSlideId");
        displayName = required(displayName, "displayName");
        license = required(license, "license");
        artifactRevisionId = required(artifactRevisionId, "artifactRevisionId");
        if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Viewer slide checksum is invalid");
        }
        if (cropX < 0 || cropY < 0 || cropWidth <= 0 || cropHeight <= 0
                || !Double.isFinite(downsample) || downsample <= 0
                || viewerWidth != Math.max(1, (int) Math.floor(cropWidth / downsample))
                || viewerHeight != Math.max(1, (int) Math.floor(cropHeight / downsample))) {
            throw new IllegalArgumentException("Viewer slide coordinate transform is invalid");
        }
    }

    public static ViewerSlideAssociation fromReadyUpload(
            String datasetId,
            String displayName,
            String license,
            ArtifactRevision revision,
            ViewerUploadStatus upload,
            int cropX,
            int cropY,
            int cropWidth,
            int cropHeight,
            double downsample) {
        if (!"READY_PRIVATE".equals(upload.state())
                || upload.viewerSlideId().isBlank()
                || !revision.id().equals(upload.artifactRevisionId())) {
            throw new IllegalArgumentException("Viewer did not report an exact ready slide association");
        }
        var expectedChecksum = switch (upload.uploadMode()) {
            case "PREPARED_V2" -> revision.packageSha256();
            case "OME_DYNAMIC" -> revision.omeSha256();
            default -> throw new IllegalArgumentException("Viewer upload mode is invalid");
        };
        if (!upload.viewerSlideSha256().matches("[a-f0-9]{64}")
                || !upload.viewerSlideSha256().equals(expectedChecksum)) {
            throw new IllegalArgumentException("Viewer persisted slide checksum did not match upload");
        }
        return new ViewerSlideAssociation(
                datasetId,
                upload.viewerSlideId(),
                upload.viewerSlideSha256(),
                displayName,
                license,
                revision.id(),
                cropX,
                cropY,
                cropWidth,
                cropHeight,
                downsample,
                Math.max(1, (int) Math.floor(cropWidth / downsample)),
                Math.max(1, (int) Math.floor(cropHeight / downsample)));
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 4096
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value.trim();
    }
}

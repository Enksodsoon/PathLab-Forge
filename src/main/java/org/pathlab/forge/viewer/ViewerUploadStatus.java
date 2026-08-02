package org.pathlab.forge.viewer;

import java.util.Objects;

public record ViewerUploadStatus(
        String state,
        String artifactRevisionId,
        long uploadedBytes,
        long totalBytes,
        String viewerSlideId,
        String viewerSlideSha256,
        String uploadMode,
        String detail) {
    public ViewerUploadStatus {
        state = Objects.requireNonNull(state, "state");
        artifactRevisionId = Objects.requireNonNull(artifactRevisionId, "artifactRevisionId");
        viewerSlideId = Objects.requireNonNull(viewerSlideId, "viewerSlideId");
        viewerSlideSha256 = Objects.requireNonNull(viewerSlideSha256, "viewerSlideSha256");
        uploadMode = Objects.requireNonNull(uploadMode, "uploadMode");
        detail = Objects.requireNonNull(detail, "detail");
        if (uploadedBytes < 0 || totalBytes < 0 || uploadedBytes > totalBytes) {
            throw new IllegalArgumentException("Viewer upload progress is invalid");
        }
    }

    public static ViewerUploadStatus idle() {
        return new ViewerUploadStatus(
                "IDLE", "", 0, 0, "", "", "", "No Viewer upload is active");
    }
}

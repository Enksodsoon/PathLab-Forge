package org.pathlab.forge.viewer;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ViewerRemoteSlide(
        String id,
        String displayName,
        String folderId,
        String status,
        long contentRevision,
        long annotationRevision,
        long metadataRevision,
        long folderRevision,
        String thumbnailUrl,
        String tileUrl,
        long contentBytes,
        String contentSha256,
        Map<String, Object> metadata,
        Instant updatedAt) {
    public ViewerRemoteSlide(String id, String displayName, String folderId, String status,
            long contentRevision, long annotationRevision, long metadataRevision,
            long folderRevision, String thumbnailUrl, String tileUrl, Instant updatedAt) {
        this(id, displayName, folderId, status, contentRevision, annotationRevision,
                metadataRevision, folderRevision, thumbnailUrl, tileUrl, 0, "", Map.of(), updatedAt);
    }

    public ViewerRemoteSlide {
        Objects.requireNonNull(id);
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(folderId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(thumbnailUrl);
        Objects.requireNonNull(tileUrl);
        Objects.requireNonNull(contentSha256);
        metadata = Map.copyOf(metadata);
        Objects.requireNonNull(updatedAt);
        if (contentRevision < 0 || annotationRevision < 0 || metadataRevision < 0
                || folderRevision < 0 || contentBytes < 0) {
            throw new IllegalArgumentException("Remote revisions must be non-negative");
        }
    }
}

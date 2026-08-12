package org.pathlab.forge.viewer;

import java.time.Instant;
import java.util.Objects;

public record ViewerDeliveryJob(
        String id,
        String datasetId,
        String artifactRevisionId,
        String artifactSha256,
        long artifactBytes,
        String viewerOrigin,
        String ingestId,
        String uploadUri,
        long confirmedOffset,
        ViewerDeliveryState state,
        String remoteSlideId,
        String resultBundleId,
        String resultSha256,
        long resultBytes,
        Instant firstFailureAt,
        Instant nextRetryAt,
        int retryCount,
        String detail,
        Instant updatedAt) {

    public ViewerDeliveryJob {
        Objects.requireNonNull(id);
        Objects.requireNonNull(datasetId);
        Objects.requireNonNull(artifactRevisionId);
        Objects.requireNonNull(artifactSha256);
        Objects.requireNonNull(viewerOrigin);
        Objects.requireNonNull(ingestId);
        Objects.requireNonNull(uploadUri);
        Objects.requireNonNull(state);
        Objects.requireNonNull(remoteSlideId);
        Objects.requireNonNull(resultBundleId);
        Objects.requireNonNull(resultSha256);
        Objects.requireNonNull(firstFailureAt);
        Objects.requireNonNull(nextRetryAt);
        Objects.requireNonNull(detail);
        Objects.requireNonNull(updatedAt);
        if (artifactBytes < 0 || confirmedOffset < 0 || confirmedOffset > artifactBytes
                || resultBytes < 0 || retryCount < 0) {
            throw new IllegalArgumentException("Delivery counters must be bounded and non-negative");
        }
    }

    public static ViewerDeliveryJob queued(
            String id,
            String datasetId,
            String revisionId,
            String sha256,
            long bytes,
            String viewerOrigin,
            Instant now) {
        return new ViewerDeliveryJob(
                id, datasetId, revisionId, sha256, bytes, viewerOrigin,
                "", "", 0, ViewerDeliveryState.QUEUED, "", "", "", 0,
                Instant.EPOCH, Instant.EPOCH, 0, "Queued for private delivery", now);
    }

    public ViewerDeliveryJob withState(ViewerDeliveryState nextState, String nextDetail, Instant now) {
        return new ViewerDeliveryJob(
                id, datasetId, artifactRevisionId, artifactSha256, artifactBytes,
                viewerOrigin, ingestId, uploadUri, confirmedOffset, nextState,
                remoteSlideId, resultBundleId, resultSha256, resultBytes,
                firstFailureAt, nextRetryAt, retryCount, nextDetail, now);
    }

    public ViewerDeliveryJob withIngest(String nextIngestId, String nextUploadUri, Instant now) {
        return new ViewerDeliveryJob(
                id, datasetId, artifactRevisionId, artifactSha256, artifactBytes,
                viewerOrigin, nextIngestId, nextUploadUri, confirmedOffset,
                ViewerDeliveryState.UPLOADING_OME, remoteSlideId, resultBundleId,
                resultSha256, resultBytes, firstFailureAt, nextRetryAt, retryCount,
                "Uploading verified OME-TIFF", now);
    }

    public ViewerDeliveryJob withOffset(long nextOffset, ViewerDeliveryState nextState,
            String nextDetail, Instant now) {
        return new ViewerDeliveryJob(
                id, datasetId, artifactRevisionId, artifactSha256, artifactBytes,
                viewerOrigin, ingestId, uploadUri, nextOffset, nextState,
                remoteSlideId, resultBundleId, resultSha256, resultBytes,
                firstFailureAt, nextRetryAt, retryCount, nextDetail, now);
    }

    public ViewerDeliveryJob imageReady(String slideId, Instant now) {
        return new ViewerDeliveryJob(
                id, datasetId, artifactRevisionId, artifactSha256, artifactBytes,
                viewerOrigin, ingestId, uploadUri, artifactBytes,
                ViewerDeliveryState.IMAGE_READY, slideId, resultBundleId, resultSha256,
                resultBytes, firstFailureAt, nextRetryAt, retryCount,
                "Private image is ready", now);
    }

    public ViewerDeliveryJob withResults(
            String bundleId,
            String bundleSha256,
            long bundleBytes,
            ViewerDeliveryState nextState,
            String nextDetail,
            Instant now) {
        return new ViewerDeliveryJob(
                id, datasetId, artifactRevisionId, artifactSha256, artifactBytes,
                viewerOrigin, ingestId, uploadUri, confirmedOffset, nextState,
                remoteSlideId, bundleId, bundleSha256, bundleBytes,
                firstFailureAt, nextRetryAt, retryCount, nextDetail, now);
    }

    public ViewerDeliveryJob retrying(Instant firstFailure, Instant retryAt,
            int nextRetryCount, String nextDetail, Instant now) {
        return new ViewerDeliveryJob(
                id, datasetId, artifactRevisionId, artifactSha256, artifactBytes,
                viewerOrigin, ingestId, uploadUri, confirmedOffset,
                ViewerDeliveryState.RETRYING, remoteSlideId, resultBundleId,
                resultSha256, resultBytes, firstFailure, retryAt, nextRetryCount,
                nextDetail, now);
    }
}

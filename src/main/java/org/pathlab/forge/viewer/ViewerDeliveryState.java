package org.pathlab.forge.viewer;

public enum ViewerDeliveryState {
    QUEUED,
    UPLOADING_OME,
    VERIFYING_OME,
    IMAGE_READY,
    SYNCING_RESULTS,
    COMPLETE,
    RETRYING,
    PAUSED,
    FAILED,
    CANCELLED
}

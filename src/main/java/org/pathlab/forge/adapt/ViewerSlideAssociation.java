package org.pathlab.forge.adapt;

public record ViewerSlideAssociation(
        String datasetId,
        String viewerSlideId,
        String sha256,
        String displayName,
        String license,
        String artifactRevisionId) {
    public ViewerSlideAssociation {
        datasetId = required(datasetId, "datasetId");
        viewerSlideId = required(viewerSlideId, "viewerSlideId");
        displayName = required(displayName, "displayName");
        license = required(license, "license");
        artifactRevisionId = required(artifactRevisionId, "artifactRevisionId");
        if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Viewer slide checksum is invalid");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 4096
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value.trim();
    }
}

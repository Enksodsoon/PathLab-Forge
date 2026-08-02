package org.pathlab.forge.adapt;

public record PivotApproval(
        String datasetId, String manifestId, String sourceFingerprint, String inputRevision,
        String approvedBy, long approvedAt) {
    public PivotApproval {
        datasetId = required(datasetId, "datasetId");
        manifestId = required(manifestId, "manifestId");
        sourceFingerprint = required(sourceFingerprint, "sourceFingerprint");
        inputRevision = required(inputRevision, "inputRevision");
        approvedBy = required(approvedBy, "approvedBy");
        if (approvedAt < 1) throw new IllegalArgumentException("approvedAt is invalid");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 4096
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value.trim();
    }
}

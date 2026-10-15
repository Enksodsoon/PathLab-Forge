package org.pathlab.forge.annotation;

public record AnnotationRecord(
        String id,
        String type,
        String geometry,
        String label,
        String color,
        long createdAt,
        String parentId,
        String classification,
        long updatedAt,
        long revision,
        int series,
        int z,
        int t,
        String viewRevision) {
    public AnnotationRecord {
        if (series < -1 || z < -1 || t < -1) {
            throw new IllegalArgumentException("Annotation view scope is invalid");
        }
        viewRevision = viewRevision == null ? "" : viewRevision;
    }

    public AnnotationRecord(
            String id, String type, String geometry, String label, String color, long createdAt,
            String parentId, String classification, long updatedAt, long revision) {
        this(id, type, geometry, label, color, createdAt, parentId, classification,
                updatedAt, revision, -1, -1, -1, "");
    }

    public AnnotationRecord(
            String id, String type, String geometry, String label, String color, long createdAt) {
        this(id, type, geometry, label, color, createdAt, "", "", createdAt, 1,
                -1, -1, -1, "");
    }
}

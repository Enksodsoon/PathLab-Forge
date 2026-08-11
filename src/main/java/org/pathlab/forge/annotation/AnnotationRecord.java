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
        long revision) {
    public AnnotationRecord(
            String id, String type, String geometry, String label, String color, long createdAt) {
        this(id, type, geometry, label, color, createdAt, "", "", createdAt, 1);
    }
}

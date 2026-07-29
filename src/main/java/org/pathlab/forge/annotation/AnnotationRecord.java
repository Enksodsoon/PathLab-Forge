package org.pathlab.forge.annotation;

public record AnnotationRecord(
        String id,
        String type,
        String geometry,
        String label,
        String color,
        long createdAt) {}

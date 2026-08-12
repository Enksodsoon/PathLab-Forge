package org.pathlab.forge.analysis;

public record AnalysisJob(
        String id,
        String moduleId,
        String datasetId,
        String annotationId,
        String status,
        int progress,
        String detail,
        long createdAt,
        long startedAt,
        long finishedAt) {}

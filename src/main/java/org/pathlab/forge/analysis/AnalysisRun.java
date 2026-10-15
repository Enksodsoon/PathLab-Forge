package org.pathlab.forge.analysis;

import java.util.Map;

public record AnalysisRun(
        String id, String datasetId, String annotationId, String tool, String status,
        long createdAt, long startedAt, long finishedAt, String detail,
        Provenance provenance, Map<String, Double> configuration,
        Map<String, Object> outputs, boolean stale) {
    public AnalysisRun {
        configuration = Map.copyOf(configuration);
        outputs = Map.copyOf(outputs);
    }

    public record Provenance(
            String sourceFingerprint, String sourceInventorySha256, String readerEngine,
            String runtimeFingerprint, String annotationGeometry, String annotationType,
            long annotationRevision, int series, int z, int t, String viewRevision,
            String configurationSha256, String algorithm, String units, Map<String, String> secondaryInputs) {
        public Provenance { secondaryInputs = Map.copyOf(secondaryInputs); }
    }
}

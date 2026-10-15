package org.pathlab.forge.analysis;

import java.util.List;

public record AnalysisReview(String runId, long revision, List<PathObject> objects, List<Double> stainVector) {
    public AnalysisReview {
        if (runId == null || runId.isBlank() || revision < 0) throw new IllegalArgumentException("Analysis review identity/revision is invalid");
        objects = objects == null ? List.of() : List.copyOf(objects);
        stainVector = stainVector == null ? List.of() : List.copyOf(stainVector);
    }
}

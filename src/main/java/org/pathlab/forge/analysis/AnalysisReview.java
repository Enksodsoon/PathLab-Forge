package org.pathlab.forge.analysis;

import java.util.List;

public record AnalysisReview(String runId, long revision, List<PathObject> objects, List<Double> stainVector) {
    public AnalysisReview { objects = List.copyOf(objects); stainVector = List.copyOf(stainVector); }
}

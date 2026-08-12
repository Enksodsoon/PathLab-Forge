package org.pathlab.forge.analysis;

import java.util.Map;

public record PathObject(
        String id,
        String datasetId,
        String parentId,
        Kind kind,
        String geometry,
        String classification,
        String sourceRunId,
        Map<String, String> properties,
        long revision) {
    public PathObject {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public enum Kind {
        ANNOTATION,
        DETECTION,
        CELL,
        NUCLEUS,
        TMA_CORE,
        AI_SUGGESTION
    }
}

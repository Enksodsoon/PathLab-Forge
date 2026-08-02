package org.pathlab.forge.adapt;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;

public final class AdaptRequestParser {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private AdaptRequestParser() {}

    public static PivotPackRequest pivotPack(byte[] body) {
        return read(body, PivotPackRequest.class);
    }

    public static PivotApprovalRequest pivotApproval(byte[] body) {
        return read(body, PivotApprovalRequest.class);
    }

    private static <T> T read(byte[] body, Class<T> type) {
        try { return JSON.readValue(body, type); }
        catch (IOException error) { throw new IllegalArgumentException("ADAPT request JSON is invalid", error); }
    }

    public record PivotPackRequest(
            String datasetId, String viewerSlideId, String packKey, int version, String title,
            String courseId, String author, String license, String revision) {}

    public record PivotApprovalRequest(String datasetId, String approvedBy) {}
}

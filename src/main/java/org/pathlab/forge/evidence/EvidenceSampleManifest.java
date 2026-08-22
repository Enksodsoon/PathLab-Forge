package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/** Provenance and permitted-use gate for one source slide or derived sample. */
public record EvidenceSampleManifest(String source, String patientGroup, String slideId,
        String sha256, String license, String taskLabel, String permittedUse,
        long bytes, boolean grandfatheredReadOnly) {
    public static final String SCHEMA = "pathlab.evidence-sample/1";
    private static final ObjectMapper JSON = new ObjectMapper();

    public static EvidenceSampleManifest load(Path path) throws IOException {
        var value = JSON.readTree(path.toFile());
        var fields = new java.util.HashSet<String>(); value.fieldNames().forEachRemaining(fields::add);
        require(fields.equals(Set.of("schema", "source", "patientGroup", "slideId", "sha256",
                "license", "taskLabel", "permittedUse", "bytes", "grandfatheredReadOnly")),
                "Evidence sample fields are invalid");
        require(SCHEMA.equals(value.path("schema").asText()), "Evidence sample schema is invalid");
        var result = new EvidenceSampleManifest(text(value, "source", 1000), text(value, "patientGroup", 160),
                text(value, "slideId", 160), text(value, "sha256", 64), text(value, "license", 240),
                text(value, "taskLabel", 160), text(value, "permittedUse", 80),
                value.path("bytes").longValue(), value.path("grandfatheredReadOnly").asBoolean(false));
        require(result.sha256.matches("[a-f0-9]{64}") && result.bytes >= 0
                        && Set.of("private-research", "benchmark-only").contains(result.permittedUse),
                "Evidence sample provenance is invalid");
        return result;
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode value, String field, int max) {
        var result = value.path(field).asText();
        require(!result.isBlank() && result.length() <= max, "Evidence sample provenance is invalid");
        return result;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

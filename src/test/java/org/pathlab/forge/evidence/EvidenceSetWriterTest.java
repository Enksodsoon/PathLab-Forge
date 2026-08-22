package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvidenceSetWriterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporaryDirectory;

    @Test
    void fusesOnlyTrustedEvidenceFromOneExactSlideRevision() throws Exception {
        var first = evidence("a", "revision-1", "cells");
        var second = evidence("a", "revision-1", "ihc");
        var output = temporaryDirectory.resolve("artifacts/set/evidence-set.json");
        var result = new EvidenceSetWriter(temporaryDirectory).write(
                output, "set-1", java.util.List.of(first, second), Instant.EPOCH);
        var manifest = JSON.readTree(output.toFile());
        assertEquals(EvidenceSetWriter.SCHEMA, manifest.path("schema").asText());
        assertEquals(2, manifest.path("bundles").size());
        assertEquals(result.manifestSha256(), manifest.path("manifestSha256").asText());
        assertFalse(manifest.path("fusion").path("serialSectionCellMatching").asBoolean(true));

        var foreign = evidence("b", "revision-1", "foreign");
        assertThrows(IllegalArgumentException.class, () -> new EvidenceSetWriter(temporaryDirectory)
                .write(output, "bad-set", java.util.List.of(first, foreign), Instant.EPOCH));
    }

    private Path evidence(String shaSeed, String revision, String pack) throws Exception {
        var unsigned = JSON.createObjectNode();
        unsigned.put("schema", EvidenceBundleWriter.SCHEMA);
        unsigned.put("bundleId", "bundle-" + pack);
        unsigned.putObject("source").put("slideSha256", shaSeed.repeat(64)).put("revision", revision);
        unsigned.putObject("pack").put("id", pack).put("capability", "cell-morphology");
        unsigned.put("status", "completed");
        unsigned.put("researchOnly", true);
        unsigned.put("notDiagnostic", true);
        unsigned.put("reviewRequired", true);
        var path = temporaryDirectory.resolve("artifacts/" + pack + "/evidence.json");
        new EvidenceBundleWriter(temporaryDirectory.resolve("signing")).write(path, unsigned);
        return path;
    }
}

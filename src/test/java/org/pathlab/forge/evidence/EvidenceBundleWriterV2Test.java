package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvidenceBundleWriterV2Test {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporaryDirectory;

    @Test
    void signsV2WithoutEmbeddingItsOwnTrustAnchor() throws Exception {
        var unsigned = JSON.createObjectNode();
        unsigned.put("schema", EvidenceBundleWriterV2.SCHEMA);
        unsigned.put("researchOnly", true);
        unsigned.put("notDiagnostic", true);
        unsigned.put("reviewRequired", true);
        unsigned.put("qualificationAttestationSha256", "a".repeat(64));
        unsigned.put("status", "completed");
        unsigned.putObject("source").put("slideSha256", "b".repeat(64)).put("revision", "r1");
        var target = temporaryDirectory.resolve("artifacts/job/evidence.json");
        new EvidenceBundleWriterV2(temporaryDirectory).write(target, unsigned);
        var signed = JSON.readTree(target.toFile());
        assertFalse(signed.path("signature").has("publicKeyDer"));
        assertTrue(EvidenceArtifactStore.isTrusted(
                temporaryDirectory, target, "b".repeat(64), "r1"));
    }
}

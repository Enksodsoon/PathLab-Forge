package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ExternalModelWorkerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void rejectsReusableEmbeddingsAndInvalidRegions() throws Exception {
        var pack = EvidencePackManifest.load(Path.of(
                "src/main/resources/evidence-packs/he-dinov2-small-v1.json"));
        var validPrefix = "{\"schema\":\"pathlab.model-worker-result/1\","
                + "\"status\":\"completed\",\"packManifestSha256\":\"" + pack.sha256() + "\",";
        assertThrows(IllegalArgumentException.class, () -> ExternalModelWorker.validateResult(
                JSON.readTree(validPrefix + "\"regions\":[],\"embeddings\":[1,2]}"), pack));
        assertThrows(IllegalArgumentException.class, () -> ExternalModelWorker.validateResult(
                JSON.readTree(validPrefix + "\"regions\":[{\"id\":\"r1\",\"stage\":\"refined\","
                        + "\"kind\":\"support\",\"x\":0,\"y\":0,\"width\":0,\"height\":1,\"score\":0.5}]}"), pack));
    }
}

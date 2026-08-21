package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvidenceBundleWriterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporaryDirectory;

    @Test
    void writesStableVerifiableSlideBoundEvidence() throws Exception {
        var unsigned = JSON.createObjectNode();
        unsigned.put("schema", "pathlab.ai-evidence/1");
        unsigned.put("bundleId", "bundle-1");
        unsigned.set("source", JSON.valueToTree(java.util.Map.of(
                "slideSha256", "a".repeat(64), "revision", "revision-1")));
        unsigned.put("researchOnly", true);
        unsigned.put("notDiagnostic", true);
        unsigned.put("reviewRequired", true);
        var output = temporaryDirectory.resolve("bundle.json");
        var result = new EvidenceBundleWriter(temporaryDirectory.resolve("keys")).write(output, unsigned);
        var stored = JSON.readTree(output.toFile());
        assertEquals(result.manifestSha256(), stored.path("manifestSha256").asText());
        var signature = stored.path("signature");
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                Base64.getUrlDecoder().decode(signature.path("publicKeyDer").asText()))));
        verifier.update(("pathlab.ai-evidence/1\n" + result.manifestSha256()).getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(signature.path("value").asText())));
    }

    @Test
    void canonicalHashDoesNotDependOnObjectInsertionOrder() throws Exception {
        var first = JSON.createObjectNode().put("z", 1).put("a", 2);
        var second = JSON.createObjectNode().put("a", 2).put("z", 1);
        assertEquals(
                EvidenceBundleWriter.sha256(EvidenceBundleWriter.canonicalBytes(first)),
                EvidenceBundleWriter.sha256(EvidenceBundleWriter.canonicalBytes(second)));
    }

    @Test
    void canonicalNumbersMatchViewerContract() throws Exception {
        var value = JSON.createObjectNode().put("whole", 100.0).put("zero", -0.0).put("part", 0.125);
        assertEquals(
                "{\"part\":0.125,\"whole\":100,\"zero\":0}",
                new String(EvidenceBundleWriter.canonicalBytes(value), StandardCharsets.UTF_8));
    }
}

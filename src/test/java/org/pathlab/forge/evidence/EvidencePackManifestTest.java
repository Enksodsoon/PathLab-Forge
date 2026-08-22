package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvidencePackManifestTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporaryDirectory;

    @Test
    void acceptsBoundedPrivateResearchPack() throws Exception {
        var path = write("private-research", "qualified", "[\"generic\",\"er\"]", "ihc-descriptive", "[\"ihc_dab\"]");
        var pack = EvidencePackManifest.load(path);
        assertEquals("ihc-descriptive-v1", pack.packId());
        assertTrue(pack.pilotEligible());
        assertTrue(pack.markers().contains("generic"));
        assertEquals("od-v1", pack.preprocessingId());
        assertEquals(64, pack.sha256().length());
    }

    @Test
    void experimentalPackRunsOnlyAsANonIdentifyingQualificationJob() throws Exception {
        var pack = EvidencePackManifest.load(write(
                "private-research", "experimental", "[\"generic\"]", "ihc-descriptive", "[\"ihc_dab\"]"));

        assertFalse(pack.pilotEligible());
        pack.requireExecutableForJob("qualification-0123abcd");
        assertThrows(IllegalArgumentException.class, () -> pack.requireExecutableForJob("staff-demo"));
        assertThrows(IllegalArgumentException.class, () -> pack.requireExecutableForJob("qualification-person-name"));
    }

    @Test
    void blocksBenchmarkOnlyPackFromPilot() throws Exception {
        var pack = EvidencePackManifest.load(write("benchmark-only", "experimental", "[]", "he-evidence", "[\"he\"]"));
        assertFalse(pack.pilotEligible());
        assertThrows(IllegalArgumentException.class, pack::requirePilotEligible);
    }

    @Test
    void acceptanceOnlyGpuPackRunsOnlyForAcceptanceIdsAndNeverBecomesPilotEligible() throws Exception {
        var value = manifest("benchmark-only", "not-evaluable", "[]", "he-evidence", "[\"he\"]")
                .replace("\"resourceEnvelope\"", "\"usageLimits\":{\"acceptanceOnly\":true},\"runtimeCompatibility\":{"
                        + "\"workerProtocol\":\"pathlab.model-worker-result/1\",\"executionProvider\":\"cuda\","
                        + "\"cuda\":\"12.6\",\"gpuArchitecture\":\"sm_61\",\"requiresExternalWorker\":true},"
                        + "\"resourceEnvelope\"");
        var path = temporaryDirectory.resolve("acceptance-only.json");
        Files.writeString(path, value);
        var pack = EvidencePackManifest.load(path);

        assertTrue(pack.acceptanceOnly());
        assertFalse(pack.pilotEligible());
        pack.requireExecutableForJob("acceptance-0123abcd");
        assertThrows(IllegalArgumentException.class, () -> pack.requireExecutableForJob("learner-job"));
    }

    @Test
    void rejectsAcceptanceOnlyPackThatClaimsExperimentalUse() throws Exception {
        var value = manifest("private-research", "experimental", "[]", "he-evidence", "[\"he\"]")
                .replace("\"resourceEnvelope\"", "\"usageLimits\":{\"acceptanceOnly\":true},\"runtimeCompatibility\":{"
                        + "\"workerProtocol\":\"pathlab.model-worker-result/1\",\"executionProvider\":\"cuda\","
                        + "\"cuda\":\"12.6\",\"gpuArchitecture\":\"sm_61\",\"requiresExternalWorker\":true},"
                        + "\"resourceEnvelope\"");
        var path = temporaryDirectory.resolve("invalid-acceptance-only.json");
        Files.writeString(path, value);
        assertThrows(IllegalArgumentException.class, () -> EvidencePackManifest.load(path));
    }

    @Test
    void rejectsNetworkedOrOversizedPack() throws Exception {
        var value = manifest("private-research", "qualified", "[]", "he-evidence", "[\"he\"]")
                .replace("\"maxVramMiB\":4096", "\"maxVramMiB\":9000")
                .replace("\"network\":false", "\"network\":true");
        var path = temporaryDirectory.resolve("invalid.json");
        Files.writeString(path, value);
        assertThrows(IllegalArgumentException.class, () -> EvidencePackManifest.load(path));
    }

    @Test
    void bundledPilotCatalogActivatesOnlyInstalledLawfulBaselines() throws Exception {
        var root = Path.of("src/main/resources/evidence-packs");
        var watershed = EvidencePackManifest.load(root.resolve("cell-od-watershed-v1.json"));
        var ihc = EvidencePackManifest.load(root.resolve("ihc-descriptive-v1.json"));
        assertFalse(watershed.pilotEligible());
        assertFalse(ihc.pilotEligible());
        watershed.requireExecutableForJob("qualification-0123abcd");
        ihc.requireExecutableForJob("qualification-0123abcd");
        var dino = EvidencePackManifest.load(root.resolve("he-dinov2-small-v1.json"));
        assertFalse(dino.pilotEligible());
        assertEquals("ed25f3a31f01632728cabb09d1542f84ab7b0056",
                dino.licenseLedger().get(0).revision());
        assertEquals(7, dino.artifacts().size());
        assertEquals("ae1e99fcefd534ed978cdeb8326f08030c96e28b7a81ffcbc98a857c84d14be1",
                dino.artifacts().get(0).sha256());
        assertEquals("daa33e61d2607e23ecae29e82f274ca05c64f896f9c37061c6ca77ca81aa774b",
                dino.artifacts().stream().filter(item -> "worker".equals(item.name()))
                        .findFirst().orElseThrow().sha256());
        assertFalse(EvidencePackManifest.load(root.resolve("he-hibou-b-v1.json")).pilotEligible());
        var session0 = EvidencePackManifest.load(root.resolve("he-dinov2-small-session0-acceptance-v1.json"));
        assertTrue(session0.acceptanceOnly());
        assertFalse(session0.pilotEligible());
        session0.requireExecutableForJob("acceptance-0123abcd");
        var hoverNet = EvidencePackManifest.load(root.resolve("cell-hovernet-fast-v1.json"));
        assertFalse(hoverNet.pilotEligible());
        assertEquals("cuda", hoverNet.runtimeCompatibility().executionProvider());
        assertEquals(2, hoverNet.licenseLedger().size());
        assertFalse(EvidencePackManifest.load(root.resolve("he-gigapath-benchmark-v1.json")).pilotEligible());
    }

    @Test
    void everyBundledPackResolvesItsModelCard() throws Exception {
        var root = Path.of("src/main/resources/evidence-packs");
        try (var manifests = Files.list(root)) {
            for (var manifest : manifests.filter(path -> path.toString().endsWith(".json")).toList()) {
                var card = JSON.readTree(manifest.toFile()).path("validation").path("modelCard").asText();
                assertTrue(!card.isBlank() && Files.isRegularFile(Path.of(card)),
                        () -> manifest.getFileName() + " references a missing model card: " + card);
            }
        }
    }

    private Path write(String use, String status, String markers, String capability, String stains) throws Exception {
        var path = temporaryDirectory.resolve(java.util.UUID.randomUUID() + ".json");
        Files.writeString(path, manifest(use, status, markers, capability, stains));
        return path;
    }

    private static String manifest(String use, String status, String markers, String capability, String stains) {
        return "{\"schema\":\"pathlab.ai-pack/1\",\"packId\":\"ihc-descriptive-v1\",\"version\":\"1\"," +
                "\"capability\":\"" + capability + "\",\"acceptedStains\":" + stains + "," +
                "\"preprocessing\":{\"id\":\"od-v1\",\"tilePixels\":512},\"artifacts\":[]," +
                "\"rights\":{\"license\":\"internal\",\"allowedUse\":\"" + use + "\"," +
                "\"redistributable\":false,\"derivativesAllowed\":false,\"reviewedAt\":\"2026-08-22T00:00:00Z\"}," +
                "\"resourceEnvelope\":{\"maxRamMiB\":4096,\"maxVramMiB\":4096,\"maxSeconds\":300,\"network\":false}," +
                "\"validation\":{\"status\":\"" + status + "\",\"modelCard\":\"model.md\",\"heldOutEvaluation\":\"evaluation.json\"}," +
                "\"outputSchema\":\"pathlab.ai-evidence/1\",\"markers\":" + markers + "}";
    }
}

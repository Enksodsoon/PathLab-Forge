package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvidencePackManifestTest {
    @TempDir Path temporaryDirectory;

    @Test
    void acceptsBoundedPrivateResearchPack() throws Exception {
        var path = write("private-research", "experimental", "[\"generic\",\"er\"]", "ihc-descriptive", "[\"ihc_dab\"]");
        var pack = EvidencePackManifest.load(path);
        assertEquals("ihc-descriptive-v1", pack.packId());
        assertTrue(pack.pilotEligible());
        assertTrue(pack.markers().contains("generic"));
        assertEquals("od-v1", pack.preprocessingId());
        assertEquals(64, pack.sha256().length());
    }

    @Test
    void blocksBenchmarkOnlyPackFromPilot() throws Exception {
        var pack = EvidencePackManifest.load(write("benchmark-only", "experimental", "[]", "he-evidence", "[\"he\"]"));
        assertFalse(pack.pilotEligible());
        assertThrows(IllegalArgumentException.class, pack::requirePilotEligible);
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
        assertTrue(EvidencePackManifest.load(root.resolve("cell-od-watershed-v1.json")).pilotEligible());
        assertTrue(EvidencePackManifest.load(root.resolve("ihc-descriptive-v1.json")).pilotEligible());
        assertFalse(EvidencePackManifest.load(root.resolve("he-dinov2-small-v1.json")).pilotEligible());
        assertFalse(EvidencePackManifest.load(root.resolve("he-hibou-b-v1.json")).pilotEligible());
        assertFalse(EvidencePackManifest.load(root.resolve("he-gigapath-benchmark-v1.json")).pilotEligible());
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

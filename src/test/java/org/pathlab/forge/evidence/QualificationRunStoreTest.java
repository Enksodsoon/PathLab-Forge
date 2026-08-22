package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QualificationRunStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void persistsSignedAttestationAndComputesTargetWithoutChangingCandidateManifest() throws Exception {
        var packPath = temporaryDirectory.resolve("pack.json");
        Files.writeString(packPath, deploymentPack());
        Files.writeString(temporaryDirectory.resolve("request.json"),
                "{\"packManifest\":" + new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(packPath.toAbsolutePath().toString()) + "}");
        var campaignPath = temporaryDirectory.resolve("campaign.json");
        Files.writeString(campaignPath, QualificationCampaignManifestTest.campaign("request.json"));
        var campaign = QualificationCampaignManifest.load(campaignPath);
        var state = Files.createDirectories(temporaryDirectory.resolve("state"));
        try (var store = new QualificationRunStore(state.resolve("jobs.sqlite3"), state)) {
            var created = store.create(campaign, Instant.parse("2026-08-22T00:00:00Z"));
            assertFalse(created.campaignCompleted());
            var report = temporaryDirectory.resolve("report.json");
            var unsigned = QualificationReportValidatorTest.report(campaign);
            unsigned.put("packManifestSha256", EvidencePackManifest.load(packPath).sha256());
            new QualificationReportWriter(state).write(report, unsigned);
            var completed = store.attachAttestation(campaign.campaignId(), "dinov2", report,
                    Instant.parse("2026-08-22T00:01:00Z"));
            assertTrue(completed.campaignCompleted());
            assertTrue(completed.campaignTargetMet());
            assertEquals("qualified", completed.tracks().get(0).verdict());
            assertEquals(campaign.sha256(), completed.manifestSha256());
            new QualifiedPackRegistry(state).requireQualified(EvidencePackManifest.load(packPath),
                    completed.tracks().get(0).artifactSha256());
        }
    }

    private static String deploymentPack() {
        return """
                {"schema":"pathlab.ai-pack/2","packId":"dinov2-small","version":"1.0.0",
                "capability":"he-evidence","scope":"deployment","activationEligible":true,
                "acceptedStains":["he"],"preprocessing":{"id":"frozen","tilePixels":512},
                "artifacts":[],"runtimeCompatibility":{"workerProtocol":"pathlab.model-worker-result/2",
                "executionProvider":"cpu","cuda":"none","gpuArchitecture":"none",
                "requiresExternalWorker":false,"segmentMaxSeconds":120},
                "licenseLedger":[{"component":"test","kind":"weights","license":"test-only",
                "revision":"fixed","permittedUse":"private-research","redistributable":false,
                "derivativesAllowed":true}],
                "derivativePermissions":{"atlasResearch":true,"atlasClean":true,
                "reviewedAt":"2026-08-22T00:00:00Z"},
                "resourceEnvelope":{"lane":"cpu_io","maxRamMiB":1024,"maxVramMiB":0,
                "maxSeconds":120,"network":false},
                "qualificationPolicy":{"requiresSignedAttestation":true,
                "protocolSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                "outputSchema":"pathlab.model-qualification-report/2","markers":[]}
                """;
    }
}

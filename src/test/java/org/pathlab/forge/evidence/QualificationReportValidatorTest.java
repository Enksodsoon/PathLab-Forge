package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QualificationReportValidatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporaryDirectory;

    @Test
    void trustsPinnedSignerInsteadOfPayloadSuppliedKey() throws Exception {
        Files.writeString(temporaryDirectory.resolve("request.json"), "{}");
        var campaignPath = temporaryDirectory.resolve("campaign.json");
        Files.writeString(campaignPath, QualificationCampaignManifestTest.campaign("request.json"));
        var campaign = QualificationCampaignManifest.load(campaignPath);
        var unsigned = report(campaign);
        var reportPath = temporaryDirectory.resolve("report.json");
        new QualificationReportWriter(temporaryDirectory.resolve("state")).write(reportPath, unsigned);

        var result = new QualificationReportValidator(temporaryDirectory.resolve("state"))
                .validate(reportPath, campaign, campaign.tracks().get(0));
        assertEquals("qualified", result.verdict());

        Files.delete(temporaryDirectory.resolve("state/trust/approved-signers.json"));
        assertThrows(IllegalArgumentException.class, () ->
                new QualificationReportValidator(temporaryDirectory.resolve("state"))
                        .validate(reportPath, campaign, campaign.tracks().get(0)));
    }

    static ObjectNode report(QualificationCampaignManifest campaign) {
        var track = campaign.tracks().get(0);
        var root = JSON.createObjectNode();
        root.put("schema", "pathlab.model-qualification-report/2");
        root.put("campaignId", campaign.campaignId());
        root.put("campaignManifestSha256", campaign.sha256());
        root.put("trackId", track.id());
        root.put("candidateId", track.candidateId());
        root.put("capability", track.capability());
        root.put("scope", track.scope());
        root.put("packManifestSha256", "b".repeat(64));
        root.put("protocolSha256", track.protocolSha256());
        root.put("generatedAt", "2026-08-22T00:00:00Z");
        root.put("researchOnly", true);
        root.put("notDiagnostic", true);
        root.put("status", "qualified");
        var checks = root.putArray("checks");
        checks.addObject().put("id", "determinism").put("outcome", "pass");
        root.putArray("reasons");
        return root;
    }
}

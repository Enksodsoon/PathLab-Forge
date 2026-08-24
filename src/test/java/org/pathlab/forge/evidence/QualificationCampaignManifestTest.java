package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QualificationCampaignManifestTest {
    @TempDir Path temporaryDirectory;

    @Test
    void loadsFrozenAllRounderCampaignWithoutAllowingPathEscape() throws Exception {
        Files.writeString(temporaryDirectory.resolve("request.json"), "{}");
        var manifest = temporaryDirectory.resolve("campaign.json");
        Files.writeString(manifest, campaign("request.json"));

        var loaded = QualificationCampaignManifest.load(manifest);
        assertEquals("all-rounder-1", loaded.campaignId());
        assertEquals(1, loaded.maxRemediationAttempts());
        assertEquals("he-evidence", loaded.tracks().get(0).capability());

        Files.writeString(manifest, campaign("../request.json"));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationCampaignManifest.load(manifest));
    }

    static String campaign(String requestPath) {
        return """
                {"schema":"pathlab.qualification-campaign/1","campaignId":"all-rounder-1",
                 "createdAt":"2026-08-22T00:00:00Z","researchOnly":true,"notDiagnostic":true,
                 "maxRemediationAttempts":1,
                 "quota":{"sourceBytes":48318382080,"derivedBytes":26843545600,
                 "modelBytes":10737418240,"evidenceBytes":10737418240,"reserveBytes":10737418240},
                 "tracks":[{"id":"dinov2","candidateId":"dinov2-small","capability":"he-evidence",
                 "scope":"deployment","requestPath":"%s","remediationRequestPath":null,
                 "expectedAttestationPath":null,
                 "protocolSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "dependsOn":[],"required":true}]}
                """.formatted(requestPath.replace("\\", "\\\\"));
    }
}

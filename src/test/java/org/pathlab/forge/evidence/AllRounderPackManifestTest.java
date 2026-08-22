package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class AllRounderPackManifestTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void deterministicV2CandidatesPinTheirPreregisteredProtocols() throws Exception {
        for (var id : java.util.List.of("cell-od-watershed", "ihc-descriptive",
                "special-stain-descriptive", "cytology-descriptive")) {
            var packPath = Path.of("src/main/resources/evidence-packs/" + id + "-v2.json");
            var protocolPath = Path.of("qualification-protocols/" + id + "-v2.json");
            var pack = EvidencePackManifest.load(packPath);
            var raw = JSON.readTree(packPath.toFile());
            // Git may materialize CRLF on Windows. Protocol identities use the
            // repository-canonical UTF-8/LF representation on every platform.
            var canonicalProtocol = Files.readString(protocolPath, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            var protocolSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalProtocol.getBytes(StandardCharsets.UTF_8)));
            assertEquals(protocolSha, raw.path("qualificationPolicy").path("protocolSha256").asText());
            assertEquals(EvidencePackManifest.SCHEMA_V2, pack.schema());
            assertTrue(pack.activationEligible());
            assertEquals("deployment", pack.scope());
        }
    }
}

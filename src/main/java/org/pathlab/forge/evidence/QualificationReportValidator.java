package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Strict verifier for signed, non-publishable qualification attestations. */
public final class QualificationReportValidator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> VERDICTS = Set.of("qualified", "experimental", "unsupported", "not_evaluable");
    private final TrustedSignerRegistry registry;

    public QualificationReportValidator(Path stateRoot) {
        registry = new TrustedSignerRegistry(stateRoot);
    }

    public Validated validate(Path path, QualificationCampaignManifest campaign,
            QualificationCampaignManifest.Track track) throws IOException {
        var normalized = path.toAbsolutePath().normalize();
        require(Files.isRegularFile(normalized) && Files.size(normalized) <= 2L * 1024 * 1024,
                "Qualification report is unavailable or too large");
        var root = JSON.readTree(normalized.toFile());
        exact(root, Set.of("schema", "campaignId", "campaignManifestSha256", "trackId", "candidateId",
                "capability", "scope", "packManifestSha256", "protocolSha256", "generatedAt",
                "researchOnly", "notDiagnostic", "status", "checks", "reasons",
                "manifestSha256", "signature"));
        require(QualificationReportWriter.SCHEMA.equals(root.path("schema").asText()),
                "Qualification report schema is unsupported");
        require(campaign.campaignId().equals(root.path("campaignId").asText())
                        && campaign.sha256().equals(root.path("campaignManifestSha256").asText()),
                "Qualification report campaign identity changed");
        require(track.id().equals(root.path("trackId").asText())
                        && track.candidateId().equals(root.path("candidateId").asText())
                        && track.capability().equals(root.path("capability").asText())
                        && track.scope().equals(root.path("scope").asText()),
                "Qualification report track identity changed");
        require(track.protocolSha256().equals(root.path("protocolSha256").asText()),
                "Qualification protocol checksum changed");
        require(root.path("packManifestSha256").asText().matches("[a-f0-9]{64}"),
                "Qualification pack checksum is invalid");
        Instant.parse(root.path("generatedAt").asText());
        require(root.path("researchOnly").asBoolean(false) && root.path("notDiagnostic").asBoolean(false),
                "Qualification report safety boundary is invalid");
        var verdict = root.path("status").asText();
        require(VERDICTS.contains(verdict), "Qualification verdict is invalid");
        require(root.path("checks").isArray() && !root.path("checks").isEmpty()
                        && root.path("checks").size() <= 200, "Qualification checks are invalid");
        require(root.path("reasons").isArray() && root.path("reasons").size() <= 100,
                "Qualification reasons are invalid");
        var suppliedSha = root.path("manifestSha256").asText();
        require(suppliedSha.matches("[a-f0-9]{64}"), "Qualification report checksum is invalid");
        var unsigned = ((ObjectNode) root.deepCopy());
        unsigned.remove(Set.of("manifestSha256", "signature"));
        var calculated = EvidenceBundleWriter.sha256(EvidenceBundleWriter.canonicalBytes(unsigned));
        require(suppliedSha.equals(calculated), "Qualification report checksum mismatch");
        verify(root.path("signature"), suppliedSha);
        return new Validated(verdict, suppliedSha, root.path("packManifestSha256").asText());
    }

    private void verify(JsonNode signature, String manifestSha) throws IOException {
        exact(signature, Set.of("algorithm", "keyId", "value"));
        require("Ed25519".equals(signature.path("algorithm").asText()), "Qualification signature is invalid");
        var keyId = signature.path("keyId").asText();
        require(keyId.matches("[a-f0-9]{64}"), "Qualification signer id is invalid");
        var publicBytes = registry.find(keyId, "qualification")
                .orElseThrow(() -> new IllegalArgumentException("Qualification signer is not trusted"));
        try {
            var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicBytes));
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update((QualificationReportWriter.SCHEMA + "\n" + manifestSha)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var encoded = java.util.Base64.getUrlDecoder().decode(signature.path("value").asText());
            require(verifier.verify(encoded), "Qualification signature is invalid");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Qualification signature is invalid", error);
        }
    }

    private static void exact(JsonNode value, Set<String> expected) {
        require(value != null && value.isObject(), "Qualification report object is invalid");
        var actual = new HashSet<String>();
        value.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(expected), "Qualification report fields are invalid");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record Validated(String verdict, String manifestSha256, String packManifestSha256) { }
}

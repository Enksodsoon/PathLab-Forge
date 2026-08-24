package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

/** Finds a signed terminal artifact bound to one exact slide revision. */
public final class EvidenceArtifactStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TERMINAL = Set.of("completed", "partial", "abstained");

    private EvidenceArtifactStore() {}

    static boolean isTrusted(Path stateRoot, Path path, String slideSha256, String revision) {
        return matches(stateRoot.toAbsolutePath().normalize(), path.toAbsolutePath().normalize(),
                slideSha256, revision);
    }

    public static Optional<Path> find(Path stateRoot, String slideSha256, String revision)
            throws IOException {
        var artifacts = stateRoot.toAbsolutePath().normalize().resolve("artifacts");
        if (!Files.isDirectory(artifacts)) return Optional.empty();
        try (var paths = Files.walk(artifacts, 3)) {
            return paths.filter(path -> path.getFileName().toString().equals("evidence.json"))
                    .filter(Files::isRegularFile)
                    .limit(2_000)
                    .filter(path -> matches(stateRoot, path, slideSha256, revision))
                    .max(Comparator.comparingLong(EvidenceArtifactStore::modified));
        }
    }

    private static boolean matches(Path stateRoot, Path path, String sha, String revision) {
        try {
            var root = JSON.readTree(path.toFile());
            if (!Set.of("pathlab.ai-evidence/1", "pathlab.ai-evidence/2")
                    .contains(root.path("schema").asText())) return false;
            if (!sha.equals(root.path("source").path("slideSha256").asText())
                    || !revision.equals(root.path("source").path("revision").asText())
                    || !TERMINAL.contains(root.path("status").asText())
                    || root.path("qualificationOnly").asBoolean(false)
                    || !root.path("manifestSha256").asText().matches("[a-f0-9]{64}")
                    || !"Ed25519".equals(root.path("signature").path("algorithm").asText())) return false;
            var unsigned = root.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) unsigned).remove("manifestSha256");
            ((com.fasterxml.jackson.databind.node.ObjectNode) unsigned).remove("signature");
            var expected = EvidenceBundleWriter.sha256(EvidenceBundleWriter.canonicalBytes(unsigned));
            if (!expected.equals(root.path("manifestSha256").asText())) return false;
            var keyId = root.path("signature").path("keyId").asText();
            var key = new TrustedSignerRegistry(stateRoot).find(keyId, "evidence");
            if (key.isEmpty()) return false;
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(key.get())));
            verifier.update((root.path("schema").asText() + "\n" + expected)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return verifier.verify(Base64.getUrlDecoder().decode(
                    root.path("signature").path("value").asText()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ignored) { return Long.MIN_VALUE; }
    }
}

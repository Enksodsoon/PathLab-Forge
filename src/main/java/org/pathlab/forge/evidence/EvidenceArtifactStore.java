package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

/** Finds a signed terminal artifact bound to one exact slide revision. */
public final class EvidenceArtifactStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TERMINAL = Set.of("completed", "partial", "abstained");

    private EvidenceArtifactStore() {}

    public static Optional<Path> find(Path stateRoot, String slideSha256, String revision)
            throws IOException {
        var artifacts = stateRoot.toAbsolutePath().normalize().resolve("artifacts");
        if (!Files.isDirectory(artifacts)) return Optional.empty();
        try (var paths = Files.walk(artifacts, 3)) {
            return paths.filter(path -> path.getFileName().toString().equals("evidence.json"))
                    .filter(Files::isRegularFile)
                    .limit(2_000)
                    .filter(path -> matches(path, slideSha256, revision))
                    .max(Comparator.comparingLong(EvidenceArtifactStore::modified));
        }
    }

    private static boolean matches(Path path, String sha, String revision) {
        try {
            var root = JSON.readTree(path.toFile());
            return "pathlab.ai-evidence/1".equals(root.path("schema").asText())
                    && sha.equals(root.path("source").path("slideSha256").asText())
                    && revision.equals(root.path("source").path("revision").asText())
                    && TERMINAL.contains(root.path("status").asText())
                    && root.path("manifestSha256").asText().matches("[a-f0-9]{64}")
                    && "Ed25519".equals(root.path("signature").path("algorithm").asText());
        } catch (IOException ignored) {
            return false;
        }
    }

    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ignored) { return Long.MIN_VALUE; }
    }
}

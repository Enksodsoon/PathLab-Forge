package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvidenceArtifactStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void selectsOnlyExactSlideRevisionAndTerminalSignedArtifact() throws Exception {
        write("old", "a".repeat(64), "revision-old", "completed");
        var exact = write("exact", "a".repeat(64), "revision-1", "completed");
        write("running", "a".repeat(64), "revision-1", "running");

        assertEquals(exact, EvidenceArtifactStore.find(
                temporaryDirectory, "a".repeat(64), "revision-1").orElseThrow());
        assertTrue(EvidenceArtifactStore.find(
                temporaryDirectory, "b".repeat(64), "revision-1").isEmpty());
    }

    @Test
    void rejectsTamperingAndUntrustedEmbeddedKeys() throws Exception {
        var exact = write("trusted", "a".repeat(64), "revision-1", "completed");
        var json = new ObjectMapper().readTree(exact.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("source"))
                .put("revision", "revision-tampered");
        new ObjectMapper().writeValue(exact.toFile(), json);
        assertTrue(EvidenceArtifactStore.find(
                temporaryDirectory, "a".repeat(64), "revision-tampered").isEmpty());

        var foreignRoot = temporaryDirectory.resolve("foreign");
        var foreignPath = foreignRoot.resolve("artifacts/foreign/evidence.json");
        var unsigned = unsigned("a".repeat(64), "revision-2", "completed");
        new EvidenceBundleWriter(foreignRoot.resolve("signing")).write(foreignPath, unsigned);
        var copied = temporaryDirectory.resolve("artifacts/foreign/evidence.json");
        Files.createDirectories(copied.getParent());
        Files.copy(foreignPath, copied);
        assertTrue(EvidenceArtifactStore.find(
                temporaryDirectory, "a".repeat(64), "revision-2").isEmpty());
    }

    private Path write(String job, String sha, String revision, String status) throws Exception {
        var path = temporaryDirectory.resolve("artifacts").resolve(job).resolve("evidence.json");
        new EvidenceBundleWriter(temporaryDirectory.resolve("signing"))
                .write(path, unsigned(sha, revision, status));
        return path.toAbsolutePath().normalize();
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode unsigned(
            String sha, String revision, String status) {
        var root = new ObjectMapper().createObjectNode();
        root.put("schema", EvidenceBundleWriter.SCHEMA);
        root.put("researchOnly", true);
        root.put("notDiagnostic", true);
        root.put("reviewRequired", true);
        root.put("status", status);
        root.putObject("source").put("slideSha256", sha).put("revision", revision);
        return root;
    }
}

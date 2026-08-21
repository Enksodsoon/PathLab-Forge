package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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

    private Path write(String job, String sha, String revision, String status) throws Exception {
        var path = temporaryDirectory.resolve("artifacts").resolve(job).resolve("evidence.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"schema\":\"pathlab.ai-evidence/1\",\"source\":{" +
                "\"slideSha256\":\"" + sha + "\",\"revision\":\"" + revision + "\"}," +
                "\"status\":\"" + status + "\",\"manifestSha256\":\"" + "c".repeat(64) + "\"," +
                "\"signature\":{\"algorithm\":\"Ed25519\"}}" );
        return path.toAbsolutePath().normalize();
    }
}

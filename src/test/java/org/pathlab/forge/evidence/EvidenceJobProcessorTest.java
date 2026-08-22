package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvidenceJobProcessorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporaryDirectory;

    @Test
    void treatsMalformedRequestJsonAsPermanentValidationFailure() throws Exception {
        var request = temporaryDirectory.resolve("malformed-request.json");
        Files.writeString(request, "{not-json");
        var now = Instant.parse("2026-08-22T00:00:00Z");

        try (var queue = new EvidenceJobQueue(temporaryDirectory.resolve("state/jobs.sqlite3"))) {
            queue.submit("job-invalid", request, now);
            var claimed = queue.claimNext("worker-1", now, Duration.ofMinutes(2)).orElseThrow();

            assertThrows(IllegalArgumentException.class, () ->
                    new EvidenceJobProcessor(queue, temporaryDirectory.resolve("state"))
                            .process(claimed, "worker-1", now.plusSeconds(1)));
        }
    }

    @Test
    void treatsUndecodablePreviewAsPermanentValidationFailure() throws Exception {
        var source = temporaryDirectory.resolve("source.bin");
        Files.writeString(source, "immutable source bytes");
        var preview = temporaryDirectory.resolve("preview.png");
        Files.writeString(preview, "not an image");
        var pack = temporaryDirectory.resolve("pack.json");
        Files.writeString(pack, packJson());
        var request = request(source, preview, pack, "revision-invalid-preview");
        var now = Instant.parse("2026-08-22T00:00:00Z");

        try (var queue = new EvidenceJobQueue(temporaryDirectory.resolve("state/jobs.sqlite3"))) {
            queue.submit("job-invalid-preview", request, now);
            var claimed = queue.claimNext("worker-1", now, Duration.ofMinutes(2)).orElseThrow();

            assertThrows(IllegalArgumentException.class, () ->
                    new EvidenceJobProcessor(queue, temporaryDirectory.resolve("state"))
                            .process(claimed, "worker-1", now.plusSeconds(1)));
        }
    }

    @Test
    void signsQcEvidenceAndAbstainsForBlankPreview() throws Exception {
        var source = temporaryDirectory.resolve("blank-source.bin");
        Files.writeString(source, "immutable blank source bytes");
        var preview = temporaryDirectory.resolve("blank.png");
        var image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 16, 12);
        graphics.dispose();
        ImageIO.write(image, "png", preview.toFile());
        var pack = temporaryDirectory.resolve("blank-pack.json");
        Files.writeString(pack, packJson());
        var request = request(source, preview, pack, "revision-blank");
        var now = Instant.parse("2026-08-22T00:00:00Z");

        try (var queue = new EvidenceJobQueue(temporaryDirectory.resolve("blank-state/jobs.sqlite3"))) {
            queue.submit("job-blank", request, now);
            var claimed = queue.claimNext("worker-1", now, Duration.ofMinutes(2)).orElseThrow();
            var result = new EvidenceJobProcessor(queue, temporaryDirectory.resolve("blank-state"))
                    .process(claimed, "worker-1", now.plusSeconds(1));

            assertEquals(EvidenceJobState.ABSTAINED, result.state());
            var evidence = JSON.readTree(temporaryDirectory
                    .resolve("blank-state/artifacts/job-blank/evidence.json").toFile());
            assertEquals("abstained", evidence.path("status").asText());
            assertTrue(evidence.path("qc").path("abstentionReasons").size() > 0);
        }
    }

    @Test
    void completesOfflineDescriptiveIhcJobIntoSignedAtomicEvidence() throws Exception {
        var source = temporaryDirectory.resolve("source.bin");
        Files.writeString(source, "immutable source bytes");
        var preview = temporaryDirectory.resolve("preview.png");
        var image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 16, 12);
        graphics.setColor(new Color(70, 40, 120));
        graphics.fillRect(2, 2, 4, 4);
        graphics.setColor(new Color(130, 85, 40));
        graphics.fillRect(8, 4, 6, 5);
        graphics.dispose();
        ImageIO.write(image, "png", preview.toFile());
        var pack = temporaryDirectory.resolve("pack.json");
        Files.writeString(pack, packJson());
        var request = temporaryDirectory.resolve("request.json");
        JSON.writeValue(request.toFile(), java.util.Map.of(
                "schema", "pathlab.evidence-job/1",
                "sourcePath", source.toString(),
                "sourceSha256", sha256(source),
                "slideRevision", "revision-1",
                "previewPath", preview.toString(),
                "sourceWidth", 1600,
                "sourceHeight", 1200,
                "packManifest", pack.toString(),
                "stain", "ihc_dab",
                "marker", "ki-67"));

        var now = Instant.parse("2026-08-22T00:00:00Z");
        try (var queue = new EvidenceJobQueue(temporaryDirectory.resolve("state/jobs.sqlite3"))) {
            queue.submit("job-1", request, now);
            var claimed = queue.claimNext("worker-1", now, Duration.ofMinutes(2)).orElseThrow();
            var completed = new EvidenceJobProcessor(queue, temporaryDirectory.resolve("state"))
                    .process(claimed, "worker-1", now.plusSeconds(1));

            assertEquals(EvidenceJobState.COMPLETED, completed.state());
            var evidence = JSON.readTree(
                    temporaryDirectory.resolve("state/artifacts/job-1/evidence.json").toFile());
            assertEquals("pathlab.ai-evidence/1", evidence.path("schema").asText());
            assertEquals("ki-67", evidence.path("ihcDescriptors").get(0).path("marker").asText());
            assertEquals("relative_only", evidence.path("ihcDescriptors").get(0)
                    .path("calibrationStatus").asText());
            assertEquals("od-watershed", evidence.path("ihcDescriptors").get(0)
                    .path("cellMaskSource").asText());
            assertTrue(evidence.path("cellAggregates").get(0)
                    .path("meanNucleusPerimeterPx").asDouble() > 0);
            assertTrue(evidence.path("researchOnly").asBoolean());
            assertTrue(evidence.path("provenance").path("offlineAnalysis").asBoolean());
            assertEquals(64, evidence.path("manifestSha256").asText().length());
        }
    }

    private static String packJson() {
        return "{\"schema\":\"pathlab.ai-pack/1\",\"packId\":\"ihc-descriptive-v1\",\"version\":\"1\"," +
                "\"capability\":\"ihc-descriptive\",\"acceptedStains\":[\"ihc_dab\"]," +
                "\"preprocessing\":{\"id\":\"od-v1\",\"tilePixels\":512},\"artifacts\":[]," +
                "\"rights\":{\"license\":\"internal\",\"allowedUse\":\"private-research\"," +
                "\"redistributable\":false,\"derivativesAllowed\":false,\"reviewedAt\":\"2026-08-22T00:00:00Z\"}," +
                "\"resourceEnvelope\":{\"maxRamMiB\":1024,\"maxVramMiB\":0,\"maxSeconds\":300,\"network\":false}," +
                "\"validation\":{\"status\":\"experimental\",\"modelCard\":\"model.md\",\"heldOutEvaluation\":\"evaluation.json\"}," +
                "\"outputSchema\":\"pathlab.ai-evidence/1\",\"markers\":[\"generic\",\"ki-67\"]}";
    }

    private Path request(Path source, Path preview, Path pack, String revision) throws Exception {
        var request = temporaryDirectory.resolve(revision + "-request.json");
        JSON.writeValue(request.toFile(), java.util.Map.of(
                "schema", "pathlab.evidence-job/1",
                "sourcePath", source.toString(),
                "sourceSha256", sha256(source),
                "slideRevision", revision,
                "previewPath", preview.toString(),
                "sourceWidth", 1600,
                "sourceHeight", 1200,
                "packManifest", pack.toString(),
                "stain", "ihc_dab",
                "marker", "generic"));
        return request;
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }
}

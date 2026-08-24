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
    void routesExternalCellCandidatesThroughTheModelWorker() throws Exception {
        var external = EvidencePackManifest.load(Path.of(
                "src/main/resources/evidence-packs/cell-hovernet-fast-v1.json"));
        var deterministic = EvidencePackManifest.load(Path.of(
                "src/main/resources/evidence-packs/cell-od-watershed-v2.json"));

        assertTrue(EvidenceJobProcessor.usesExternalModelWorker(external));
        assertEquals(false, EvidenceJobProcessor.usesExternalModelWorker(deterministic));
    }

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
    void rejectsHeEvidenceWithoutChecksumBoundTileCache() throws Exception {
        var source = temporaryDirectory.resolve("he-source.bin");
        Files.writeString(source, "immutable H&E source bytes");
        var preview = temporaryDirectory.resolve("he-preview.png");
        var image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "png", preview.toFile());
        var pack = temporaryDirectory.resolve("he-pack.json");
        Files.writeString(pack, hePackJson());
        var request = temporaryDirectory.resolve("he-request.json");
        JSON.writeValue(request.toFile(), java.util.Map.of(
                "schema", "pathlab.evidence-job/1",
                "sourcePath", source.toString(),
                "sourceSha256", sha256(source),
                "slideRevision", "revision-he",
                "previewPath", preview.toString(),
                "sourceWidth", 1600,
                "sourceHeight", 1200,
                "packManifest", pack.toString(),
                "stain", "he",
                "marker", "generic"));
        var now = Instant.parse("2026-08-22T00:00:00Z");

        try (var queue = new EvidenceJobQueue(temporaryDirectory.resolve("he-state/jobs.sqlite3"))) {
            queue.submit("job-he-no-tiles", request, now);
            var claimed = queue.claimNext("worker-1", now, Duration.ofMinutes(2)).orElseThrow();
            assertThrows(IllegalArgumentException.class, () ->
                    new EvidenceJobProcessor(queue, temporaryDirectory.resolve("he-state"))
                            .process(claimed, "worker-1", now.plusSeconds(1)));
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
        JSON.writeValue(request.toFile(), java.util.Map.ofEntries(
                java.util.Map.entry("schema", "pathlab.evidence-job/1"),
                java.util.Map.entry("sourcePath", source.toString()),
                java.util.Map.entry("sourceSha256", sha256(source)),
                java.util.Map.entry("slideRevision", "revision-1"),
                java.util.Map.entry("previewPath", preview.toString()),
                java.util.Map.entry("sourceWidth", 1600),
                java.util.Map.entry("sourceHeight", 1200),
                java.util.Map.entry("packManifest", pack.toString()),
                java.util.Map.entry("stain", "ihc_dab"),
                java.util.Map.entry("marker", "ki-67"),
                java.util.Map.entry("markerIdentitySource", "slide-label")));

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
            assertEquals("marker-aware", evidence.path("ihcDescriptors").get(0)
                    .path("analysisMode").asText());
            assertEquals("slide-label", evidence.path("ihcDescriptors").get(0)
                    .path("markerIdentitySource").asText());
            assertEquals("nuclear", evidence.path("ihcDescriptors").get(0)
                    .path("compartment").asText());
            assertTrue(evidence.path("ihcDescriptors").get(0).path("abstentionReason").isNull());
            assertTrue(evidence.path("ihcDescriptors").get(0).path("measurements")
                    .has("intensityDistribution"));
            assertEquals("od-watershed", evidence.path("ihcDescriptors").get(0)
                    .path("cellMaskSource").asText());
            assertEquals("od-watershed", evidence.path("cellAggregates").get(0)
                    .path("algorithm").asText());
            assertTrue(evidence.path("cellAggregates").get(0)
                    .path("meanNucleusPerimeterPx").asDouble() > 0);
            assertTrue(evidence.path("researchOnly").asBoolean());
            assertTrue(evidence.path("provenance").path("offlineAnalysis").asBoolean());
            assertEquals(64, evidence.path("manifestSha256").asText().length());
        }
    }

    @Test
    void refusesPdL1CompartmentClaimsWithoutReviewedGeometry() throws Exception {
        var evidence = processIhcEvidence("pd-l1", "faculty-approved", "faculty-authored", "job-pdl1");
        var descriptor = evidence.path("ihcDescriptors").get(0);

        assertEquals("pd-l1", descriptor.path("markerId").asText());
        assertEquals("generic-fallback", descriptor.path("analysisMode").asText());
        assertEquals("generic-region", descriptor.path("compartment").asText());
        assertEquals("none", descriptor.path("compartmentSource").asText());
        assertEquals("COMPARTMENT_REVIEW_REQUIRED", descriptor.path("abstentionReason").asText());
        assertTrue(evidence.path("qc").path("warnings").toString()
                .contains("COMPARTMENT_REVIEW_REQUIRED"));
    }

    @Test
    void genericIhcOutputIsDescriptiveAndContainsNoClinicalScores() throws Exception {
        var evidence = processIhcEvidence("generic", "none", "unknown", "job-generic");
        var descriptor = evidence.path("ihcDescriptors").get(0);

        assertEquals("generic-descriptive", descriptor.path("analysisMode").asText());
        assertEquals("generic-region", descriptor.path("compartment").asText());
        assertTrue(descriptor.path("abstentionReason").isNull());
        for (var prohibited : java.util.List.of(
                "positive", "negative", "tps", "cps", "ascoCapCategory",
                "diagnosis", "prognosis", "treatmentGuidance")) {
            assertTrue(!descriptor.has(prohibited), () -> "Clinical output leaked: " + prohibited);
        }
    }

    @Test
    void resumesSignedIhcPackagingFromRefiningCheckpointAfterRunnerRestart() throws Exception {
        var source = temporaryDirectory.resolve("resume-source.bin");
        Files.writeString(source, "immutable resume source bytes");
        var preview = temporaryDirectory.resolve("resume-preview.png");
        var image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(70, 40, 120));
        graphics.fillRect(2, 2, 8, 7);
        graphics.dispose();
        ImageIO.write(image, "png", preview.toFile());
        var pack = temporaryDirectory.resolve("resume-pack.json");
        Files.writeString(pack, packJson());
        var request = request(source, preview, pack, "revision-resume");
        var database = temporaryDirectory.resolve("resume-state/jobs.sqlite3");
        var now = Instant.parse("2026-08-22T00:00:00Z");

        try (var queue = new EvidenceJobQueue(database)) {
            queue.submit("job-resume", request, now);
            var claimed = queue.claimNext("old-worker", now, Duration.ofMinutes(2)).orElseThrow();
            var running = queue.checkpoint(claimed.id(), "old-worker", EvidenceJobState.RUNNING,
                    "running", 0.25, "running", now.plusSeconds(1), Duration.ofMinutes(2));
            queue.checkpoint(running.id(), "old-worker", EvidenceJobState.REFINING,
                    "refining", 0.65, "refining", now.plusSeconds(2), Duration.ofMinutes(2));
        }

        try (var queue = new EvidenceJobQueue(database)) {
            queue.recoverOrphanedActiveJobs(now.plusSeconds(3));
            var recovered = queue.claimNext("new-worker", now.plusSeconds(3), Duration.ofMinutes(2)).orElseThrow();
            var completed = new EvidenceJobProcessor(queue, temporaryDirectory.resolve("resume-state"))
                    .process(recovered, "new-worker", now.plusSeconds(4));
            assertEquals(EvidenceJobState.ABSTAINED, completed.state());
            assertTrue(Files.isRegularFile(temporaryDirectory
                    .resolve("resume-state/artifacts/job-resume/evidence.json")));
        }
    }

    private static String packJson() {
        return "{\"schema\":\"pathlab.ai-pack/1\",\"packId\":\"ihc-descriptive-v1\",\"version\":\"1\"," +
                "\"capability\":\"ihc-descriptive\",\"acceptedStains\":[\"ihc_dab\"]," +
                "\"preprocessing\":{\"id\":\"od-v1\",\"tilePixels\":512},\"artifacts\":[]," +
                "\"rights\":{\"license\":\"internal\",\"allowedUse\":\"private-research\"," +
                "\"redistributable\":false,\"derivativesAllowed\":false,\"reviewedAt\":\"2026-08-22T00:00:00Z\"}," +
                "\"resourceEnvelope\":{\"maxRamMiB\":1024,\"maxVramMiB\":0,\"maxSeconds\":300,\"network\":false}," +
                "\"validation\":{\"status\":\"qualified\",\"modelCard\":\"model.md\",\"heldOutEvaluation\":\"evaluation.json\"}," +
                "\"outputSchema\":\"pathlab.ai-evidence/1\",\"markers\":[\"generic\",\"ki-67\",\"pd-l1\"]}";
    }

    private static String hePackJson() {
        return "{\"schema\":\"pathlab.ai-pack/1\",\"packId\":\"he-fixture-v1\",\"version\":\"1\","
                + "\"capability\":\"he-evidence\",\"acceptedStains\":[\"he\"],"
                + "\"preprocessing\":{\"id\":\"he-fixture-v1\",\"tilePixels\":512},\"artifacts\":[],"
                + "\"rights\":{\"license\":\"internal\",\"allowedUse\":\"private-research\","
                + "\"redistributable\":false,\"derivativesAllowed\":false,"
                + "\"reviewedAt\":\"2026-08-22T00:00:00Z\"},"
                + "\"resourceEnvelope\":{\"maxRamMiB\":1024,\"maxVramMiB\":0,"
                + "\"maxSeconds\":300,\"network\":false},"
                + "\"validation\":{\"status\":\"experimental\",\"modelCard\":\"model.md\","
                + "\"heldOutEvaluation\":\"fixture.json\"},\"outputSchema\":\"pathlab.ai-evidence/1\"}";
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

    private com.fasterxml.jackson.databind.JsonNode processIhcEvidence(
            String marker, String compartmentSource, String markerIdentitySource, String jobId) throws Exception {
        var source = temporaryDirectory.resolve(jobId + "-source.bin");
        Files.writeString(source, "immutable " + jobId + " source bytes");
        var preview = temporaryDirectory.resolve(jobId + "-preview.png");
        var image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 16, 12);
        graphics.setColor(new Color(90, 55, 35));
        graphics.fillRect(2, 2, 12, 8);
        graphics.dispose();
        ImageIO.write(image, "png", preview.toFile());
        var pack = temporaryDirectory.resolve(jobId + "-pack.json");
        Files.writeString(pack, packJson());
        var request = temporaryDirectory.resolve(jobId + "-request.json");
        JSON.writeValue(request.toFile(), java.util.Map.ofEntries(
                java.util.Map.entry("schema", "pathlab.evidence-job/1"),
                java.util.Map.entry("sourcePath", source.toString()),
                java.util.Map.entry("sourceSha256", sha256(source)),
                java.util.Map.entry("slideRevision", "revision-" + jobId),
                java.util.Map.entry("previewPath", preview.toString()),
                java.util.Map.entry("sourceWidth", 1600),
                java.util.Map.entry("sourceHeight", 1200),
                java.util.Map.entry("packManifest", pack.toString()),
                java.util.Map.entry("stain", "ihc_dab"),
                java.util.Map.entry("marker", marker),
                java.util.Map.entry("compartmentSource", compartmentSource),
                java.util.Map.entry("markerIdentitySource", markerIdentitySource)));
        var state = temporaryDirectory.resolve(jobId + "-state");
        var now = Instant.parse("2026-08-22T00:00:00Z");
        try (var queue = new EvidenceJobQueue(state.resolve("jobs.sqlite3"))) {
            queue.submit(jobId, request, now);
            var claimed = queue.claimNext("worker-1", now, Duration.ofMinutes(2)).orElseThrow();
            var completed = new EvidenceJobProcessor(queue, state)
                    .process(claimed, "worker-1", now.plusSeconds(1));
            assertEquals(EvidenceJobState.COMPLETED, completed.state());
        }
        return JSON.readTree(state.resolve("artifacts").resolve(jobId).resolve("evidence.json").toFile());
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }
}

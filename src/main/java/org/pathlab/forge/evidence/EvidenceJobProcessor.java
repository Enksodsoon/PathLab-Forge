package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import javax.imageio.ImageIO;

/** Executes bounded, network-free deterministic Evidence Mentor packs. */
public final class EvidenceJobProcessor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Set<String> REQUIRED_REQUEST_FIELDS = Set.of(
            "schema", "sourcePath", "sourceSha256", "slideRevision", "previewPath",
            "sourceWidth", "sourceHeight", "packManifest", "stain", "marker");
    private static final Set<String> OPTIONAL_REQUEST_FIELDS = Set.of(
            "compartmentSource", "controlsValidated", "markerIdentitySource",
            "tileCacheManifest", "tileCacheManifestSha256");
    private final EvidenceJobQueue queue;
    private final Path stateRoot;

    public EvidenceJobProcessor(EvidenceJobQueue queue, Path stateRoot) throws IOException {
        this.queue = queue;
        this.stateRoot = stateRoot.toAbsolutePath().normalize();
        Files.createDirectories(this.stateRoot.resolve("artifacts"));
    }

    /** Resolves the lane from the validated pack manifest; the IPC caller cannot select it. */
    public static EvidenceExecutionLane executionLane(Path requestPath) throws IOException {
        return executionPlan(requestPath).lane();
    }

    public static ExecutionPlan executionPlan(Path requestPath) throws IOException {
        return executionPlan(requestPath, "");
    }

    public static ExecutionPlan executionPlan(Path requestPath, String jobId) throws IOException {
        final JsonNode request;
        try {
            request = JSON.readTree(requestPath.toAbsolutePath().normalize().toFile());
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalArgumentException("Evidence job request JSON is invalid", invalidJson);
        }
        // Legacy v1 submissions were accepted before lane metadata existed and migrate to CPU/I/O.
        if (request == null || !request.path("packManifest").isTextual()) return new ExecutionPlan(EvidenceExecutionLane.CPU_IO, "");
        var pack = EvidencePackManifest.load(regularPath(request, "packManifest"));
        pack.requireExecutableForJob(jobId);
        return new ExecutionPlan("cuda".equals(pack.runtimeCompatibility().executionProvider())
                ? EvidenceExecutionLane.GPU : EvidenceExecutionLane.CPU_IO, pack.sha256());
    }

    public record ExecutionPlan(EvidenceExecutionLane lane, String packSha256) { }

    public EvidenceJob process(EvidenceJob job, String workerId, Instant now) throws IOException {
        final JsonNode request;
        try {
            request = JSON.readTree(job.requestPath().toFile());
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalArgumentException("Evidence job request JSON is invalid", invalidJson);
        }
        var requestFields = fieldNames(request);
        require(request.isObject() && requestFields.containsAll(REQUIRED_REQUEST_FIELDS)
                        && java.util.stream.Stream.concat(REQUIRED_REQUEST_FIELDS.stream(), OPTIONAL_REQUEST_FIELDS.stream())
                                .collect(java.util.stream.Collectors.toSet()).containsAll(requestFields),
                "Evidence job request is invalid");
        require(Set.of("pathlab.evidence-job/1", "pathlab.evidence-job/2").contains(text(request, "schema")),
                "Evidence job schema is unsupported");
        var source = regularPath(request, "sourcePath");
        var preview = regularPath(request, "previewPath");
        var expectedSha = text(request, "sourceSha256");
        require(expectedSha.matches("[a-f0-9]{64}") && expectedSha.equals(sha256(source)),
                "Evidence source checksum does not match");
        var pack = EvidencePackManifest.load(regularPath(request, "packManifest"));
        pack.requireExecutableForJob(job.id());
        var durable = queue.snapshot(job.id()).orElseThrow();
        require(durable.requestSha256().isBlank() || durable.requestSha256().equals(sha256(job.requestPath())),
                "Evidence request changed after submission");
        require(durable.packSha256().isBlank() || durable.packSha256().equals(pack.sha256()),
                "Evidence pack changed after submission");
        if (durable.checkpointPath() != null) {
            require(Files.isRegularFile(durable.checkpointPath())
                            && durable.checkpointSha256().equals(sha256(durable.checkpointPath())),
                    "Evidence checkpoint checksum does not match");
        }
        var stain = text(request, "stain");
        pack.requireStain(stain);
        var marker = text(request, "marker").toLowerCase();
        if (!pack.markers().contains(marker)) marker = "generic";
        require(pack.capability() != EvidencePackManifest.Capability.IHC_DESCRIPTIVE
                        || pack.markers().contains(marker),
                "Requested IHC marker is unsupported and generic fallback is unavailable");
        var sourceWidth = positiveInt(request, "sourceWidth");
        var sourceHeight = positiveInt(request, "sourceHeight");
        if (pack.capability() == EvidencePackManifest.Capability.HE_EVIDENCE) {
            var tileManifestPath = regularPath(request, "tileCacheManifest");
            var tileManifestSha = text(request, "tileCacheManifestSha256");
            var tileCache = EvidenceTileCacheManifest.load(tileManifestPath, tileManifestSha);
            tileCache.requireSource(expectedSha, text(request, "slideRevision"),
                    sourceWidth, sourceHeight, pack.tilePixels());
            require(tileCache.sample().bytes() == Files.size(source)
                            && "private-research".equals(tileCache.sample().permittedUse()),
                    "H&E source provenance or permitted use is invalid");
        } else {
            require(!request.has("tileCacheManifest") && !request.has("tileCacheManifestSha256"),
                    "Only H&E evidence jobs may provide a tile cache");
        }
        checkCancellation(job.id(), workerId, now);
        var active = job;
        if (active.state() == EvidenceJobState.VALIDATING || active.state() == EvidenceJobState.RUNNING) {
            active = queue.checkpoint(job.id(), workerId, EvidenceJobState.RUNNING,
                    "running", 0.25, "Running offline bounded brightfield analysis", now, LEASE);
        }

        final BufferedImage image;
        try {
            image = ImageIO.read(preview.toFile());
        } catch (javax.imageio.IIOException invalidImage) {
            throw new IllegalArgumentException("Evidence preview could not be decoded", invalidImage);
        }
        require(image != null, "Evidence preview could not be decoded");
        var analysis = BrightfieldTileAnalyzer.analyze(image, marker);
        JsonNode modelResult = null;
        if (pack.capability() == EvidencePackManifest.Capability.HE_EVIDENCE) {
            modelResult = new ExternalModelWorker(stateRoot).execute(pack, job.requestPath(), job.id(),
                    durable.checkpointPath(), progress -> {
                var current = queue.find(job.id()).orElseThrow();
                if (current.cancelRequested()) {
                    throw new CancellationException();
                }
                queue.heartbeat(job.id(), workerId, progress.completedUnits(), progress.totalUnits(),
                        progress.checkpointPath(), progress.checkpointSha256(), Instant.now(), LEASE);
            });
            require("completed".equals(modelResult.path("status").asText()),
                    "H&E model worker returned unsupported or not_evaluable");
        }
        var stainQc = BrightfieldStainQc.inspect(image,
                request.path("controlsValidated").isBoolean()
                        && request.path("controlsValidated").booleanValue());
        var tissue = tissueFraction(image);
        var focus = focus(image);
        var abstentionReasons = new java.util.ArrayList<String>();
        if (tissue < 0.05) abstentionReasons.add("insufficient_tissue");
        if (focus < 0.05) abstentionReasons.add("out_of_focus_or_uninformative_preview");
        abstentionReasons.addAll(stainQc.reasons().stream()
                .filter(reason -> !abstentionReasons.contains(reason)).toList());
        var compartmentSource = request.path("compartmentSource").isTextual()
                ? request.path("compartmentSource").textValue() : "none";
        require(Set.of("none", "faculty-authored", "faculty-approved", "model-suggested")
                .contains(compartmentSource), "Evidence compartment source is invalid");
        var markerIdentitySource = request.path("markerIdentitySource").isTextual()
                ? request.path("markerIdentitySource").textValue() : "unknown";
        require(Set.of("unknown", "faculty-authored", "slide-label", "import-metadata")
                .contains(markerIdentitySource), "Evidence marker identity source is invalid");
        // This pack has no compartment geometry and no qualified marker-specific algorithms yet.
        // A provenance label alone must never be promoted into a tumor/immune measurement claim.
        var descriptorWarning = pack.capability() != EvidencePackManifest.Capability.IHC_DESCRIPTIVE
                || "generic".equals(marker) ? null
                : "pd-l1".equals(marker)
                        ? "COMPARTMENT_REVIEW_REQUIRED"
                        : "MARKER_SPECIFIC_QUALIFICATION_REQUIRED";
        var abstained = !abstentionReasons.isEmpty();
        var refiningAt = now.plusSeconds(1);
        checkCancellation(job.id(), workerId, refiningAt);
        var refining = active;
        if (active.state() != EvidenceJobState.PACKAGING) {
            refining = queue.checkpoint(active.id(), workerId, EvidenceJobState.REFINING,
                    "refining", 0.65, "Refining deterministic region descriptors", refiningAt, LEASE);
        }
        var packagingAt = now.plusSeconds(2);
        var packaging = queue.checkpoint(refining.id(), workerId, EvidenceJobState.PACKAGING,
                "packaging", 0.9, "Signing immutable evidence bundle", packagingAt, LEASE);

        var artifactRoot = stateRoot.resolve("artifacts").resolve(job.id()).normalize();
        require(artifactRoot.startsWith(stateRoot.resolve("artifacts")), "Evidence artifact path escaped state root");
        new EvidenceQuotaManager(stateRoot).requireReservation(EvidenceQuotaManager.Bucket.EVIDENCE_TEST,
                Math.max(1, Files.size(preview)));
        Files.createDirectories(artifactRoot);
        var unsigned = evidence(request, pack, image, analysis, stainQc, modelResult, focus, tissue,
                abstentionReasons, compartmentSource, markerIdentitySource, descriptorWarning, now);
        var finalArtifact = artifactRoot.resolve("evidence.json");
        new EvidenceBundleWriter(stateRoot.resolve("signing")).write(finalArtifact, unsigned);
        queue.recordFinalArtifact(job.id(), workerId, sha256(finalArtifact), now.plusMillis(2500));
        var terminal = abstained ? EvidenceJobState.ABSTAINED : EvidenceJobState.COMPLETED;
        var detail = abstained
                ? "Signed QC evidence abstained from descriptive analysis"
                : "Signed evidence is ready for faculty review";
        return queue.checkpoint(packaging.id(), workerId, terminal,
                terminal.name().toLowerCase(), 1, detail,
                now.plusSeconds(3), LEASE);
    }

    private static ObjectNode evidence(
            JsonNode request,
            EvidencePackManifest pack,
            BufferedImage image,
            BrightfieldTileAnalyzer.Result analysis,
            BrightfieldStainQc.Result stainQc,
            JsonNode modelResult,
            double focus,
            double tissue,
            java.util.List<String> abstentionReasons,
            String compartmentSource,
            String markerIdentitySource,
            String descriptorWarning,
            Instant now) {
        var root = JSON.createObjectNode();
        root.put("schema", EvidenceBundleWriter.SCHEMA);
        root.put("bundleId", "evidence-" + java.util.UUID.randomUUID());
        root.set("source", JSON.valueToTree(java.util.Map.of(
                "slideSha256", text(request, "sourceSha256"),
                "revision", text(request, "slideRevision"))));
        root.set("pack", JSON.valueToTree(java.util.Map.of(
                "id", pack.packId(),
                "version", pack.version(),
                "manifestSha256", pack.sha256(),
                "preprocessing", pack.preprocessingId(),
                "artifacts", pack.artifacts().stream().map(EvidencePackManifest.Artifact::sha256).toList(),
                "allowedUse", pack.allowedUse(),
                "acceptanceOnly", pack.acceptanceOnly(),
                "validationStatus", pack.validationStatus().wire())));
        root.put("status", abstentionReasons.isEmpty() ? "completed" : "abstained");
        root.put("researchOnly", true);
        root.put("notDiagnostic", true);
        root.put("reviewRequired", true);
        var sourceWidth = positiveInt(request, "sourceWidth");
        var sourceHeight = positiveInt(request, "sourceHeight");
        root.set("coordinates", JSON.valueToTree(java.util.Map.of(
                "space", "source-pixel", "originX", 0, "originY", 0,
                "scaleX", (double) sourceWidth / image.getWidth(),
                "scaleY", (double) sourceHeight / image.getHeight())));
        root.set("evidence", modelResult == null
                ? JSON.valueToTree(java.util.List.of(java.util.Map.of(
                        "id", "region-1", "stage", "refined", "kind", "support",
                        "x", 0, "y", 0, "width", sourceWidth, "height", sourceHeight,
                        "score", Math.max(0, Math.min(1, 1 - analysis.dabAreaFraction())))))
                : modelResult.path("regions").deepCopy());
        var aggregate = new java.util.LinkedHashMap<String, Object>();
        aggregate.put("regionId", "region-1");
        aggregate.put("algorithm", "od-connected-components");
        aggregate.put("count", analysis.cellCount());
        aggregate.put("densityPerMm2", null);
        aggregate.put("meanNucleusAreaPx2", analysis.meanNucleusAreaPx2());
        aggregate.put("meanNucleusPerimeterPx", analysis.meanNucleusPerimeterPx());
        aggregate.put("meanNucleusEccentricity", analysis.meanNucleusEccentricity());
        aggregate.put("meanNucleusSolidity", analysis.meanNucleusSolidity());
        aggregate.put("uncertainty", 1 - Math.min(focus, tissue));
        root.set("cellAggregates", JSON.valueToTree(
                pack.capability() == EvidencePackManifest.Capability.HE_EVIDENCE
                        ? java.util.List.of() : java.util.List.of(aggregate)));
        var ihc = new java.util.ArrayList<java.util.Map<String, Object>>();
        if (pack.capability() == EvidencePackManifest.Capability.IHC_DESCRIPTIVE) {
            var descriptor = new java.util.LinkedHashMap<String, Object>();
            descriptor.put("regionId", "region-1");
            descriptor.put("markerId", analysis.marker());
            descriptor.put("marker", analysis.marker());
            descriptor.put("markerIdentitySource", markerIdentitySource);
            descriptor.put("analysisMode", "generic".equals(analysis.marker())
                    ? "generic-descriptive" : "generic-fallback");
            descriptor.put("cellMaskSource", "od-connected-components");
            descriptor.put("compartmentSource", compartmentSource);
            descriptor.put("calibrationStatus", stainQc.calibrationStatus());
            descriptor.put("compartment", "generic-region");
            descriptor.put("dabAreaFraction", analysis.dabAreaFraction());
            descriptor.put("meanDabOd", analysis.meanDabOd());
            descriptor.put("uncertainty", 1 - Math.min(focus, tissue));
            descriptor.put("abstentionReason", descriptorWarning);
            descriptor.put("researchEstimate", true);
            ihc.add(descriptor);
        }
        root.set("ihcDescriptors", JSON.valueToTree(ihc));
        root.set("citations", JSON.createArrayNode());
        var qc = new java.util.LinkedHashMap<String, Object>();
        qc.put("focus", focus);
        qc.put("tissueFraction", tissue);
        qc.put("uncertainty", 1 - Math.min(focus, tissue));
        qc.put("abstentionReasons", abstentionReasons);
        qc.put("calibrationStatus", stainQc.calibrationStatus());
        qc.put("backgroundFraction", stainQc.backgroundFraction());
        qc.put("saturationFraction", stainQc.saturationFraction());
        qc.put("stainSeparation", stainQc.separationScore());
        qc.put("warnings", descriptorWarning == null ? java.util.List.of() : java.util.List.of(descriptorWarning));
        root.set("qc", JSON.valueToTree(qc));
        root.set("provenance", JSON.valueToTree(java.util.Map.of(
                "createdAt", now.toString(), "codeRevision", "pathlab-forge-evidence-mentor-v1",
                "offlineAnalysis", true)));
        return root;
    }

    private void checkCancellation(String id, String workerId, Instant now) throws IOException {
        var current = queue.find(id).orElseThrow();
        if (current.cancelRequested()) {
            queue.checkpoint(id, workerId, EvidenceJobState.CANCELLED, "cancelled", current.progress(),
                    "Cancelled at a durable checkpoint", now, LEASE);
            throw new CancellationException();
        }
    }

    private static double tissueFraction(BufferedImage image) {
        var tissue = 0;
        for (var y = 0; y < image.getHeight(); y++) for (var x = 0; x < image.getWidth(); x++) {
            var rgb = image.getRGB(x, y);
            if (((rgb >>> 16 & 0xff) + (rgb >>> 8 & 0xff) + (rgb & 0xff)) / 3.0 < 235) tissue++;
        }
        return (double) tissue / (image.getWidth() * image.getHeight());
    }

    private static double focus(BufferedImage image) {
        double gradients = 0;
        var samples = 0;
        for (var y = 1; y < image.getHeight(); y++) for (var x = 1; x < image.getWidth(); x++) {
            var here = image.getRGB(x, y) & 0xff;
            gradients += Math.abs(here - (image.getRGB(x - 1, y) & 0xff));
            gradients += Math.abs(here - (image.getRGB(x, y - 1) & 0xff));
            samples += 2;
        }
        return Math.min(1, gradients / Math.max(1, samples) / 32.0);
    }

    private static Path regularPath(JsonNode request, String name) {
        var path = Path.of(text(request, name)).toAbsolutePath().normalize();
        require(Files.isRegularFile(path), "Evidence input is unavailable: " + name);
        return path;
    }

    private static int positiveInt(JsonNode request, String name) {
        var value = request.path(name);
        require(value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0,
                "Evidence source geometry is invalid");
        return value.intValue();
    }

    private static Set<String> fieldNames(JsonNode value) {
        var result = new java.util.HashSet<String>();
        value.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static String text(JsonNode root, String name) {
        var value = root.path(name);
        require(value.isTextual() && !value.textValue().isBlank(), "Evidence field is invalid: " + name);
        return value.textValue();
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public static final class CancellationException extends IOException {
        private static final long serialVersionUID = 1L;
        public CancellationException() { super("Evidence job was cancelled"); }
    }
}

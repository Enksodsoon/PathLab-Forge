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
    private static final long ARTIFACT_QUOTA = 10L * 1024 * 1024 * 1024;
    private static final Set<String> REQUEST_FIELDS = Set.of(
            "schema", "sourcePath", "sourceSha256", "slideRevision", "previewPath",
            "sourceWidth", "sourceHeight", "packManifest", "stain", "marker");
    private final EvidenceJobQueue queue;
    private final Path stateRoot;

    public EvidenceJobProcessor(EvidenceJobQueue queue, Path stateRoot) throws IOException {
        this.queue = queue;
        this.stateRoot = stateRoot.toAbsolutePath().normalize();
        Files.createDirectories(this.stateRoot.resolve("artifacts"));
    }

    public EvidenceJob process(EvidenceJob job, String workerId, Instant now) throws IOException {
        final JsonNode request;
        try {
            request = JSON.readTree(job.requestPath().toFile());
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalArgumentException("Evidence job request JSON is invalid", invalidJson);
        }
        require(request.isObject() && fieldNames(request).equals(REQUEST_FIELDS),
                "Evidence job request is invalid");
        require("pathlab.evidence-job/1".equals(text(request, "schema")),
                "Evidence job schema is unsupported");
        var source = regularPath(request, "sourcePath");
        var preview = regularPath(request, "previewPath");
        var expectedSha = text(request, "sourceSha256");
        require(expectedSha.matches("[a-f0-9]{64}") && expectedSha.equals(sha256(source)),
                "Evidence source checksum does not match");
        var pack = EvidencePackManifest.load(regularPath(request, "packManifest"));
        pack.requirePilotEligible();
        var stain = text(request, "stain");
        pack.requireStain(stain);
        require(pack.capability() != EvidencePackManifest.Capability.HE_EVIDENCE,
                "H&E encoder artifact is not installed");
        var marker = text(request, "marker").toLowerCase();
        if (!pack.markers().contains(marker)) marker = "generic";
        require(pack.capability() != EvidencePackManifest.Capability.IHC_DESCRIPTIVE
                        || pack.markers().contains(marker),
                "Requested IHC marker is unsupported and generic fallback is unavailable");
        require(positiveInt(request, "sourceWidth") > 0 && positiveInt(request, "sourceHeight") > 0,
                "Evidence source geometry is invalid");
        checkCancellation(job.id(), workerId, now);
        var running = queue.checkpoint(job.id(), workerId, EvidenceJobState.RUNNING,
                "running", 0.25, "Running offline bounded brightfield analysis", now, LEASE);

        final BufferedImage image;
        try {
            image = ImageIO.read(preview.toFile());
        } catch (javax.imageio.IIOException invalidImage) {
            throw new IllegalArgumentException("Evidence preview could not be decoded", invalidImage);
        }
        require(image != null, "Evidence preview could not be decoded");
        var analysis = BrightfieldTileAnalyzer.analyze(image, marker);
        var tissue = tissueFraction(image);
        var focus = focus(image);
        var abstentionReasons = new java.util.ArrayList<String>();
        if (tissue < 0.05) abstentionReasons.add("insufficient_tissue");
        if (focus < 0.05) abstentionReasons.add("out_of_focus_or_uninformative_preview");
        var abstained = !abstentionReasons.isEmpty();
        var refiningAt = now.plusSeconds(1);
        checkCancellation(job.id(), workerId, refiningAt);
        var refining = queue.checkpoint(running.id(), workerId, EvidenceJobState.REFINING,
                "refining", 0.65, "Refining deterministic region descriptors", refiningAt, LEASE);
        var packagingAt = now.plusSeconds(2);
        var packaging = queue.checkpoint(refining.id(), workerId, EvidenceJobState.PACKAGING,
                "packaging", 0.9, "Signing immutable evidence bundle", packagingAt, LEASE);

        var artifactRoot = stateRoot.resolve("artifacts").resolve(job.id()).normalize();
        require(artifactRoot.startsWith(stateRoot.resolve("artifacts")), "Evidence artifact path escaped state root");
        require(directoryBytes(stateRoot.resolve("artifacts")) < ARTIFACT_QUOTA,
                "Evidence artifact quota is exhausted");
        Files.createDirectories(artifactRoot);
        var unsigned = evidence(request, pack, image, analysis, focus, tissue,
                abstentionReasons, now);
        new EvidenceBundleWriter(stateRoot.resolve("signing"))
                .write(artifactRoot.resolve("evidence.json"), unsigned);
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
            double focus,
            double tissue,
            java.util.List<String> abstentionReasons,
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
        root.set("evidence", JSON.valueToTree(java.util.List.of(java.util.Map.of(
                "id", "region-1", "stage", "refined", "kind", "support",
                "x", 0, "y", 0, "width", sourceWidth, "height", sourceHeight,
                "score", Math.max(0, Math.min(1, 1 - analysis.dabAreaFraction()))))));
        var aggregate = new java.util.LinkedHashMap<String, Object>();
        aggregate.put("regionId", "region-1");
        aggregate.put("algorithm", "od-watershed");
        aggregate.put("count", analysis.cellCount());
        aggregate.put("densityPerMm2", null);
        aggregate.put("meanNucleusAreaPx2", analysis.meanNucleusAreaPx2());
        root.set("cellAggregates", JSON.valueToTree(java.util.List.of(aggregate)));
        root.set("ihcDescriptors", JSON.valueToTree(
                pack.capability() == EvidencePackManifest.Capability.IHC_DESCRIPTIVE
                        ? java.util.List.of(java.util.Map.of(
                                "regionId", "region-1", "marker", analysis.marker(),
                                "compartment", analysis.compartment(),
                                "dabAreaFraction", analysis.dabAreaFraction(),
                                "meanDabOd", analysis.meanDabOd(),
                                "researchEstimate", true))
                        : java.util.List.of()));
        root.set("citations", JSON.createArrayNode());
        root.set("qc", JSON.valueToTree(java.util.Map.of(
                "focus", focus, "tissueFraction", tissue,
                "uncertainty", 1 - Math.min(focus, tissue),
                "abstentionReasons", abstentionReasons)));
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

    private static long directoryBytes(Path root) throws IOException {
        if (!Files.exists(root)) return 0;
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try { return Files.size(path); } catch (IOException ignored) { return Long.MAX_VALUE; }
            }).sum();
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

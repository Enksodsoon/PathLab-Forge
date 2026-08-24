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
            "tileCacheManifest", "tileCacheManifestSha256", "qualificationAttestationSha256",
            "qualificationCampaignManifest", "qualificationCohortManifest",
            "qualificationCohortManifestSha256", "reviewedRegions");
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
        return executionPlan(requestPath, jobId, null);
    }

    public static ExecutionPlan executionPlan(Path requestPath, String jobId, Path stateRoot) throws IOException {
        final JsonNode request;
        try {
            request = JSON.readTree(requestPath.toAbsolutePath().normalize().toFile());
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalArgumentException("Evidence job request JSON is invalid", invalidJson);
        }
        // Legacy v1 submissions were accepted before lane metadata existed and migrate to CPU/I/O.
        if (request == null || !request.path("packManifest").isTextual()) return new ExecutionPlan(EvidenceExecutionLane.CPU_IO, "");
        var pack = EvidencePackManifest.load(regularPath(request, "packManifest"));
        if (stateRoot == null) pack.requireExecutableForJob(jobId);
        else pack.requireExecutableForJob(jobId, new QualifiedPackRegistry(stateRoot),
                request.path("qualificationAttestationSha256").asText(""));
        return new ExecutionPlan("cuda".equals(pack.runtimeCompatibility().executionProvider())
                ? EvidenceExecutionLane.GPU : EvidenceExecutionLane.CPU_IO, pack.sha256());
    }

    public record ExecutionPlan(EvidenceExecutionLane lane, String packSha256) { }

    static boolean usesExternalModelWorker(EvidencePackManifest pack) {
        return pack.runtimeCompatibility().requiresExternalWorker()
                && Set.of(EvidencePackManifest.Capability.HE_EVIDENCE,
                        EvidencePackManifest.Capability.CELL_MORPHOLOGY)
                        .contains(pack.capability());
    }

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
        require(!request.has("qualificationCampaignManifest")
                        || job.id().matches("qualification-[a-f0-9]{8,64}"),
                "Only qualification jobs may bind a campaign manifest");
        var source = regularPath(request, "sourcePath");
        var preview = regularPath(request, "previewPath");
        var expectedSha = text(request, "sourceSha256");
        require(expectedSha.matches("[a-f0-9]{64}") && expectedSha.equals(sha256(source)),
                "Evidence source checksum does not match");
        var pack = EvidencePackManifest.load(regularPath(request, "packManifest"));
        pack.requireExecutableForJob(job.id(), new QualifiedPackRegistry(stateRoot),
                request.path("qualificationAttestationSha256").asText(""));
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
        require(request.has("qualificationCohortManifest")
                        == request.has("qualificationCohortManifestSha256"),
                "Qualification cohort path and checksum must be supplied together");
        Path qualificationCohortPath = null;
        var qualificationCohortSha = "";
        if (request.has("qualificationCohortManifest")) {
            require(request.has("qualificationCampaignManifest"),
                    "Only campaign jobs may attach a qualification cohort");
            qualificationCohortPath = regularPath(request, "qualificationCohortManifest");
            qualificationCohortSha = text(request, "qualificationCohortManifestSha256");
            require(qualificationCohortSha.matches("[a-f0-9]{64}")
                            && qualificationCohortSha.equals(sha256(qualificationCohortPath)),
                    "Qualification cohort checksum does not match");
            require(Set.of(EvidencePackManifest.Capability.HE_EVIDENCE,
                            EvidencePackManifest.Capability.CELL_MORPHOLOGY).contains(pack.capability()),
                    "This capability cannot attach a qualification cohort");
        }
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
        JsonNode cellQualificationMetrics = null;
        if (pack.capability() == EvidencePackManifest.Capability.CELL_MORPHOLOGY
                && qualificationCohortPath != null && !usesExternalModelWorker(pack)) {
            cellQualificationMetrics = CellInstanceQualificationEvaluator.evaluate(
                    qualificationCohortPath, qualificationCohortSha);
        }
        JsonNode modelResult = null;
        if (usesExternalModelWorker(pack)) {
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
                    "External model worker returned unsupported or not_evaluable");
            if (pack.capability() == EvidencePackManifest.Capability.CELL_MORPHOLOGY) {
                cellQualificationMetrics = modelResult.path("qualificationMetrics");
            }
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
        var reviewedRegions = reviewedRegions(request.path("reviewedRegions"), image,
                sourceWidth, sourceHeight);
        if (reviewedRegions.isEmpty()) compartmentSource = "none";
        else if (reviewedRegions.stream().allMatch(region -> "faculty-authored".equals(region.reviewSource()))) {
            compartmentSource = "faculty-authored";
        } else if (reviewedRegions.stream().anyMatch(region -> "faculty-approved".equals(region.reviewSource()))) {
            compartmentSource = "faculty-approved";
        } else compartmentSource = "model-suggested";
        var ihcResult = pack.capability() == EvidencePackManifest.Capability.IHC_DESCRIPTIVE
                ? IhcMeasurementAnalyzer.analyze(image, marker, reviewedRegions,
                        request.path("controlsValidated").asBoolean(false)) : null;
        var specialResult = Set.of(EvidencePackManifest.Capability.SPECIAL_STAIN_DESCRIPTIVE,
                        EvidencePackManifest.Capability.CYTOLOGY_DESCRIPTIVE).contains(pack.capability())
                ? SpecialStainAnalyzer.analyze(image, stain) : null;
        var descriptorWarning = ihcResult != null ? ihcResult.abstentionReason()
                : specialResult != null ? specialResult.abstentionReason() : null;
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
        var qualificationOnly = job.id().matches("qualification-[a-f0-9]{8,64}");
        var evidenceV2 = EvidencePackManifest.SCHEMA_V2.equals(pack.schema()) && !qualificationOnly;
        var unsigned = evidenceV2
                ? evidenceV2(request, pack, image, analysis, modelResult, focus, tissue,
                        abstentionReasons, compartmentSource, ihcResult, specialResult,
                        reviewedRegions, now)
                : evidence(request, pack, image, analysis, stainQc, modelResult, focus, tissue,
                        abstentionReasons, compartmentSource, markerIdentitySource, descriptorWarning,
                        ihcResult, specialResult, reviewedRegions, now);
        var finalArtifact = artifactRoot.resolve("evidence.json");
        if (evidenceV2) new EvidenceBundleWriterV2(stateRoot).write(finalArtifact, unsigned);
        else new EvidenceBundleWriter(stateRoot.resolve("signing")).write(finalArtifact, unsigned);
        if (job.id().matches("qualification-[a-f0-9]{8,64}")
                && request.path("qualificationCampaignManifest").isTextual()) {
            writeQualificationReport(job, request, pack, modelResult, cellQualificationMetrics,
                    abstained, now.plusMillis(2400));
        }
        queue.recordFinalArtifact(job.id(), workerId, sha256(finalArtifact), now.plusMillis(2500));
        var terminal = abstained ? EvidenceJobState.ABSTAINED : EvidenceJobState.COMPLETED;
        var detail = abstained
                ? "Signed QC evidence abstained from descriptive analysis"
                : "Signed evidence is ready for faculty review";
        return queue.checkpoint(packaging.id(), workerId, terminal,
                terminal.name().toLowerCase(), 1, detail,
                now.plusSeconds(3), LEASE);
    }

    private void writeQualificationReport(EvidenceJob job, JsonNode request, EvidencePackManifest pack,
            JsonNode modelResult, JsonNode cellQualificationMetrics, boolean abstained, Instant now)
            throws IOException {
        var campaign = QualificationCampaignManifest.load(
                regularPath(request, "qualificationCampaignManifest"));
        var normalizedRequest = job.requestPath().toAbsolutePath().normalize();
        var track = campaign.tracks().stream().filter(candidate ->
                normalizedRequest.equals(candidate.requestPath())
                        || normalizedRequest.equals(candidate.remediationRequestPath()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "Qualification request is not bound to the frozen campaign"));
        require(track.candidateId().equals(pack.packId())
                        && track.capability().equals(pack.capability().wire())
                        && track.scope().equals(pack.scope()),
                "Qualification request pack identity changed");
        if (track.expectedAttestationPath() == null) return;
        var report = JSON.createObjectNode();
        report.put("schema", QualificationReportWriter.SCHEMA);
        report.put("campaignId", campaign.campaignId());
        report.put("campaignManifestSha256", campaign.sha256());
        report.put("trackId", track.id());
        report.put("candidateId", track.candidateId());
        report.put("capability", track.capability());
        report.put("scope", track.scope());
        report.put("packManifestSha256", pack.sha256());
        report.put("protocolSha256", track.protocolSha256());
        report.put("generatedAt", now.toString());
        report.put("researchOnly", true);
        report.put("notDiagnostic", true);
        var heldOutRequired = switch (pack.capability()) {
            case CELL_MORPHOLOGY, IHC_DESCRIPTIVE, SPECIAL_STAIN_DESCRIPTIVE,
                    CYTOLOGY_DESCRIPTIVE, HE_EVIDENCE, GROUNDED_TUTOR, ATLAS_DISTILLATION -> true;
        };
        var retrieval = modelResult == null ? null : modelResult.path("qualificationMetrics");
        var hasRetrieval = retrieval != null && retrieval.isObject()
                && "pathlab.he-retrieval-metrics/1".equals(retrieval.path("schema").asText());
        var hasCellMetrics = cellQualificationMetrics != null && cellQualificationMetrics.isObject()
                && CellInstanceQualificationEvaluator.SCHEMA.equals(
                        cellQualificationMetrics.path("schema").asText());
        var repeatable = hasRetrieval && retrieval.path("exactRankingRepeatability").asBoolean(false);
        var recallPass = hasRetrieval && finiteAtLeast(retrieval, "macroRecallAt5Improvement",
                retrieval.path("minimumMacroRecallAt5Improvement").asDouble(Double.POSITIVE_INFINITY));
        var ndcgPass = hasRetrieval && finiteAtLeast(retrieval, "macroNdcgAt10Improvement",
                retrieval.path("minimumMacroNdcgAt10Improvement").asDouble(Double.POSITIVE_INFINITY));
        var oodPass = hasRetrieval && finiteAtLeast(retrieval, "oodAuRoc",
                retrieval.path("minimumOodAuRoc").asDouble(Double.POSITIVE_INFINITY));
        var fullCohort = hasRetrieval && "ready".equals(retrieval.path("cohortStatus").asText());
        var cellPqPass = hasCellMetrics && finiteAtLeast(cellQualificationMetrics, "macroPq",
                cellQualificationMetrics.path("minimumMacroPq").asDouble(Double.POSITIVE_INFINITY));
        var cellDicePass = hasCellMetrics && finiteAtLeast(cellQualificationMetrics, "instanceDice",
                cellQualificationMetrics.path("minimumInstanceDice").asDouble(Double.POSITIVE_INFINITY));
        var cellCountPass = hasCellMetrics && finiteAtMost(cellQualificationMetrics, "countError",
                cellQualificationMetrics.path("maximumCountError").asDouble(Double.NEGATIVE_INFINITY));
        var cellMorphometryPass = hasCellMetrics && finiteAtMost(cellQualificationMetrics, "morphometryBias",
                cellQualificationMetrics.path("maximumMorphometryBias").asDouble(Double.NEGATIVE_INFINITY));
        var cellFailurePass = hasCellMetrics && finiteAtMost(cellQualificationMetrics, "failedRegionRate",
                cellQualificationMetrics.path("maximumFailedRegionRate").asDouble(Double.NEGATIVE_INFINITY));
        var cellRepeatable = hasCellMetrics && cellQualificationMetrics.path("deterministicRepeat").asBoolean(false);
        var cellCoverage = hasCellMetrics && cellQualificationMetrics.path("crossTissuePerformance").asBoolean(false);
        var cellIntegrity = hasCellMetrics && cellQualificationMetrics.path("rightsAndIntegrityPassed").asBoolean(false);
        var cellResources = hasCellMetrics && cellQualificationMetrics.path("resourceCompliant").asBoolean(false);
        var cellQualified = hasCellMetrics && cellPqPass && cellDicePass && cellCountPass
                && cellMorphometryPass && cellFailurePass && cellRepeatable && cellCoverage
                && cellIntegrity && cellResources;
        var qualified = !abstained && (pack.capability() == EvidencePackManifest.Capability.CELL_MORPHOLOGY
                ? cellQualified : fullCohort && repeatable && recallPass && ndcgPass && oodPass);
        report.put("status", abstained ? "not_evaluable" : qualified ? "qualified" : "experimental");
        var checks = report.putArray("checks");
        checks.addObject().put("id", "offline-bounded-execution").put("outcome",
                abstained ? "not_evaluable" : hasCellMetrics && !cellResources ? "fail" : "pass")
                .put("detail", hasCellMetrics && !cellResources
                        ? "Candidate exceeded its declared time or memory envelope"
                        : "Candidate executed without network access inside its declared resource envelope");
        if (hasCellMetrics) {
            checks.addObject().put("id", "cell-cohort-integrity-and-rights")
                    .put("outcome", cellIntegrity ? "pass" : "fail")
                    .put("detail", "Restricted held-out samples passed exact local checksums and patient overlap audit")
                    .put("cohortManifestSha256", cellQualificationMetrics.path("cohortManifestSha256").asText())
                    .put("sampleCount", cellQualificationMetrics.path("sampleCount").asInt())
                    .put("upstreamChecksumAvailable",
                            cellQualificationMetrics.path("upstreamChecksumAvailable").asBoolean(false));
            checks.addObject().put("id", "cell-instance-accuracy")
                    .put("outcome", cellPqPass && cellDicePass ? "pass" : "fail")
                    .put("macroPq", cellQualificationMetrics.path("macroPq").asDouble())
                    .put("instanceDice", cellQualificationMetrics.path("instanceDice").asDouble());
            checks.addObject().put("id", "cell-count-and-morphometry")
                    .put("outcome", cellCountPass && cellMorphometryPass ? "pass" : "fail")
                    .put("countError", cellQualificationMetrics.path("countError").asDouble())
                    .put("morphometryBias", cellQualificationMetrics.path("morphometryBias").asDouble());
            checks.addObject().put("id", "cell-failure-rate")
                    .put("outcome", cellFailurePass ? "pass" : "fail")
                    .put("failedRegionRate", cellQualificationMetrics.path("failedRegionRate").asDouble());
            checks.addObject().put("id", "cell-determinism-cross-tissue-resources")
                    .put("outcome", cellRepeatable && cellCoverage && cellResources ? "pass" : "fail")
                    .put("deterministicRepeat", cellRepeatable)
                    .put("crossTissuePerformance", cellCoverage)
                    .put("resourceCompliant", cellResources);
            checks.addObject().put("id", "preregistered-held-out-gates")
                    .put("outcome", qualified ? "pass" : "fail")
                    .put("detail", qualified ? "Every frozen cell-instance gate passed"
                            : "One or more frozen cell-instance gates failed");
        } else if (hasRetrieval) {
            checks.addObject().put("id", "cohort-integrity-and-rights").put("outcome", "pass")
                    .put("detail", "Every cohort tile and provenance record passed checksum and permitted-use validation")
                    .put("cohortManifestSha256", retrieval.path("cohortManifestSha256").asText())
                    .put("sampleCount", retrieval.path("sampleCount").asInt());
            checks.addObject().put("id", "retrieval-baseline-comparison")
                    .put("outcome", recallPass && ndcgPass ? "pass" : "fail")
                    .put("detail", "DINOv2 and color-histogram metrics used identical frozen tiles")
                    .put("macroRecallAt5Improvement", retrieval.path("macroRecallAt5Improvement").asDouble())
                    .put("macroNdcgAt10Improvement", retrieval.path("macroNdcgAt10Improvement").asDouble());
            checks.addObject().put("id", "exact-ranking-repeatability")
                    .put("outcome", repeatable ? "pass" : "fail")
                    .put("detail", "Two independent deterministic inference passes produced exact query rankings");
            checks.addObject().put("id", "ood-auroc")
                    .put("outcome", oodPass ? "pass" : "not_evaluable")
                    .put("detail", oodPass ? "Frozen OOD threshold passed" : "Frozen OOD fixtures are unavailable");
            checks.addObject().put("id", "preregistered-held-out-gates")
                    .put("outcome", qualified ? "pass" : "not_evaluable")
                    .put("detail", qualified ? "Every frozen H&E gate passed"
                            : "Cross-tissue patient/source-held-out coverage remains incomplete");
        } else {
            checks.addObject().put("id", "preregistered-held-out-gates").put("outcome", "not_evaluable")
                    .put("detail", "Required rights-cleared patient/source-held-out fixtures are not attached to this request");
        }
        var reasons = report.putArray("reasons");
        if (abstained) reasons.add("INPUT_QC_FAILED");
        else if (qualified) {
            // A qualified report has no unresolved reason codes.
        } else if (hasCellMetrics) {
            cellQualificationMetrics.path("notEvaluableReasons")
                    .forEach(reason -> reasons.add(reason.asText()));
            if (!cellPqPass || !cellDicePass) reasons.add("CELL_INSTANCE_ACCURACY_GATE_FAILED");
            if (!cellCountPass) reasons.add("CELL_COUNT_ERROR_GATE_FAILED");
            if (!cellMorphometryPass) reasons.add("CELL_MORPHOMETRY_GATE_FAILED");
            if (!cellFailurePass) reasons.add("CELL_FAILED_REGION_GATE_FAILED");
            if (!cellRepeatable) reasons.add("CELL_DETERMINISM_GATE_FAILED");
            if (!cellCoverage) reasons.add("CELL_CROSS_TISSUE_GATE_FAILED");
            if (!cellResources) reasons.add("CELL_RESOURCE_GATE_FAILED");
        } else if (hasRetrieval) {
            retrieval.path("notEvaluableReasons").forEach(reason -> reasons.add(reason.asText()));
            if (!recallPass || !ndcgPass) reasons.add("RETRIEVAL_BASELINE_GATE_FAILED");
            if (!repeatable) reasons.add("RANKING_REPEATABILITY_FAILED");
            if (!oodPass) reasons.add("OOD_QUALIFICATION_PENDING");
        } else reasons.add("HELD_OUT_QUALIFICATION_PENDING");
        new QualificationReportWriter(stateRoot).write(track.expectedAttestationPath(), report);
    }

    private static boolean finiteAtLeast(JsonNode node, String field, double minimum) {
        var value = node.path(field);
        return value.isNumber() && Double.isFinite(value.doubleValue())
                && Double.isFinite(minimum) && value.doubleValue() >= minimum;
    }

    private static boolean finiteAtMost(JsonNode node, String field, double maximum) {
        var value = node.path(field);
        return value.isNumber() && Double.isFinite(value.doubleValue())
                && Double.isFinite(maximum) && value.doubleValue() <= maximum;
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
            IhcMeasurementAnalyzer.Result ihcResult,
            SpecialStainAnalyzer.Result specialResult,
            java.util.List<IhcMeasurementAnalyzer.ReviewedRegion> reviewedRegions,
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
        if (request.path("qualificationCampaignManifest").isTextual()) {
            root.put("qualificationOnly", true);
        }
        var sourceWidth = positiveInt(request, "sourceWidth");
        var sourceHeight = positiveInt(request, "sourceHeight");
        root.set("coordinates", JSON.valueToTree(java.util.Map.of(
                "space", "source-pixel", "originX", 0, "originY", 0,
                "scaleX", (double) sourceWidth / image.getWidth(),
                "scaleY", (double) sourceHeight / image.getHeight())));
        var regions = new java.util.ArrayList<java.util.Map<String, Object>>();
        if (modelResult == null) {
            regions.add(java.util.Map.of(
                        "id", "region-1", "stage", "refined", "kind", "support",
                        "x", 0, "y", 0, "width", sourceWidth, "height", sourceHeight,
                        "score", Math.max(0, Math.min(1, 1 - analysis.dabAreaFraction()))));
            for (var region : reviewedRegions) {
                regions.add(java.util.Map.of(
                        "id", region.id(), "stage", "refined",
                        "kind", region.kind() + "-compartment", "reviewStatus", region.reviewSource(),
                        "x", (int) Math.round(region.x() * (double) sourceWidth / image.getWidth()),
                        "y", (int) Math.round(region.y() * (double) sourceHeight / image.getHeight()),
                        "width", (int) Math.round(region.width() * (double) sourceWidth / image.getWidth()),
                        "height", (int) Math.round(region.height() * (double) sourceHeight / image.getHeight()),
                        "score", 1.0));
            }
            root.set("evidence", JSON.valueToTree(regions));
        } else root.set("evidence", modelResult.path("regions").deepCopy());
        var aggregate = new java.util.LinkedHashMap<String, Object>();
        aggregate.put("regionId", "region-1");
        aggregate.put("algorithm", "od-watershed");
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
        if (ihcResult != null) {
            var descriptor = new java.util.LinkedHashMap<String, Object>();
            descriptor.put("regionId", "region-1");
            descriptor.put("markerId", analysis.marker());
            descriptor.put("marker", analysis.marker());
            descriptor.put("markerIdentitySource", markerIdentitySource);
            descriptor.put("analysisMode", ihcResult.analysisMode());
            descriptor.put("cellMaskSource", "od-watershed");
            descriptor.put("compartmentSource", compartmentSource);
            descriptor.put("calibrationStatus", stainQc.calibrationStatus());
            descriptor.put("compartment", ihcResult.compartment());
            descriptor.put("measurements", ihcResult.measurements());
            descriptor.put("qc", ihcResult.qc());
            descriptor.put("uncertainty", Math.max(1 - Math.min(focus, tissue), ihcResult.uncertainty()));
            descriptor.put("abstentionReason", ihcResult.abstentionReason());
            descriptor.put("researchEstimate", true);
            ihc.add(descriptor);
        }
        root.set("ihcDescriptors", JSON.valueToTree(ihc));
        var special = new java.util.ArrayList<java.util.Map<String, Object>>();
        if (specialResult != null) {
            var descriptor = new java.util.LinkedHashMap<String, Object>();
            descriptor.put("regionId", "region-1");
            descriptor.put("stainId", specialResult.stainId());
            descriptor.put("analysisMode", specialResult.analysisMode());
            descriptor.put("cellMaskSource", pack.capability() == EvidencePackManifest.Capability.CYTOLOGY_DESCRIPTIVE
                    ? "od-watershed" : "none");
            descriptor.put("measurements", specialResult.measurements());
            descriptor.put("qc", specialResult.qc());
            descriptor.put("uncertainty", specialResult.uncertainty());
            descriptor.put("abstentionReason", specialResult.abstentionReason());
            descriptor.put("researchEstimate", true);
            special.add(descriptor);
        }
        root.set(pack.capability() == EvidencePackManifest.Capability.CYTOLOGY_DESCRIPTIVE
                ? "cytologyDescriptors" : "specialStainDescriptors", JSON.valueToTree(special));
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

    private static java.util.List<IhcMeasurementAnalyzer.ReviewedRegion> reviewedRegions(
            JsonNode node, BufferedImage image, int sourceWidth, int sourceHeight) {
        if (node.isMissingNode() || node.isNull()) return java.util.List.of();
        require(node.isArray() && node.size() <= 64, "Reviewed regions are invalid");
        var result = new java.util.ArrayList<IhcMeasurementAnalyzer.ReviewedRegion>();
        for (var item : node) {
            require(fieldNames(item).equals(Set.of("id", "kind", "reviewSource", "x", "y", "width", "height")),
                    "Reviewed region fields are invalid");
            var x = boundedCoordinate(item, "x", sourceWidth - 1);
            var y = boundedCoordinate(item, "y", sourceHeight - 1);
            var width = boundedCoordinate(item, "width", sourceWidth - x);
            var height = boundedCoordinate(item, "height", sourceHeight - y);
            require(width > 0 && height > 0, "Reviewed region geometry is invalid");
            result.add(new IhcMeasurementAnalyzer.ReviewedRegion(
                    text(item, "id"), text(item, "kind"), text(item, "reviewSource"),
                    (int) Math.floor(x * (double) image.getWidth() / sourceWidth),
                    (int) Math.floor(y * (double) image.getHeight() / sourceHeight),
                    Math.max(1, (int) Math.ceil(width * (double) image.getWidth() / sourceWidth)),
                    Math.max(1, (int) Math.ceil(height * (double) image.getHeight() / sourceHeight))));
        }
        return java.util.List.copyOf(result);
    }

    private static ObjectNode evidenceV2(
            JsonNode request, EvidencePackManifest pack, BufferedImage image,
            BrightfieldTileAnalyzer.Result analysis, JsonNode modelResult, double focus, double tissue,
            java.util.List<String> abstentionReasons, String compartmentSource,
            IhcMeasurementAnalyzer.Result ihcResult, SpecialStainAnalyzer.Result specialResult,
            java.util.List<IhcMeasurementAnalyzer.ReviewedRegion> reviewedRegions, Instant now) {
        var sourceWidth = positiveInt(request, "sourceWidth");
        var sourceHeight = positiveInt(request, "sourceHeight");
        var root = JSON.createObjectNode();
        root.put("schema", EvidenceBundleWriterV2.SCHEMA);
        root.put("bundleId", "evidence-" + java.util.UUID.randomUUID());
        root.set("source", JSON.valueToTree(java.util.Map.of(
                "slideSha256", text(request, "sourceSha256"), "revision", text(request, "slideRevision"),
                "width", sourceWidth, "height", sourceHeight)));
        root.set("pack", JSON.valueToTree(java.util.Map.of(
                "id", pack.packId(), "version", pack.version(), "manifestSha256", pack.sha256(),
                "capability", pack.capability().wire(), "scope", pack.scope(),
                "preprocessing", pack.preprocessingId(),
                "artifacts", pack.artifacts().stream().map(EvidencePackManifest.Artifact::sha256).toList())));
        root.put("qualificationAttestationSha256", text(request, "qualificationAttestationSha256"));
        root.put("status", abstentionReasons.isEmpty() ? "completed" : "abstained");
        root.put("researchOnly", true);
        root.put("notDiagnostic", true);
        root.put("reviewRequired", true);
        root.set("coordinates", JSON.valueToTree(java.util.Map.of(
                "space", "source-pixel", "originX", 0, "originY", 0,
                "scaleX", (double) sourceWidth / image.getWidth(),
                "scaleY", (double) sourceHeight / image.getHeight())));
        var regions = root.putArray("regions");
        if (modelResult != null && modelResult.path("regions").isArray()) {
            for (var candidate : modelResult.path("regions")) {
                var region = regions.addObject();
                region.put("id", candidate.path("id").asText("region-" + regions.size()));
                region.put("stage", candidate.path("stage").asText("refined"));
                region.put("kind", candidate.path("kind").asText("support"));
                region.put("reviewStatus", "unreviewed");
                for (var field : java.util.List.of("x", "y", "width", "height", "score")) {
                    region.put(field, candidate.path(field).asDouble());
                }
            }
        }
        if (regions.isEmpty()) {
            var region = regions.addObject();
            region.put("id", "region-1"); region.put("stage", "refined"); region.put("kind", "analysis");
            region.put("reviewStatus", "unreviewed"); region.put("x", 0); region.put("y", 0);
            region.put("width", sourceWidth); region.put("height", sourceHeight); region.put("score", 1.0);
        }
        for (var reviewed : reviewedRegions) {
            var region = regions.addObject();
            region.put("id", reviewed.id()); region.put("stage", "refined");
            region.put("kind", "analysis".equals(reviewed.kind())
                    ? "analysis" : reviewed.kind() + "-compartment");
            region.put("reviewStatus", reviewed.reviewSource());
            region.put("x", reviewed.x() * (double) sourceWidth / image.getWidth());
            region.put("y", reviewed.y() * (double) sourceHeight / image.getHeight());
            region.put("width", reviewed.width() * (double) sourceWidth / image.getWidth());
            region.put("height", reviewed.height() * (double) sourceHeight / image.getHeight());
            region.put("score", 1.0);
        }
        var includeCells = pack.capability() != EvidencePackManifest.Capability.HE_EVIDENCE
                && pack.capability() != EvidencePackManifest.Capability.SPECIAL_STAIN_DESCRIPTIVE;
        var instances = root.putArray("cellInstances");
        if (includeCells) {
            var index = 0;
            for (var cell : analysis.instances()) {
                var item = instances.addObject(); item.put("id", "cell-" + (++index));
                item.put("regionId", "region-1"); item.put("maskEncoding", "rle");
                item.set("mask", JSON.valueToTree(cell.rle())); item.put("areaPx2", cell.areaPx2());
                item.put("perimeterPx", cell.perimeterPx()); item.put("eccentricity", cell.eccentricity());
                item.put("solidity", cell.solidity()); item.put("meanIntensity", cell.meanIntensity());
                item.put("uncertainty", Math.max(0, 1 - focus)); item.put("category", "unclassified");
            }
        }
        var aggregates = root.putArray("cellAggregates");
        if (includeCells) {
            var aggregate = aggregates.addObject(); aggregate.put("regionId", "region-1");
            aggregate.put("algorithm", "od-watershed"); aggregate.put("count", analysis.cellCount());
            aggregate.putNull("densityPerMm2");
            aggregate.set("distributions", JSON.valueToTree(java.util.Map.of(
                    "meanAreaPx2", analysis.meanNucleusAreaPx2() == null ? 0 : analysis.meanNucleusAreaPx2(),
                    "meanPerimeterPx", analysis.meanNucleusPerimeterPx() == null ? 0 : analysis.meanNucleusPerimeterPx(),
                    "meanEccentricity", analysis.meanNucleusEccentricity() == null ? 0 : analysis.meanNucleusEccentricity(),
                    "meanSolidity", analysis.meanNucleusSolidity() == null ? 0 : analysis.meanNucleusSolidity())));
            aggregate.put("uncertainty", 1 - Math.min(focus, tissue));
            aggregate.set("qc", JSON.valueToTree(abstentionReasons));
        }
        var ihc = root.putArray("ihcDescriptors");
        if (ihcResult != null) addStainDescriptor(ihc, "ihc_dab", ihcResult.markerId(),
                ihcResult.analysisMode(), "od-watershed", compartmentSource,
                ihcResult.calibrationStatus(), ihcResult.measurements(), ihcResult.qc(),
                ihcResult.uncertainty(), ihcResult.abstentionReason());
        var special = root.putArray("specialStainDescriptors");
        var cytology = root.putArray("cytologyDescriptors");
        if (specialResult != null) addStainDescriptor(
                pack.capability() == EvidencePackManifest.Capability.CYTOLOGY_DESCRIPTIVE ? cytology : special,
                specialResult.stainId(), "none", specialResult.analysisMode(),
                pack.capability() == EvidencePackManifest.Capability.CYTOLOGY_DESCRIPTIVE ? "od-watershed" : "none",
                "none", "relative_only", specialResult.measurements(), specialResult.qc(),
                specialResult.uncertainty(), specialResult.abstentionReason());
        root.set("citations", JSON.createArrayNode());
        root.set("qc", JSON.valueToTree(java.util.Map.of(
                "focus", focus, "tissueFraction", tissue, "uncertainty", 1 - Math.min(focus, tissue),
                "abstentionReasons", abstentionReasons,
                "warnings", java.util.List.of())));
        var provenance = root.putObject("provenance");
        provenance.put("createdAt", now.toString());
        provenance.put("codeRevision", "pathlab-forge-evidence-mentor-v2");
        provenance.put("offlineAnalysis", true);
        provenance.putNull("campaignId");
        return root;
    }

    private static void addStainDescriptor(com.fasterxml.jackson.databind.node.ArrayNode output,
            String stainId, String markerId, String mode, String maskSource, String compartmentSource,
            String calibration, java.util.Map<String, Object> measurements, java.util.List<String> qc,
            double uncertainty, String abstention) {
        var item = output.addObject(); item.put("regionId", "region-1"); item.put("stainId", stainId);
        item.put("markerId", markerId); item.put("analysisMode", mode); item.put("cellMaskSource", maskSource);
        item.put("compartmentSource", compartmentSource); item.put("calibrationStatus", calibration);
        item.set("measurements", JSON.valueToTree(measurements)); item.set("qc", JSON.valueToTree(qc));
        item.put("uncertainty", Math.max(0, Math.min(1, uncertainty)));
        if (abstention == null) item.putNull("abstentionReason"); else item.put("abstentionReason", abstention);
        item.put("researchEstimate", true);
    }

    private static int boundedCoordinate(JsonNode node, String name, int maximum) {
        var value = node.path(name);
        require(value.isIntegralNumber() && value.canConvertToInt()
                        && value.intValue() >= 0 && value.intValue() <= maximum,
                "Reviewed region geometry is invalid");
        return value.intValue();
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

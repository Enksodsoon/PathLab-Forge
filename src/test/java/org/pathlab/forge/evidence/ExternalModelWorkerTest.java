package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ExternalModelWorkerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void rejectsReusableEmbeddingsAndInvalidRegions() throws Exception {
        var pack = EvidencePackManifest.load(Path.of(
                "src/main/resources/evidence-packs/he-dinov2-small-v1.json"));
        var validPrefix = "{\"schema\":\"pathlab.model-worker-result/1\","
                + "\"status\":\"completed\",\"packManifestSha256\":\"" + pack.sha256() + "\",";
        assertThrows(IllegalArgumentException.class, () -> ExternalModelWorker.validateResult(
                JSON.readTree(validPrefix + "\"regions\":[],\"embeddings\":[1,2]}"), pack));
        assertThrows(IllegalArgumentException.class, () -> ExternalModelWorker.validateResult(
                JSON.readTree(validPrefix + "\"regions\":[{\"id\":\"r1\",\"stage\":\"refined\","
                        + "\"kind\":\"support\",\"x\":0,\"y\":0,\"width\":0,\"height\":1,\"score\":0.5}]}"), pack));
    }

    @Test void acceptsBoundedQualificationMetricsButRejectsExportedEmbeddings() throws Exception {
        var pack = EvidencePackManifest.load(Path.of(
                "src/main/resources/evidence-packs/he-dinov2-small-v1.json"));
        var prefix = "{\"schema\":\"pathlab.model-worker-result/1\","
                + "\"status\":\"completed\",\"packManifestSha256\":\"" + pack.sha256() + "\","
                + "\"regions\":[],\"qualificationMetrics\":{"
                + "\"schema\":\"pathlab.he-retrieval-metrics/1\",\"cohortManifestSha256\":\""
                + "a".repeat(64) + "\",\"sampleCount\":360,\"referenceCount\":180,"
                + "\"queryCount\":180,\"oodCount\":0,\"evaluationGroups\":[\"gi\"],"
                + "\"baselineMacroRecallAt5\":0.5,\"modelMacroRecallAt5\":0.7,"
                + "\"macroRecallAt5Improvement\":0.2,\"baselineMacroNdcgAt10\":0.4,"
                + "\"modelMacroNdcgAt10\":0.6,\"macroNdcgAt10Improvement\":0.2,"
                + "\"exactRankingRepeatability\":true,\"notEvaluableReasons\":[\"INSUFFICIENT_OOD\"],"
                + "\"embeddingsExported\":";

        assertDoesNotThrow(() -> ExternalModelWorker.validateResult(
                JSON.readTree(prefix + "false}}"), pack));
        assertThrows(IllegalArgumentException.class, () -> ExternalModelWorker.validateResult(
                JSON.readTree(prefix + "true}}"), pack));
    }

    @Test void exposesOnlyTheControlledWorkerFailureLine() {
        var output = "boot noise C:\\private\\slide.svs\r\n"
                + "PathLab DINOv2 worker failed closed: CUDA sm_61 host is unavailable\r\n"
                + "Traceback C:\\private\\slide.svs\r\n";

        assertEquals("CUDA sm_61 host is unavailable",
                ExternalModelWorker.safeFailureDetail(output));
        assertEquals("worker exceeded its declared resource envelope",
                ExternalModelWorker.safeFailureDetail(
                        "PathLab model worker failed closed: worker exceeded its declared resource envelope"));
        assertEquals("", ExternalModelWorker.safeFailureDetail(
                "Traceback C:\\private\\patient-123.svs"));
    }

    @Test void validatesPortableRuntimeFilesBeforeLaunchingPython(@TempDir Path install) throws Exception {
        var runtime = install.resolve("runtime");
        Files.createDirectories(runtime);
        var python = runtime.resolve("python.exe");
        Files.writeString(python, "portable-python");
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(python)));
        var manifest = JSON.readTree("""
                {"schema":"pathlab.model-runtime/1","files":[
                  {"path":"runtime/python.exe","bytes":15,"sha256":"%s"}
                ]}
                """.formatted(hash));

        assertDoesNotThrow(() -> ExternalModelWorker.validateRuntimeFileLedger(manifest, install));
        Files.writeString(python, "tampered");
        assertThrows(IllegalArgumentException.class,
                () -> ExternalModelWorker.validateRuntimeFileLedger(manifest, install));
        var traversal = JSON.readTree("""
                {"schema":"pathlab.model-runtime/1","files":[
                  {"path":"../outside","bytes":1,"sha256":"%s"}
                ]}
                """.formatted(hash));
        assertThrows(IllegalArgumentException.class,
                () -> ExternalModelWorker.validateRuntimeFileLedger(traversal, install));
    }

    @Test void validatesSharedRuntimeAndCandidateReferencesBeforeLaunch(@TempDir Path state) throws Exception {
        var models = state.resolve("models");
        var runtimeRoot = models.resolve("he-dinov2-small-v1/1");
        var python = runtimeRoot.resolve("runtime/Scripts/python.exe");
        Files.createDirectories(python.getParent());
        Files.writeString(python, "portable-python");
        var pythonHash = sha256(python);
        var runtimeManifest = runtimeRoot.resolve("runtime-manifest.json");
        Files.writeString(runtimeManifest, """
                {"schema":"pathlab.model-runtime/1","files":[
                  {"path":"runtime/Scripts/python.exe","bytes":15,"sha256":"%s"}
                ]}
                """.formatted(pythonHash));
        var candidateRoot = models.resolve("hovernet-fast-monusac-v1/1");
        Files.createDirectories(candidateRoot);
        var ledger = candidateRoot.resolve("candidate-ledger.json");
        var weight = candidateRoot.resolve("weights.tar");
        Files.writeString(ledger, "frozen-candidate");
        Files.writeString(weight, "frozen-weight");
        var install = models.resolve("cell-hovernet-fast-monusac-v1/4");
        Files.createDirectories(install);
        var reference = JSON.readTree("""
                {"schema":"pathlab.model-runtime-reference/1",
                 "sharedRuntimePack":"he-dinov2-small-v1","sharedRuntimeVersion":"1",
                 "sharedRuntimeRoot":"%s","sharedRuntimeManifestSha256":"%s",
                 "pythonRelativePath":"runtime/Scripts/python.exe",
                 "candidateId":"hovernet-fast-monusac-v1","candidateVersion":"1",
                 "candidateRoot":"%s","candidateLedgerSha256":"%s",
                 "weightFile":"weights.tar","weightSha256":"%s",
                 "runtimeCopiedIntoCandidate":false,"analysisNetwork":"disabled"}
                """.formatted(runtimeRoot.toString().replace("\\", "\\\\"), sha256(runtimeManifest),
                        candidateRoot.toString().replace("\\", "\\\\"), sha256(ledger), sha256(weight)));

        var resolved = ExternalModelWorker.resolveSharedRuntime(state, install, reference);
        assertEquals(python.toAbsolutePath().normalize(), resolved.python());
        assertEquals(runtimeRoot.toAbsolutePath().normalize(), resolved.root());
        Files.writeString(weight, "tampered");
        assertThrows(IllegalArgumentException.class,
                () -> ExternalModelWorker.resolveSharedRuntime(state, install, reference));
    }

    @Test void acceptsBoundedCellQualificationMetrics() throws Exception {
        var pack = EvidencePackManifest.load(Path.of(
                "src/main/resources/evidence-packs/cell-hovernet-fast-v1.json"));
        var result = JSON.readTree("""
                {"schema":"pathlab.model-worker-result/1","status":"completed",
                 "packManifestSha256":"%s","regions":[],"qualificationMetrics":{
                   "schema":"pathlab.cell-instance-metrics/1","cohortManifestSha256":"%s",
                   "sampleCount":23,"evaluatedSampleCount":23,"macroPq":0.5,"instanceDice":0.75,
                   "countError":0.1,"morphometryBias":0.08,"failedRegionRate":0.0,
                   "deterministicRepeat":true,"crossTissuePerformance":true,
                   "rightsAndIntegrityPassed":true,"resourceCompliant":true,
                   "elapsedSeconds":200.0,"peakHeapMiB":1200.0,"minimumMacroPq":0.45,
                   "minimumInstanceDice":0.7,"maximumCountError":0.15,
                   "maximumMorphometryBias":0.1,"maximumFailedRegionRate":0.05,
                   "sourceIntegrity":"local-first-acquisition-sha256","upstreamChecksumAvailable":false,
                   "perOrgan":{},"notEvaluableReasons":[]}}
                """.formatted(pack.sha256(), "a".repeat(64)));

        assertDoesNotThrow(() -> ExternalModelWorker.validateResult(result, pack));
        ((com.fasterxml.jackson.databind.node.ObjectNode) result.path("qualificationMetrics"))
                .put("macroPq", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> ExternalModelWorker.validateResult(result, pack));
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }
}

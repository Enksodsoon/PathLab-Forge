package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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

    @Test void exposesOnlyTheControlledWorkerFailureLine() {
        var output = "boot noise C:\\private\\slide.svs\r\n"
                + "PathLab DINOv2 worker failed closed: CUDA sm_61 host is unavailable\r\n"
                + "Traceback C:\\private\\slide.svs\r\n";

        assertEquals("CUDA sm_61 host is unavailable",
                ExternalModelWorker.safeFailureDetail(output));
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
}

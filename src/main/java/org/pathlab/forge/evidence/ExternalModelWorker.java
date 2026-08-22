package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.pathlab.forge.runtime.ChildProcessContainment;

/** Executes an installed, checksum-pinned model worker through a bounded file protocol. */
public final class ExternalModelWorker {
    public static final String RESULT_SCHEMA = "pathlab.model-worker-result/1";
    public static final String PROGRESS_SCHEMA = "pathlab.model-worker-progress/1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> STATUSES = Set.of("completed", "unsupported", "not_evaluable");
    private final Path stateRoot;

    public ExternalModelWorker(Path stateRoot) {
        this.stateRoot = stateRoot.toAbsolutePath().normalize();
    }

    public JsonNode execute(EvidencePackManifest pack, Path request, String jobId) throws IOException {
        return execute(pack, request, jobId, null, progress -> { });
    }

    public JsonNode execute(EvidencePackManifest pack, Path request, String jobId,
            ProgressListener listener) throws IOException {
        return execute(pack, request, jobId, null, listener);
    }

    public JsonNode execute(EvidencePackManifest pack, Path request, String jobId, Path resumeCheckpoint,
            ProgressListener listener) throws IOException {
        var runtime = pack.runtimeCompatibility();
        if (!runtime.requiresExternalWorker()) {
            throw new IllegalArgumentException("AI pack does not declare an external model worker");
        }
        if ("cuda".equals(runtime.executionProvider())
                && (!"12.6".equals(runtime.cuda()) || !"sm_61".equals(runtime.gpuArchitecture()))) {
            throw new IllegalArgumentException("Model worker is incompatible with the Pascal qualification host");
        }
        var install = stateRoot.resolve("models").resolve(pack.packId()).resolve(pack.version()).normalize();
        require(install.startsWith(stateRoot.resolve("models")), "Model worker path escaped state root");
        var executable = install.resolve(System.getProperty("os.name", "").startsWith("Windows")
                ? "worker.exe" : "worker");
        require(Files.isRegularFile(executable), "Model worker artifact is not installed");
        var expected = pack.artifacts().stream().filter(item -> "worker".equals(item.name()))
                .map(EvidencePackManifest.Artifact::sha256).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Model worker checksum is not declared"));
        require(expected.equals(sha256(executable)), "Model worker checksum does not match");

        var outputRoot = stateRoot.resolve("worker-output").resolve(jobId).normalize();
        require(outputRoot.startsWith(stateRoot.resolve("worker-output")), "Model output path escaped state root");
        Files.createDirectories(outputRoot);
        var output = outputRoot.resolve("result.json");
        var partial = outputRoot.resolve("result.json.partial");
        var progressPath = outputRoot.resolve("progress.json");
        Files.deleteIfExists(partial);
        var command = new java.util.ArrayList<String>();
        command.add(executable.toString()); command.add("--request"); command.add(request.toString());
        command.add("--output"); command.add(partial.toString()); command.add("--offline");
        if (resumeCheckpoint != null) {
            require("pathlab.model-worker/2".equals(runtime.workerProtocol()),
                    "Model worker checkpoint resume is unsupported");
            command.add("--resume-checkpoint"); command.add(resumeCheckpoint.toString());
        }
        var builder = new ProcessBuilder(command).directory(install.toFile());
        var environment = builder.environment();
        environment.remove("HTTP_PROXY"); environment.remove("HTTPS_PROXY");
        environment.remove("ALL_PROXY"); environment.remove("HF_TOKEN");
        environment.put("HF_HUB_OFFLINE", "1");
        environment.put("TRANSFORMERS_OFFLINE", "1");
        environment.put("PATHLAB_ANALYSIS_NETWORK", "disabled");
        environment.put("PATHLAB_MAX_VRAM_MIB", Integer.toString(pack.maxVramMiB()));
        environment.put("PATHLAB_MAX_RAM_MIB", Integer.toString(pack.maxRamMiB()));
        var process = ChildProcessContainment.global().register(builder.redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start());
        try {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(pack.maxSeconds());
            var lastProgressModified = -1L;
            var lastHeartbeat = 0L;
            var lastProgress = new Progress(0, 0, null, "");
            while (!process.waitFor(1, TimeUnit.SECONDS)) {
                if (System.nanoTime() >= deadline) {
                    process.destroyForcibly();
                    throw new ResourceLimitException("Model worker exceeded its bounded runtime");
                }
                if (Files.isRegularFile(progressPath)) {
                    var modified = Files.getLastModifiedTime(progressPath).toMillis();
                    if (modified != lastProgressModified) {
                        lastProgress = readProgress(progressPath, jobId, pack);
                        listener.update(lastProgress);
                        lastProgressModified = modified;
                        lastHeartbeat = System.nanoTime();
                        continue;
                    }
                }
                if (System.nanoTime() - lastHeartbeat >= TimeUnit.SECONDS.toNanos(10)) {
                    listener.update(lastProgress);
                    lastHeartbeat = System.nanoTime();
                }
            }
        } catch (IOException error) {
            process.destroyForcibly();
            Files.deleteIfExists(partial);
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); process.destroyForcibly();
            Files.deleteIfExists(partial);
            throw new IOException("Model worker was interrupted", error);
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(partial)) {
            Files.deleteIfExists(partial);
            throw new IllegalArgumentException("Model worker failed closed");
        }
        var result = JSON.readTree(partial.toFile());
        validateResult(result, pack);
        try {
            Files.move(partial, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(partial, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return result;
    }

    private static Progress readProgress(Path path, String jobId, EvidencePackManifest pack) throws IOException {
        var value = JSON.readTree(path.toFile());
        require(value.isObject() && fieldNames(value).equals(Set.of("schema", "jobId", "packManifestSha256",
                        "completedUnits", "totalUnits", "checkpointPath", "checkpointSha256", "updatedAt")),
                "Model worker progress fields are invalid");
        require(PROGRESS_SCHEMA.equals(value.path("schema").asText()), "Model worker progress schema is invalid");
        require(jobId.equals(value.path("jobId").asText())
                        && pack.sha256().equals(value.path("packManifestSha256").asText()),
                "Model worker progress identity does not match");
        var completed = value.path("completedUnits").asLong(-1);
        var total = value.path("totalUnits").asLong(-1);
        require(completed >= 0 && total >= completed, "Model worker progress counts are invalid");
        var checkpointText = value.path("checkpointPath").asText();
        var checksum = value.path("checkpointSha256").asText();
        Path checkpoint = checkpointText.isBlank() ? null : Path.of(checkpointText).toAbsolutePath().normalize();
        if (checkpoint != null) {
            require(Files.isRegularFile(checkpoint) && checksum.matches("[a-f0-9]{64}")
                    && checksum.equals(sha256(checkpoint)), "Model worker checkpoint is invalid");
        } else require(checksum.isBlank(), "Checkpoint checksum has no checkpoint");
        Instant.parse(value.path("updatedAt").asText());
        return new Progress(completed, total, checkpoint, checksum);
    }

    private static Set<String> fieldNames(JsonNode value) {
        var names = new java.util.HashSet<String>(); value.fieldNames().forEachRemaining(names::add); return names;
    }

    @FunctionalInterface
    public interface ProgressListener { void update(Progress progress) throws IOException; }
    public record Progress(long completedUnits, long totalUnits, Path checkpointPath, String checkpointSha256) { }
    public static final class ResourceLimitException extends IOException {
        private static final long serialVersionUID = 1L;
        ResourceLimitException(String message) { super(message); }
    }

    static void validateResult(JsonNode result, EvidencePackManifest pack) {
        require(result.isObject() && RESULT_SCHEMA.equals(result.path("schema").asText()),
                "Model worker result schema is invalid");
        require(pack.sha256().equals(result.path("packManifestSha256").asText()),
                "Model worker result pack identity does not match");
        require(STATUSES.contains(result.path("status").asText()), "Model worker result status is invalid");
        var regions = result.path("regions");
        require(regions.isArray() && regions.size() <= 1_000, "Model worker regions are invalid");
        for (var region : regions) {
            require(region.isObject() && region.path("id").asText().matches("[A-Za-z0-9._-]{1,120}")
                            && Set.of("coarse", "refined").contains(region.path("stage").asText())
                            && Set.of("support", "similar", "contrast").contains(region.path("kind").asText())
                            && finiteNonnegative(region, "x") && finiteNonnegative(region, "y")
                            && finitePositive(region, "width") && finitePositive(region, "height")
                            && finiteUnit(region, "score"),
                    "Model worker region is invalid");
        }
        var serialized = result.toString();
        require(!serialized.matches("(?is).*\"(embedding|embeddings|rawPixels|diagnosis|clinicalScore|tps|cps|treatment)\".*"),
                "Model worker emitted prohibited reusable or clinical output");
    }

    private static String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[1024 * 1024];
            int read; while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.GeneralSecurityException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static boolean finiteNonnegative(JsonNode node, String field) {
        return node.path(field).isNumber() && Double.isFinite(node.path(field).doubleValue())
                && node.path(field).doubleValue() >= 0;
    }
    private static boolean finitePositive(JsonNode node, String field) {
        return finiteNonnegative(node, field) && node.path(field).doubleValue() > 0;
    }
    private static boolean finiteUnit(JsonNode node, String field) {
        return finiteNonnegative(node, field) && node.path(field).doubleValue() <= 1;
    }
}

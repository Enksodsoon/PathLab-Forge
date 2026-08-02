package org.pathlab.forge.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.pathlab.forge.library.DatasetRepository;

/** Runs the separately trained research model against one local WSI at a time. */
public final class AiResearchService implements AutoCloseable {
    private static final Duration TIMEOUT = Duration.ofMinutes(20);
    private final DatasetRepository repository;
    private final Path resultRoot;
    private final Path cli;
    private final Path modelRoot;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();

    public AiResearchService(DatasetRepository repository, Path managedRoot) {
        this(
                repository,
                managedRoot.resolve("ai-research"),
                configuredPath("pathlab.forge.ai.cli", "PATHLAB_FORGE_AI_CLI"),
                configuredPath("pathlab.forge.ai.modelRoot", "PATHLAB_FORGE_AI_MODEL_ROOT"));
    }

    AiResearchService(
            DatasetRepository repository, Path resultRoot, Path cli, Path modelRoot) {
        this.repository = repository;
        this.resultRoot = resultRoot.toAbsolutePath().normalize();
        this.cli = cli;
        this.modelRoot = modelRoot;
    }

    public Status status() {
        var available = cli != null
                && modelRoot != null
                && Files.isRegularFile(cli)
                && Files.isRegularFile(modelRoot.resolve("model_config.json"));
        var detail = available
                ? "Research model is ready for local WSI analysis"
                : "Configure PATHLAB_FORGE_AI_CLI and PATHLAB_FORGE_AI_MODEL_ROOT";
        return new Status(available, busy.get(), detail);
    }

    public Optional<String> result(String datasetId) throws IOException {
        requireDatasetId(datasetId);
        var path = resultPath(datasetId);
        return Files.isRegularFile(path)
                ? Optional.of(Files.readString(path, StandardCharsets.UTF_8))
                : Optional.empty();
    }

    public String analyze(String datasetId) throws IOException {
        requireDatasetId(datasetId);
        var configuration = status();
        if (!configuration.available()) {
            throw new IllegalStateException(configuration.detail());
        }
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("Another WSI analysis is already running");
        }
        try {
            var dataset = repository.find(datasetId)
                    .orElseThrow(() -> new IllegalArgumentException("Dataset was not found"));
            if (dataset.selectedSeries() < 0) {
                throw new IllegalStateException("Select an image series before analysis");
            }
            var source = modelInput(dataset.sourcePath(), dataset.outputPath());
            var output = resultPath(datasetId);
            Files.createDirectories(output.getParent());
            var partial = output.resolveSibling(output.getFileName() + ".partial");
            var log = output.resolveSibling("analysis.log");
            Files.deleteIfExists(partial);
            var command = List.of(
                    cli.toString(),
                    "predict-slide-mil",
                    "--model-root", modelRoot.toString(),
                    "--slide", source.toString(),
                    "--output", partial.toString(),
                    "--batch-size", "8");
            var process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            activeProcess.set(process);
            boolean completed;
            try {
                completed = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("WSI analysis was interrupted", error);
            }
            if (!completed) {
                process.destroyForcibly();
                throw new IOException("WSI analysis exceeded the 20-minute safety limit");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(partial)) {
                throw new IOException("WSI analysis failed: " + boundedLog(log));
            }
            var json = Files.readString(partial, StandardCharsets.UTF_8);
            if (!json.startsWith("{") || !json.contains("\"suspected_regions\"")) {
                throw new IOException("WSI analysis produced an invalid evidence result");
            }
            json = withSourceCoordinateTransform(json, dataset, source);
            Files.writeString(partial, json, StandardCharsets.UTF_8);
            try {
                Files.move(
                        partial,
                        output,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
            }
            return json;
        } finally {
            activeProcess.set(null);
            busy.set(false);
        }
    }

    private Path resultPath(String datasetId) {
        return resultRoot.resolve(datasetId).resolve("analysis.json");
    }

    static Path modelInput(String sourcePath, String outputPath) {
        var source = Path.of(sourcePath).toAbsolutePath().normalize();
        var name = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".vsi") && Files.isRegularFile(source)) {
            return source;
        }
        if (outputPath != null && !outputPath.isBlank()) {
            var staging = Path.of(outputPath).toAbsolutePath().normalize();
            if (Files.isRegularFile(staging)) {
                return staging;
            }
        }
        throw new IllegalStateException(
                name.endsWith(".vsi")
                        ? "Prepare the VSI staging image before AI analysis"
                        : "The local WSI source is unavailable");
    }

    private static String withSourceCoordinateTransform(
            String json, org.pathlab.forge.library.LocalDataset dataset, Path analyzedSource) {
        var original = Path.of(dataset.sourcePath()).toAbsolutePath().normalize();
        var transformed = !analyzedSource.equals(original);
        var scale = transformed ? dataset.downsample() : 1.0;
        var originX = transformed ? dataset.cropX() : 0;
        var originY = transformed ? dataset.cropY() : 0;
        var transform = "\"source_coordinate_transform\":{"
                + "\"origin_x\":" + originX
                + ",\"origin_y\":" + originY
                + ",\"scale_x\":" + scale
                + ",\"scale_y\":" + scale
                + ",\"applied\":" + transformed + "},";
        return "{" + transform + json.substring(1);
    }

    private static void requireDatasetId(String datasetId) {
        if (datasetId == null || !datasetId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Dataset id is invalid");
        }
    }

    private static Path configuredPath(String property, String environment) {
        var value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
        }
        return value == null || value.isBlank()
                ? null
                : Path.of(value).toAbsolutePath().normalize();
    }

    private static String boundedLog(Path log) {
        try {
            var value = Files.readString(log, StandardCharsets.UTF_8).trim();
            return value.length() <= 2_000 ? value : value.substring(value.length() - 2_000);
        } catch (IOException ignored) {
            return "no process log was available";
        }
    }

    @Override
    public void close() {
        var process = activeProcess.getAndSet(null);
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    public record Status(boolean available, boolean busy, String detail) {}
}

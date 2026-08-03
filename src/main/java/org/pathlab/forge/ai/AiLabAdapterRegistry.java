package org.pathlab.forge.ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Public, non-secret capability catalogue for optional local research adapters. */
public final class AiLabAdapterRegistry {
    private AiLabAdapterRegistry() {}

    public static List<AdapterStatus> statuses(Path bracsCli, Path bracsModelRoot) {
        return List.of(
                adapter("histoqc", command("PATHLAB_HISTOQC_CLI"), null, 3_072, 4,
                        "teacher-review", "Configure the pinned HistoQC wrapper"),
                new AdapterStatus("pivot", true, "Built into Forge", 1_024, 3, "teacher-review"),
                adapter("bracs", bracsCli, bracsModelRoot, 3_072, 3,
                        "teacher-review", "Configure PATHLAB_FORGE_AI_CLI and PATHLAB_FORGE_AI_MODEL_ROOT"),
                adapter("wsinfer", command("PATHLAB_WSINFER_CLI"), null, 3_072, 3,
                        "teacher-review", "Configure the bounded WSInfer wrapper"),
                adapter("foundation", command("PATHLAB_FOUNDATION_CLI"),
                        configuredPath("PATHLAB_FOUNDATION_MODEL_ROOT"), 3_072, 3,
                        "shadow", "Configure an approved frozen encoder and checksum manifest"),
                adapter("morphology", command("PATHLAB_MORPHOLOGY_CLI"),
                        configuredPath("PATHLAB_MORPHOLOGY_MODEL_ROOT"), 3_072, 3,
                        "teacher-review", "Configure a pinned approved morphology encoder; unknown stains abstain"),
                adapter("monai-label", command("PATHLAB_MONAI_LABEL_CLI"), null, 3_072, 3,
                        "teacher-review", "Configure the optional local MONAI Label sidecar"),
                adapter("local-llm", command("PATHLAB_LLAMACPP_CLI"),
                        configuredPath("PATHLAB_LLM_MODEL"), 2_560, 3,
                        "teacher-review", "Configure a pinned local GGUF model"));
    }

    private static AdapterStatus adapter(
            String id,
            Path executable,
            Path requiredArtifact,
            int memoryMiB,
            int threads,
            String activation,
            String unavailableDetail) {
        var available = executable != null
                && Files.isRegularFile(executable)
                && (requiredArtifact == null
                        || Files.isRegularFile(requiredArtifact)
                        || Files.isRegularFile(requiredArtifact.resolve("model_config.json")));
        return new AdapterStatus(
                id,
                available,
                available ? "Configured locally; research-only" : unavailableDetail,
                memoryMiB,
                threads,
                activation);
    }

    private static Path command(String environment) {
        return configuredPath(environment);
    }

    private static Path configuredPath(String environment) {
        var value = System.getenv(environment);
        return value == null || value.isBlank()
                ? null
                : Path.of(value).toAbsolutePath().normalize();
    }

    public record AdapterStatus(
            String id,
            boolean available,
            String detail,
            int maxMemoryMiB,
            int maxThreads,
            String activation) {}
}

package org.pathlab.forge.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AiLabAdapterRegistryTest {
    @TempDir Path temporaryDirectory;

    @Test
    void publishesEveryApprovedAdapterWithoutPretendingOptionalRuntimesExist() throws Exception {
        var cli = Files.writeString(temporaryDirectory.resolve("model.exe"), "fixture");
        var model = Files.createDirectories(temporaryDirectory.resolve("model"));
        Files.writeString(model.resolve("model_config.json"), "{}");

        var statuses = AiLabAdapterRegistry.statuses(cli, model);

        assertEquals(
                java.util.List.of("histoqc", "pivot", "bracs", "wsinfer", "foundation", "monai-label", "local-llm"),
                statuses.stream().map(AiLabAdapterRegistry.AdapterStatus::id).toList());
        assertTrue(statuses.stream().filter(item -> item.id().equals("pivot")).findFirst().orElseThrow().available());
        assertTrue(statuses.stream().filter(item -> item.id().equals("bracs")).findFirst().orElseThrow().available());
        assertFalse(statuses.stream().filter(item -> item.id().equals("histoqc")).findFirst().orElseThrow().available());
        assertTrue(statuses.stream().allMatch(item -> item.maxThreads() <= 4));
        assertTrue(statuses.stream().allMatch(item -> item.maxMemoryMiB() <= 3_072));
    }
}

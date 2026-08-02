package org.pathlab.forge.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.library.PropertiesDatasetRepository;

final class AiResearchServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsExplicitConfigurationAndReadsOnlyBoundedDatasetResults() throws Exception {
        var repository = new PropertiesDatasetRepository(temporaryDirectory.resolve("library.properties"));
        var results = temporaryDirectory.resolve("results");
        var unavailable = new AiResearchService(repository, results, null, null);
        assertFalse(unavailable.status().available());
        assertTrue(unavailable.status().detail().contains("PATHLAB_FORGE_AI_CLI"));

        var cli = Files.writeString(temporaryDirectory.resolve("model.exe"), "fixture");
        var model = Files.createDirectories(temporaryDirectory.resolve("model"));
        Files.writeString(model.resolve("model_config.json"), "{}");
        var service = new AiResearchService(repository, results, cli, model);
        assertTrue(service.status().available());

        var output = Files.createDirectories(results.resolve("dataset-1"))
                .resolve("analysis.json");
        Files.writeString(output, "{\"suspected_regions\":[]}");
        assertEquals("{\"suspected_regions\":[]}", service.result("dataset-1").orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> service.result("../outside"));
    }

    @Test
    void usesThePreparedTiffForARealWorldVsiDataset() throws Exception {
        var vsi = Files.writeString(temporaryDirectory.resolve("case.vsi"), "vendor container");
        var staging = Files.writeString(temporaryDirectory.resolve("case.ome.tiff"), "tiff fixture");
        assertEquals(staging.toAbsolutePath(), AiResearchService.modelInput(
                vsi.toString(), staging.toString()));
        assertThrows(
                IllegalStateException.class,
                () -> AiResearchService.modelInput(vsi.toString(), ""));
    }
}

package org.pathlab.forge.library;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PropertiesDatasetRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsDatasetsAcrossRepositoryRestart() throws Exception {
        var storePath = temporaryDirectory.resolve("library.properties");
        var dataset = new LocalDataset(
                "dataset-1",
                "case.ome.tiff",
                temporaryDirectory.resolve("case.ome.tiff").toString(),
                4096,
                DatasetFormat.OME_TIFF,
                DatasetStatus.READY,
                "OME-TIFF signature verified",
                "",
                "");

        new PropertiesDatasetRepository(storePath).save(dataset);
        var restarted = new PropertiesDatasetRepository(storePath);

        assertEquals(java.util.List.of(dataset), restarted.list());
    }
}

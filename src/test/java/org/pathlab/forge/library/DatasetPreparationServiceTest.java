package org.pathlab.forge.library;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DatasetPreparationServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsValidatedManagedOmeCopyThroughPartialFile() throws Exception {
        var source = temporaryDirectory.resolve("case.ome.tiff");
        var bytes = new byte[] {'I', 'I', 42, 0, 1, 2, 3, 4};
        Files.write(source, bytes);
        var repository = new PropertiesDatasetRepository(
                temporaryDirectory.resolve("library.properties"));
        var dataset = new DatasetInspector().inspect(source);
        repository.save(dataset);

        var prepared = new DatasetPreparationService(
                        repository, temporaryDirectory.resolve("managed"))
                .prepare(dataset.id());

        var output = Path.of(prepared.outputPath());
        assertEquals(DatasetStatus.LOCAL_COPY_READY, prepared.status());
        assertTrue(Files.isRegularFile(output));
        assertFalse(Files.exists(output.resolveSibling(output.getFileName() + ".partial")));
        assertArrayEquals(bytes, Files.readAllBytes(output));
        assertEquals(64, prepared.sha256().length());
        assertEquals(prepared, repository.find(dataset.id()).orElseThrow());
    }
}

package org.pathlab.forge.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.library.DatasetStatus;
import org.pathlab.forge.library.PropertiesDatasetRepository;

final class UniversalDatasetImporterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsReadableDatasetWithArbitraryDetectedFormat() throws Exception {
        var source = Files.write(temporaryDirectory.resolve("scan.czi"), new byte[] {1, 2, 3});
        var repository = new PropertiesDatasetRepository(temporaryDirectory.resolve("library.properties"));
        DatasetProbe probe = path -> new DatasetProbe.Result(
                new ReaderDescriptor(
                        "BIO_FORMATS", "zeiss-czi", "Zeiss CZI", List.of("czi"),
                        true, false, true, true),
                "Zeiss CZI",
                List.of(path),
                "b".repeat(64));

        var result = new UniversalDatasetImporter(repository, probe).importPaths(List.of(source));

        assertEquals(1, result.datasets().size());
        assertTrue(result.diagnostics().isEmpty());
        var dataset = repository.list().get(0);
        assertEquals("ZEISS_CZI", dataset.format().name());
        assertEquals("zeiss-czi", dataset.readerId());
        assertEquals(DatasetStatus.VERIFYING_SOURCE, dataset.status());
    }

    @Test
    void retainsOnlyRepairableProbeFailures() throws Exception {
        var missing = Files.write(temporaryDirectory.resolve("missing.vsi"), new byte[] {1});
        var corrupt = Files.write(temporaryDirectory.resolve("corrupt.dat"), new byte[] {2});
        var repository = new PropertiesDatasetRepository(temporaryDirectory.resolve("failures.properties"));
        DatasetProbe probe = path -> {
            if (path.equals(missing)) {
                throw new ImportProbeException(new ImportDiagnostic(
                        ImportDiagnostic.Code.MISSING_COMPANION,
                        "Required ETS companion is missing",
                        List.of(path)));
            }
            throw new ImportProbeException(new ImportDiagnostic(
                    ImportDiagnostic.Code.CORRUPT,
                    "Reader rejected corrupt content",
                    List.of(path)));
        };

        var result = new UniversalDatasetImporter(repository, probe)
                .importPaths(List.of(missing, corrupt));

        assertEquals(1, repository.list().size());
        assertEquals(DatasetStatus.NEEDS_COMPANIONS, repository.list().get(0).status());
        assertEquals(2, result.diagnostics().size());
    }
}

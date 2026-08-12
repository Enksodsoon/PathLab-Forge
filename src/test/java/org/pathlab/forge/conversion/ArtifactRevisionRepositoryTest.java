package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.library.DatasetFormat;
import org.pathlab.forge.library.DatasetStatus;
import org.pathlab.forge.library.LocalDataset;

final class ArtifactRevisionRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void retainsHistoricalRevisionWhenConfigurationChanges() throws Exception {
        var repository = new ArtifactRevisionRepository(temporaryDirectory);
        var firstConfiguration = configured(3, 1.5);
        var first = repository.create(firstConfiguration, 7_557, 7_360);
        repository.save(first.ready("a".repeat(64), "b".repeat(64)));

        var secondConfiguration = firstConfiguration.withExportConfiguration(
                DatasetStatus.READY_TO_CONVERT,
                "changed",
                2,
                512,
                184,
                1.0,
                1,
                0,
                0,
                512,
                184);
        var second = repository.create(secondConfiguration, 512, 184);

        assertNotEquals(first.configurationRevision(), second.configurationRevision());
        assertEquals(2, repository.list(first.datasetId()).size());
        assertEquals(
                ArtifactRevisionStatus.READY,
                repository.find(first.datasetId(), first.id()).orElseThrow().status());
    }

    @Test
    void persistsEditableNamesAndDeletesOnlyTheRequestedOwnedRevision() throws Exception {
        var repository = new ArtifactRevisionRepository(temporaryDirectory);
        var dataset = configured(3, 1.5);
        var first = repository.create(dataset, 7_557, 7_360);
        var second = repository.create(dataset, 7_557, 7_360);
        Files.writeString(Path.of(first.packagePath()), "first package");
        Files.writeString(Path.of(second.packagePath()), "second package");

        repository.save(first.renamed("HER2 focus region"));
        assertEquals(
                "HER2 focus region",
                repository.find(dataset.id(), first.id()).orElseThrow().name());
        assertThrows(IllegalArgumentException.class, () -> first.renamed("x".repeat(81)));
        assertThrows(IllegalArgumentException.class, () -> first.renamed("bad\nname"));

        repository.delete(dataset.id(), first.id());

        assertTrue(repository.find(dataset.id(), first.id()).isEmpty());
        assertTrue(repository.find(dataset.id(), second.id()).isPresent());
        assertTrue(Files.isRegularFile(Path.of(second.packagePath())));
    }

    @Test
    void recordsTheApprovedDynamicOmeProfileForEveryNewArtifact() throws Exception {
        var repository = new ArtifactRevisionRepository(temporaryDirectory);
        var revision = repository.create(
                configured(3, 1.5),
                7_557,
                7_360,
                ArtifactRevisionFormat.OME_DYNAMIC_V1);
        var properties = Files.readString(
                Path.of(revision.omePath()).getParent().resolve("revision.properties"));

        assertTrue(properties.contains("omeProfile=ome-dynamic-v1"));
        assertTrue(properties.contains("omeJpegQuality=75"));
        assertTrue(properties.contains("format=OME_DYNAMIC_V1"));
    }

    private static LocalDataset configured(int series, double downsample) {
        return new LocalDataset(
                        "dataset-1",
                        "case.vsi",
                        "C:\\slides\\case.vsi",
                        100,
                        DatasetFormat.VSI,
                        DatasetStatus.READER_REQUIRED,
                        "ready",
                        "",
                        "",
                        -1,
                        0,
                        0,
                        1.0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        "a".repeat(64),
                        "case.vsi|100|0|" + "b".repeat(64),
                        "",
                        "",
                        "")
                .withExportConfiguration(
                        DatasetStatus.READY_TO_CONVERT,
                        "configured",
                        series,
                        165_845,
                        90_735,
                        downsample,
                        1,
                        69_790,
                        23_372,
                        11_336,
                        11_040);
    }
}

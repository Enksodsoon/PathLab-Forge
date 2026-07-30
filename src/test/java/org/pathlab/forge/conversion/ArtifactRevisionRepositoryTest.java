package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void removesOnlyNewUnapprovedSupersededPayloadsAndKeepsAuditMetadata() throws Exception {
        var managed = temporaryDirectory.resolve("managed-cleanup");
        var repository = new ArtifactRevisionRepository(managed);
        var dataset = configured(0, 1);
        var superseded = repository.create(dataset, 100, 50);
        Files.write(Path.of(superseded.omePath()), new byte[] {1});
        Files.write(Path.of(superseded.packagePath()), new byte[] {2});
        var current = repository.create(dataset, 100, 50);
        Files.write(Path.of(current.omePath()), new byte[] {3});

        var report = repository.cleanupSupersededUnapproved(dataset.id(), current.id());

        assertEquals(2, report.deletedFiles());
        assertTrue(Files.isRegularFile(Path.of(current.omePath())));
        assertTrue(repository.find(dataset.id(), superseded.id()).isPresent());
        assertTrue(Files.notExists(Path.of(superseded.omePath())));
    }

    @Test
    void retainsHistoricalRevisionWhenConfigurationChanges() throws Exception {
        var repository = new ArtifactRevisionRepository(temporaryDirectory);
        var firstConfiguration = configured(3, 1.5);
        var first = repository.create(firstConfiguration, 7_557, 7_360);
        assertEquals("ome-dynamic-v1", first.omeProfile());
        assertEquals(75, first.omeJpegQuality());
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
    void loadsLegacyRevisionWithoutDynamicProfile() throws Exception {
        var repository = new ArtifactRevisionRepository(temporaryDirectory);
        var revision = repository.create(configured(0, 1), 100, 50);
        var file = Path.of(revision.omePath()).getParent().resolve("revision.properties");
        var text = Files.readString(file)
                .replaceAll("(?m)^omeProfile=.*\\R", "")
                .replaceAll("(?m)^omeJpegQuality=.*\\R", "");
        Files.writeString(file, text);

        var loaded = repository.find(revision.datasetId(), revision.id()).orElseThrow();

        assertEquals("", loaded.omeProfile());
        assertEquals(0, loaded.omeJpegQuality());
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

package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.derivative.DerivativeEngine;
import org.pathlab.forge.derivative.DerivativeInfo;
import org.pathlab.forge.library.DatasetFormat;
import org.pathlab.forge.library.DatasetInspector;
import org.pathlab.forge.library.DatasetStatus;
import org.pathlab.forge.library.PropertiesDatasetRepository;

final class ConversionVerificationRaceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void inspectionPersistsSelectionWhileSourceDigestIsStillRunning() throws Exception {
        var source = temporaryDirectory.resolve("case.vsi");
        Files.write(source, new byte[] {1, 2, 3});
        Files.write(
                Files.createDirectories(temporaryDirectory.resolve("case"))
                        .resolve("frame.ets"),
                new byte[] {4, 5, 6});
        var repository = new PropertiesDatasetRepository(
                temporaryDirectory.resolve("library.properties"));
        var pending = new DatasetInspector().inspectFast(source);
        repository.save(pending);

        try (var service = new ConversionService(
                repository, inspectingEngine(), unavailableDerivative(), temporaryDirectory.resolve("managed"))) {
            var series = service.inspectWhileVerifying(pending.id());
            var persisted = repository.find(pending.id()).orElseThrow();

            assertEquals(1, series.size());
            assertEquals(DatasetStatus.VERIFYING_SOURCE, persisted.status());
            assertEquals(4, persisted.selectedSeries());
            assertEquals(2000, persisted.width());
            assertEquals(1000, persisted.height());
            assertEquals(2000, persisted.cropWidth());
            assertEquals(1000, persisted.cropHeight());
            assertFalse(persisted.configurationRevision().isBlank());
        }
    }

    @Test
    void inspectionDoesNotOverwriteVerificationThatFinishesDuringReaderWork() throws Exception {
        var source = temporaryDirectory.resolve("cohort.svs");
        Files.write(source, new byte[] {'I', 'I', 42, 0, 1, 2, 3});
        var repository = new PropertiesDatasetRepository(
                temporaryDirectory.resolve("svs-library.properties"));
        var pending = new DatasetInspector().inspectFast(source);
        repository.save(pending);
        var verifiedFingerprint = "a".repeat(64);

        var engine = inspectingEngine(() -> {
            try {
                repository.update(pending.id(), current -> current.withSourceIdentity(
                        DatasetStatus.READY,
                        "SVS verified",
                        verifiedFingerprint,
                        current.sourceInventory()));
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        });
        try (var service = new ConversionService(
                repository, engine, unavailableDerivative(), temporaryDirectory.resolve("managed-svs"))) {
            service.inspectWhileVerifying(pending.id());
            var persisted = repository.find(pending.id()).orElseThrow();

            assertEquals(DatasetFormat.SVS, persisted.format());
            assertEquals(DatasetStatus.READY_TO_CONVERT, persisted.status());
            assertEquals(verifiedFingerprint, persisted.sourceFingerprint());
            assertEquals(4, persisted.selectedSeries());
        }
    }

    private static ConversionEngine inspectingEngine() {
        return inspectingEngine(() -> {});
    }

    private static ConversionEngine inspectingEngine(Runnable duringInspection) {
        return new ConversionEngine() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String runtimeDescription() {
                return "verification race test";
            }

            @Override
            public List<SeriesInfo> inspect(Path ignored) {
                duringInspection.run();
                return List.of(new SeriesInfo(
                        4, "Tissue", 2000, 1000, 3, 1, 1, "uint8", 0.25, 0.25, "µm"));
            }

            @Override
            public void convert(Path source, int seriesIndex, Path output) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static DerivativeEngine unavailableDerivative() {
        return new DerivativeEngine() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String description() {
                return "unavailable";
            }

            @Override
            public void optimizeOme(Path source, Path output, int width, int height) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DerivativeInfo generateDzi(Path source, Path output, int width, int height) {
                throw new UnsupportedOperationException();
            }
        };
    }
}

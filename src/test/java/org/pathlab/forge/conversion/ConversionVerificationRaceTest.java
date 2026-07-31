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

    private static ConversionEngine inspectingEngine() {
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

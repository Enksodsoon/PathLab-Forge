package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.derivative.DerivativeEngine;
import org.pathlab.forge.derivative.DerivativeInfo;
import org.pathlab.forge.library.DatasetInspector;
import org.pathlab.forge.library.DatasetStatus;
import org.pathlab.forge.library.PropertiesDatasetRepository;

final class ConversionSourceRevalidationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsCompanionRemovedAfterImport() throws Exception {
        var fixture = fixture();
        Files.delete(fixture.companion());

        var error = assertThrows(
                IllegalStateException.class,
                () -> fixture.service().start(fixture.datasetId()));

        assertTrue(error.getMessage().contains("companion"));
    }

    @Test
    void rejectsCompanionMutatedAfterImport() throws Exception {
        var fixture = fixture();
        Files.write(fixture.companion(), new byte[] {9, 8, 7});

        var error = assertThrows(
                IllegalStateException.class,
                () -> fixture.service().start(fixture.datasetId()));

        assertTrue(error.getMessage().contains("changed"));
    }

    @Test
    void cancellationInterruptsConversionAndRemovesPartialOutput() throws Exception {
        var source = temporaryDirectory.resolve("case.ome.tif");
        Files.write(source, new byte[] {'I', 'I', 42, 0, 1, 2, 3});
        var dataset = new DatasetInspector()
                .inspect(source)
                .withExportConfiguration(
                        DatasetStatus.READY_TO_CONVERT,
                        "configured",
                        0,
                        1000,
                        500,
                        1.0,
                        1,
                        0,
                        0,
                        1000,
                        500);
        var repository = new PropertiesDatasetRepository(
                temporaryDirectory.resolve("cancel-library.properties"));
        repository.save(dataset);
        var entered = new CountDownLatch(1);
        try (var service = new ConversionService(
                repository,
                new ConversionEngine() {
                    @Override
                    public boolean available() {
                        return true;
                    }

                    @Override
                    public String runtimeDescription() {
                        return "test";
                    }

                    @Override
                    public List<SeriesInfo> inspect(Path ignored) {
                        return List.of();
                    }

                    @Override
                    public void convert(Path ignored, int series, Path output)
                            throws IOException {
                        Files.write(output, new byte[] {'I', 'I', 42, 0});
                        entered.countDown();
                        try {
                            Thread.sleep(TimeUnit.MINUTES.toMillis(5));
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IOException("cancelled", error);
                        }
                    }
                },
                unavailableDerivative(),
                temporaryDirectory.resolve("cancel-managed"))) {
            var started = service.start(dataset.id());
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            service.cancel(dataset.id());
            for (var attempt = 0; attempt < 100; attempt++) {
                if (repository.find(dataset.id()).orElseThrow().status()
                        == DatasetStatus.CANCELLED) {
                    break;
                }
                Thread.sleep(10);
            }

            assertEquals(
                    DatasetStatus.CANCELLED,
                    repository.find(dataset.id()).orElseThrow().status());
            assertFalse(Files.exists(temporaryDirectory
                    .resolve("cancel-managed")
                    .resolve(dataset.id())
                    .resolve("artifacts")
                    .resolve(started.currentArtifactRevision())
                    .resolve("render.partial.ome.tif")));
        }
    }

    @Test
    void reportsOmeOptimizationInsteadOfAppearingStuckAfterRenderedRgb() throws Exception {
        var source = temporaryDirectory.resolve("progress.ome.tif");
        Files.write(source, new byte[] {'I', 'I', 42, 0, 1, 2, 3});
        var dataset = new DatasetInspector()
                .inspect(source)
                .withExportConfiguration(
                        DatasetStatus.READY_TO_CONVERT,
                        "configured",
                        0,
                        1000,
                        500,
                        1.0,
                        1,
                        0,
                        0,
                        1000,
                        500);
        var repository = new PropertiesDatasetRepository(
                temporaryDirectory.resolve("progress-library.properties"));
        repository.save(dataset);
        var optimizing = new CountDownLatch(1);
        try (var service = new ConversionService(
                repository,
                new ConversionEngine() {
                    @Override
                    public boolean available() {
                        return true;
                    }

                    @Override
                    public String runtimeDescription() {
                        return "test";
                    }

                    @Override
                    public List<SeriesInfo> inspect(Path ignored) {
                        return List.of();
                    }

                    @Override
                    public void convert(Path ignored, int series, Path output)
                            throws IOException {
                        Files.write(output, new byte[] {'I', 'I', 42, 0, 1});
                    }
                },
                new DerivativeEngine() {
                    @Override
                    public boolean available() {
                        return true;
                    }

                    @Override
                    public String description() {
                        return "test derivative";
                    }

                    @Override
                    public void optimizeOme(
                            Path rendered, Path output, int width, int height)
                            throws IOException {
                        optimizing.countDown();
                        try {
                            Thread.sleep(TimeUnit.MINUTES.toMillis(5));
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IOException("cancelled", error);
                        }
                    }

                    @Override
                    public DerivativeInfo generateDzi(
                            Path source, Path output, int width, int height) {
                        throw new AssertionError("DZI generation must not start");
                    }
                },
                temporaryDirectory.resolve("progress-managed"))) {
            service.start(dataset.id());
            assertTrue(optimizing.await(5, TimeUnit.SECONDS));

            var inProgress = repository.find(dataset.id()).orElseThrow();
            assertEquals(DatasetStatus.OPTIMIZING_OME, inProgress.status());
            assertTrue(inProgress.detail().contains("compressing"));

            service.cancel(dataset.id());
        }
    }

    private Fixture fixture() throws Exception {
        var source = temporaryDirectory.resolve("case.vsi");
        Files.write(source, new byte[] {1, 2, 3});
        var companion = Files.createDirectories(temporaryDirectory.resolve("_case_"))
                .resolve("frame.ets");
        Files.write(companion, new byte[] {4, 5, 6});
        var dataset = new DatasetInspector()
                .inspect(source)
                .withExportConfiguration(
                        DatasetStatus.READY_TO_CONVERT,
                        "configured",
                        0,
                        1000,
                        500,
                        1.0,
                        1,
                        0,
                        0,
                        1000,
                        500);
        var repository = new PropertiesDatasetRepository(
                temporaryDirectory.resolve("library.properties"));
        repository.save(dataset);
        var service = new ConversionService(
                repository,
                new ConversionEngine() {
                    @Override
                    public boolean available() {
                        return true;
                    }

                    @Override
                    public String runtimeDescription() {
                        return "test";
                    }

                    @Override
                    public List<SeriesInfo> inspect(Path ignored) {
                        return List.of();
                    }

                    @Override
                    public void convert(Path ignored, int series, Path output) {
                        throw new AssertionError("Conversion must not start");
                    }
                },
                unavailableDerivative(),
                temporaryDirectory.resolve("managed"));
        return new Fixture(dataset.id(), companion, service);
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
            public void optimizeOme(Path source, Path output, int width, int height)
                    throws IOException {
                throw new IOException("unavailable");
            }

            @Override
            public DerivativeInfo generateDzi(
                    Path source, Path output, int width, int height) throws IOException {
                throw new IOException("unavailable");
            }
        };
    }

    private record Fixture(String datasetId, Path companion, ConversionService service) {}
}

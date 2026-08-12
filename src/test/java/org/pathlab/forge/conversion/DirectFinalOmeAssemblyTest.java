package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.derivative.DerivativeEngine;
import org.pathlab.forge.derivative.DerivativeInfo;
import org.pathlab.forge.library.DatasetInspector;
import org.pathlab.forge.library.DatasetStatus;
import org.pathlab.forge.library.PropertiesDatasetRepository;

final class DirectFinalOmeAssemblyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void parallelRegionsBecomeFinalPyramidWithoutSecondFullImageRewrite() throws Exception {
        var source = temporaryDirectory.resolve("case.vsi");
        Files.write(source, new byte[] {1, 2, 3});
        Files.write(
                Files.createDirectories(temporaryDirectory.resolve("case")).resolve("frame.ets"),
                new byte[] {4, 5, 6});
        var dataset = new DatasetInspector()
                .inspect(source)
                .withExportConfiguration(
                        DatasetStatus.READY_TO_CONVERT,
                        "configured",
                        0,
                        30_000,
                        10_000,
                        1,
                        1,
                        0,
                        0,
                        30_000,
                        10_000);
        var repository = new PropertiesDatasetRepository(
                temporaryDirectory.resolve("library.properties"));
        repository.save(dataset);
        var directAssemblies = new AtomicInteger();
        var optimizationRewrites = new AtomicInteger();
        var observedWorkers = new AtomicInteger();
        ConversionEngine engine = new ConversionEngine() {
            @Override public boolean available() { return true; }
            @Override public String runtimeDescription() { return "test"; }
            @Override public List<SeriesInfo> inspect(Path ignored) { return List.of(); }
            @Override public void convert(Path ignored, int series, Path output) {
                throw new AssertionError("Single conversion path must not run");
            }
            @Override public boolean supportsParallelRegions() { return true; }
            @Override
            public List<Path> convertRegions(
                    ConversionRequest request, Path outputDirectory, int workers)
                    throws IOException {
                observedWorkers.set(workers);
                var first = outputDirectory.resolve("first.tif");
                var second = outputDirectory.resolve("second.tif");
                Files.write(first, new byte[] {'I', 'I', 42, 0});
                Files.write(second, new byte[] {'I', 'I', 42, 0});
                return List.of(first, second);
            }
        };
        DerivativeEngine derivative = new DerivativeEngine() {
            @Override public boolean available() { return true; }
            @Override public String description() { return "test"; }
            @Override public boolean supportsDirectFinalOme() { return true; }
            @Override
            public void assembleRegionsFinal(
                    List<Path> regions, Path output, int width, int height) throws IOException {
                directAssemblies.incrementAndGet();
                Files.write(output, new byte[] {'I', 'I', 42, 0, 1, 2, 3, 4});
            }
            @Override
            public void optimizeOme(Path rendered, Path output, int width, int height) {
                optimizationRewrites.incrementAndGet();
            }
            @Override
            public DerivativeInfo generateDzi(Path ome, Path root, int width, int height)
                    throws IOException {
                throw new IOException("stop after direct final OME");
            }
        };

        var priorBudget = System.getProperty("pathlab.forge.secondsBudget.enabled");
        System.setProperty("pathlab.forge.secondsBudget.enabled", "false");
        try {
            try (var service = new ConversionService(
                    repository, engine, derivative, temporaryDirectory.resolve("managed"))) {
                service.start(dataset.id());
                for (var attempt = 0; attempt < 200; attempt++) {
                    if (repository.find(dataset.id()).orElseThrow().status()
                            == DatasetStatus.FAILED) {
                        break;
                    }
                    Thread.sleep(10);
                }
            }
        } finally {
            if (priorBudget == null) {
                System.clearProperty("pathlab.forge.secondsBudget.enabled");
            } else {
                System.setProperty("pathlab.forge.secondsBudget.enabled", priorBudget);
            }
        }

        assertEquals(1, directAssemblies.get());
        assertEquals(0, optimizationRewrites.get());
        assertEquals(
                ConversionService.parallelRgbWorkers(
                        Runtime.getRuntime().availableProcessors(),
                        org.pathlab.forge.runtime.RuntimeProfile.system().maxConversionWorkers(),
                        false),
                observedWorkers.get());
    }
}

package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.derivative.DerivativeEngine;
import org.pathlab.forge.derivative.DerivativeInfo;
import org.pathlab.forge.library.DatasetInspector;
import org.pathlab.forge.library.PropertiesDatasetRepository;

final class ConversionPreviewTest {
    @TempDir
    Path tempDirectory;

    @Test
    void adoptsTheExistingPreviewWhenOnlyTheConversionConfigurationChanged() throws Exception {
        var source = tempDirectory.resolve("stable-source.ome.tif");
        Files.write(source, new byte[] {'I', 'I', 42, 0, 1});
        var repository = new PropertiesDatasetRepository(tempDirectory.resolve("stable.properties"));
        var dataset = new DatasetInspector().inspect(source);
        repository.save(dataset);
        ConversionEngine engine = new ConversionEngine() {
            @Override public boolean available() { return true; }
            @Override public String runtimeDescription() { return "stable preview test"; }
            @Override public List<SeriesInfo> inspect(Path ignored) {
                return List.of(new SeriesInfo(
                        0, "Tissue", 4_000, 2_000, 3, 1, 1, "uint8", 0.25, 0.25, "µm"));
            }
            @Override public void convert(Path ignored, int series, Path output) {}
        };
        DerivativeEngine derivatives = new DerivativeEngine() {
            @Override public boolean available() { return true; }
            @Override public String description() { return "must reuse existing viewer cache"; }
            @Override public void optimizeOme(Path input, Path output, int width, int height) {}
            @Override public DerivativeInfo generateDzi(Path input, Path root, int width, int height) {
                throw new AssertionError("Existing viewer cache must be adopted");
            }
            @Override public DerivativeInfo generateViewerDzi(
                    Path input, Path root, int width, int height) {
                throw new AssertionError("Existing viewer cache must be adopted");
            }
        };
        var managed = tempDirectory.resolve("managed-stable");
        try (var service = new ConversionService(repository, engine, derivatives, managed)) {
            service.inspect(dataset.id());
        }
        var configured = repository.find(dataset.id()).orElseThrow();
        var legacy = Files.createDirectories(managed
                .resolve(dataset.id()).resolve("previews").resolve("pv5").resolve("old-config"));
        Files.writeString(legacy.resolve("slide.dzi"), "<Image />");
        Files.writeString(legacy.resolve("preview-dimensions.txt"), "4000,2000");

        try (var service = new ConversionService(repository, engine, derivatives, managed)) {
            var preview = service.preview(dataset.id());
            var expectedIdentity = configured.sourceFingerprint().substring(0, 16) + "-s0";
            assertEquals(expectedIdentity, preview.root().getFileName().toString());
            assertTrue(Files.isRegularFile(preview.root().resolve("slide.dzi")));
            assertTrue(Files.isRegularFile(preview.root().resolve("preview-source.txt")));
            assertFalse(Files.exists(legacy));
        }
    }

    @Test
    void requestsResourceBoundedVsiPreviewAndRemovesTheTemporaryOme() throws Exception {
        var source = tempDirectory.resolve("case.vsi");
        Files.write(source, new byte[] {1, 2, 3});
        Files.write(
                Files.createDirectories(tempDirectory.resolve("case")).resolve("frame.ets"),
                new byte[] {4, 5, 6});
        var repository =
                new PropertiesDatasetRepository(tempDirectory.resolve("library.properties"));
        var dataset = new DatasetInspector().inspect(source);
        repository.save(dataset);
        var obsoletePreview = Files.createDirectories(tempDirectory
                .resolve("managed")
                .resolve(dataset.id())
                .resolve("previews")
                .resolve("native-rgb-v3")
                .resolve("old-revision"));
        Files.writeString(obsoletePreview.resolve("slide.dzi"), "<Image />");
        var requestedMaxDimension = new AtomicInteger();
        var derivativeReadTemporaryOme = new AtomicBoolean();
        var viewerBuilds = new AtomicInteger();
        ConversionEngine engine = new ConversionEngine() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String runtimeDescription() {
                return "preview test";
            }

            @Override
            public List<SeriesInfo> inspect(Path ignored) {
                return List.of(new SeriesInfo(
                        0, "Tissue", 18_032, 9_148, 3, 1, 1, "uint8", 0.27, 0.27, "µm"));
            }

            @Override
            public void convert(Path ignored, int seriesIndex, Path output) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PreviewSource renderPreview(
                    Path ignored, int seriesIndex, Path output, int maxDimension)
                    throws java.io.IOException {
                requestedMaxDimension.set(maxDimension);
                Files.write(output, new byte[] {'I', 'I', 43, 0});
                return new PreviewSource(output, 9_016, 4_574);
            }
        };
        DerivativeEngine derivatives = new DerivativeEngine() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String description() {
                return "preview derivative test";
            }

            @Override
            public void optimizeOme(Path input, Path output, int width, int height) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DerivativeInfo generateDzi(
                    Path input, Path outputRoot, int width, int height)
                    throws java.io.IOException {
                throw new AssertionError("Viewer preparation must skip production quality selection");
            }

            @Override
            public DerivativeInfo generateViewerDzi(
                    Path input, Path outputRoot, int width, int height)
                    throws java.io.IOException {
                viewerBuilds.incrementAndGet();
                derivativeReadTemporaryOme.set(Files.isRegularFile(input));
                Files.createDirectories(outputRoot);
                Files.writeString(outputRoot.resolve("slide.dzi"), "<Image />");
                return new DerivativeInfo(outputRoot, 9, 1, 0, "preview");
            }
        };

        try (var service = new ConversionService(
                repository, engine, derivatives, tempDirectory.resolve("managed"))) {
            service.inspect(dataset.id());
            var preview = service.preview(dataset.id());

            assertEquals(9_016, requestedMaxDimension.get());
            assertEquals(9_016, preview.width());
            assertEquals(4_574, preview.height());
            assertTrue(derivativeReadTemporaryOme.get());
            assertTrue(preview.root().toString().contains("pv5"));
            assertTrue(Files.notExists(preview.root().resolve("source-preview.ome.tif")));
            assertTrue(Files.notExists(obsoletePreview));

            var firstRoot = preview.root();
            service.selectSeries(dataset.id(), 0, 2.0, 100, 100, 8_000, 4_000);
            var afterConfigurationChange = service.preview(dataset.id());
            assertEquals(firstRoot, afterConfigurationChange.root());
            assertEquals(1, viewerBuilds.get());
        }

        var restartInspections = new AtomicInteger();
        ConversionEngine restartEngine = new ConversionEngine() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String runtimeDescription() {
                return "cached preview test";
            }

            @Override
            public List<SeriesInfo> inspect(Path ignored) {
                restartInspections.incrementAndGet();
                return List.of(new SeriesInfo(
                        0, "Tissue", 18_032, 9_148, 3, 1, 1, "uint8", 0.27, 0.27, "µm"));
            }

            @Override
            public void convert(Path ignored, int seriesIndex, Path output) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean supportsDirectTiles() {
                return true;
            }

            @Override
            public DirectTileSource directTileSource(Path ignored, int seriesIndex) {
                return new DirectTileSource(18_032, 9_148, 512);
            }

            @Override
            public byte[] readDirectTile(
                    Path ignored, int seriesIndex, int level, int tileX, int tileY) {
                return new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
            }
        };
        try (var restartedService = new ConversionService(
                repository, restartEngine, derivatives, tempDirectory.resolve("managed"))) {
            var cached = restartedService.preview(dataset.id());

            assertEquals(9_016, cached.width());
            assertEquals(4_574, cached.height());
            assertEquals(0, restartInspections.get());
            assertEquals(18_032, restartedService.directPreview(dataset.id()).width());
            assertEquals(
                    4,
                    restartedService.directPreviewTile(dataset.id(), 15, 0, 0).length);
        }
    }
}

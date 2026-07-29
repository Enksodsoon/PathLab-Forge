package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            assertTrue(preview.root().toString().contains("efficient-rgb-2x-v4"));
            assertTrue(Files.notExists(preview.root().resolve("source-preview.ome.tif")));
            assertTrue(Files.notExists(obsoletePreview));
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
        };
        try (var restartedService = new ConversionService(
                repository, restartEngine, derivatives, tempDirectory.resolve("managed"))) {
            var cached = restartedService.preview(dataset.id());

            assertEquals(9_016, cached.width());
            assertEquals(4_574, cached.height());
            assertEquals(0, restartInspections.get());
        }
    }
}

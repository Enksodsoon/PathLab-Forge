package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    void requestsAHighDetailBoundedVsiPreview() throws Exception {
        var source = tempDirectory.resolve("case.vsi");
        Files.write(source, new byte[] {1, 2, 3});
        Files.write(
                Files.createDirectories(tempDirectory.resolve("case")).resolve("frame.ets"),
                new byte[] {4, 5, 6});
        var repository =
                new PropertiesDatasetRepository(tempDirectory.resolve("library.properties"));
        var dataset = new DatasetInspector().inspect(source);
        repository.save(dataset);
        var requestedMaxDimension = new AtomicInteger();
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
                        0, "Tissue", 72_792, 66_004, 3, 1, 1, "uint8", 0.27, 0.27, "µm"));
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
                return new PreviewSource(output, maxDimension, 11_140);
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
                Files.createDirectories(outputRoot);
                Files.writeString(outputRoot.resolve("slide.dzi"), "<Image />");
                return new DerivativeInfo(outputRoot, 9, 1, 0, "preview");
            }
        };

        try (var service = new ConversionService(
                repository, engine, derivatives, tempDirectory.resolve("managed"))) {
            service.inspect(dataset.id());
            var preview = service.preview(dataset.id());

            assertEquals(12_288, requestedMaxDimension.get());
            assertEquals(12_288, preview.width());
            assertTrue(preview.root().toString().contains("rgb-12288-v2"));
        }
    }
}

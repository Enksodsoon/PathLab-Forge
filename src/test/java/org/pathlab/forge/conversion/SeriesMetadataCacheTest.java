package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

final class SeriesMetadataCacheTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void servesThePreparedOmePreviewThumbnailWithoutReadingTheFullSeries() throws Exception {
        var source = temporaryDirectory.resolve("prepared.ome.tif");
        Files.write(source, new byte[] {'I', 'I', 42, 0, 1});
        var repository = new PropertiesDatasetRepository(temporaryDirectory.resolve("prepared.properties"));
        var dataset = new DatasetInspector().inspect(source);
        repository.save(dataset);
        var inspections = new AtomicInteger();
        var unsafeThumbnailReads = new AtomicInteger();
        var managed = temporaryDirectory.resolve("managed-prepared");
        var thumbnail = new byte[] {(byte) 0xff, (byte) 0xd8, 9, 8, (byte) 0xff, (byte) 0xd9};

        try (var service = new ConversionService(
                repository,
                engine(inspections, unsafeThumbnailReads, new byte[] {1}),
                unavailableDerivative(),
                managed)) {
            service.inspect(dataset.id());
            var selected = repository.find(dataset.id()).orElseThrow();
            var preview = Files.createDirectories(managed
                    .resolve(dataset.id())
                    .resolve("previews")
                    .resolve("pv5")
                    .resolve(selected.sourceFingerprint().substring(0, 16) + "-s0"));
            Files.writeString(preview.resolve("slide.dzi"), "<Image />");
            Files.writeString(preview.resolve("preview-dimensions.txt"), "18032,9148");
            Files.write(preview.resolve("thumbnail.jpg"), thumbnail);

            assertArrayEquals(thumbnail, service.seriesThumbnail(dataset.id(), 0));
            assertEquals(0, unsafeThumbnailReads.get());
        }
    }

    @Test
    void restoresVerifiedSeriesAndThumbnailWithoutReinspection() throws Exception {
        var source = temporaryDirectory.resolve("slide.ome.tif");
        Files.write(source, new byte[] {'I', 'I', 42, 0, 1, 2, 3});
        var repository = new PropertiesDatasetRepository(
                temporaryDirectory.resolve("library.properties"));
        var dataset = new DatasetInspector().inspect(source);
        repository.save(dataset);
        var inspections = new AtomicInteger();
        var thumbnailRenders = new AtomicInteger();
        var thumbnail = new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9};
        var managed = temporaryDirectory.resolve("managed");

        try (var service = new ConversionService(
                repository,
                engine(inspections, thumbnailRenders, thumbnail),
                unavailableDerivative(),
                managed)) {
            var series = service.inspect(dataset.id());
            assertEquals(1, series.size());
            assertArrayEquals(thumbnail, service.seriesThumbnail(dataset.id(), 0));
        }

        try (var restarted = new ConversionService(
                repository,
                engine(inspections, thumbnailRenders, thumbnail),
                unavailableDerivative(),
                managed)) {
            var restored = restarted.inspect(dataset.id());
            assertEquals("Tissue", restored.get(0).name());
            assertArrayEquals(thumbnail, restarted.seriesThumbnail(dataset.id(), 0));
            assertTrue(repository
                    .find(dataset.id())
                    .orElseThrow()
                    .detail()
                    .contains("loaded instantly"));
        }

        assertEquals(1, inspections.get());
        assertEquals(1, thumbnailRenders.get());
    }

    @Test
    void rejectsTraversalAndIgnoresAChangedFingerprint() throws Exception {
        var cache = new SeriesMetadataCache(temporaryDirectory.resolve("managed"));
        var series = List.of(new SeriesInfo(
                0, "Overview", 8000, 6000, 3, 1, 1, "uint8", 0.5, 0.5, "µm", 4));
        cache.save("dataset", "fingerprint-one", series);

        assertTrue(cache.load("dataset", "fingerprint-two").isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> cache.thumbnailFile("..", "fingerprint-one", 0));
    }

    private static ConversionEngine engine(
            AtomicInteger inspections, AtomicInteger thumbnails, byte[] thumbnail) {
        return new ConversionEngine() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String runtimeDescription() {
                return "series cache test";
            }

            @Override
            public List<SeriesInfo> inspect(Path ignored) {
                inspections.incrementAndGet();
                return List.of(new SeriesInfo(
                        0, "Tissue", 18_032, 9_148, 3, 1, 1, "uint8", 0.27, 0.27, "µm", 5));
            }

            @Override
            public void convert(Path ignored, int seriesIndex, Path output) {
                throw new UnsupportedOperationException();
            }

            @Override
            public byte[] seriesThumbnail(Path ignored, int seriesIndex, int maxDimension) {
                assertEquals(320, maxDimension);
                thumbnails.incrementAndGet();
                return thumbnail;
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
            public DerivativeInfo generateDzi(
                    Path source, Path output, int width, int height) {
                throw new UnsupportedOperationException();
            }
        };
    }
}

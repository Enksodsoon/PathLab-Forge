package org.pathlab.forge.analysis;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.annotation.AnnotationRepository;
import org.pathlab.forge.conversion.BioFormatsEngine;
import org.pathlab.forge.conversion.ConversionService;
import org.pathlab.forge.derivative.VipsRuntime;
import org.pathlab.forge.library.DatasetInspector;
import org.pathlab.forge.library.PropertiesDatasetRepository;

final class HeRealFileIntegrationTest {
    @TempDir Path temp;

    @Test
    void analyzesOriginalRgbPixelsFromARealEligibleSlide() throws Exception {
        var sourceValue = System.getProperty("pathlab.forge.test.heSource", "");
        var runtimeValue = System.getProperty("pathlab.forge.test.runtimeRoot", "");
        Assumptions.assumeTrue(!sourceValue.isBlank() && !runtimeValue.isBlank());
        var source = Path.of(sourceValue);
        var managed = temp.resolve("managed");
        var repository = new PropertiesDatasetRepository(temp.resolve("library.properties"));
        var dataset = new DatasetInspector().inspect(source);
        repository.save(dataset);
        var annotations = new AnnotationRepository(managed);
        try (var conversion = new ConversionService(
                repository,
                BioFormatsEngine.discover(Path.of(runtimeValue)),
                VipsRuntime.discover(Path.of(runtimeValue)),
                managed)) {
            var series = conversion.inspect(dataset.id()).stream()
                    .filter(item -> item.isRgbPlane() && item.width() >= 256 && item.height() >= 256)
                    .findFirst().orElseThrow();
            conversion.selectSeries(dataset.id(), series.index(), 1, 0, 0, series.width(), series.height());
            var roi = annotations.create(dataset.id(), "rectangle", "0,0;256,256", "H&E acceptance", "#f3b33d");
            var result = new HeAnalysisService(repository, annotations, conversion, managed)
                    .analyze(dataset.id(), roi.id(), 0.15, 0.15);

            assertTrue(result.sampledPixels() > 0);
            assertTrue(Double.isFinite(result.hematoxylin().meanOd()));
            assertTrue(Double.isFinite(result.eosin().meanOd()));
        }
    }
}

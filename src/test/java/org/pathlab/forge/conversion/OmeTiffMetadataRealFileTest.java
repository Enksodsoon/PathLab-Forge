package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.derivative.OmeTiffMetadataInjector;

final class OmeTiffMetadataRealFileTest {
    @TempDir Path temporary;

    @Test
    void bioFormatsRecognizesInjectedSelectedViewAsOmeTiff() throws Exception {
        var configured = System.getProperty("pathlab.forge.test.omeMetadataTarget", "");
        var runtime = System.getProperty("pathlab.forge.test.runtimeRoot", "");
        assumeTrue(!configured.isBlank() && !runtime.isBlank());
        var source = Path.of(configured);
        assumeTrue(Files.isRegularFile(source));
        var copy = temporary.resolve("selected-view.ome.tif");
        Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);

        OmeTiffMetadataInjector.inject(
                copy, 2220, 2967, "c".repeat(64), "PATHOLOGY_STANDARD");

        try (var engine = BioFormatsEngine.discover(Path.of(runtime))) {
            assertTrue(engine.available());
            var series = engine.inspect(copy);
            assertFalse(series.isEmpty());
            assertEquals(2220, series.get(0).width());
            assertEquals(2967, series.get(0).height());
            assertEquals(3, series.get(0).channels());
        }
    }
}

package org.pathlab.forge.derivative;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

final class RealQualityProbeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    @EnabledIfSystemProperty(named = "pathlab.forge.test.ome", matches = ".+")
    void evaluatesNativeResolutionRoisFromARealOme() throws Exception {
        var ome = Path.of(System.getProperty("pathlab.forge.test.ome"));
        var width = Integer.getInteger("pathlab.forge.test.ome.width");
        var height = Integer.getInteger("pathlab.forge.test.ome.height");
        var runtime = VipsRuntime.discover(temporaryDirectory);

        var selection = runtime.selectDziQuality(
                ome, temporaryDirectory.resolve("quality"), width, height);

        System.out.printf(
                "{\"quality\":%d,\"minimumWindowedSsim\":%.9f,"
                        + "\"meanDeltaE00\":%.9f}%n",
                selection.quality(),
                selection.minimumWindowedSsim(),
                selection.meanDeltaE00());
        assertTrue(selection.minimumWindowedSsim()
                >= AdaptiveJpegQualitySelector.MINIMUM_SSIM);
        assertTrue(selection.meanDeltaE00()
                <= AdaptiveJpegQualitySelector.MAXIMUM_MEAN_DELTA_E00);
    }

    @Test
    @EnabledIfSystemProperty(named = "pathlab.forge.test.fullDzi", matches = "true")
    void generatesAndMeasuresAFullRealDzi() throws Exception {
        var ome = Path.of(System.getProperty("pathlab.forge.test.ome"));
        var width = Integer.getInteger("pathlab.forge.test.ome.width");
        var height = Integer.getInteger("pathlab.forge.test.ome.height");
        var runtime = VipsRuntime.discover(temporaryDirectory);
        var started = System.nanoTime();

        var derivative =
                runtime.generateDzi(ome, temporaryDirectory.resolve("derivative"), width, height);
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        System.out.printf(
                "{\"elapsedMillis\":%d,\"bytes\":%d,\"fileCount\":%d,"
                        + "\"tileCount\":%d,\"quality\":%d,\"minimumWindowedSsim\":%.9f,"
                        + "\"meanDeltaE00\":%.9f}%n",
                elapsedMillis,
                derivative.bytes(),
                derivative.fileCount(),
                derivative.tileCount(),
                derivative.jpegQuality(),
                derivative.minimumWindowedSsim(),
                derivative.meanDeltaE00());
        assertTrue(derivative.bytes() > 0);
        assertTrue(derivative.tileCount() > 0);
    }
}

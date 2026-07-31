package org.pathlab.forge.derivative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DirectRegionDziTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesACompleteQualityGatedDziDirectlyFromAlignedRegions() throws Exception {
        var runtime = VipsRuntime.discover(temporaryDirectory);
        Assumptions.assumeTrue(runtime.available());
        var regions = List.of(
                patternedRegion("region-00.png", 0),
                patternedRegion("region-01.png", 37));
        var progress = new ArrayList<DerivativeProgress>();

        var result = runtime.generateDziFromRegions(
                regions,
                temporaryDirectory.resolve("derivative"),
                1024,
                1024,
                1.0,
                progress::add);

        assertEquals(DziValidator.expectedTileCount(1024, 1024), result.tileCount());
        assertTrue(result.minimumWindowedSsim()
                >= AdaptiveJpegQualitySelector.MINIMUM_SSIM);
        assertTrue(result.meanDeltaE00()
                <= AdaptiveJpegQualitySelector.MAXIMUM_MEAN_DELTA_E00);
        assertTrue(progress.stream().anyMatch(item -> item.stage().equals("DZI_TILES")));
        assertTrue(progress.stream().anyMatch(item -> item.stage().equals("DZI_VALIDATING")));
    }

    private Path patternedRegion(String name, int phase) throws Exception {
        var image = new BufferedImage(1024, 512, BufferedImage.TYPE_INT_RGB);
        for (var y = 0; y < image.getHeight(); y++) {
            for (var x = 0; x < image.getWidth(); x++) {
                var red = (x / 5 + y / 7 + phase) & 0xff;
                var green = (x / 11 + y / 3 + phase * 2) & 0xff;
                var blue = ((x ^ y) + phase * 3) & 0xff;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        var output = temporaryDirectory.resolve(name);
        ImageIO.write(image, "png", output.toFile());
        return output;
    }
}

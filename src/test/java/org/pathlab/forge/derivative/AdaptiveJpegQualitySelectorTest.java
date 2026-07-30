package org.pathlab.forge.derivative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AdaptiveJpegQualitySelectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void choosesSmallestCandidateThatPassesThirtyTwoSpatialRegions() throws Exception {
        var image = new BufferedImage(512, 256, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(184, 116, 146));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }
        var probe = temporaryDirectory.resolve("probe.png");
        ImageIO.write(image, "png", probe.toFile());

        var selection = AdaptiveJpegQualitySelector.select(probe);

        assertEquals(85, selection.quality());
        assertTrue(selection.minimumWindowedSsim() >= 0.985);
        assertTrue(selection.meanDeltaE00() <= 1.5);
    }

    @Test
    void reportsExactIdentityMetrics() {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);

        assertEquals(1.0, AdaptiveJpegQualitySelector.windowedSsim(image, image));
        assertEquals(0.0, AdaptiveJpegQualitySelector.meanDeltaE00(image, image));
    }
}

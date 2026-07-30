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

    @Test
    void derivesThirtyTwoBoundedNativeRoisAcrossImageClasses() throws Exception {
        var overview = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        for (var y = 0; y < overview.getHeight(); y++) {
            for (var x = 0; x < overview.getWidth(); x++) {
                var value = (x * 3 + y * 5) & 0xff;
                overview.setRGB(x, y, new Color(value, 255 - value, value / 2).getRGB());
            }
        }
        var path = temporaryDirectory.resolve("overview.png");
        ImageIO.write(overview, "png", path.toFile());

        var rois = AdaptiveJpegQualitySelector.planNativeRois(path, 16_384, 8_192);

        assertEquals(32, rois.size());
        assertEquals(32, rois.stream().distinct().count());
        assertTrue(rois.stream().allMatch(roi -> roi.x() >= 0
                && roi.y() >= 0
                && roi.x() + roi.width() <= 16_384
                && roi.y() + roi.height() <= 8_192));
    }
}

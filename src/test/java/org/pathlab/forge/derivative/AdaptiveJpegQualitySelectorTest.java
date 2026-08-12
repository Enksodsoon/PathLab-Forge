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
    void escalatesThroughHighQualityCandidatesWithoutChangingResolution() {
        assertEquals(
                java.util.List.of(65, 70, 75, 80, 85, 90, 95),
                AdaptiveJpegQualitySelector.QUALITIES);
    }

    @Test
    void choosesSmallestCandidateThatPassesSixtyFourSpatialRegions() throws Exception {
        var image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
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

        assertEquals(65, selection.quality());
        assertTrue(selection.minimumWindowedSsim() >= 0.970);
        assertTrue(selection.meanDeltaE00() <= 2.5);
        assertTrue(selection.minimumEdgeDetailRetention() >= 0.90);
    }

    @Test
    void reportsExactIdentityMetrics() {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);

        assertEquals(1.0, AdaptiveJpegQualitySelector.windowedSsim(image, image));
        assertEquals(0.0, AdaptiveJpegQualitySelector.meanDeltaE00(image, image));
        assertEquals(1.0, AdaptiveJpegQualitySelector.edgeDetailRetention(image, image));
    }

    @Test
    void edgeGateDetectsSmoothingThatRemovesTissueDetail() {
        var reference = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        var smoothed = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (var y = 0; y < 64; y++) {
            for (var x = 0; x < 64; x++) {
                reference.setRGB(x, y, ((x / 2 + y / 2) & 1) == 0
                        ? Color.BLACK.getRGB()
                        : Color.WHITE.getRGB());
                smoothed.setRGB(x, y, new Color(127, 127, 127).getRGB());
            }
        }

        assertTrue(AdaptiveJpegQualitySelector.edgeDetailRetention(reference, smoothed) < 0.10);
    }

    @Test
    void derivesSixtyFourBoundedNativeRoisAcrossImageClasses() throws Exception {
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

        assertEquals(64, rois.size());
        assertEquals(64, rois.stream().distinct().count());
        assertTrue(rois.stream().allMatch(roi -> roi.x() >= 0
                && roi.y() >= 0
                && roi.x() + roi.width() <= 16_384
                && roi.y() + roi.height() <= 8_192));
    }
}

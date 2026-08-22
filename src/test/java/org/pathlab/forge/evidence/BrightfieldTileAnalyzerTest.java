package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

final class BrightfieldTileAnalyzerTest {
    @Test
    void countsBoundedNuclearComponentsAndDescribesDabWithoutClinicalCalls() {
        var image = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 32, 24);
        graphics.setColor(new Color(75, 45, 125));
        graphics.fillRect(3, 4, 5, 5);
        graphics.fillRect(18, 4, 6, 5);
        graphics.setColor(new Color(135, 90, 45));
        graphics.fillRect(8, 14, 12, 6);
        graphics.dispose();

        var result = BrightfieldTileAnalyzer.analyze(image, "ki-67");

        assertEquals(2, result.cellCount());
        assertTrue(result.meanNucleusAreaPx2() >= 25);
        assertTrue(result.meanNucleusPerimeterPx() > 0);
        assertTrue(result.meanNucleusEccentricity() >= 0);
        assertTrue(result.meanNucleusSolidity() > 0);
        assertTrue(result.dabAreaFraction() > 0);
        assertTrue(result.meanDabOd() > 0);
        assertEquals("nuclear", result.compartment());
        assertTrue(result.researchEstimate());
        assertEquals(2, result.instances().size());
        assertTrue(result.instances().stream().allMatch(instance -> !instance.rle().isEmpty()));
    }

    @Test
    void markerControlledWatershedSplitsTouchingElongatedNuclearMask() {
        var image = new BufferedImage(24, 16, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 24, 16);
        graphics.setColor(new Color(75, 45, 125)); graphics.fillRect(6, 5, 10, 5);
        graphics.dispose();

        var result = BrightfieldTileAnalyzer.analyze(image, "generic");

        assertEquals(2, result.cellCount());
        assertEquals("cell-1", result.instances().get(0).id());
        assertTrue(result.instances().stream().allMatch(instance -> instance.areaPx2() == 25));
    }

    @Test
    void unknownMarkerUsesGenericDescriptiveCompartment() {
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        var result = BrightfieldTileAnalyzer.analyze(image, "cdx2");
        assertEquals("generic", result.marker());
        assertEquals("generic-region", result.compartment());
    }

    @Test
    void stainQcDefaultsToRelativeOnlyWithoutValidatedControls() {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 16, 16);
        graphics.setColor(new Color(80, 50, 125)); graphics.fillRect(1, 1, 7, 7);
        graphics.setColor(new Color(140, 90, 40)); graphics.fillRect(8, 8, 7, 7);
        graphics.dispose();

        var qc = BrightfieldStainQc.inspect(image, false);

        assertEquals("relative_only", qc.calibrationStatus());
        assertTrue(qc.separationScore() > 0);
    }
}

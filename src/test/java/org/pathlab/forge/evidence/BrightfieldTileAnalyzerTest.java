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
        assertTrue(result.dabAreaFraction() > 0);
        assertTrue(result.meanDabOd() > 0);
        assertEquals("nuclear", result.compartment());
        assertTrue(result.researchEstimate());
    }

    @Test
    void unknownMarkerUsesGenericDescriptiveCompartment() {
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        var result = BrightfieldTileAnalyzer.analyze(image, "cdx2");
        assertEquals("generic", result.marker());
        assertEquals("generic-region", result.compartment());
    }
}

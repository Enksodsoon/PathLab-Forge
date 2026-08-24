package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;

final class IhcMeasurementAnalyzerTest {
    @Test
    void keepsNuclearAndMembraneMeasurementsDescriptive() {
        var image = fixture();
        var nuclear = IhcMeasurementAnalyzer.analyze(image, "ki-67", List.of(), false);
        var membrane = IhcMeasurementAnalyzer.analyze(image, "her2", List.of(), false);
        assertEquals("nuclear", nuclear.compartment());
        assertTrue(nuclear.measurements().containsKey("dabAssociatedNuclei"));
        assertEquals("membrane", membrane.compartment());
        assertTrue(membrane.measurements().containsKey("meanMembraneCompleteness"));
        assertTrue(nuclear.measurements().keySet().stream().noneMatch(key -> Set.of("positive", "tps", "cps").contains(key)));
    }

    @Test
    void refusesPdL1CompartmentsUntilReviewedGeometryExists() {
        var fallback = IhcMeasurementAnalyzer.analyze(fixture(), "pd-l1", List.of(), false);
        assertEquals("generic-fallback", fallback.analysisMode());
        assertEquals("COMPARTMENT_REVIEW_REQUIRED", fallback.abstentionReason());

        var reviewed = IhcMeasurementAnalyzer.analyze(fixture(), "pd-l1", List.of(
                new IhcMeasurementAnalyzer.ReviewedRegion(
                        "tumor-1", "tumor", "faculty-approved", 0, 0, 16, 16)), false);
        assertEquals("marker-aware", reviewed.analysisMode());
        assertNull(reviewed.abstentionReason());
        assertTrue(reviewed.measurements().containsKey("compartments"));
    }

    private static BufferedImage fixture() {
        var image = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 32, 24);
        graphics.setColor(new Color(75, 45, 125)); graphics.fillRect(5, 5, 6, 6);
        graphics.setColor(new Color(135, 90, 45)); graphics.fillRect(4, 4, 8, 1);
        graphics.fillRect(4, 11, 8, 1); graphics.fillRect(4, 4, 1, 8); graphics.fillRect(11, 4, 1, 8);
        graphics.dispose();
        return image;
    }

    private static final class Set {
        static java.util.Set<String> of(String... values) { return java.util.Set.of(values); }
    }
}

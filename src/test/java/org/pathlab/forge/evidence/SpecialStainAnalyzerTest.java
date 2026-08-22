package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

final class SpecialStainAnalyzerTest {
    @Test
    void emitsDescriptivePasAndPapanicolaouMeasurementsWithUnknownFallback() {
        var image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 20, 20);
        graphics.setColor(new Color(180, 70, 150)); graphics.fillRect(2, 2, 10, 10);
        graphics.dispose();

        var pas = SpecialStainAnalyzer.analyze(image, "pas");
        assertEquals("special-stain-descriptive", pas.analysisMode());
        assertTrue((double) pas.measurements().get("stainAreaFraction") > 0);
        var pap = SpecialStainAnalyzer.analyze(image, "papanicolaou");
        assertEquals("cytology-descriptive", pap.analysisMode());
        var unknown = SpecialStainAnalyzer.analyze(image, "reticulin");
        assertEquals("generic_brightfield", unknown.stainId());
        assertEquals("UNSUPPORTED_STAIN_GENERIC_FALLBACK", unknown.abstentionReason());
    }
}

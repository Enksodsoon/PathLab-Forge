package org.pathlab.forge.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class HeAnalysisServiceTest {
    @Test
    void whiteBackgroundHasNoStainConcentration() {
        var result = HeAnalysisService.concentrations(255, 255, 255);

        assertEquals(0, result[0], 0.000001);
        assertEquals(0, result[1], 0.000001);
    }

    @Test
    void deterministicPurpleAndPinkPixelsSeparateDifferently() {
        var purple = HeAnalysisService.concentrations(70, 45, 105);
        var pink = HeAnalysisService.concentrations(220, 130, 175);

        assertTrue(purple[0] > pink[0]);
        assertTrue(pink[1] > 0);
    }
}

package org.pathlab.forge.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.pathlab.forge.conversion.RgbRegion;

final class ClassicalAnalysisTest {
    @Test
    void computesBoundedQcAndTissueSummary() {
        var pixels = new byte[] {
            (byte) 255, (byte) 255, (byte) 255,
            40, 30, 60,
            30, 20, 50,
            (byte) 250, (byte) 250, (byte) 250
        };
        var region = new RgbRegion(10, 20, 2, 2, pixels);

        var qc = ClassicalAnalysis.qualityControl(region);
        var tissue = ClassicalAnalysis.detectTissue(region, 0.8);

        assertEquals(4, qc.sampledPixels());
        assertEquals(2, tissue.tissuePixels());
        assertEquals(0.5, tissue.tissueFraction());
    }

    @Test
    void derivesAnAffineTransformFromThreeLandmarks() {
        var transform = ClassicalAnalysis.affine(
                new double[][] {{0, 0}, {1, 0}, {0, 1}},
                new double[][] {{10, 20}, {12, 20}, {10, 23}});

        var mapped = transform.apply(2, 2);
        assertEquals(14, mapped[0], 0.0001);
        assertEquals(26, mapped[1], 0.0001);
        assertTrue(ClassicalAnalysis.classifyPixel(20, 20, 20, 0.1, 0.9));
    }
}

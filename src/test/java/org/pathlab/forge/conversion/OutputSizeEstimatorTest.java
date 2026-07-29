package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class OutputSizeEstimatorTest {
    @Test
    void estimatesRgbPyramidAndDownsampleWithoutOverflow() {
        assertEquals(400, OutputSizeEstimator.rgbPyramidUpperBound(10, 10, 1));
        assertEquals(100, OutputSizeEstimator.rgbPyramidUpperBound(10, 10, 2));
        assertTrue(OutputSizeEstimator.rgbPyramidUpperBound(
                        Integer.MAX_VALUE, Integer.MAX_VALUE, 1)
                > 0);
    }
}

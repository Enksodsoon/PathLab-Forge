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

    @Test
    void predictsTheObservedCompressedSmallVsiExport() {
        var estimate = OutputSizeEstimator.compressedOmeTiff(
                8_021, 9_366, 8, 1_116_691_456L, false);
        var observedBytes = 874_756L;

        assertTrue(estimate.lowerBytes() <= observedBytes);
        assertTrue(observedBytes <= estimate.upperBytes());
        assertTrue(Math.abs(estimate.expectedBytes() - observedBytes) < 200_000);
    }

    @Test
    void distinguishesCompressedFileEstimateFromPeakWorkspace() {
        var nativeEstimate = OutputSizeEstimator.compressedOmeTiff(
                49_941, 62_174, 1, 3_000_000_000L, false);
        var estimate = OutputSizeEstimator.compressedOmeTiff(
                49_941, 62_174, 1.5, 3_000_000_000L, false);
        var twoTimesEstimate = OutputSizeEstimator.compressedOmeTiff(
                49_941, 62_174, 2, 3_000_000_000L, false);
        var workspace = OutputSizeEstimator.rgbPyramidUpperBound(
                49_941, 62_174, 1.5);

        assertTrue(estimate.lowerBytes() <= estimate.expectedBytes());
        assertTrue(estimate.expectedBytes() <= estimate.upperBytes());
        assertTrue(estimate.expectedBytes() < workspace);
        assertTrue(nativeEstimate.expectedBytes() > estimate.expectedBytes());
        assertTrue(estimate.expectedBytes() > twoTimesEstimate.expectedBytes());
    }
}

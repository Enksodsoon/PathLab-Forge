package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ConversionRequestTest {
    @Test
    void matchesQuPathFloorGeometryAtHalfPixelBoundaries() {
        var request = new ConversionRequest(
                Path.of("case.vsi"),
                2,
                0,
                0,
                165_845,
                90_735,
                165_845,
                90_735,
                2.0);

        assertEquals(82_922, request.outputWidth());
        assertEquals(45_367, request.outputHeight());
    }

    @Test
    void validatesSourcePixelCropAndSupportedDownsample() {
        var request = new ConversionRequest(
                Path.of("case.vsi"), 2, 100, 200, 1000, 500, 2000, 1000, 2);

        assertEquals(500, request.outputWidth());
        assertEquals(250, request.outputHeight());
        assertEquals(500_000, request.estimatedRgbPyramidBytes());
    }

    @Test
    void rejectsCropOutsideSeriesAndUnsupportedDownsample() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversionRequest(
                        Path.of("case.vsi"), 0, 900, 0, 200, 100, 1000, 1000, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversionRequest(
                        Path.of("case.vsi"), 0, 0, 0, 100, 100, 1000, 1000, 3));
    }

    @Test
    void supportsQuPathCompatibleOnePointFiveDownsampleGeometry() {
        var request = new ConversionRequest(
                Path.of("case.vsi"),
                13,
                69_790,
                23_372,
                11_336,
                11_040,
                165_845,
                90_735,
                1.5);

        assertEquals(7_557, request.outputWidth());
        assertEquals(7_360, request.outputHeight());
    }
}

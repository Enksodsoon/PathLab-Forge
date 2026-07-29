package org.pathlab.forge.derivative;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VipsRuntimeTest {
    @Test
    void usesQuPathSizedQualityForWholeSlidesAndHigherQualityForCrops() {
        assertEquals(75, VipsRuntime.omeJpegQuality(82_922, 45_367));
        assertEquals(93, VipsRuntime.omeJpegQuality(7_557, 7_360));
    }
}

package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class OutputSizeGuardTest {
    @Test
    void acceptsQuPathSizedRenderedRgbAndRejectsBalloonedLosslessOutput() {
        var sourceBytes = 2_001_290_718L;
        var maximum = OutputSizeGuard.maximumSuitableBytes(
                sourceBytes, 82_922, 45_367, 2.0);

        assertTrue(maximum >= 235_517_679L);
        assertDoesNotThrow(() -> OutputSizeGuard.requireSuitable(
                235_517_679L, sourceBytes, 82_922, 45_367, 2.0));
        assertThrows(IllegalStateException.class, () -> OutputSizeGuard.requireSuitable(
                12_591_424_951L, sourceBytes, 110_563, 60_490, 1.5));
    }
}

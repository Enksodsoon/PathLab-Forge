package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class DiskPreflightTest {
    @Test
    void reservesTenGibibytesBeyondTheEstimatedNextStagePeak() {
        var gib = 1024L * 1024 * 1024;
        assertEquals(60 * gib, DiskPreflight.requiredUsableBytes(50 * gib));
        assertDoesNotThrow(() -> DiskPreflight.requireCapacity(60 * gib, 50 * gib));
        assertThrows(
                IllegalStateException.class,
                () -> DiskPreflight.requireCapacity(60 * gib - 1, 50 * gib));
    }
}

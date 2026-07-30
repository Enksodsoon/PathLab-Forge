package org.pathlab.forge.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RuntimeProfileTest {
    @Test
    void targetProfileBoundsSixCoreEightGigabyteDevice() {
        var profile = RuntimeProfile.target();

        assertEquals(5, profile.maxConversionWorkers());
        assertEquals(640L * 1024 * 1024, profile.bioFormatsHeapBytes());
        assertEquals(4, profile.vipsConcurrency());
        assertEquals(768L * 1024 * 1024, profile.vipsCacheBytes());
        assertEquals(5_500L * 1024 * 1024, profile.processTreeLimitBytes());
        assertFalse(profile.mayLaunchWorker(1_249L * 1024 * 1024));
        assertTrue(profile.mayLaunchWorker(1_250L * 1024 * 1024));
        assertTrue(profile.mustPause(767L * 1024 * 1024));
    }
}

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
        assertEquals(5, profile.vipsConcurrency());
        assertEquals(1_024L * 1024 * 1024, profile.vipsCacheBytes());
        assertEquals(5_500L * 1024 * 1024, profile.processTreeLimitBytes());
        assertFalse(profile.mayLaunchWorker(1_249L * 1024 * 1024));
        assertTrue(profile.mayLaunchWorker(1_250L * 1024 * 1024));
        assertTrue(profile.mustPause(767L * 1024 * 1024));
    }

    @Test
    void adaptsConcurrencyAndCacheToHigherSpecHardware() {
        var profile = RuntimeProfile.adaptive(12, 32L * 1024 * 1024 * 1024);

        assertEquals("adaptive-12c-32gb", profile.name());
        assertEquals(8, profile.maxConversionWorkers());
        assertEquals(11, profile.vipsConcurrency());
        assertTrue(profile.vipsCacheBytes() > RuntimeProfile.target().vipsCacheBytes());
        assertTrue(profile.processTreeLimitBytes() > RuntimeProfile.target().processTreeLimitBytes());
    }

    @Test
    void doesNotTreatHyperthreadsAsIndependentWholeSlideDecoders() {
        assertEquals(5, RuntimeProfile.effectiveCpuParallelism(6));
        assertEquals(6, RuntimeProfile.effectiveCpuParallelism(12));
        assertEquals(12, RuntimeProfile.effectiveCpuParallelism(24));
    }
}

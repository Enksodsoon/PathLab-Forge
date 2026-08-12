package org.pathlab.forge.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RuntimeProfileTest {
    @Test
    void targetProfileBoundsSixCoreEightGigabyteDevice() {
        var profile = RuntimeProfile.target();

        assertEquals(2, profile.maxConversionWorkers());
        assertEquals(640L * 1024 * 1024, profile.bioFormatsHeapBytes());
        assertEquals(2, profile.vipsConcurrency());
        assertEquals(256L * 1024 * 1024, profile.vipsCacheBytes());
        assertEquals(2, profile.quPathProcessors());
        assertEquals(2L * 1024 * 1024 * 1024, profile.quPathHeapBytes());
        assertEquals(1, profile.previewReaderSessions());
        assertEquals(128L * 1024 * 1024, profile.previewReaderCacheBytes());
        assertEquals(1, profile.bioFormatsDirectReaders());
        assertEquals(5_500L * 1024 * 1024, profile.processTreeLimitBytes());
        assertFalse(profile.mayLaunchWorker(1_249L * 1024 * 1024));
        assertTrue(profile.mayLaunchWorker(1_250L * 1024 * 1024));
        assertTrue(profile.mustPause(767L * 1024 * 1024));
    }

    @Test
    void adaptsConcurrencyAndCacheToHigherSpecHardware() {
        var profile = RuntimeProfile.adaptive(12, 32L * 1024 * 1024 * 1024);

        assertEquals("adaptive-12c-32gb", profile.name());
        assertEquals(6, profile.maxConversionWorkers());
        assertEquals(6, profile.vipsConcurrency());
        assertTrue(profile.vipsCacheBytes() > RuntimeProfile.target().vipsCacheBytes());
        assertTrue(profile.processTreeLimitBytes() > RuntimeProfile.target().processTreeLimitBytes());
    }

    @Test
    void keepsSmallMachinesResponsiveAndScalesMonotonically() {
        var four = RuntimeProfile.adaptive(4, 4L * 1024 * 1024 * 1024);
        var eight = RuntimeProfile.adaptive(6, 8L * 1024 * 1024 * 1024);
        var sixteen = RuntimeProfile.adaptive(8, 16L * 1024 * 1024 * 1024);

        assertEquals(1, four.maxConversionWorkers());
        assertEquals(128L * 1024 * 1024, four.vipsCacheBytes());
        assertTrue(four.maxConversionWorkers() <= eight.maxConversionWorkers());
        assertTrue(eight.maxConversionWorkers() <= sixteen.maxConversionWorkers());
    }

    @Test
    void doesNotTreatHyperthreadsAsIndependentWholeSlideDecoders() {
        assertEquals(5, RuntimeProfile.effectiveCpuParallelism(6));
        assertEquals(6, RuntimeProfile.effectiveCpuParallelism(12));
        assertEquals(12, RuntimeProfile.effectiveCpuParallelism(24));
    }
}

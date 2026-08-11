package org.pathlab.forge.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pathlab.forge.conversion.ArtifactRevisionFormat;

final class ForgeBenchmarkTest {
    @AfterEach
    void clearFormat() {
        System.clearProperty("pathlab.forge.benchmark.format");
    }

    @Test
    void defaultsToPreparedAndAcceptsTheDirectProfile() {
        assertEquals(ArtifactRevisionFormat.PREPARED_DZI_V2, ForgeBenchmark.benchmarkFormat());
        System.setProperty("pathlab.forge.benchmark.format", "ome_dynamic_v1");
        assertEquals(ArtifactRevisionFormat.OME_DYNAMIC_V1, ForgeBenchmark.benchmarkFormat());
    }

    @Test
    void rejectsUnknownFormats() {
        System.setProperty("pathlab.forge.benchmark.format", "future-v2");
        assertThrows(IllegalArgumentException.class, ForgeBenchmark::benchmarkFormat);
    }
}

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
    void defaultsToTheOnlySupportedDirectProfile() {
        assertEquals(ArtifactRevisionFormat.PREPARED_DZI_V2, ForgeBenchmark.benchmarkFormat());
        System.setProperty("pathlab.forge.benchmark.format", "prepared_dzi_v2");
        assertEquals(ArtifactRevisionFormat.PREPARED_DZI_V2, ForgeBenchmark.benchmarkFormat());
    }

    @Test
    void rejectsEveryAlternativeRoute() {
        System.setProperty("pathlab.forge.benchmark.format", "ome_dynamic_v1");
        assertThrows(IllegalArgumentException.class, ForgeBenchmark::benchmarkFormat);
    }
}

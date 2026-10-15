package org.pathlab.forge.runtime;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class ProcessTreeMemoryTest {
    @Test void reportsMeasuredCoverageInsteadOfHeapAsRss() {
        var sample = ProcessTreeMemory.snapshot();
        assertTrue(sample.totalProcesses() >= 1);
        assertTrue(sample.measuredProcesses() <= sample.totalProcesses());
        assertEquals(sample.measuredProcesses() == sample.totalProcesses(), sample.complete());
        if (sample.complete()) assertTrue(sample.residentBytes() > 0);
        else assertEquals(-1, ProcessTreeMemory.workingSetBytes());
    }
}

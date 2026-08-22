package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

final class AtlasFeasibilityGateTest {
    @Test void continuesOnlyWhenProjectedInsideSevenDaysAndResourcesHold() {
        assertEquals("CONTINUE_LOCAL", AtlasFeasibilityGate.decide(
                new AtlasFeasibilityGate.Probe(4, 10_000, 1_000, 100_000, 4500, 12000, 0, true)).status());
        assertEquals("LOCAL_INSUFFICIENT", AtlasFeasibilityGate.decide(
                new AtlasFeasibilityGate.Probe(4, 10_000, 1_000, 1_000_000, 4500, 12000, 0, true)).status());
        assertEquals("LOCAL_INSUFFICIENT", AtlasFeasibilityGate.decide(
                new AtlasFeasibilityGate.Probe(4, 10_000, 1_000, 100_000, 4700, 12000, 0, true)).status());
    }
}

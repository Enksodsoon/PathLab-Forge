package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class AtlasFeasibilityGateTest {
    @Test
    void requiresFourHoursImprovementResourceComplianceAndBoundedQuote() {
        var segments = new ArrayList<AtlasFeasibilityGate.Segment>();
        for (var index = 0; index < 16; index++) segments.add(new AtlasFeasibilityGate.Segment(
                Duration.ofMinutes(15), 8L * 1024 * 1024 * 1024,
                4L * 1024 * 1024 * 1024, 0.1 + index * 0.01, true));
        var decision = AtlasFeasibilityGate.evaluate(segments, Duration.ofDays(6));
        assertEquals("experimental", decision.status());
        assertEquals("LOCAL_PROBE_PASSED_FULL_QUALIFICATION_PENDING", decision.reason());
        assertFalse(decision.paidComputeQuoteAllowed());
        AtlasFeasibilityGate.requireQuote(10_000, 24);
        assertThrows(IllegalArgumentException.class, () -> AtlasFeasibilityGate.requireQuote(10_001, 24));
        assertThrows(IllegalArgumentException.class, () -> AtlasFeasibilityGate.requireQuote(9_000, 16));
    }
}

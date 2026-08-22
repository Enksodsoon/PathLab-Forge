package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class EvidenceRealCohortIntegrationTest {
    @Test
    void validatesExplicitPrivateCohortWithoutPromotingMissingCoverage() throws Exception {
        var value = System.getenv("PATHLAB_EVIDENCE_COHORT");
        Assumptions.assumeTrue(value != null && !value.isBlank(),
                "Set PATHLAB_EVIDENCE_COHORT to run the private real-data contract check");

        var cohort = EvidenceQualificationCohort.load(Path.of(value));
        var assessment = cohort.assessReadiness();

        assertEquals("not_evaluable", assessment.status());
        assertTrue(assessment.reasons().contains("INSUFFICIENT_QUERY_GI"));
        assertTrue(assessment.reasons().contains("INSUFFICIENT_REFERENCE_BREAST"));
        assertTrue(assessment.reasons().contains("INSUFFICIENT_OOD"));
    }
}

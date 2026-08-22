package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class EvidenceQualificationCohortTest {
    private static final Set<String> GROUPS = Set.of(
            "breast", "gi", "lung", "lymph-node", "benign-reactive");

    @Test
    void reportsMissingGroupsAndSplitLeakageAsNotEvaluable() {
        var samples = List.of(
                sample("reference", "breast", "patient-1", "slide-1", "source-a"),
                sample("query", "breast", "patient-1", "slide-2", "source-a"),
                sample("ood", "breast", "patient-3", "slide-3", "source-c"));
        var cohort = cohort(samples);

        var assessment = cohort.assessReadiness();

        assertEquals("not_evaluable", assessment.status());
        assertTrue(assessment.reasons().contains("INSUFFICIENT_QUERY_GI"));
        assertTrue(assessment.reasons().contains("PATIENT_SPLIT_OVERLAP"));
        assertTrue(assessment.reasons().contains("SOURCE_SPLIT_OVERLAP"));
    }

    @Test
    void becomesReadyOnlyWithEveryPreregisteredGroupAndNoLeakage() {
        var samples = new ArrayList<EvidenceQualificationCohort.Sample>();
        var index = 0;
        for (var group : GROUPS.stream().sorted().toList()) {
            samples.add(sample("reference", group, "patient-r-" + index,
                    "slide-r-" + index, "source-r-" + index));
            samples.add(sample("query", group, "patient-q-" + index,
                    "slide-q-" + index, "source-q-" + index));
            index++;
        }
        samples.add(sample("ood", "breast", "patient-o", "slide-o", "source-o"));

        assertEquals("ready", cohort(samples).assessReadiness().status());
    }

    private static EvidenceQualificationCohort cohort(List<EvidenceQualificationCohort.Sample> samples) {
        return new EvidenceQualificationCohort(Path.of("cohort.json"), "cohort-v1",
                Instant.parse("2026-08-22T00:00:00Z"), "private-research-model-qualification",
                new EvidenceQualificationCohort.AcceptanceCriteria(
                        GROUPS, 1, 1, 1, 0, 0, 0, "color-histogram-v1", 0.05, 0.03, 0.8),
                samples);
    }

    private static EvidenceQualificationCohort.Sample sample(
            String split, String group, String patient, String slide, String sourceGroup) {
        var source = "dataset-" + sourceGroup;
        var provenance = new EvidenceSampleManifest(source, patient, slide, "a".repeat(64),
                "reviewed research terms", "research-group", "private-research", 1, false);
        var cache = new EvidenceTileCacheManifest(Path.of("tile-cache.json"), "b".repeat(64),
                "a".repeat(64), "revision-1", 512, 512, Path.of("sample.json"),
                "c".repeat(64), 512, "png", "rgb-srgb-uint8", provenance, List.of());
        return new EvidenceQualificationCohort.Sample(split + "-" + group, Path.of("tile-cache.json"),
                "b".repeat(64), group, "research-group", split, sourceGroup, cache);
    }
}

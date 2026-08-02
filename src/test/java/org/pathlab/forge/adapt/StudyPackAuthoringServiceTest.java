package org.pathlab.forge.adapt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.pathlab.forge.pivot.PivotManifest;
import org.pathlab.forge.pivot.PivotTask;

final class StudyPackAuthoringServiceTest {
    @Test
    void exportsApprovedPivotCoordinatesAgainstOnlyTheViewerSlideIdentifier() {
        var manifest = new PivotManifest(
                "pathlab-pivot/v1", "pivot-1", "aabbccddeeff0011", "local-slide",
                "source-fingerprint", "revision-1", 0, 1_000, 800, 10_000, 8_000,
                12L, 20L, 30L, 24, 1, 0,
                List.of(new PivotTask(
                        "pivot-1", "pivot-1.jpg", 400, 200, 800, 600,
                        3, 1, 2, 4, .8, .1, .5)));

        var body = new StudyPackAuthoringService().fromApprovedPivot(
                manifest,
                new PivotApproval("local-slide", manifest.id(), manifest.sourceFingerprint(), manifest.inputRevision(), "faculty", 1),
                new ViewerSlideAssociation("local-slide", "viewer-slide-123", "a".repeat(64), "Teaching", "CC BY 4.0", "artifact"),
                "histology-navigation", 2, "Navigation practice", "path-101",
                "Dr Rivera", "CC BY 4.0", "2026-08");

        assertTrue(body.contains("\"viewerSlideId\":\"viewer-slide-123\""));
        assertTrue(body.contains("\"type\":\"spatial\""));
        assertTrue(body.contains("\"targetX\":0.04"));
        assertTrue(body.contains("\"targetY\":0.025"));
        assertTrue(body.contains("\"targetWidth\":0.08"));
        assertTrue(body.contains("\"targetHeight\":0.075"));
        assertTrue(body.contains("\"source\":\"PIVOT manifest aabbccddeeff0011\""));
        assertTrue(!body.contains("queryFile") && !body.contains("jpg"));
    }

    @Test
    void rejectsCoordinatesOutsideTheManifestInsteadOfLeakingPixels() {
        var manifest = new PivotManifest(
                "pathlab-pivot/v1", "pivot-1", "aabbccddeeff0011", "local-slide",
                "source-fingerprint", "revision-1", 0, 100, 100, 1_000, 800,
                12L, 20L, 30L, 24, 1, 0,
                List.of(new PivotTask("bad", "bad.jpg", 950, 700, 100, 200, 0, 0, 0, 1, .5, .2, .2)));

        assertThrows(IllegalArgumentException.class, () -> new StudyPackAuthoringService().fromApprovedPivot(
                manifest,
                new PivotApproval("local-slide", manifest.id(), manifest.sourceFingerprint(), manifest.inputRevision(), "faculty", 1),
                new ViewerSlideAssociation("local-slide", "viewer-1", "a".repeat(64), "Teaching", "L", "artifact"),
                "p", 1, "T", "c", "A", "L", "R"));
    }
}

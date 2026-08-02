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
                "source-fingerprint", "artifact:artifact", 0, 1_000, 800, 10_000, 8_000,
                12L, 20L, 30L, 24, 1, 0,
                List.of(new PivotTask(
                        "pivot-1", "pivot-1.jpg", 400, 200, 800, 600,
                        3, 1, 2, 4, .8, .1, .5)));

        var body = new StudyPackAuthoringService().fromApprovedPivot(
                manifest,
                new PivotApproval("local-slide", manifest.id(), manifest.sourceFingerprint(), manifest.inputRevision(), "faculty", 1),
                new ViewerSlideAssociation("local-slide", "viewer-slide-123", "a".repeat(64),
                        "Teaching", "CC BY 4.0", "artifact", 200, 100, 2_000, 1_000,
                        2, 1_000, 500),
                "histology-navigation", 2, "Navigation practice", "path-101",
                "Dr Rivera", "CC BY 4.0", "2026-08");

        assertTrue(body.contains("\"viewerSlideId\":\"viewer-slide-123\""));
        assertTrue(body.contains("\"type\":\"spatial\""));
        assertTrue(body.contains("\"targetX\":0.1"));
        assertTrue(body.contains("\"targetY\":0.1"));
        assertTrue(body.contains("\"targetWidth\":0.4"));
        assertTrue(body.contains("\"targetHeight\":0.6"));
        assertTrue(body.contains("\"source\":\"PIVOT manifest aabbccddeeff0011\""));
        assertTrue(!body.contains("queryFile") && !body.contains("jpg"));
    }

    @Test
    void rejectsCoordinatesOutsideTheManifestInsteadOfLeakingPixels() {
        var manifest = new PivotManifest(
                "pathlab-pivot/v1", "pivot-1", "aabbccddeeff0011", "local-slide",
                "source-fingerprint", "artifact:artifact", 0, 100, 100, 1_000, 800,
                12L, 20L, 30L, 24, 1, 0,
                List.of(new PivotTask("bad", "bad.jpg", 950, 700, 100, 200, 0, 0, 0, 1, .5, .2, .2)));

        assertThrows(IllegalArgumentException.class, () -> new StudyPackAuthoringService().fromApprovedPivot(
                manifest,
                new PivotApproval("local-slide", manifest.id(), manifest.sourceFingerprint(), manifest.inputRevision(), "faculty", 1),
                new ViewerSlideAssociation("local-slide", "viewer-1", "a".repeat(64),
                        "Teaching", "L", "artifact", 0, 0, 1_000, 800, 1, 1_000, 800),
                "p", 1, "T", "c", "A", "L", "R"));
    }

    @Test
    void mapsExactCropEdgesAndRejectsSourceGlobalTargetsOutsideTheViewerCrop() {
        var edge = manifest(new PivotTask("edge", "edge.jpg", 200, 100, 2_000, 1_000,
                0, 0, 0, 1, .5, .2, .3));
        var association = new ViewerSlideAssociation("local-slide", "viewer-1", "a".repeat(64),
                "Teaching", "L", "artifact", 200, 100, 2_000, 1_000, 2, 1_000, 500);
        var body = new StudyPackAuthoringService().fromApprovedPivot(edge, approval(edge), association,
                "p", 1, "T", "c", "A", "L", "R");
        assertTrue(body.contains("\"targetX\":0.0"));
        assertTrue(body.contains("\"targetY\":0.0"));
        assertTrue(body.contains("\"targetWidth\":1.0"));
        assertTrue(body.contains("\"targetHeight\":1.0"));

        var outside = manifest(new PivotTask("outside", "outside.jpg", 199, 100, 10, 10,
                0, 0, 0, 1, .5, .2, .3));
        assertThrows(IllegalArgumentException.class,
                () -> new StudyPackAuthoringService().fromApprovedPivot(outside, approval(outside),
                        association, "p", 1, "T", "c", "A", "L", "R"));
    }

    @Test
    void rejectsViewerTransformFromAnotherArtifactRevision() {
        var manifest = manifest(new PivotTask("task", "task.jpg", 200, 100, 10, 10,
                0, 0, 0, 1, .5, .2, .3));
        var stale = new ViewerSlideAssociation("local-slide", "viewer-1", "a".repeat(64),
                "Teaching", "L", "stale-artifact", 200, 100, 2_000, 1_000, 2, 1_000, 500);
        assertThrows(IllegalArgumentException.class,
                () -> new StudyPackAuthoringService().fromApprovedPivot(manifest, approval(manifest),
                        stale, "p", 1, "T", "c", "A", "L", "R"));
    }

    private static PivotManifest manifest(PivotTask task) {
        return new PivotManifest("pathlab-pivot/v1", "pivot-1", "aabbccddeeff0011",
                "local-slide", "source-fingerprint", "artifact:artifact", 0, 1_000, 800,
                10_000, 8_000, 1, 2, 3, 4, 0, 0, List.of(task));
    }

    private static PivotApproval approval(PivotManifest manifest) {
        return new PivotApproval("local-slide", manifest.id(), manifest.sourceFingerprint(),
                manifest.inputRevision(), "faculty", 1);
    }
}

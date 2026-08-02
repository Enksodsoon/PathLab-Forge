package org.pathlab.forge.adapt;

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
                manifest, "histology-navigation", 2, "Navigation practice", "path-101",
                "viewer-slide-123", "Dr Rivera", "CC BY 4.0", "2026-08", true);

        assertTrue(body.contains("\"viewerSlideId\":\"viewer-slide-123\""));
        assertTrue(body.contains("\"type\":\"spatial\""));
        assertTrue(body.contains("\"targetX\":400.0"));
        assertTrue(body.contains("\"source\":\"PIVOT manifest aabbccddeeff0011\""));
        assertTrue(!body.contains("queryFile") && !body.contains("jpg"));
    }
}

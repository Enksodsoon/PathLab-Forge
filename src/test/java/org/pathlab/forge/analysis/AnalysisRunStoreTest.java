package org.pathlab.forge.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class AnalysisRunStoreTest {
    private static AnalysisRun run(String status) {
        return new AnalysisRun("id", "dataset", "roi", "qc", status, 1, 2, 3, "", new AnalysisRun.Provenance(
                "source", "inventory", "reader", "runtime", "0,0;4,4", "rectangle", 1,
                2, 3, 4, "view", "config", "qc-v1", "source pixels", Map.of()), Map.of(), Map.of("value", 2), false);
    }
    @Test void cancellationCannotBecomeSuccessAndCompletedOutputsSurviveRestart() throws Exception {
        var file = Files.createTempDirectory("analysis-store").resolve("runs.sqlite");
        var store = new AnalysisRunStore(file);
        store.insert(run("QUEUED"));
        assertTrue(store.update(run("CANCELLED")));
        assertFalse(store.update(run("SUCCEEDED")));
        assertEquals("CANCELLED", new AnalysisRunStore(file).get("id").status());
    }
    @Test void restartNeverReplaysUnfinishedRuns() throws Exception {
        var file = Files.createTempDirectory("analysis-store").resolve("runs.sqlite");
        var store = new AnalysisRunStore(file);
        store.insert(run("RUNNING"));
        var recovered = new AnalysisRunStore(file).get("id");
        assertEquals("INTERRUPTED", recovered.status());
        assertTrue(recovered.outputs().isEmpty());
        assertEquals(3, recovered.provenance().z());
    }
}

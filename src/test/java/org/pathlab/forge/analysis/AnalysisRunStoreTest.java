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
    @Test void pagingPreservesOldOutputsAndRecoversActiveRowsBeyondFirstPage() throws Exception {
        var file = Files.createTempDirectory("analysis-paging").resolve("runs.sqlite");
        var store = new AnalysisRunStore(file);
        for (var index = 0; index < 125; index++) {
            var source = run(index == 0 ? "RUNNING" : "SUCCEEDED");
            store.insert(new AnalysisRun("id-" + index, source.datasetId(), source.annotationId(), source.tool(),
                    source.status(), index, 0, 0, "", source.provenance(), source.configuration(), source.outputs(), false));
        }
        assertEquals("id-124", store.list("dataset", 50, 0).get(0).id());
        assertEquals(25, store.list("dataset", 50, 100).size());
        var reopened = new AnalysisRunStore(file);
        assertEquals("INTERRUPTED", reopened.get("id-0").status());
        assertEquals(2, reopened.get("id-1").outputs().get("value"));
        assertEquals(125, reopened.list("dataset").size());
        assertThrows(IllegalArgumentException.class, () -> reopened.list("dataset", 0, 0));
    }
}

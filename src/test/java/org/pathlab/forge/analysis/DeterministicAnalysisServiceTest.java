package org.pathlab.forge.analysis;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.pathlab.forge.annotation.AnnotationRepository;
import org.pathlab.forge.conversion.RgbRegion;
import org.pathlab.forge.library.*;

final class DeterministicAnalysisServiceTest {
    @Test void exactScopeResultsPersistBecomeStaleAndCancellationCannotSucceed() throws Exception {
        var root = Files.createTempDirectory("deterministic-tools");
        var datasets = new PropertiesDatasetRepository(root.resolve("library.properties"));
        var datasetId = "ca38d59a-08ce-44a2-aaf2-cb96bd147bdf";
        datasets.save(new LocalDataset(datasetId, "Synthetic", root.resolve("synthetic.tif").toString(), 1,
                DatasetFormat.OME_TIFF, DatasetStatus.READY, "", "", "")
                .withSourceIdentity(DatasetStatus.READY, "", "source-hash", "inventory"));
        var annotations = new AnnotationRepository(root);
        var roi = annotations.create(datasetId, "polygon", "0,0;8,0;0,8", "", "#ffaa22", 2, 3, 4, "view");
        var wait = new AtomicBoolean(false);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        DeterministicAnalysisService.RegionLoader loader = (id, series,z,t,x,y,w,h) -> {
            assertEquals(2, series); assertEquals(3,z); assertEquals(4,t);
            if (wait.get()) {
                entered.countDown();
                // Deliberately emulate a native reader ignoring interrupt; result must still not become success.
                while (release.getCount() > 0) { try { release.await(20, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {} }
            }
            var bytes = new byte[w*h*3]; java.util.Arrays.fill(bytes, (byte) 100);
            return new RgbRegion(x,y,w,h,bytes);
        };
        String completedId;
        try (var service = new DeterministicAnalysisService(datasets,annotations,root,loader,()->false,tool->true)) {
            var run = service.submit(new DeterministicAnalysisService.Request(datasetId,roi.id(),"qc",Map.of()));
            completedId = run.id();
            var completed = awaitTerminal(service, run.id());
            assertEquals("SUCCEEDED", completed.status());
            assertFalse(completed.outputs().isEmpty());
            assertTrue(service.exportJson(run.id()).contains("source-hash"));
            wait.set(true);
            var cancelled = service.submit(new DeterministicAnalysisService.Request(datasetId,roi.id(),"qc",Map.of()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("CANCELLED", service.cancel(cancelled.id()).status()); release.countDown();
            assertEquals("CANCELLED", awaitTerminal(service,cancelled.id()).status());
            annotations.updateGeometry(datasetId,roi.id(),"0,0;6,0;0,6","","#ffaa22",1);
            assertTrue(service.get(completedId).stale());
        }
        try (var service = new DeterministicAnalysisService(datasets,annotations,root,loader,()->false,tool->false)) {
            assertEquals("SUCCEEDED", service.get(completedId).status());
            assertFalse(service.get(completedId).outputs().isEmpty());
            assertThrows(IllegalStateException.class,()->service.submit(new DeterministicAnalysisService.Request(datasetId,roi.id(),"qc",Map.of())));
        }
    }
    private static AnalysisRun awaitTerminal(DeterministicAnalysisService service,String id) throws Exception {
        for (var count=0;count<250;count++) {
            var run=service.get(id);
            if (!java.util.Set.of("QUEUED","RUNNING").contains(run.status())) return run;
            Thread.sleep(20);
        }
        throw new AssertionError("Analysis did not finish");
    }
}

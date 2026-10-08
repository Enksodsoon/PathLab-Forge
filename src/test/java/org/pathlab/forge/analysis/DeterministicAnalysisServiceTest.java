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
        var source = root.resolve("synthetic.tif"); Files.write(source, new byte[] {1,2,3});
        var inventory = DatasetSourceInventory.singleFile(source);
        var view = new org.pathlab.forge.reader.ViewDefinition(2, org.pathlab.forge.reader.AxisSelection.slice(3), org.pathlab.forge.reader.AxisSelection.slice(4),
                java.util.List.of(new org.pathlab.forge.reader.ChannelRender(0,true,"#ffffff",0,255)), org.pathlab.forge.reader.RenderProfile.PATHOLOGY_STANDARD);
        datasets.save(new LocalDataset(datasetId, "Synthetic", source.toString(), 3,
                DatasetFormat.OME_TIFF, DatasetStatus.READY, "", "", "")
                .withSourceIdentity(DatasetStatus.READY, "", inventory.fingerprint(), inventory.serialized())
                .withViewDefinition(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(view), view.revision()));
        var annotations = new AnnotationRepository(root);
        var roi = annotations.create(datasetId, "polygon", "0,0;8,0;0,8", "", "#ffaa22", 2, 3, 4, view.revision());
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
            assertTrue(service.exportJson(run.id()).contains(inventory.fingerprint()));
            for (var tool : java.util.List.of("he", "stain_vector", "normalize_preview", "tissue", "nucleus_candidates", "tma")) {
                var next = service.submit(new DeterministicAnalysisService.Request(datasetId,roi.id(),tool,
                        tool.equals("nucleus_candidates") ? Map.of("darknessThreshold",150.0) : Map.of()));
                assertEquals("SUCCEEDED", awaitTerminal(service,next.id()).status(), tool);
                if (tool.equals("he")) {
                    var he = service.get(next.id());
                    assertEquals(8, he.outputs().get("maskWidth"));
                    assertEquals(0, he.outputs().get("maskX"));
                    var bits = java.util.BitSet.valueOf(java.util.Base64.getDecoder().decode(he.outputs().get("hematoxylinMaskBitsetBase64").toString()));
                    assertTrue(bits.cardinality() <= ((Number) he.outputs().get("sampledPixels")).intValue(), "Threshold mask must exclude pixels outside the polygon");
                }
                if (tool.equals("tma")) {
                    assertThrows(IllegalStateException.class, () -> service.persistReviewedTma(next.id(), 0));
                    var review = service.review(next.id());
                    assertEquals(9,review.objects().size());
                    var core = review.objects().get(0);
                    var objects = new java.util.ArrayList<>(review.objects());
                    var properties = new java.util.HashMap<>(core.properties()); properties.put("missing","true");
                    objects.set(0,new PathObject(core.id(),core.datasetId(),core.parentId(),core.kind(),core.geometry(),"Corrected core",core.sourceRunId(),properties,core.revision()));
                    var saved = service.saveReview(next.id(),new AnalysisReview(next.id(),0,objects,java.util.List.of()));
                    assertEquals("true",saved.objects().get(0).properties().get("missing"));
                    assertEquals("Corrected core",service.review(next.id()).objects().get(0).classification());
                    assertThrows(IllegalStateException.class,()->service.saveReview(next.id(),review));
                    var persisted = service.persistReviewedTma(next.id(), saved.revision());
                    assertEquals(8, persisted.size(), "Missing cores must not become real annotation ROIs");
                    assertEquals(8, service.persistReviewedTma(next.id(), saved.revision()).size());
                    assertEquals(9, annotations.list(datasetId).size(), "Repeated persistence must not duplicate the grid");
                    assertTrue(persisted.stream().allMatch(item -> item.parentId().equals(roi.id())
                            && item.series() == 2 && item.z() == 3 && item.t() == 4 && item.viewRevision().equals(view.revision())));
                    assertThrows(IllegalArgumentException.class, () -> service.submitTmaCore(next.id(), saved.revision(), core.id(), "qc", Map.of()));
                    var present = saved.objects().get(1);
                    var coreRun = awaitTerminal(service, service.submitTmaCore(next.id(), saved.revision(), present.id(), "qc", Map.of()).id());
                    assertEquals("SUCCEEDED", coreRun.status());
                    assertEquals(next.id(), coreRun.provenance().secondaryInputs().get("tmaRunId"));
                    assertEquals("1", coreRun.provenance().secondaryInputs().get("tmaReviewRevision"));
                    assertEquals(present.geometry(), coreRun.provenance().annotationGeometry());
                    var directCoreRun = awaitTerminal(service, service.submit(new DeterministicAnalysisService.Request(datasetId, present.id(), "qc", Map.of())).id());
                    assertEquals(next.id(), directCoreRun.provenance().secondaryInputs().get("tmaRunId"), "Every submission path must retain reviewed-core provenance");
                    var changedReview = service.saveReview(next.id(), saved);
                    assertTrue(service.get(coreRun.id()).stale(), "Changed review invalidates core-run acceptance");
                    assertThrows(IllegalStateException.class, () -> service.persistReviewedTma(next.id(), changedReview.revision()));
                }
                if (tool.equals("stain_vector")) {
                    var review=service.review(next.id());
                    service.saveReview(next.id(),new AnalysisReview(next.id(),review.revision(),review.objects(),java.util.List.of(1.0,2.0,2.0)));
                    assertEquals(1.0/3,service.review(next.id()).stainVector().get(0),.0001);
                }
            }
            var registration = service.submit(new DeterministicAnalysisService.Request(datasetId,roi.id(),"registration",Map.of(),datasetId,roi.id(),"0,0;1,0;0,1","1,1;2,1;1,2"));
            var matched = awaitTerminal(service,registration.id());
            assertEquals("SUCCEEDED",matched.status());
            assertEquals(true,matched.outputs().get("approximate"));
            assertEquals(view.revision(),matched.provenance().secondaryInputs().get("targetViewRevision"));
            assertEquals("NOT_PROVIDED", matched.outputs().get("independentValidation"));
            assertTrue(matched.outputs().get("registrationOverlayDataUrl").toString().startsWith("data:image/png;base64,"));
            var checked = service.submit(new DeterministicAnalysisService.Request(datasetId,roi.id(),"registration",Map.of(),datasetId,roi.id(),
                    "0,0;1,0;0,1","1,1;2,1;1,2", "3,3;4,4", "4,4;5,6"));
            var checkedResult = awaitTerminal(service,checked.id());
            assertEquals("SUCCEEDED", checkedResult.status());
            assertEquals(Math.sqrt(.5), ((Number) checkedResult.outputs().get("independentRmsTargetPixels")).doubleValue(), 1e-9);
            assertEquals("3,3;4,4", checkedResult.provenance().secondaryInputs().get("independentSourceLandmarks"));
            assertThrows(IllegalArgumentException.class, () -> service.submit(new DeterministicAnalysisService.Request(datasetId,roi.id(),"registration",Map.of(),datasetId,roi.id(),
                    "0,0;1,0;0,1","1,1;2,1;1,2", "0,0", "3,3")), "Fitted landmarks cannot validate their own fit");
            assertThrows(IllegalArgumentException.class, () -> service.submit(new DeterministicAnalysisService.Request(datasetId,roi.id(),"registration",Map.of(),datasetId,roi.id(),
                    "0,0;1,0;0,1","1,1;2,1;1,2", "3,3", "")));
            assertThrows(IllegalArgumentException.class, () -> service.submit(new DeterministicAnalysisService.Request(datasetId,roi.id(),"registration",Map.of(),datasetId,roi.id(),
                    "0,0;1,0;0,1","1,1;2,1;1,2", "NaN,3", "4,4")));
            wait.set(true);
            var cancelled = service.submit(new DeterministicAnalysisService.Request(datasetId,roi.id(),"qc",Map.of()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("CANCELLED", service.cancel(cancelled.id()).status()); release.countDown();
            assertEquals("CANCELLED", awaitTerminal(service,cancelled.id()).status());
            var firstPage = service.page(datasetId, 2, 0);
            assertTrue(firstPage.hasMore()); assertEquals(2, firstPage.nextOffset());
            var secondPage = service.page(datasetId, 2, firstPage.nextOffset());
            assertTrue(secondPage.runs().stream().noneMatch(item -> firstPage.runs().stream().anyMatch(first -> first.id().equals(item.id()))));
            assertThrows(IllegalArgumentException.class, () -> service.page(datasetId, 101, 0));
            Files.write(source,new byte[] {1,2,3,4});
            assertTrue(service.get(completedId).stale(),"External source change must invalidate an unchanged stored fingerprint");
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

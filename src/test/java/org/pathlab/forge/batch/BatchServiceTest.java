package org.pathlab.forge.batch;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.conversion.*;
import org.pathlab.forge.derivative.*;
import org.pathlab.forge.library.*;

final class BatchServiceTest {
    @TempDir Path root;
    final AtomicBoolean fail = new AtomicBoolean(true);
    final AtomicInteger renders = new AtomicInteger();
    ConversionEngine engine() {
        return new ConversionEngine() {
            public boolean available() { return true; }
            public String runtimeDescription() { return "synthetic forced-failure fixture"; }
            public List<SeriesInfo> inspect(Path source) { return List.of(); }
            public void convert(Path source, int series, Path output) throws IOException {
                renders.incrementAndGet();
                if (source.getFileName().toString().startsWith("fail") && fail.get()) throw new IOException("forced fixture failure");
                Files.copy(source, output, StandardCopyOption.REPLACE_EXISTING);
            }
        };
    }
    DerivativeEngine derivative() {
        return new DerivativeEngine() {
            public boolean available() { return true; }
            public String description() { return "synthetic fixture, no real slide qualification"; }
            public void optimizeOme(Path source, Path output, int width, int height) throws IOException { Files.copy(source, output, StandardCopyOption.REPLACE_EXISTING); }
            public DerivativeInfo generateDzi(Path source, Path output, int width, int height) throws IOException { throw new IOException("Teaching not used by this fixture"); }
        };
    }
    LocalDataset dataset(String name, boolean configured) throws Exception {
        var source = root.resolve(name + ".ome.tif");
        Files.write(source, new byte[] {'I','I',42,0,1,2,3,4});
        var dataset = new DatasetInspector().inspect(source);
        return configured ? dataset.withExportConfiguration(DatasetStatus.READY_TO_CONVERT, "fixture",0,16,16,1,100,0,0,16,16) : dataset;
    }
    BatchService batches(DatasetRepository repository, ConversionService conversions) throws IOException {
        return new BatchService(repository, conversions, new BatchStore(root.resolve("batches.db")), artifact -> new BatchService.Delivery("FAILED", "Retry delivery of this verified artifact", "forced transfer fixture failure"));
    }
    BatchRun await(BatchService service, String id) throws Exception {
        for (int i=0;i<500;i++) {
            var batch = service.get(id);
            if (batch.items().stream().allMatch(item -> List.of("FAILED","SUCCEEDED","CANCELLED").contains(item.state()))) return batch;
            Thread.sleep(20);
        }
        fail("Fixture batch did not finish"); return null;
    }

    @Test void tenMixedItemsHaveDurableOutcomesAndOnlyOneFailedItemRetries() throws Exception {
        var ids = new ArrayList<String>();
        try (var repository = new SqliteDatasetRepository(root.resolve("library.db"), root.resolve("legacy.properties"));
                var conversions = new ConversionService(repository, engine(), derivative(), root.resolve("managed"))) {
            for (int i=0;i<10;i++) {
                var item = dataset(i==0 ? "fail" : "slide"+i, i<8); repository.save(item); ids.add(item.id());
            }
            var service = batches(repository, conversions);
            var batch = await(service, service.create(ids, ArtifactRevisionFormat.OME_DYNAMIC_V1).id());
            assertEquals(7, batch.items().stream().filter(item -> item.state().equals("SUCCEEDED")).count(), batch.toString());
            assertEquals(3, batch.items().stream().filter(item -> item.state().equals("FAILED")).count());
            assertEquals(batch, new BatchStore(root.resolve("batches.db")).get(batch.id()));
            fail.set(false);
            batch = await(service, service.retry(batch.id(), ids.get(0)).id());
            assertEquals(8, batch.items().stream().filter(item -> item.state().equals("SUCCEEDED")).count(), batch.toString());
            assertEquals(9, renders.get()); // Eight admitted slides plus exactly one retry.
            var before = renders.get();
            var first = batch.items().get(0);
            repository.save(first.snapshot().withExportConfiguration(DatasetStatus.READY_TO_CONVERT,"changed",0,16,16,2,100,0,0,16,16));
            var report = service.report(batch.id());
            assertEquals(first.snapshot(), report.slides().get(0).item().snapshot());
            assertEquals(first.artifactRevisionId(), report.slides().get(0).artifact().id());
            assertEquals("FAILED", report.slides().get(0).delivery().state());
            assertEquals(before, renders.get()); // Reporting a failed transfer never reconverts.
            assertTrue(service.reportJson(batch.id()).contains(first.snapshot().configurationRevision()));
            assertTrue(service.reportCsv(batch.id()).contains(first.artifactRevisionId()));
            repository.delete(ids.get(0));
            assertEquals(first.artifactRevisionId(), service.report(batch.id()).slides().get(0).artifact().id());
        }
    }

    @Test void pausePinnedIdentityAndCancellationSurviveRestartWithoutDuplicateQueue() throws Exception {
        var database = root.resolve("library.db"); String id; String batchId; String artifactId;
        try (var repository = new SqliteDatasetRepository(database, root.resolve("legacy.properties"));
                var conversions = new ConversionService(repository, engine(), derivative(), root.resolve("managed"))) {
            conversions.setQueuePaused(true); var source = dataset("paused",true); id=source.id(); repository.save(source);
            var service=batches(repository,conversions); var batch=service.create(List.of(id),ArtifactRevisionFormat.OME_DYNAMIC_V1);
            batchId=batch.id(); artifactId=batch.items().get(0).artifactRevisionId();
            assertFalse(artifactId.isBlank(),batch.toString()); assertEquals(0,renders.get()); assertEquals(1,repository.listQueueEntries().size());
            assertEquals(artifactId,conversions.savedRevision(id,artifactId).orElseThrow().id());
            // Simulate interruption after manifest admission but before queue persistence.
            repository.deleteQueueEntry(id);
        }
        try (var repository = new SqliteDatasetRepository(database, root.resolve("legacy.properties"));
                var conversions = new ConversionService(repository, engine(), derivative(), root.resolve("managed"))) {
            var service=batches(repository,conversions); service.recoverPending(); service.recoverPending();
            assertTrue(conversions.queuePaused()); assertEquals(1,repository.listQueueEntries().size());
            assertEquals(artifactId,service.get(batchId).items().get(0).artifactRevisionId());
            service.cancel(batchId); assertTrue(repository.listQueueEntries().isEmpty());
        }
        try (var repository = new SqliteDatasetRepository(database, root.resolve("legacy.properties"));
                var conversions = new ConversionService(repository, engine(), derivative(), root.resolve("managed"))) {
            var service=batches(repository,conversions); service.recoverPending();
            assertEquals("CANCELLED",service.get(batchId).items().get(0).state());
            assertTrue(repository.listQueueEntries().isEmpty()); assertEquals(0,renders.get());
            assertTrue(Files.isRegularFile(root.resolve("paused.ome.tif")));
        }
    }

    @Test void pendingAdmissionRejectsChangedSettingsAndFailureBeforeManifestCommitNeverQueues() throws Exception {
        try (var repository = new SqliteDatasetRepository(root.resolve("library.db"), root.resolve("legacy.properties"));
                var conversions = new ConversionService(repository, engine(), derivative(), root.resolve("managed"))) {
            conversions.setQueuePaused(true); var source=dataset("saved",true); repository.save(source);
            var store=new BatchStore(root.resolve("batches.db"));
            store.save(new BatchRun("pending",System.currentTimeMillis(),ArtifactRevisionFormat.OME_DYNAMIC_V1,List.of(new BatchRun.Item(source,"","PENDING","interrupted before admission",1))));
            repository.save(source.withExportConfiguration(DatasetStatus.READY_TO_CONVERT,"changed",0,16,16,2,100,0,0,16,16));
            var service=batches(repository,conversions); service.recoverPending();
            assertEquals("FAILED",service.get("pending").items().get(0).state()); assertTrue(repository.listQueueEntries().isEmpty());
            repository.save(source);
            assertThrows(IOException.class,()->conversions.startExpected(source,ArtifactRevisionFormat.OME_DYNAMIC_V1,admitted->{ throw new IOException("forced manifest write failure"); }));
            assertTrue(repository.listQueueEntries().isEmpty()); assertEquals(0,renders.get());
        }
    }


    @Test void restartAtDurableStagePreservesSourceAndRetainsVerifiedOutput() throws Exception {
        for (var stage : List.of(StageCheckpoint.Stage.SOURCE_VERIFIED, StageCheckpoint.Stage.REGIONS_RENDERING,
                StageCheckpoint.Stage.OME_VERIFIED, StageCheckpoint.Stage.PACKAGE_COMMITTED)) {
            var database=root.resolve(stage.name()+".db"); String batchId; String artifactId; Path output;
            int before=renders.get();
            try (var repository = new SqliteDatasetRepository(database,root.resolve("legacy.properties"));
                    var conversions = new ConversionService(repository,engine(),derivative(),root.resolve("managed"))) {
                conversions.setQueuePaused(true); var source=dataset("stage"+stage.name(),true); repository.save(source);
                var service=batches(repository,conversions); var batch=service.create(List.of(source.id()),ArtifactRevisionFormat.OME_DYNAMIC_V1);
                batchId=batch.id(); artifactId=batch.items().get(0).artifactRevisionId();
                var artifact=conversions.savedRevision(source.id(),artifactId).orElseThrow(); output=Path.of(artifact.omePath());
                new StageCheckpointStore(output.getParent()).save(new StageCheckpoint(artifact.id(),artifact.configurationRevision(),artifact.sourceFingerprint(),stage,1,1,System.currentTimeMillis()));
                if(stage.ordinal()>=StageCheckpoint.Stage.OME_VERIFIED.ordinal()) Files.copy(Path.of(source.sourcePath()),output);
            }
            try (var repository = new SqliteDatasetRepository(database,root.resolve("legacy.properties"));
                    var conversions = new ConversionService(repository,engine(),derivative(),root.resolve("managed"))) {
                var service=batches(repository,conversions); service.recoverPending(); conversions.setQueuePaused(false);
                var finished=await(service,batchId);
                assertEquals("SUCCEEDED",finished.items().get(0).state(),stage.toString());
                assertEquals(artifactId,finished.items().get(0).artifactRevisionId());
                assertArrayEquals(Files.readAllBytes(Path.of(finished.items().get(0).snapshot().sourcePath())),Files.readAllBytes(output));
                assertEquals(before+(stage.ordinal()>=StageCheckpoint.Stage.OME_VERIFIED.ordinal()?0:1),renders.get());
                service.cancel(batchId); assertTrue(Files.isRegularFile(output));
            }
        }
    }

    @Test void changedSourceDuringPauseFailsClosedBeforeRenderingAndTeachingRequiresExplicitEntry() throws Exception {
        try(var repository=new SqliteDatasetRepository(root.resolve("library.db"),root.resolve("legacy.properties"));
                var conversions=new ConversionService(repository,engine(),derivative(),root.resolve("managed"))) {
            conversions.setQueuePaused(true); var source=dataset("changed-source",true); repository.save(source);
            var service=batches(repository,conversions); var batch=service.create(List.of(source.id()),ArtifactRevisionFormat.OME_DYNAMIC_V1);
            Files.write(Path.of(source.sourcePath()),new byte[] {'I','I',42,0,9,9,9,9,9});
            conversions.setQueuePaused(false); batch=await(service,batch.id());
            assertEquals("FAILED",batch.items().get(0).state()); assertEquals(0,renders.get());
            assertTrue(Files.isRegularFile(Path.of(source.sourcePath())));
            conversions.setQueuePaused(true); var teaching=dataset("teaching",true); repository.save(teaching);
            conversions.startTeaching(teaching.id());
            assertEquals("PREPARED_DZI_V2",repository.listQueueEntries().get(0).requestedFormat());
            conversions.cancel(teaching.id()); conversions.start(teaching.id(),ArtifactRevisionFormat.PREPARED_DZI_V2);
            assertEquals("OME_DYNAMIC_V1",repository.listQueueEntries().get(0).requestedFormat());
        }
    }


    @Test void deferredStartupRecoversBatchWideCancellationBeforeAnyReaderRuns() throws Exception {
        var database=root.resolve("cancel-restart.db"); String batchId;
        try (var repository=new SqliteDatasetRepository(database,root.resolve("legacy.properties"));
                var conversions=new ConversionService(repository,engine(),derivative(),root.resolve("managed"),true)) {
            var first=dataset("cancel-first",true); var second=dataset("cancel-second",true); repository.save(first);repository.save(second);
            var batch=batches(repository,conversions).create(List.of(first.id(),second.id()),ArtifactRevisionFormat.OME_DYNAMIC_V1); batchId=batch.id();
            var requested=batch.items().stream().map(item->item.withOutcome(item.artifactRevisionId(),"CANCEL_REQUESTED","forced interruption after batch cancellation intent")).toList();
            new BatchStore(root.resolve("batches.db")).save(new BatchRun(batch.id(),batch.createdAt(),batch.format(),requested));
        }
        try(var repository=new SqliteDatasetRepository(database,root.resolve("legacy.properties"));
                var conversions=new ConversionService(repository,engine(),derivative(),root.resolve("managed"),true)) {
            var service=batches(repository,conversions); service.recoverPending(); conversions.resumeQueueDispatch();
            assertTrue(service.get(batchId).items().stream().allMatch(item->item.state().equals("CANCELLED")));
            assertTrue(repository.listQueueEntries().isEmpty()); assertEquals(0,renders.get()); assertFalse(conversions.queuePaused());
        }
    }

    @Test void csvEscapesQuotesNewlinesAndSpreadsheetFormulas() {
        assertEquals("\"' =SUM(1,2)\"",BatchService.csvCell(" =SUM(1,2)"));
        assertEquals("\"'@formula\nrest\"",BatchService.csvCell("@formula\nrest"));
        assertEquals("\"a\"\"b\nc\"",BatchService.csvCell("a\"b\nc"));
    }
}


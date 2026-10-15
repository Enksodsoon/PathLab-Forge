package org.pathlab.forge.batch;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.conversion.*;
import org.pathlab.forge.derivative.*;
import org.pathlab.forge.library.*;

final class BatchInspectionTest {
    @TempDir Path root;
    static final List<SeriesInfo> SERIES = List.of(new SeriesInfo(0,"small",8,8,3,1,1,"uint8",1,1,"um"), new SeriesInfo(2,"large",16,16,3,1,1,"uint8",1,1,"um"));
    static DerivativeEngine derivative() {
        return new DerivativeEngine() {
            public boolean available() { return true; }
            public String description() { return "synthetic metadata/OME fixture"; }
            public void optimizeOme(Path source, Path output, int width, int height) throws IOException { Files.copy(source,output,StandardCopyOption.REPLACE_EXISTING); }
            public DerivativeInfo generateDzi(Path source, Path output, int width, int height) throws IOException { throw new IOException("not a Teaching fixture"); }
        };
    }
    static ConversionEngine engine(AtomicInteger inspections, CountDownLatch entered, CountDownLatch release) {
        return new ConversionEngine() {
            public boolean available() { return true; }
            public String runtimeDescription() { return "synthetic bounded inspection"; }
            public List<SeriesInfo> inspect(Path source) throws IOException {
                inspections.incrementAndGet(); entered.countDown();
                try { if(!release.await(20,TimeUnit.SECONDS)) throw new IOException("fixture reader timeout"); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new IOException("fixture inspection interrupted",interrupted); }
                return SERIES;
            }
            public void convert(Path source,int series,Path output) throws IOException { Files.copy(source,output,StandardCopyOption.REPLACE_EXISTING); }
        };
    }
    static LocalDataset source(Path root,String name) throws Exception {
        var path=root.resolve(name+".ome.tif");Files.write(path,new byte[]{'I','I',42,0,1,2,3,4});
        return new DatasetInspector().inspectFast(path);
    }
    static BatchService batches(Path root,DatasetRepository repository,ConversionService conversions) throws IOException {
        return new BatchService(repository,conversions,new BatchStore(root.resolve("batches.db")),id->null);
    }
    static BatchRun await(BatchService service,String id) throws Exception {
        for(int attempt=0;attempt<1500;attempt++) {
            var batch=service.get(id);
            if(batch.items().stream().allMatch(item->List.of("FAILED","SUCCEEDED","CANCELLED").contains(item.state()))) return batch;
            Thread.sleep(20);
        }
        fail("Synthetic batch did not finish");return null;
    }

    @Test void manifestPrecedesInspectionAndCancelDoesNotWaitForTheReader() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var inspections=new AtomicInteger();
        try(var repository=new SqliteDatasetRepository(root.resolve("forge.db"),root.resolve("legacy"));
                var conversions=new ConversionService(repository,engine(inspections,entered,release),derivative(),root.resolve("managed"),true);
                var service=batches(root,repository,conversions)) {
            var original=source(root,"blocked");repository.save(original);
            var batch=service.create(List.of(original.id()),ArtifactRevisionFormat.OME_DYNAMIC_V1);
            assertEquals(original,batch.items().get(0).initialSnapshot());assertEquals(-1,batch.items().get(0).snapshot().selectedSeries());
            conversions.resumeQueueDispatch();assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),()-> {
                assertEquals("INSPECTING",service.get(batch.id()).items().get(0).state());
                assertEquals("CANCELLED",service.cancel(batch.id()).items().get(0).state());
                assertTrue(repository.listQueueEntries().isEmpty());
            });
            release.countDown();assertTrue(Files.isRegularFile(Path.of(original.sourcePath())));
        } finally { release.countDown(); }
    }

    @Test void cancelledReaderCannotFailOrCommitOverANewerRetryAttempt() throws Exception {
        for (boolean lateFailure : List.of(true, false)) {
            var folder = Files.createDirectories(root.resolve(lateFailure ? "late-failure" : "late-success"));
            var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var release = new CountDownLatch(1);
            var inspections = new AtomicInteger();
            var reader = new ConversionEngine() {
                public boolean available() { return true; }
                public String runtimeDescription() { return "delayed cancellation unwind fixture"; }
                public List<SeriesInfo> inspect(Path source) throws IOException {
                    if (inspections.incrementAndGet() == 1) {
                        entered.countDown();
                        try { new CountDownLatch(1).await(); }
                        catch (InterruptedException expected) {
                            interrupted.countDown();
                            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("fixture unwind timeout"); }
                            catch (InterruptedException repeated) { throw new IOException(repeated); }
                            if (lateFailure) throw new IOException("previous attempt reader interrupted");
                            // A native reader can swallow cancellation and return successful metadata late.
                        }
                    }
                    return SERIES;
                }
                public void convert(Path source, int series, Path output) throws IOException { Files.copy(source, output, StandardCopyOption.REPLACE_EXISTING); }
            };
            try (var repository = new SqliteDatasetRepository(folder.resolve("forge.db"), folder.resolve("legacy"));
                    var conversions = new ConversionService(repository, reader, derivative(), folder.resolve("managed"), true);
                    var service = batches(folder, repository, conversions)) {
                var original = source(folder, "retry"); repository.save(original);
                var batch = service.create(List.of(original.id()), ArtifactRevisionFormat.OME_DYNAMIC_V1);
                conversions.resumeQueueDispatch(); assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals("CANCELLED", service.cancel(batch.id()).items().get(0).state());
                assertTrue(interrupted.await(5, TimeUnit.SECONDS));
                var retried = service.retry(batch.id(), original.id()).items().get(0);
                assertEquals(2, retried.attempts()); assertEquals("PENDING", retried.state());
                conversions.setQueuePaused(true); release.countDown(); Thread.sleep(150);
                var pending = service.get(batch.id()).items().get(0);
                assertEquals("PENDING", pending.state(), pending.detail()); assertEquals(retried, pending);
                assertEquals(original.configurationRevision(), repository.find(original.id()).orElseThrow().configurationRevision());
                assertEquals(-1, repository.find(original.id()).orElseThrow().selectedSeries());
                assertTrue(repository.listQueueEntries().isEmpty());
                conversions.setQueuePaused(false);
                var finished = await(service, batch.id()).items().get(0);
                assertEquals("SUCCEEDED", finished.state(), finished.detail()); assertEquals(2, finished.attempts());
                // Successful late metadata may populate the immutable source-keyed cache, but cannot commit attempt 2.
                assertEquals(lateFailure ? 2 : 1, inspections.get()); assertEquals(original, finished.initialSnapshot());
            } finally { release.countDown(); }
        }
    }

    @Test void concurrentUserSelectionSurvivesAndCannotBeGuessedByBatchInspection() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var inspections=new AtomicInteger();
        try(var repository=new SqliteDatasetRepository(root.resolve("forge.db"),root.resolve("legacy"));
                var conversions=new ConversionService(repository,engine(inspections,entered,release),derivative(),root.resolve("managed"),true);
                var service=batches(root,repository,conversions)) {
            var original=source(root,"cas");repository.save(original);var batch=service.create(List.of(original.id()),ArtifactRevisionFormat.OME_DYNAMIC_V1);
            conversions.resumeQueueDispatch();assertTrue(entered.await(5,TimeUnit.SECONDS));
            var edited=original.withExportConfiguration(DatasetStatus.READY_TO_CONVERT,"user chose exact plane",0,8,8,1,100,1,1,4,4);
            repository.save(edited);release.countDown();
            assertEquals("FAILED",await(service,batch.id()).items().get(0).state());
            assertEquals(edited,repository.find(original.id()).orElseThrow());assertTrue(repository.listQueueEntries().isEmpty());
        } finally { release.countDown(); }
    }

    @Test void pausePreventsInspectionAndPreparedManifestRecoversBeforeDatasetCasCommit() throws Exception {
        var inspections=new AtomicInteger();String batchId;LocalDataset original;LocalDataset prepared;
        try(var repository=new SqliteDatasetRepository(root.resolve("forge.db"),root.resolve("legacy"));
                var conversions=new ConversionService(repository,engine(inspections,new CountDownLatch(0),new CountDownLatch(0)),derivative(),root.resolve("managed"),true);
                var service=batches(root,repository,conversions)) {
            conversions.setQueuePaused(true);original=source(root,"prepared");repository.save(original);
            var batch=service.create(List.of(original.id()),ArtifactRevisionFormat.OME_DYNAMIC_V1);batchId=batch.id();
            conversions.resumeQueueDispatch();Thread.sleep(1100);assertEquals(0,inspections.get());
            // Synthetic crash checkpoint: exact prepared settings durable, dataset CAS not applied.
            prepared=conversions.prepareExpected(original);
            var frozen=batch.items().get(0).withPrepared(prepared);
            new BatchStore(root.resolve("batches.db")).save(new BatchRun(batch.id(),batch.createdAt(),batch.format(),List.of(frozen)));
            assertEquals(original,repository.find(original.id()).orElseThrow());
        }
        try(var repository=new SqliteDatasetRepository(root.resolve("forge.db"),root.resolve("legacy"));
                var conversions=new ConversionService(repository,engine(inspections,new CountDownLatch(0),new CountDownLatch(0)),derivative(),root.resolve("managed"),true);
                var service=batches(root,repository,conversions)) {
            service.recoverPending();conversions.resumeQueueDispatch();conversions.setQueuePaused(false);
            var finished=await(service,batchId);
            assertEquals("SUCCEEDED",finished.items().get(0).state());assertEquals(original,finished.items().get(0).initialSnapshot());
            assertEquals(prepared.configurationRevision(),finished.items().get(0).snapshot().configurationRevision());assertEquals(1,inspections.get());
        }
    }
    @Test void realChildJvmKillDuringInspectionRecoversOriginalIntent() throws Exception {
        var batchId=killDuringInspection(root,"ome");
        var initial=new BatchStore(root.resolve("batches.db")).get(batchId).items().get(0).initialSnapshot();
        var inspections=new AtomicInteger();
        try(var repository=new SqliteDatasetRepository(root.resolve("forge.db"),root.resolve("legacy"));
                var conversions=new ConversionService(repository,engine(inspections,new CountDownLatch(0),new CountDownLatch(0)),derivative(),root.resolve("managed"),true);
                var service=batches(root,repository,conversions)) {
            service.recoverPending();conversions.resumeQueueDispatch();
            var finished=await(service,batchId);var item=finished.items().get(0);
            assertEquals("SUCCEEDED",item.state(),item.detail());assertEquals(initial,item.initialSnapshot());
            assertEquals(2,item.snapshot().selectedSeries());assertEquals(1,inspections.get());
            assertFalse(item.snapshot().configurationRevision().isBlank());
            assertArrayEquals(Files.readAllBytes(Path.of(initial.sourcePath())),Files.readAllBytes(Path.of(conversions.savedRevision(initial.id(),item.artifactRevisionId()).orElseThrow().omePath())));
        }
    }

    @Test void killedInspectionRejectsModifiedMissingAndNewCompanionSourcesBeforeReader() throws Exception {
        for(var mutation:List.of("modified","missing","new-companion")) {
            var folder=Files.createDirectories(root.resolve(mutation));var batchId=killDuringInspection(folder,mutation.equals("new-companion")?"vsi":"ome");
            var initial=new BatchStore(folder.resolve("batches.db")).get(batchId).items().get(0).initialSnapshot();var source=Path.of(initial.sourcePath());
            if(mutation.equals("modified")) Files.write(source,new byte[]{'I','I',42,0,9,9,9,9,9});
            else if(mutation.equals("missing")) Files.delete(source);
            else Files.write(folder.resolve("case/extra.ets"),new byte[]{9});
            var inspections=new AtomicInteger();
            try(var repository=new SqliteDatasetRepository(folder.resolve("forge.db"),folder.resolve("legacy"));
                    var conversions=new ConversionService(repository,engine(inspections,new CountDownLatch(0),new CountDownLatch(0)),derivative(),folder.resolve("managed"),true);
                    var service=batches(folder,repository,conversions)) {
                service.recoverPending();conversions.resumeQueueDispatch();var item=await(service,batchId).items().get(0);
                assertEquals("FAILED",item.state(),mutation);assertEquals(initial,item.initialSnapshot());assertEquals(0,inspections.get());
                assertTrue(repository.listQueueEntries().isEmpty());assertEquals("",item.artifactRevisionId());
            }
        }
    }

    @Test void streamingSourceDigestHonorsCancellationWithoutChangingTheSource() throws Exception {
        var original=source(root,"interrupt");
        Thread.currentThread().interrupt();
        try { assertThrows(java.io.InterruptedIOException.class,()->SourceDigest.compute(SourceSnapshot.fromSerialized(Path.of(original.sourcePath()),original.sourceInventory()))); }
        finally { Thread.interrupted(); }
        assertEquals(8,Files.size(Path.of(original.sourcePath())));
    }


    @Test void tenFreshSourcesKeepMixedInspectionOutcomesAndRetryOnlyFailedConversion() throws Exception {
        var inspections=new AtomicInteger();var renders=new AtomicInteger();var forceFailure=new java.util.concurrent.atomic.AtomicBoolean(true);
        var reader=new ConversionEngine() {
            public boolean available() { return true; }
            public String runtimeDescription() { return "ten mixed fresh-source synthetic fixture"; }
            public List<SeriesInfo> inspect(Path source) throws IOException {
                inspections.incrementAndGet();
                if(source.getFileName().toString().startsWith("bad-reader")) throw new IOException("forced fixture metadata failure");
                if(source.getFileName().toString().startsWith("unsupported")) return List.of(new SeriesInfo(0,"single channel",8,8,1,1,1,"uint8",1,1,"um"));
                return SERIES;
            }
            public void convert(Path source,int series,Path output) throws IOException {
                renders.incrementAndGet();if(source.getFileName().toString().startsWith("fail-render")&&forceFailure.get()) throw new IOException("forced fixture render failure");
                Files.copy(source,output,StandardCopyOption.REPLACE_EXISTING);
            }
        };
        try(var repository=new SqliteDatasetRepository(root.resolve("forge.db"),root.resolve("legacy"));
                var conversions=new ConversionService(repository,reader,derivative(),root.resolve("managed"),true);
                var service=batches(root,repository,conversions)) {
            var ids=new java.util.ArrayList<String>();
            for(int index=0;index<10;index++) {
                var source=source(root,index==0?"fail-render":index==8?"bad-reader":index==9?"unsupported":"fresh"+index);
                repository.save(source);ids.add(source.id());
            }
            var created=service.create(ids,ArtifactRevisionFormat.OME_DYNAMIC_V1);
            assertTrue(created.items().stream().allMatch(item->item.snapshot().selectedSeries()==-1));
            conversions.resumeQueueDispatch();var finished=await(service,created.id());
            assertEquals(7,finished.items().stream().filter(item->item.state().equals("SUCCEEDED")).count());
            assertEquals(3,finished.items().stream().filter(item->item.state().equals("FAILED")).count());assertEquals(10,inspections.get());assertEquals(8,renders.get());
            var retained=finished.items().get(1).artifactRevisionId();forceFailure.set(false);
            finished=await(service,service.retry(created.id(),ids.get(0)).id());
            assertEquals("SUCCEEDED",finished.items().get(0).state());assertEquals(10,inspections.get());assertEquals(9,renders.get());
            assertEquals(retained,finished.items().get(1).artifactRevisionId());
            assertTrue(Files.isRegularFile(Path.of(conversions.savedRevision(ids.get(1),retained).orElseThrow().omePath())));
            assertTrue(finished.items().stream().allMatch(item->item.initialSnapshot().selectedSeries()==-1));
        }
    }

    @Test void cachedSeriesCannotBypassSourceVerification() throws Exception {
        var inspections=new AtomicInteger();
        try(var repository=new SqliteDatasetRepository(root.resolve("forge.db"),root.resolve("legacy"));
                var conversions=new ConversionService(repository,engine(inspections,new CountDownLatch(0),new CountDownLatch(0)),derivative(),root.resolve("managed"))) {
            var original=source(root,"cached");original=new DatasetInspector().inspect(Path.of(original.sourcePath()));repository.save(original);
            conversions.inspect(original.id());Files.write(Path.of(original.sourcePath()),new byte[]{'I','I',42,0,9,9,9,9,9});
            var id=original.id();assertThrows(IllegalStateException.class,()->conversions.series(id));assertEquals(1,inspections.get());
        }
    }

    @Test void legacyFiveFieldItemMigratesToAnInitialSnapshotWithoutChangingSavedSettings() throws Exception {
        var original=source(root,"legacy");var batch=new BatchRun("old-batch",System.currentTimeMillis(),ArtifactRevisionFormat.OME_DYNAMIC_V1,List.of(new BatchRun.Item(original,"","PENDING","old record",1)));
        var store=new BatchStore(root.resolve("batches.db"));store.save(batch);
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("batches.db"));var update=connection.prepareStatement("UPDATE conversion_batches SET record_json=? WHERE id=?")) {
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper().setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.IS_GETTER,com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE);
            var json=mapper.valueToTree(batch);((com.fasterxml.jackson.databind.node.ObjectNode)json.path("items").get(0)).remove("initialSnapshot");
            update.setString(1,mapper.writeValueAsString(json));update.setString(2,batch.id());update.executeUpdate();
        }
        assertEquals(original,store.get(batch.id()).items().get(0).initialSnapshot());assertEquals(batch,store.get(batch.id()));
    }

    private static String killDuringInspection(Path folder,String format) throws Exception {
        var entries=new java.util.LinkedHashSet<String>();
        for(var loader=BatchInspectionTest.class.getClassLoader();loader!=null;loader=loader.getParent()) {
            if(loader instanceof java.net.URLClassLoader urls) for(var url:urls.getURLs()) if(url.getProtocol().equals("file")) entries.add(Path.of(url.toURI()).toString());
        }
        if(entries.isEmpty()) entries.add(System.getProperty("java.class.path"));
        var javaCommand=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var log=folder.resolve("child.log");
        var process=new ProcessBuilder(javaCommand,"-cp",String.join(java.io.File.pathSeparator,entries),InspectionKillFixture.class.getName(),folder.toString(),format).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            for(int attempt=0;attempt<500&&!Files.exists(folder.resolve("inspection-entered"));attempt++) {
                if(!process.isAlive()) fail("Synthetic child failed before inspection: "+Files.readString(log));
                Thread.sleep(20);
            }
            assertTrue(Files.exists(folder.resolve("inspection-entered")),Files.readString(log));
            var id=Files.readString(folder.resolve("batch-id"));
            assertEquals("INSPECTING",new BatchStore(folder.resolve("batches.db")).get(id).items().get(0).state());
            process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));
            return id;
        } finally { if(process.isAlive()) { process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS); } }
    }

    public static final class InspectionKillFixture {
        public static void main(String[] args) throws Exception {
            var folder=Path.of(args[0]);Files.createDirectories(folder);
            LocalDataset original;
            if(args[1].equals("vsi")) {
                var source=folder.resolve("case.vsi");Files.write(source,new byte[]{'I','I',42,0,1,2,3,4});
                Files.write(Files.createDirectories(folder.resolve("case")).resolve("frame.ets"),new byte[]{1,2,3,4});
                original=new DatasetInspector().inspectFast(source);
            } else original=source(folder,"case");
            try(var repository=new SqliteDatasetRepository(folder.resolve("forge.db"),folder.resolve("legacy"));
                    var conversions=new ConversionService(repository,new ConversionEngine() {
                        public boolean available() { return true; }
                        public String runtimeDescription() { return "forced-kill synthetic metadata reader"; }
                        public List<SeriesInfo> inspect(Path source) throws IOException {
                            Files.writeString(folder.resolve("inspection-entered"),"metadata reader entered after durable batch INSPECTING commit");
                            try { Thread.sleep(60000); } catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new IOException(failure); }
                            return SERIES;
                        }
                        public void convert(Path source,int series,Path output) throws IOException { throw new IOException("Reader must be killed before conversion"); }
                    },derivative(),folder.resolve("managed"),true);
                    var service=batches(folder,repository,conversions)) {
                repository.save(original);var batch=service.create(List.of(original.id()),ArtifactRevisionFormat.OME_DYNAMIC_V1);
                Files.writeString(folder.resolve("batch-id"),batch.id());conversions.resumeQueueDispatch();
                Thread.sleep(60000);
            }
        }
    }


}

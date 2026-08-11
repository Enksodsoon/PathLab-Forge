package org.pathlab.forge.analysis;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class AnalysisJobService implements AutoCloseable {
    private final HeAnalysisService he;
    private final BooleanSupplier conversionBusy;
    private final BooleanSupplier pathologyEnabled;
    private final Map<String, AnalysisJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> active = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public AnalysisJobService(
            HeAnalysisService he,
            BooleanSupplier conversionBusy,
            BooleanSupplier pathologyEnabled) {
        this.he = he;
        this.conversionBusy = conversionBusy;
        this.pathologyEnabled = pathologyEnabled;
        var profile = org.pathlab.forge.runtime.RuntimeProfile.system();
        var workers = profile.quPathHeapBytes() >= 4L * 1024 * 1024 * 1024 ? 2 : 1;
        executor = Executors.newFixedThreadPool(workers, runnable -> {
            var thread = new Thread(runnable, "pathlab-analysis");
            thread.setDaemon(true);
            return thread;
        });
    }

    public AnalysisJob submitHe(
            String datasetId, String annotationId, double hThreshold, double eThreshold) {
        if (!pathologyEnabled.getAsBoolean()) {
            throw new IllegalStateException("Install the verified Pathology Tools pack first");
        }
        var id = UUID.randomUUID().toString();
        var created = new AnalysisJob(
                id, "pathology.he", datasetId, annotationId, "QUEUED", 0,
                "Waiting for the bounded analysis worker", System.currentTimeMillis(), 0, 0);
        jobs.put(id, created);
        active.put(id, executor.submit(() -> runHe(created, hThreshold, eThreshold)));
        return created;
    }

    public AnalysisJob get(String id) {
        var job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("Analysis job was not found");
        return job;
    }

    public AnalysisJob cancel(String id) {
        var current = get(id);
        var future = active.remove(id);
        if (future != null) future.cancel(true);
        var cancelled = new AnalysisJob(
                current.id(), current.moduleId(), current.datasetId(), current.annotationId(),
                "CANCELLED", current.progress(), "Analysis cancelled", current.createdAt(),
                current.startedAt(), System.currentTimeMillis());
        jobs.put(id, cancelled);
        return cancelled;
    }

    private void runHe(AnalysisJob initial, double hThreshold, double eThreshold) {
        try {
            while (conversionBusy.getAsBoolean()) {
                if (Thread.currentThread().isInterrupted()) return;
                TimeUnit.MILLISECONDS.sleep(250);
            }
            var started = System.currentTimeMillis();
            jobs.put(initial.id(), new AnalysisJob(
                    initial.id(), initial.moduleId(), initial.datasetId(), initial.annotationId(),
                    "RUNNING", 10, "Reading bounded original RGB pixels", initial.createdAt(), started, 0));
            var result = he.analyze(
                    initial.datasetId(), initial.annotationId(), hThreshold, eThreshold);
            jobs.put(initial.id(), new AnalysisJob(
                    initial.id(), initial.moduleId(), initial.datasetId(), initial.annotationId(),
                    "SUCCEEDED", 100,
                    "Research-only H&E analysis completed for " + result.sampledPixels() + " sampled pixels",
                    initial.createdAt(), started, System.currentTimeMillis()));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException error) {
            var current = jobs.get(initial.id());
            if (current != null && !"CANCELLED".equals(current.status())) {
                jobs.put(initial.id(), new AnalysisJob(
                        initial.id(), initial.moduleId(), initial.datasetId(), initial.annotationId(),
                        "FAILED", current.progress(),
                        error.getMessage() == null ? "Analysis failed" : error.getMessage(),
                        initial.createdAt(), current.startedAt(), System.currentTimeMillis()));
            }
        } finally {
            active.remove(initial.id());
        }
    }

    @Override
    public void close() {
        active.values().forEach(future -> future.cancel(true));
        executor.shutdownNow();
    }
}

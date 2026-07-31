package org.pathlab.forge.library;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SourceVerificationService implements AutoCloseable {
    private final DatasetRepository repository;
    private final Map<String, CompletableFuture<LocalDataset>> active = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "pathlab-source-digest");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    public SourceVerificationService(DatasetRepository repository) {
        this.repository = repository;
    }

    public CompletableFuture<LocalDataset> verifyAsync(LocalDataset dataset) {
        if (!dataset.sourceFingerprint().isBlank()
                && DatasetSourceInventory.matchesSnapshot(
                        Path.of(dataset.sourcePath()), dataset.sourceInventory())) {
            return CompletableFuture.completedFuture(dataset);
        }
        return active.computeIfAbsent(
                dataset.id(),
                ignored -> CompletableFuture.supplyAsync(() -> verify(dataset), executor)
                        .whenComplete((result, error) -> active.remove(dataset.id())));
    }

    public LocalDataset await(String datasetId) throws IOException {
        var dataset = repository.find(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("Dataset was not found"));
        try {
            return verifyAsync(dataset).join();
        } catch (CompletionException error) {
            if (error.getCause() instanceof java.io.UncheckedIOException io) {
                throw io.getCause();
            }
            if (error.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("Source verification failed", error.getCause());
        }
    }

    private LocalDataset verify(LocalDataset dataset) {
        try {
            var snapshot = DatasetInspector.snapshot(
                    Path.of(dataset.sourcePath()), dataset.format());
            var digest = SourceDigest.compute(snapshot);
            var after = DatasetInspector.snapshot(
                    Path.of(dataset.sourcePath()), dataset.format());
            if (!snapshot.fingerprint().equals(after.fingerprint())) {
                throw new IOException("Source changed while content digest was being computed");
            }
            return repository.update(dataset.id(), current -> mergeVerification(
                    current, snapshot, digest.fingerprint(), digest.serializedInventory()));
        } catch (IOException | DatasetInspectionException error) {
            try {
                var current = repository.find(dataset.id()).orElse(dataset);
                repository.save(current.withPreparation(
                        DatasetStatus.FAILED,
                        error.getMessage() == null ? "Source verification failed" : error.getMessage(),
                        dataset.outputPath(),
                        dataset.sha256()));
            } catch (IOException ignored) {
                // Preserve original verification error.
            }
            throw new java.io.UncheckedIOException(
                    error instanceof IOException io
                            ? io
                            : new IOException(error.getMessage(), error));
        }
    }

    private static LocalDataset mergeVerification(
            LocalDataset current,
            SourceSnapshot snapshot,
            String fingerprint,
            String inventory) {
        var verifiedStatus = DatasetInspector.readyStatus(current.format(), snapshot);
        var verifiedDetail = DatasetInspector.readyDetail(current.format(), snapshot);
        if (verifiedStatus != DatasetStatus.NEEDS_COMPANIONS
                && current.status() == DatasetStatus.VERIFYING_SOURCE
                && current.selectedSeries() >= 0) {
            verifiedStatus = DatasetStatus.READY_TO_CONVERT;
            verifiedDetail = "Source content verified; conversion settings are ready";
        } else if (verifiedStatus != DatasetStatus.NEEDS_COMPANIONS
                && current.status() != DatasetStatus.VERIFYING_SOURCE) {
            verifiedStatus = current.status();
            verifiedDetail = current.detail();
        }
        return current.withSourceIdentity(verifiedStatus, verifiedDetail, fingerprint, inventory);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}

package org.pathlab.forge.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.pathlab.forge.conversion.ArtifactRevision;
import org.pathlab.forge.conversion.ArtifactRevisionFormat;
import org.pathlab.forge.conversion.ArtifactRevisionStatus;
import org.pathlab.forge.conversion.ConversionService;
import org.pathlab.forge.library.DatasetRepository;

public final class BatchService implements AutoCloseable {
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private final DatasetRepository datasets;
    private final ConversionService conversions;
    private final BatchStore store;
    private final Function<String, Delivery> delivery;
    private boolean closed;
    private int preparationPageOffset;
    private String activePreparation = "";
    public record Delivery(String state, String nextAction, String detail) {}
    public record SlideReport(BatchRun.Item item, ArtifactRevision artifact, long artifactBytes, Delivery delivery, String nextAction) {}
    public record Report(String batchId, long createdAt, ArtifactRevisionFormat format, boolean queuePaused, List<SlideReport> slides) {}

    public BatchService(DatasetRepository datasets, ConversionService conversions, BatchStore store,
            Function<String, Delivery> delivery) {
        this.datasets = datasets; this.conversions = conversions; this.store = store; this.delivery = delivery;
        conversions.setBatchMaintenance(this::maintenance);
    }

    public synchronized BatchRun create(List<String> ids, ArtifactRevisionFormat format) throws IOException {
        if (ids.isEmpty() || ids.size() > 1000 || Set.copyOf(ids).size() != ids.size())
            throw new IllegalArgumentException("Select 1 to 1000 distinct slides");
        java.util.Objects.requireNonNull(format, "format");
        if (format != ArtifactRevisionFormat.OME_DYNAMIC_V1) throw new IllegalArgumentException("Batch conversion currently supports direct OME only");
        var items = new ArrayList<BatchRun.Item>();
        for (var id : ids) {
            var snapshot = datasets.find(id).orElseThrow(() -> new IllegalArgumentException("Dataset was not found: " + id));
            items.add(new BatchRun.Item(snapshot, "", "PENDING", "Waiting for durable queue admission", 1));
        }
        var batch = new BatchRun(UUID.randomUUID().toString(), System.currentTimeMillis(), format, items);
        store.save(batch); // Every original setting and source identity exists before the first dispatch.
        for (var item : items) if (!needsPreparation(item)) admit(batch.id(), item.snapshot().id());
        return get(batch.id());
    }

    private void admit(String id, String datasetId) throws IOException {
        var batch = store.get(id);
        var item = requireItem(batch, datasetId);
        if (closed || TERMINAL.contains(item.state())) return;
        try {
            conversions.startExpected(item.snapshot(), batch.format(), admitted ->
                    updateItem(id, item.withOutcome(admitted.currentArtifactRevision(), "ADMITTED", admitted.detail())));
        } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
            updateItem(id, item.withOutcome(item.artifactRevisionId(), "FAILED", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
    }

    private static boolean needsPreparation(BatchRun.Item item) {
        return item.snapshot().selectedSeries() < 0 || item.snapshot().sourceFingerprint().isBlank()
                || item.state().equals("PREPARED")
                || item.artifactRevisionId().isBlank() && !item.snapshot().equals(item.initialSnapshot());
    }

    private static String preparationKey(String batchId, String datasetId) { return batchId + ":" + datasetId; }

    private synchronized void maintenance() {
        if (closed || !activePreparation.isBlank() || conversions.queuePaused()) return;
        try {
            var page = store.list(100, preparationPageOffset);
            for (var batch : page) for (var item : batch.items()) {
                if (TERMINAL.contains(item.state()) || item.state().equals("CANCEL_REQUESTED") || !needsPreparation(item)) continue;
                var key = preparationKey(batch.id(), item.snapshot().id());
                if (conversions.submitBatchPreparation(key, () -> prepare(batch.id(), item, key))) activePreparation = key;
                return;
            }
            preparationPageOffset = page.size() < 100 ? 0 : preparationPageOffset + 100;
        } catch (IOException | RuntimeException ignored) {
            // The durable row remains pending; the next bounded scheduler pass retries the store read.
        }
    }

    private static boolean samePreparationAttempt(BatchRun.Item current, BatchRun.Item expected) {
        return current.attempts() == expected.attempts() && current.initialSnapshot().equals(expected.initialSnapshot())
                && current.snapshot().equals(expected.snapshot());
    }

    private void prepare(String id, BatchRun.Item scheduled, String key) {
        var datasetId = scheduled.snapshot().id();
        BatchRun.Item original = scheduled;
        try {
            synchronized (this) {
                var current = requireItem(store.get(id), datasetId);
                if (!samePreparationAttempt(current, scheduled)) return;
                original = current;
                if (closed || TERMINAL.contains(original.state()) || original.state().equals("CANCEL_REQUESTED")
                        || conversions.queuePaused()) return;
                updateItem(id, original.withOutcome(original.artifactRevisionId(), "INSPECTING", "Reading saved source metadata; original batch intent is durable"));
            }
            // Existing conversion executor: one metadata inspection, no store/repository/service lock held.
            var alreadyPrepared = original.snapshot().selectedSeries() >= 0 && !original.snapshot().sourceFingerprint().isBlank();
            org.pathlab.forge.library.LocalDataset prepared;
            if (alreadyPrepared) {
                conversions.validatePreparedSource(original.snapshot());
                prepared = original.snapshot();
            } else prepared = conversions.prepareExpected(original.initialSnapshot());
            synchronized (this) {
                var current = requireItem(store.get(id), datasetId);
                if (!samePreparationAttempt(current, original) || closed || TERMINAL.contains(current.state()) || current.state().equals("CANCEL_REQUESTED")
                        || Thread.currentThread().isInterrupted()) return;
                original = current.withPrepared(prepared);
                updateItem(id, original); // Durable exact settings before the dataset CAS.
                conversions.applyPreparedExpected(current.initialSnapshot(), prepared);
                var frozen = requireItem(store.get(id), datasetId);
                updateItem(id, frozen.withOutcome(frozen.artifactRevisionId(), "PENDING", "Inspection complete; waiting for durable conversion admission"));
                // The preparation is frozen even if admission is interrupted; snapshot differs from initial intent.
                admit(id, datasetId);
            }
        } catch (IOException | RuntimeException failure) {
            synchronized (this) {
                try {
                    var current = requireItem(store.get(id), datasetId);
                    if (samePreparationAttempt(current, original) && !closed && !TERMINAL.contains(current.state()) && !current.state().equals("CANCEL_REQUESTED"))
                        updateItem(id, current.withOutcome(current.artifactRevisionId(), "FAILED", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
                } catch (IOException ignored) { /* Existing INSPECTING/PREPARED record remains recoverable. */ }
            }
        } finally {
            synchronized (this) { if (activePreparation.equals(key)) activePreparation = ""; }
        }
    }

    @Override public synchronized void close() {
        closed = true;
        conversions.setBatchMaintenance(() -> {});
        if (!activePreparation.isBlank()) conversions.cancelBatchPreparation(activePreparation);
    }

    public synchronized BatchRun get(String id) throws IOException {
        var batch = store.get(id);
        var items = new ArrayList<BatchRun.Item>();
        for (var item : batch.items()) {
            if (!TERMINAL.contains(item.state()) && !item.artifactRevisionId().isBlank()) {
                var artifact = artifact(item);
                if (artifact != null && (artifact.status() == ArtifactRevisionStatus.READY || artifact.status() == ArtifactRevisionStatus.APPROVED))
                    item = item.withOutcome(artifact.id(), "SUCCEEDED", "Verified local artifact retained");
                else if (artifact != null && artifact.status() == ArtifactRevisionStatus.FAILED)
                    item = item.withOutcome(artifact.id(), "FAILED", artifact.failure());
                else {
                    var current = datasets.find(item.snapshot().id()).orElse(null);
                    if (current == null || !current.configurationRevision().equals(item.snapshot().configurationRevision())) {
                        item = item.withOutcome(item.artifactRevisionId(), "FAILED", "Saved dataset settings are no longer available; the original batch snapshot is retained");
                    } else if (current.currentArtifactRevision().equals(item.artifactRevisionId())) {
                        if (current.status() == org.pathlab.forge.library.DatasetStatus.CANCELLED)
                            item = item.withOutcome(item.artifactRevisionId(), "CANCELLED", current.detail());
                        else if (current.status() == org.pathlab.forge.library.DatasetStatus.FAILED)
                            item = item.withOutcome(item.artifactRevisionId(), "FAILED", current.detail());
                    }
                }
            }
            items.add(item);
        }
        var refreshed = new BatchRun(batch.id(), batch.createdAt(), batch.format(), items);
        if (!refreshed.equals(batch)) store.save(refreshed);
        return refreshed;
    }

    public synchronized List<BatchRun> list(int limit, int offset) throws IOException {
        var result = new ArrayList<BatchRun>();
        for (var batch : store.list(limit, offset)) result.add(get(batch.id()));
        return List.copyOf(result);
    }

    public synchronized BatchRun retry(String id, String datasetId) throws IOException {
        var item = requireItem(get(id), datasetId);
        if (!item.state().equals("FAILED") && !item.state().equals("CANCELLED"))
            throw new IllegalStateException("Only a failed or cancelled slide can be retried");
        updateItem(id, new BatchRun.Item(item.snapshot(), item.artifactRevisionId(), "PENDING", "Retry pending", item.attempts() + 1, item.initialSnapshot()));
        if (!needsPreparation(requireItem(store.get(id), datasetId))) admit(id, datasetId);
        return get(id);
    }

    public synchronized BatchRun cancel(String id) throws IOException {
        var batch = get(id);
        var requested = batch.items().stream().map(item -> TERMINAL.contains(item.state()) ? item
                : item.withOutcome(item.artifactRevisionId(), "CANCEL_REQUESTED", "Cancellation requested; sources and verified artifacts retained")).toList();
        batch = new BatchRun(batch.id(), batch.createdAt(), batch.format(), requested);
        store.save(batch); // Persist cancellation of every remaining item before interrupting any reader.
        for (var item : batch.items()) {
            if (TERMINAL.contains(item.state())) continue;
            conversions.cancelBatchPreparation(preparationKey(id, item.snapshot().id()));
            var current = datasets.find(item.snapshot().id()).orElse(null);
            if (current != null && current.configurationRevision().equals(item.snapshot().configurationRevision())
                    && current.currentArtifactRevision().equals(item.artifactRevisionId())) conversions.cancel(current.id());
            updateItem(id, item.withOutcome(item.artifactRevisionId(), "CANCELLED", "Cancelled; sources and verified artifacts retained"));
        }
        return get(id);
    }

    // Call after opening the dataset repository; bounded pages use the existing conversion scheduler.
    public synchronized void recoverPending() throws IOException {
        for (int offset = 0; ; offset += 100) {
            var page = store.list(100, offset);
            for (var saved : page) {
                var batch = get(saved.id());
                if (batch.items().stream().anyMatch(item -> item.state().equals("CANCEL_REQUESTED"))) {
                    cancel(batch.id());
                    continue;
                }
                for (var item : batch.items()) if (!TERMINAL.contains(item.state()) && !needsPreparation(item)) admit(batch.id(), item.snapshot().id());
            }
            if (page.size() < 100) return;
        }
    }

    public synchronized Report report(String id) throws IOException {
        var batch = get(id);
        var slides = new ArrayList<SlideReport>();
        for (var item : batch.items()) {
            var artifact = artifact(item);
            var status = item.artifactRevisionId().isBlank() ? null : delivery.apply(item.artifactRevisionId());
            if (status == null) status = new Delivery("NOT_REQUESTED", "Approve artifact and choose a Viewer destination", "No delivery in the current Viewer account");
            var output = artifact == null ? null : Path.of(batch.format() == ArtifactRevisionFormat.OME_DYNAMIC_V1 ? artifact.omePath() : artifact.packagePath());
            var bytes = output != null && Files.isRegularFile(output) ? Files.size(output) : 0;
            var next = item.state().equals("SUCCEEDED") ? status.nextAction()
                    : item.state().equals("FAILED") || item.state().equals("CANCELLED") ? "Review failure and retry this slide with its saved settings"
                    : conversions.queuePaused() ? "Resume the conversion queue" : "Wait for local conversion";
            slides.add(new SlideReport(item, artifact, bytes, status, next));
        }
        return new Report(batch.id(), batch.createdAt(), batch.format(), conversions.queuePaused(), List.copyOf(slides));
    }

    public String reportJson(String id) throws IOException { return new ObjectMapper().writeValueAsString(report(id)); }

    public String reportCsv(String id) throws IOException {
        var report = report(id);
        var csv = new StringBuilder("batch_id,dataset_id,display_name,source_path,source_fingerprint,configuration_revision,series,crop_x,crop_y,crop_width,crop_height,downsample,artifact_revision,local_state,detail,artifact_sha256,artifact_bytes,viewer_state,next_action\r\n");
        for (var slide : report.slides()) {
            var item = slide.item(); var snapshot = item.snapshot(); var artifact = slide.artifact();
            var sha = artifact == null ? "" : report.format() == ArtifactRevisionFormat.OME_DYNAMIC_V1 ? artifact.omeSha256() : artifact.packageSha256();
            var values = List.of(report.batchId(), snapshot.id(), snapshot.displayName(), snapshot.sourcePath(), snapshot.sourceFingerprint(), snapshot.configurationRevision(), Integer.toString(snapshot.selectedSeries()), Integer.toString(snapshot.cropX()), Integer.toString(snapshot.cropY()), Integer.toString(snapshot.cropWidth()), Integer.toString(snapshot.cropHeight()), Double.toString(snapshot.downsample()), item.artifactRevisionId(), item.state(), item.detail(), sha, Long.toString(slide.artifactBytes()), slide.delivery().state(), slide.nextAction());
            csv.append(values.stream().map(BatchService::csvCell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        }
        return csv.toString();
    }

    static String csvCell(String value) {
        // Spreadsheet applications evaluate formulas even in quoted CSV fields.
        if (value.matches("(?s)^[\\s]*[=+@-].*")) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private ArtifactRevision artifact(BatchRun.Item item) throws IOException {
        if (item.artifactRevisionId().isBlank()) return null;
        return conversions.savedRevision(item.snapshot().id(), item.artifactRevisionId()).orElse(null);
    }

    private static BatchRun.Item requireItem(BatchRun batch, String datasetId) {
        return batch.items().stream().filter(item -> item.snapshot().id().equals(datasetId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Slide does not belong to this batch"));
    }

    private void updateItem(String id, BatchRun.Item replacement) throws IOException {
        var batch = store.get(id);
        var items = batch.items().stream().map(item -> item.snapshot().id().equals(replacement.snapshot().id()) ? replacement : item).toList();
        store.save(new BatchRun(batch.id(), batch.createdAt(), batch.format(), items));
    }
}

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

public final class BatchService {
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private final DatasetRepository datasets;
    private final ConversionService conversions;
    private final BatchStore store;
    private final Function<String, Delivery> delivery;
    public record Delivery(String state, String nextAction, String detail) {}
    public record SlideReport(BatchRun.Item item, ArtifactRevision artifact, long artifactBytes, Delivery delivery, String nextAction) {}
    public record Report(String batchId, long createdAt, ArtifactRevisionFormat format, boolean queuePaused, List<SlideReport> slides) {}

    public BatchService(DatasetRepository datasets, ConversionService conversions, BatchStore store,
            Function<String, Delivery> delivery) {
        this.datasets = datasets; this.conversions = conversions; this.store = store; this.delivery = delivery;
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
        for (var item : items) admit(batch.id(), item.snapshot().id());
        return get(batch.id());
    }

    private void admit(String id, String datasetId) throws IOException {
        var batch = store.get(id);
        var item = requireItem(batch, datasetId);
        try {
            conversions.startExpected(item.snapshot(), batch.format(), admitted ->
                    updateItem(id, item.withOutcome(admitted.currentArtifactRevision(), "ADMITTED", admitted.detail())));
        } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
            updateItem(id, item.withOutcome(item.artifactRevisionId(), "FAILED", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
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
        updateItem(id, new BatchRun.Item(item.snapshot(), item.artifactRevisionId(), "PENDING", "Retry pending", item.attempts() + 1));
        admit(id, datasetId);
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
                for (var item : batch.items()) if (!TERMINAL.contains(item.state())) admit(batch.id(), item.snapshot().id());
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

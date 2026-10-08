package org.pathlab.forge.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import org.pathlab.forge.annotation.AnnotationRecord;
import org.pathlab.forge.annotation.AnnotationRepository;
import org.pathlab.forge.conversion.RgbRegion;
import org.pathlab.forge.library.DatasetRepository;

public final class DeterministicAnalysisService implements AutoCloseable {
    public static final Set<String> TOOLS = Set.of("he", "stain_vector", "normalize_preview", "tma", "tissue", "qc", "nucleus_candidates", "registration");
    private final DatasetRepository datasets;
    private final AnnotationRepository annotations;
    private final AnalysisRunStore store;
    private final RegionLoader loader;
    private final BooleanSupplier conversionBusy;
    private final Predicate<String> toolEnabled;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, FutureTask<Void>> active = new ConcurrentHashMap<>();
    // ponytail: one worker and eight waiting runs; increase only after reader/memory qualification.
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), runnable -> { var thread = new Thread(runnable, "pathlab-deterministic-analysis"); thread.setDaemon(true); return thread; });

    public DeterministicAnalysisService(DatasetRepository datasets, AnnotationRepository annotations,
            Path managedRoot, RegionLoader loader, BooleanSupplier conversionBusy, Predicate<String> toolEnabled)
            throws IOException {
        this.datasets = datasets; this.annotations = annotations; this.loader = loader;
        this.conversionBusy = conversionBusy; this.toolEnabled = toolEnabled;
        store = new AnalysisRunStore(managedRoot.resolve("analysis-runs.sqlite"));
    }

    public synchronized AnalysisRun submit(Request request) throws IOException {
        if (!TOOLS.contains(request.tool())) throw new IllegalArgumentException("Unsupported deterministic tool");
        if (!toolEnabled.test(request.tool())) throw new IllegalStateException("Install and enable the verified tool pack first");
        if (active.size() >= 9) throw new IllegalStateException("Analysis queue is full (one active and eight waiting)");
        validateConfiguration(request.tool(), request.configuration());
        var dataset = datasets.find(request.datasetId()).orElseThrow(() -> new IllegalArgumentException("Dataset was not found"));
        var roi = annotation(request.datasetId(), request.annotationId());
        if (roi.series() < 0 || roi.z() < 0 || roi.t() < 0 || roi.viewRevision().isBlank()) {
            throw new IllegalArgumentException("Analysis ROI must have an exact series/Z/T view scope");
        }
        if (!request.tool().equals("registration")) new RoiMask(roi.type(), roi.geometry()).bounds();
        var secondary = new TreeMap<String, String>();
        if (request.tool().equals("registration")) {
            var targetDataset = datasets.find(request.targetDatasetId()).orElseThrow(() -> new IllegalArgumentException("Target dataset was not found"));
            var target = annotation(request.targetDatasetId(), request.targetAnnotationId());
            if (target.series() < 0 || target.z() < 0 || target.t() < 0 || target.viewRevision().isBlank()) throw new IllegalArgumentException("Target must have an exact plane scope");
            ClassicalAnalysis.affine(landmarks(request.sourceLandmarks()), landmarks(request.targetLandmarks()));
            secondary.put("targetDatasetId", request.targetDatasetId()); secondary.put("targetAnnotationId", target.id());
            secondary.put("targetAnnotationRevision", Long.toString(target.revision()));
            secondary.put("targetSourceFingerprint", targetDataset.sourceFingerprint());
            secondary.put("targetInventorySha256", hash(targetDataset.sourceInventory()));
            secondary.put("targetSeries", Integer.toString(target.series())); secondary.put("targetZ", Integer.toString(target.z()));
            secondary.put("targetT", Integer.toString(target.t())); secondary.put("targetViewRevision", target.viewRevision());
            secondary.put("sourceLandmarks", request.sourceLandmarks()); secondary.put("targetLandmarks", request.targetLandmarks());
        }
        var provenance = new AnalysisRun.Provenance(dataset.sourceFingerprint(), hash(dataset.sourceInventory()),
                dataset.readerEngine(), dataset.runtimeFingerprint(), roi.geometry(), roi.type(), roi.revision(),
                roi.series(), roi.z(), roi.t(), roi.viewRevision(), hash(mapper.writeValueAsString(new TreeMap<>(request.configuration())) + mapper.writeValueAsString(secondary)),
                request.tool() + "-forge-deterministic-v1", "source pixels; OD/fractions/unitless as named; no diagnostic claims", secondary);
        var run = new AnalysisRun(UUID.randomUUID().toString(), request.datasetId(), request.annotationId(),
                request.tool(), "QUEUED", System.currentTimeMillis(), 0, 0, "Waiting for bounded local worker",
                provenance, request.configuration(), Map.of(), false);
        store.insert(run);
        var task = new FutureTask<Void>(() -> { execute(run, request); return null; });
        active.put(run.id(), task);
        try { executor.execute(task); }
        catch (java.util.concurrent.RejectedExecutionException error) {
            active.remove(run.id()); store.update(changed(run, "FAILED", "Analysis queue is unavailable", Map.of())); throw error;
        }
        return run;
    }

    public AnalysisRun get(String id) throws IOException {
        var run = store.get(id);
        return new AnalysisRun(run.id(), run.datasetId(), run.annotationId(), run.tool(), run.status(), run.createdAt(),
                run.startedAt(), run.finishedAt(), run.detail(), run.provenance(), run.configuration(), run.outputs(), stale(run));
    }
    public List<AnalysisRun> list(String datasetId) throws IOException {
        var result = new java.util.ArrayList<AnalysisRun>();
        for (var run : store.list(datasetId)) result.add(get(run.id()));
        return List.copyOf(result);
    }
    public AnalysisRun cancel(String id) throws IOException {
        var current = store.get(id);
        if (store.update(changed(current, "CANCELLED", "Cancelled; no outputs were accepted", Map.of()))) {
            var task = active.remove(id);
            if (task != null) { task.cancel(true); executor.remove(task); }
        }
        return get(id);
    }
    public String exportJson(String id) throws IOException { return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(get(id)); }

    private void execute(AnalysisRun initial, Request request) {
        var run = initial;
        try {
            while (conversionBusy.getAsBoolean()) { checkCancelled(); TimeUnit.MILLISECONDS.sleep(250); }
            checkCancelled();
            if (!toolEnabled.test(run.tool())) throw new IllegalStateException("Tool pack was disabled while waiting");
            if (stale(run)) throw new IllegalStateException("Source or ROI changed before analysis started");
            run = changed(run, "RUNNING", "Reading exact native RGB ROI", Map.of());
            if (!store.update(run)) return;
            Map<String, Object> outputs;
            if (request.tool().equals("registration")) outputs = registration(request);
            else if (request.tool().equals("tma")) {
                var bounds = new RoiMask(run.provenance().annotationType(), run.provenance().annotationGeometry()).bounds();
                var sourceRun = run;
                var cores = TmaGrid.create(run.datasetId(), run.annotationId(), integer(request, "rows", 3), integer(request, "columns", 3), bounds.x(), bounds.y(), bounds.width(), bounds.height())
                        .stream().map(core -> new PathObject(core.id(), core.datasetId(), core.parentId(), core.kind(), core.geometry(), core.classification(), sourceRun.id(), core.properties(), core.revision())).toList();
                outputs = Map.of("cores", cores,
                        "method", "Manual grid; core positions require user correction; no automatic tissue matching");
            } else {
                var mask = new RoiMask(run.provenance().annotationType(), run.provenance().annotationGeometry());
                var bounds = mask.bounds();
                var region = loader.load(run.datasetId(), run.provenance().series(), run.provenance().z(), run.provenance().t(), bounds.x(), bounds.y(), bounds.width(), bounds.height());
                if (region.x() != bounds.x() || region.y() != bounds.y() || region.width() != bounds.width() || region.height() != bounds.height()) throw new IOException("Reader returned another ROI");
                checkCancelled();
                var pixels = mask.pixels(region);
                outputs = process(request, region, pixels, run.id());
            }
            checkCancelled();
            if (stale(run)) throw new IllegalStateException("Source or ROI changed during analysis; rerun on current inputs");
            store.update(changed(run, "SUCCEEDED", "Research-only local result; inspect before use", outputs));
        } catch (InterruptedException | java.util.concurrent.CancellationException error) {
            Thread.currentThread().interrupt();
            try { store.update(changed(run, "CANCELLED", "Cancelled; no outputs were accepted", Map.of())); } catch (IOException ignored) {}
        } catch (IOException | RuntimeException error) {
            try { store.update(changed(run, "FAILED", error.getMessage() == null ? "Analysis failed" : error.getMessage(), Map.of())); } catch (IOException ignored) {}
        } finally { active.remove(initial.id()); }
    }

    private Map<String, Object> process(Request request, RgbRegion region, boolean[] mask, String runId) throws IOException {
        return switch (request.tool()) {
            case "qc" -> Map.of("qualityControl", ClassicalAnalysis.qualityControl(region, mask), "method", "Deterministic brightness/gradient/colour heuristics; review required");
            case "tissue" -> tissue(request, region, mask);
            case "nucleus_candidates" -> {
                var geometries = ClassicalAnalysis.nucleusCandidates(region, integer(request, "darknessThreshold", 80), mask);
                var objects = geometries.stream().map(geometry -> new PathObject(UUID.randomUUID().toString(), request.datasetId(), request.annotationId(),
                        PathObject.Kind.NUCLEUS, geometry, "Unreviewed candidate", runId, Map.of("reviewRequired", "true", "geometryType", "point"), 1)).toList();
                yield Map.of("candidateGeometry", geometries, "objects", objects, "geometryType", "point", "method", "Dark horizontal local minima at stride two; candidates, not counted biological nuclei");
            }
            case "stain_vector" -> Map.of("opticalDensityVector", StainTools.estimateOpticalDensityVector(region, mask), "method", "Single mean OD direction; not separation of stain components");
            case "normalize_preview" -> Map.of("previewDataUrl", preview(StainTools.normalizePreview(region, integer(request, "targetRed", 180), integer(request, "targetGreen", 160), integer(request, "targetBlue", 190), mask)),
                    "method", "Mean RGB scaling preview, max512 pixels per axis; original pixels preserved");
            case "he" -> he(request, region, mask);
            default -> throw new IllegalArgumentException("Unsupported image tool");
        };
    }

    private static Map<String, Object> tissue(Request request, RgbRegion region, boolean[] roi) {
        var rgb = region.interleavedRgb(); var bits = new java.util.BitSet(roi.length);
        for (var index = 0; index < roi.length; index++) {
            checkCancelled();
            var luminance = (.2126 * (rgb[index * 3] & 255) + .7152 * (rgb[index * 3 + 1] & 255) + .0722 * (rgb[index * 3 + 2] & 255)) / 255;
            if (roi[index] && luminance < value(request, "luminanceThreshold", .88)) bits.set(index);
        }
        return Map.of("tissue", ClassicalAnalysis.detectTissue(region, value(request, "luminanceThreshold", .88), roi),
                "maskBitsetBase64", Base64.getEncoder().encodeToString(bits.toByteArray()), "maskEncoding", "row-major LSB-first bitset",
                "maskX", region.x(), "maskY", region.y(), "maskWidth", region.width(), "maskHeight", region.height(), "method", "Actual luminance threshold mask inside ROI; bounding box is summary only");
    }

    private static Map<String, Object> he(Request request, RgbRegion region, boolean[] mask) {
        var rgb = region.interleavedRgb();
        var stride = HeAnalysisService.samplingStride(region.width(), region.height());
        var h = new double[1_048_576]; var e = new double[1_048_576];
        int count = 0, hAbove = 0, eAbove = 0;
        double hSum = 0, eSum = 0;
        for (var y = 0; y < region.height(); y += stride) {
            checkCancelled();
            for (var x = 0; x < region.width(); x += stride) {
                var index = y * region.width() + x;
                if (!mask[index]) continue;
                var concentration = HeAnalysisService.concentrations(rgb[index * 3] & 255, rgb[index * 3 + 1] & 255, rgb[index * 3 + 2] & 255);
                h[count] = concentration[0]; e[count] = concentration[1];
                hSum += h[count]; eSum += e[count];
                if (h[count] >= value(request, "hematoxylinThreshold", .15)) hAbove++;
                if (e[count] >= value(request, "eosinThreshold", .15)) eAbove++;
                count++;
            }
        }
        if (count == 0) throw new IllegalArgumentException("ROI contains no sampled pixels");
        Arrays.sort(h, 0, count); Arrays.sort(e, 0, count);
        return Map.of("sampledPixels", count, "samplingStride", stride,
                "hematoxylin", new HeAnalysisService.StainStats(hSum / count, h[(count - 1) / 2], h[(int) ((count - 1) * .9)], (double) hAbove / count),
                "eosin", new HeAnalysisService.StainStats(eSum / count, e[(count - 1) / 2], e[(int) ((count - 1) * .9)], (double) eAbove / count),
                "algorithm", HeAnalysisService.ALGORITHM, "hematoxylinVector", new double[] {.65,.70,.29}, "eosinVector", new double[] {.2159,.8012,.5581},
                "background", new int[] {255,255,255}, "preset", "qupath-he-default");
    }

    private Map<String, Object> registration(Request request) throws IOException {
        var targetDataset = datasets.find(request.targetDatasetId()).orElseThrow(() -> new IllegalArgumentException("Target dataset was not found"));
        var target = annotation(request.targetDatasetId(), request.targetAnnotationId());
        if (target.series() < 0 || target.z() < 0 || target.t() < 0) throw new IllegalArgumentException("Target landmarks require exact plane scope");
        var sourcePoints = landmarks(request.sourceLandmarks()); var targetPoints = landmarks(request.targetLandmarks());
        var transform = ClassicalAnalysis.affine(sourcePoints, targetPoints);
        return Map.of("transform", transform, "sourceLandmarks", sourcePoints, "targetLandmarks", targetPoints,
                "targetSourceFingerprint", targetDataset.sourceFingerprint(), "targetAnnotation", target,
                "approximate", true, "method", "Three manually matched landmark pairs; affine fit is not independent accuracy validation");
    }
    private static double[][] landmarks(String geometry) {
        var points = geometry == null ? new String[0] : geometry.split(";");
        if (points.length != 3) throw new IllegalArgumentException("Registration requires three manual source and target landmarks");
        return Arrays.stream(points).map(point -> Arrays.stream(point.split(",")).mapToDouble(Double::parseDouble).toArray()).toArray(double[][]::new);
    }
    private static String preview(RgbRegion region) throws IOException {
        var scale = Math.max(1, Math.max(region.width(), region.height()) / 512.0);
        var width = Math.max(1, (int) (region.width() / scale)); var height = Math.max(1, (int) (region.height() / scale));
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var rgb = region.interleavedRgb();
        for (var y = 0; y < height; y++) {
            checkCancelled();
            for (var x = 0; x < width; x++) {
                var index = ((int) (y * scale) * region.width() + (int) (x * scale)) * 3;
                image.setRGB(x, y, ((rgb[index] & 255) << 16) | ((rgb[index + 1] & 255) << 8) | (rgb[index + 2] & 255));
            }
        }
        var output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private AnnotationRecord annotation(String dataset, String id) throws IOException {
        return annotations.list(dataset).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Annotation was not found"));
    }
    private boolean stale(AnalysisRun run) throws IOException {
        var dataset = datasets.find(run.datasetId());
        var roi = annotations.list(run.datasetId()).stream().filter(item -> item.id().equals(run.annotationId())).findFirst();
        var changed = dataset.isEmpty() || roi.isEmpty() || !dataset.get().sourceFingerprint().equals(run.provenance().sourceFingerprint())
                || !hash(dataset.get().sourceInventory()).equals(run.provenance().sourceInventorySha256())
                || roi.get().revision() != run.provenance().annotationRevision()
                || !roi.get().geometry().equals(run.provenance().annotationGeometry())
                || !roi.get().viewRevision().equals(run.provenance().viewRevision())
                || !dataset.get().runtimeFingerprint().equals(run.provenance().runtimeFingerprint());
        var target = run.provenance().secondaryInputs();
        if (!target.isEmpty()) {
            var targetDataset = datasets.find(target.get("targetDatasetId"));
            var targetRoi = annotations.list(target.get("targetDatasetId")).stream().filter(item -> item.id().equals(target.get("targetAnnotationId"))).findFirst();
            changed |= targetDataset.isEmpty() || targetRoi.isEmpty()
                    || !targetDataset.get().sourceFingerprint().equals(target.get("targetSourceFingerprint"))
                    || !hash(targetDataset.get().sourceInventory()).equals(target.get("targetInventorySha256"))
                    || targetRoi.get().revision() != Long.parseLong(target.get("targetAnnotationRevision"));
        }
        return changed;
    }
    private static AnalysisRun changed(AnalysisRun run, String status, String detail, Map<String, Object> outputs) {
        return new AnalysisRun(run.id(), run.datasetId(), run.annotationId(), run.tool(), status, run.createdAt(),
                run.startedAt() == 0 && status.equals("RUNNING") ? System.currentTimeMillis() : run.startedAt(),
                Set.of("SUCCEEDED","FAILED","CANCELLED").contains(status) ? System.currentTimeMillis() : 0,
                detail, run.provenance(), run.configuration(), outputs, run.stale());
    }
    private static void checkCancelled() { if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException(); }
    private static double value(Request request, String key, double fallback) { return request.configuration().getOrDefault(key, fallback); }
    private static int integer(Request request, String key, int fallback) { return (int) value(request, key, fallback); }
    private static void validateConfiguration(String tool, Map<String, Double> values) {
        var allowed = switch (tool) {
            case "he" -> Set.of("hematoxylinThreshold", "eosinThreshold");
            case "normalize_preview" -> Set.of("targetRed", "targetGreen", "targetBlue");
            case "tma" -> Set.of("rows", "columns");
            case "tissue" -> Set.of("luminanceThreshold");
            case "nucleus_candidates" -> Set.of("darknessThreshold");
            default -> Set.<String>of();
        };
        for (var entry : values.entrySet()) {
            var value = entry.getValue();
            if (!allowed.contains(entry.getKey()) || value == null || !Double.isFinite(value)) throw new IllegalArgumentException("Invalid analysis configuration");
            var valid = switch (tool) {
                case "he" -> value >= 0 && value <= 3;
                case "tissue" -> value > 0 && value < 1;
                case "tma" -> value >= 1 && value <= 100 && value == Math.rint(value);
                case "normalize_preview" -> value >= 1 && value <= 255 && value == Math.rint(value);
                case "nucleus_candidates" -> value >= 1 && value <= 254 && value == Math.rint(value);
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("Analysis configuration is outside its permitted range");
        }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    @Override public void close() {
        for (var id : List.copyOf(active.keySet())) { try { cancel(id); } catch (IOException ignored) {} }
        executor.shutdownNow();
    }
    public record Request(String datasetId, String annotationId, String tool, Map<String, Double> configuration,
                          String targetDatasetId, String targetAnnotationId, String sourceLandmarks, String targetLandmarks) {
        public Request {
            if (datasetId == null || datasetId.isBlank() || annotationId == null || annotationId.isBlank()
                    || tool == null || !TOOLS.contains(tool)) throw new IllegalArgumentException("Analysis request identity/tool is invalid");
            var effective = new TreeMap<String, Double>();
            if (tool != null) switch (tool) {
                case "he" -> { effective.put("hematoxylinThreshold", .15); effective.put("eosinThreshold", .15); }
                case "normalize_preview" -> { effective.put("targetRed", 180.0); effective.put("targetGreen", 160.0); effective.put("targetBlue", 190.0); }
                case "tma" -> { effective.put("rows", 3.0); effective.put("columns", 3.0); }
                case "tissue" -> effective.put("luminanceThreshold", .88);
                case "nucleus_candidates" -> effective.put("darknessThreshold", 80.0);
                default -> { }
            }
            if (configuration != null) effective.putAll(configuration);
            configuration = Map.copyOf(effective);
        }
        public Request(String datasetId, String annotationId, String tool, Map<String, Double> configuration) {
            this(datasetId, annotationId, tool, configuration, "", "", "", "");
        }
    }
    @FunctionalInterface public interface RegionLoader {
        RgbRegion load(String datasetId, int series, int z, int t, int x, int y, int width, int height) throws IOException;
    }
}

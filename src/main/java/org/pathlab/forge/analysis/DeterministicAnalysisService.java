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
        requireNativeView(dataset, roi);
        if (!request.tool().equals("registration")) new RoiMask(roi.type(), roi.geometry()).bounds();
        var secondary = new TreeMap<String, String>();
        if(roi.type().equals("roi_mask")){secondary.put("roiMaskFormat","mask/1-even-odd");secondary.put("boundaryFlatteningTolerancePx",Double.toString(MaskContours.FLATNESS));}
        var derived = annotations.derivedProvenance(request.datasetId(), roi.id());
        if (!derived.get("sourceRunId").isBlank()) {
            secondary.putAll(tmaCoreInputs(derived.get("sourceRunId"), Long.parseLong(derived.get("reviewRevision")), roi.id()));
        }
        if (request.tool().equals("registration")) {
            var targetDataset = datasets.find(request.targetDatasetId()).orElseThrow(() -> new IllegalArgumentException("Target dataset was not found"));
            var target = annotation(request.targetDatasetId(), request.targetAnnotationId());
            requireNativeView(targetDataset, target);
            if (target.series() < 0 || target.z() < 0 || target.t() < 0 || target.viewRevision().isBlank()) throw new IllegalArgumentException("Target must have an exact plane scope");
            ClassicalAnalysis.affine(landmarks(request.sourceLandmarks()), landmarks(request.targetLandmarks()));
            registrationChecks(request);
            var sourceBounds = new RoiMask(roi.type(), roi.geometry()).bounds();
            var targetBounds = new RoiMask(target.type(), target.geometry()).bounds();
            requireInside(landmarks(request.sourceLandmarks()), sourceBounds);
            requireInside(landmarks(request.targetLandmarks()), targetBounds);
            requireInside(checkPoints(request.independentSourceLandmarks()), sourceBounds);
            requireInside(checkPoints(request.independentTargetLandmarks()), targetBounds);
            secondary.put("targetDatasetId", request.targetDatasetId()); secondary.put("targetAnnotationId", target.id());
            secondary.put("targetAnnotationRevision", Long.toString(target.revision()));
            secondary.put("targetSourceFingerprint", targetDataset.sourceFingerprint());
            secondary.put("targetInventorySha256", hash(targetDataset.sourceInventory()));
            secondary.put("targetSeries", Integer.toString(target.series())); secondary.put("targetZ", Integer.toString(target.z()));
            secondary.put("targetT", Integer.toString(target.t())); secondary.put("targetViewRevision", target.viewRevision());
            secondary.put("sourceLandmarks", request.sourceLandmarks()); secondary.put("targetLandmarks", request.targetLandmarks());
            secondary.put("independentSourceLandmarks", request.independentSourceLandmarks());
            secondary.put("independentTargetLandmarks", request.independentTargetLandmarks());
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

    public HistoryPage page(String datasetId, int limit, int offset) throws IOException {
        if (limit < 1 || limit > 100 || offset < 0 || offset > Integer.MAX_VALUE - 101) throw new IllegalArgumentException("Analysis history page is invalid");
        var rows = store.list(datasetId, limit + 1, offset);
        var result = new java.util.ArrayList<AnalysisRun>();
        for (var run : rows.subList(0, Math.min(limit, rows.size()))) result.add(get(run.id()));
        return new HistoryPage(List.copyOf(result), rows.size() > limit, offset + result.size());
    }
    public record HistoryPage(List<AnalysisRun> runs, boolean hasMore, int nextOffset) {}

    public synchronized List<AnnotationRecord> persistReviewedTma(String id, long expectedReviewRevision) throws IOException {
        var run = get(id); var reviewed = currentTmaReview(run, expectedReviewRevision);
        return annotations.saveTmaCores(run.datasetId(), run.annotationId(), run.provenance().annotationRevision(),
                id, reviewed.revision(), reviewed.objects());
    }

    public synchronized AnalysisRun submitTmaCore(String id, long expectedReviewRevision,
            String coreId, String tool, Map<String, Double> configuration) throws IOException {
        if (!Set.of("he", "tissue", "qc", "nucleus_candidates", "stain_vector", "normalize_preview").contains(tool)) {
            throw new IllegalArgumentException("Select a deterministic image tool for the reviewed core");
        }
        tmaCoreInputs(id, expectedReviewRevision, coreId);
        return submit(new Request(get(id).datasetId(), coreId, tool, configuration));
    }

    private Map<String, String> tmaCoreInputs(String id, long expectedReviewRevision, String coreId) throws IOException {
        var run = get(id); var reviewed = currentTmaReview(run, expectedReviewRevision);
        var core = reviewed.objects().stream().filter(item -> item.id().equals(coreId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Reviewed TMA core was not found"));
        if ("true".equals(core.properties().get("missing"))) throw new IllegalArgumentException("Missing cores cannot be analyzed");
        var saved = annotation(run.datasetId(), coreId); var provenance = annotations.derivedProvenance(run.datasetId(), coreId);
        if (!saved.geometry().equals(core.geometry()) || !saved.parentId().equals(run.annotationId())
                || !saved.classification().equals(core.classification()) || !provenance.get("sourceRunId").equals(id)
                || !provenance.get("reviewRevision").equals(Long.toString(reviewed.revision()))
                || !provenance.get("objectRevision").equals(Long.toString(core.revision()))) {
            throw new IllegalStateException("Persist the exact reviewed grid before analyzing its cores");
        }
        return Map.of(
                "tmaRunId", id, "tmaReviewRevision", Long.toString(reviewed.revision()),
                "tmaCoreId", coreId, "tmaObjectRevision", Long.toString(core.revision()));
    }

    private AnalysisReview currentTmaReview(AnalysisRun run, long expectedRevision) throws IOException {
        if (!run.tool().equals("tma") || !run.status().equals("SUCCEEDED") || run.stale()) {
            throw new IllegalStateException("A current completed TMA grid is required");
        }
        var reviewed = review(run.id());
        if (reviewed.revision() < 1 || reviewed.revision() != expectedRevision) throw new IllegalStateException("Save and reload the exact TMA review first");
        return reviewed;
    }
    public AnalysisRun cancel(String id) throws IOException {
        var current = store.get(id);
        if (store.update(changed(current, "CANCELLED", "Cancelled; no outputs were accepted", Map.of()))) {
            var task = active.remove(id);
            if (task != null) { task.cancel(true); executor.remove(task); }
        }
        return get(id);
    }
    public String exportJson(String id) throws IOException { return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("run",get(id), "review", review(id))); }
    public int activeCount() { return active.size(); }

    public AnalysisReview review(String id) throws IOException {
        var saved = store.review(id);
        if (saved.isPresent()) return saved.get();
        var run = get(id);
        var rawObjects = run.outputs().getOrDefault("cores", run.outputs().getOrDefault("objects", List.of()));
        List<PathObject> objects = mapper.convertValue(rawObjects, mapper.getTypeFactory().constructCollectionType(List.class, PathObject.class));
        var vector = run.outputs().getOrDefault("opticalDensityVector", List.of());
        List<Double> stain = mapper.convertValue(vector, mapper.getTypeFactory().constructCollectionType(List.class, Double.class));
        return new AnalysisReview(id, 0, objects, stain);
    }

    public synchronized AnalysisReview saveReview(String id, AnalysisReview requested) throws IOException {
        var run = get(id);
        if (!run.status().equals("SUCCEEDED") || run.stale()) throw new IllegalStateException("Only current completed runs can be reviewed");
        var previous = review(id);
        if (!id.equals(requested.runId()) || requested.revision() != previous.revision()) throw new IllegalStateException("Analysis review revision changed");
        if (requested.objects().size() != previous.objects().size()) throw new IllegalArgumentException("Reject a candidate using accepted=false; preserve its provenance");
        var ids = new java.util.HashSet<String>();
        var corrected = new java.util.ArrayList<PathObject>();
        var bounds = requested.objects().isEmpty() ? null : new RoiMask(run.provenance().annotationType(), run.provenance().annotationGeometry()).bounds();
        for (var object : requested.objects()) {
            var original = previous.objects().stream().filter(item -> item.id().equals(object.id())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown derived object"));
            if (!ids.add(object.id()) || !object.sourceRunId().equals(id) || !object.datasetId().equals(run.datasetId())
                    || !object.parentId().equals(run.annotationId()) || object.kind() != original.kind()
                    || object.classification().length() > 120 || object.revision() != original.revision()) throw new IllegalArgumentException("Derived object identity/revision is invalid");
            var type = object.kind() == PathObject.Kind.TMA_CORE ? "rectangle" : "point";
            GeometryMeasurements.validate(type, object.geometry());
            for (var point : object.geometry().split(";")) {
                var coordinates = point.split(","); var x = Double.parseDouble(coordinates[0]); var y = Double.parseDouble(coordinates[1]);
                if (x < bounds.x() || y < bounds.y() || x > (long) bounds.x() + bounds.width() || y > (long) bounds.y() + bounds.height()) {
                    throw new IllegalArgumentException("Reviewed objects must remain within the run ROI; redraw the ROI to extend coverage");
                }
            }
            var properties = new TreeMap<>(original.properties());
            for (var flag : List.of("missing", "accepted", "reviewRequired")) {
                var value = object.properties().get(flag);
                if (value != null && !Set.of("true", "false").contains(value)) throw new IllegalArgumentException("Review flags must be true or false");
                if (value != null) properties.put(flag, value);
            }
            corrected.add(new PathObject(object.id(), object.datasetId(), object.parentId(), object.kind(), object.geometry(), object.classification(), id, properties, original.revision() + 1));
        }
        var stain = requested.stainVector();
        if (!stain.isEmpty()) {
            if (!run.tool().equals("stain_vector") || stain.size() != 3 || stain.stream().anyMatch(value -> value == null || !Double.isFinite(value) || value < 0)) throw new IllegalArgumentException("Manual OD vector must contain three finite nonnegative components");
            var length = Math.sqrt(stain.stream().mapToDouble(value -> value * value).sum());
            if (!Double.isFinite(length) || length <= 0) throw new IllegalArgumentException("Manual OD vector has zero/invalid length");
            stain = stain.stream().map(value -> value / length).toList();
        }
        var saved = new AnalysisReview(id, previous.revision() + 1, corrected, stain);
        store.saveReview(saved, previous.revision());
        return saved;
    }

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
            if (!toolEnabled.test(run.tool())) throw new IllegalStateException("Tool pack was disabled during analysis; outputs were not accepted");
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
        var maximumSamples = Math.toIntExact(((long) region.width() + stride - 1) / stride * (((long) region.height() + stride - 1) / stride));
        var h = new double[maximumSamples]; var e = new double[maximumSamples];
        int count = 0, hAbove = 0, eAbove = 0;
        var maskWidth = (region.width() + stride - 1) / stride;
        var maskHeight = (region.height() + stride - 1) / stride;
        var hBits = new java.util.BitSet(maximumSamples); var eBits = new java.util.BitSet(maximumSamples);
        double hSum = 0, eSum = 0;
        for (var y = 0; y < region.height(); y += stride) {
            checkCancelled();
            for (var x = 0; x < region.width(); x += stride) {
                var index = y * region.width() + x;
                if (!mask[index]) continue;
                var concentration = HeAnalysisService.concentrations(rgb[index * 3] & 255, rgb[index * 3 + 1] & 255, rgb[index * 3 + 2] & 255);
                h[count] = concentration[0]; e[count] = concentration[1];
                hSum += h[count]; eSum += e[count];
                var maskIndex = (y / stride) * maskWidth + x / stride;
                if (h[count] >= value(request, "hematoxylinThreshold", .15)) { hAbove++; hBits.set(maskIndex); }
                if (e[count] >= value(request, "eosinThreshold", .15)) { eAbove++; eBits.set(maskIndex); }
                count++;
            }
        }
        if (count == 0) throw new IllegalArgumentException("ROI contains no sampled pixels");
        Arrays.sort(h, 0, count); Arrays.sort(e, 0, count);
        var outputs = new TreeMap<String, Object>(Map.of("sampledPixels", count, "samplingStride", stride,
                "hematoxylin", new HeAnalysisService.StainStats(hSum / count, h[(count - 1) / 2], h[(int) ((count - 1) * .9)], (double) hAbove / count),
                "eosin", new HeAnalysisService.StainStats(eSum / count, e[(count - 1) / 2], e[(int) ((count - 1) * .9)], (double) eAbove / count),
                "algorithm", HeAnalysisService.ALGORITHM, "hematoxylinVector", new double[] {.65,.70,.29}, "eosinVector", new double[] {.2159,.8012,.5581},
                "background", new int[] {255,255,255}, "preset", "qupath-he-default"));
        outputs.put("hematoxylinMaskBitsetBase64", Base64.getEncoder().encodeToString(hBits.toByteArray()));
        outputs.put("eosinMaskBitsetBase64", Base64.getEncoder().encodeToString(eBits.toByteArray()));
        outputs.put("maskEncoding", "row-major LSB-first sampled threshold bitset");
        outputs.put("maskX", region.x()); outputs.put("maskY", region.y());
        outputs.put("maskWidth", maskWidth); outputs.put("maskHeight", maskHeight);
        outputs.put("maskSourceWidth", region.width()); outputs.put("maskSourceHeight", region.height());
        outputs.put("maskSampleStride", stride);
        return outputs;
    }

    private Map<String, Object> registration(Request request) throws IOException {
        var targetDataset = datasets.find(request.targetDatasetId()).orElseThrow(() -> new IllegalArgumentException("Target dataset was not found"));
        var target = annotation(request.targetDatasetId(), request.targetAnnotationId());
        if (target.series() < 0 || target.z() < 0 || target.t() < 0) throw new IllegalArgumentException("Target landmarks require exact plane scope");
        var sourcePoints = landmarks(request.sourceLandmarks()); var targetPoints = landmarks(request.targetLandmarks());
        var transform = ClassicalAnalysis.affine(sourcePoints, targetPoints);
        var outputs = new TreeMap<String, Object>();
        outputs.put("transform", transform); outputs.put("sourceLandmarks", sourcePoints); outputs.put("targetLandmarks", targetPoints);
        outputs.put("targetSourceFingerprint", targetDataset.sourceFingerprint()); outputs.put("targetAnnotation", target);
        outputs.put("approximate", true);
        outputs.put("method", "Three manually matched landmark pairs; independent checks are held out of the fit; original pixels preserved");
        var checks = registrationChecks(request); var residuals = new java.util.ArrayList<Double>();
        for (var index = 0; index < checks[0].length; index++) {
            var predicted = transform.apply(checks[0][index][0], checks[0][index][1]);
            residuals.add(Math.hypot(predicted[0] - checks[1][index][0], predicted[1] - checks[1][index][1]));
        }
        outputs.put("independentSourceLandmarks", checks[0]); outputs.put("independentTargetLandmarks", checks[1]);
        outputs.put("independentResidualsTargetPixels", residuals);
        outputs.put("independentValidation", residuals.isEmpty() ? "NOT_PROVIDED" : "MANUAL_HELD_OUT_CHECKS");
        if (!residuals.isEmpty()) outputs.put("independentRmsTargetPixels", Math.sqrt(residuals.stream().mapToDouble(value -> value * value).average().orElseThrow()));
        var source = annotation(request.datasetId(), request.annotationId());
        var sourceBounds = new RoiMask(source.type(), source.geometry()).bounds();
        var targetBounds = new RoiMask(target.type(), target.geometry()).bounds();
        var sourceRegion = loader.load(request.datasetId(), source.series(), source.z(), source.t(), sourceBounds.x(), sourceBounds.y(), sourceBounds.width(), sourceBounds.height());
        checkCancelled();
        var targetRegion = loader.load(request.targetDatasetId(), target.series(), target.z(), target.t(), targetBounds.x(), targetBounds.y(), targetBounds.width(), targetBounds.height());
        var targetRgb = targetRegion.interleavedRgb();
        requireRegion(sourceRegion, sourceBounds); requireRegion(targetRegion, targetBounds);
        var scale = Math.max(1, Math.max(sourceRegion.width(), sourceRegion.height()) / 512.0);
        var width = Math.max(1, (int) (sourceRegion.width() / scale)); var height = Math.max(1, (int) (sourceRegion.height() / scale));
        var overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB); var covered = 0;
        for (var y = 0; y < height; y++) {
            checkCancelled();
            for (var x = 0; x < width; x++) {
                var targetPoint = transform.apply(sourceRegion.x() + (int) (x * scale), sourceRegion.y() + (int) (y * scale));
                var tx = (long) Math.floor(targetPoint[0]) - targetRegion.x(); var ty = (long) Math.floor(targetPoint[1]) - targetRegion.y();
                if (tx < 0 || ty < 0 || tx >= targetRegion.width() || ty >= targetRegion.height()) continue;
                var index = Math.toIntExact((ty * targetRegion.width() + tx) * 3);
                overlay.setRGB(x, y, 0xff000000 | ((targetRgb[index] & 255) << 16) | ((targetRgb[index + 1] & 255) << 8) | (targetRgb[index + 2] & 255)); covered++;
            }
        }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(overlay, "png", bytes);
        outputs.put("previewDataUrl", preview(sourceRegion));
        outputs.put("registrationOverlayDataUrl", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray()));
        outputs.put("overlaySourceX", sourceRegion.x()); outputs.put("overlaySourceY", sourceRegion.y());
        outputs.put("overlaySourceWidth", sourceRegion.width()); outputs.put("overlaySourceHeight", sourceRegion.height());
        outputs.put("overlayCoverageFraction", (double) covered / (width * height));
        return outputs;
    }
    private static void requireRegion(RgbRegion region, RoiMask.Bounds bounds) throws IOException {
        if (region.x() != bounds.x() || region.y() != bounds.y() || region.width() != bounds.width() || region.height() != bounds.height()) throw new IOException("Registration reader returned another ROI");
    }
    private static void requireInside(double[][] points, RoiMask.Bounds bounds) {
        for (var point : points) if (point[0] < bounds.x() || point[1] < bounds.y() || point[0] >= (long) bounds.x() + bounds.width() || point[1] >= (long) bounds.y() + bounds.height()) throw new IllegalArgumentException("Registration landmarks must be inside their saved ROI bounds");
    }
    private static double[][][] registrationChecks(Request request) {
        var source = checkPoints(request.independentSourceLandmarks()); var target = checkPoints(request.independentTargetLandmarks());
        if (source.length != target.length) throw new IllegalArgumentException("Independent check points require matching source and target pairs");
        var fitSource = landmarks(request.sourceLandmarks()); var fitTarget = landmarks(request.targetLandmarks());
        for (var index = 0; index < source.length; index++) {
            for (var fit : fitSource) if (Arrays.equals(source[index], fit)) throw new IllegalArgumentException("Independent source check points must not reuse fitted landmarks");
            for (var fit : fitTarget) if (Arrays.equals(target[index], fit)) throw new IllegalArgumentException("Independent target check points must not reuse fitted landmarks");
        }
        return new double[][][] {source, target};
    }
    private static double[][] checkPoints(String geometry) {
        if (geometry == null || geometry.isBlank()) return new double[0][];
        if (geometry.length() > 4096) throw new IllegalArgumentException("Too many landmark coordinates");
        var points = geometry.split(";", -1);
        if (points.length > 32) throw new IllegalArgumentException("At most 32 independent landmark pairs are supported");
        return Arrays.stream(points).map(point -> {
            var coordinates = point.split(",", -1);
            if (coordinates.length != 2) throw new IllegalArgumentException("Landmarks require x,y coordinate pairs");
            var result = Arrays.stream(coordinates).mapToDouble(Double::parseDouble).toArray();
            if (!Double.isFinite(result[0]) || !Double.isFinite(result[1])) throw new IllegalArgumentException("Landmarks must be finite");
            return result;
        }).toArray(double[][]::new);
    }
    private static double[][] landmarks(String geometry) {
        var points = geometry == null ? new String[0] : geometry.split(";");
        if (points.length != 3) throw new IllegalArgumentException("Registration requires three manual source and target landmarks");
        return checkPoints(geometry);
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
                || !roi.get().type().equals(run.provenance().annotationType())
                || !roi.get().viewRevision().equals(run.provenance().viewRevision())
                || !dataset.get().runtimeFingerprint().equals(run.provenance().runtimeFingerprint());
        if (dataset.isPresent()) changed |= !org.pathlab.forge.library.DatasetSourceInventory.matchesSnapshot(
                Path.of(dataset.get().sourcePath()), dataset.get().sourceInventory());
        if (dataset.isPresent() && roi.isPresent()) {
            try { requireNativeView(dataset.get(), roi.get()); } catch (IllegalArgumentException | IOException error) { changed = true; }
        }
        var target = run.provenance().secondaryInputs();
        if (target.containsKey("targetDatasetId")) {
            var targetDataset = datasets.find(target.get("targetDatasetId"));
            var targetRoi = annotations.list(target.get("targetDatasetId")).stream().filter(item -> item.id().equals(target.get("targetAnnotationId"))).findFirst();
            changed |= targetDataset.isEmpty() || targetRoi.isEmpty()
                    || !targetDataset.get().sourceFingerprint().equals(target.get("targetSourceFingerprint"))
                    || !hash(targetDataset.get().sourceInventory()).equals(target.get("targetInventorySha256"))
                    || targetRoi.get().revision() != Long.parseLong(target.get("targetAnnotationRevision"));
            if (targetDataset.isPresent()) changed |= !org.pathlab.forge.library.DatasetSourceInventory.matchesSnapshot(
                    Path.of(targetDataset.get().sourcePath()), targetDataset.get().sourceInventory());
            if (targetDataset.isPresent() && targetRoi.isPresent()) {
                try { requireNativeView(targetDataset.get(), targetRoi.get()); } catch (IllegalArgumentException | IOException error) { changed = true; }
            }
        }
        if (target.containsKey("tmaRunId")) {
            var grid = get(target.get("tmaRunId"));
            var reviewed = review(grid.id());
            changed |= grid.stale() || reviewed.revision() != Long.parseLong(target.get("tmaReviewRevision"));
            var core = reviewed.objects().stream().filter(item -> item.id().equals(target.get("tmaCoreId"))).findFirst();
            changed |= core.isEmpty() || "true".equals(core.get().properties().get("missing"))
                    || core.get().revision() != Long.parseLong(target.get("tmaObjectRevision"));
        }
        return changed;
    }

    private void requireNativeView(org.pathlab.forge.library.LocalDataset dataset, AnnotationRecord roi) throws IOException {
        if (dataset.viewDefinitionJson().isBlank()) {
            if (roi.series() != dataset.selectedSeries() || roi.z() != 0 || roi.t() != 0
                    || !roi.viewRevision().equals(dataset.configurationRevision())) {
                throw new IllegalArgumentException("ROI does not match the current native RGB view");
            }
            return;
        }
        var view = mapper.readValue(dataset.viewDefinitionJson(), org.pathlab.forge.reader.ViewDefinition.class);
        if (view.profile() != org.pathlab.forge.reader.RenderProfile.PATHOLOGY_STANDARD || view.z().projected() || view.t().projected()
                || view.series() != roi.series() || view.z().start() != roi.z() || view.t().start() != roi.t()
                || !view.revision().equals(roi.viewRevision())) {
            throw new IllegalArgumentException("Analysis requires the exact native RGB slice, not a projection or display composite");
        }
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
                          String targetDatasetId, String targetAnnotationId, String sourceLandmarks, String targetLandmarks,
                          String independentSourceLandmarks, String independentTargetLandmarks) {
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
            independentSourceLandmarks = independentSourceLandmarks == null ? "" : independentSourceLandmarks;
            independentTargetLandmarks = independentTargetLandmarks == null ? "" : independentTargetLandmarks;
        }
        public Request(String datasetId, String annotationId, String tool, Map<String, Double> configuration,
                       String targetDatasetId, String targetAnnotationId, String sourceLandmarks, String targetLandmarks) {
            this(datasetId, annotationId, tool, configuration, targetDatasetId, targetAnnotationId, sourceLandmarks, targetLandmarks, "", "");
        }
        public Request(String datasetId, String annotationId, String tool, Map<String, Double> configuration) {
            this(datasetId, annotationId, tool, configuration, "", "", "", "");
        }
    }
    @FunctionalInterface public interface RegionLoader {
        RgbRegion load(String datasetId, int series, int z, int t, int x, int y, int width, int height) throws IOException;
    }
}

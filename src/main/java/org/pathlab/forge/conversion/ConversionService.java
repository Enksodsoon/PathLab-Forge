package org.pathlab.forge.conversion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.pathlab.forge.library.DatasetRepository;
import org.pathlab.forge.library.DatasetFormat;
import org.pathlab.forge.library.DatasetSourceInventory;
import org.pathlab.forge.library.DatasetStatus;
import org.pathlab.forge.library.LocalDataset;
import org.pathlab.forge.derivative.DerivativeEngine;
import org.pathlab.forge.packageformat.PreparedPackageBuilder;
import org.pathlab.forge.packageformat.PackageMetadata;

public final class ConversionService implements AutoCloseable {
    private final DatasetRepository repository;
    private final ConversionEngine engine;
    private final DerivativeEngine derivativeEngine;
    private final Path managedRoot;
    private final ArtifactRevisionRepository artifactRepository;
    private final Map<String, List<SeriesInfo>> inspectedSeries = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> activeConversions = new ConcurrentHashMap<>();
    private final java.util.Set<String> cancelled =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ExecutorService conversionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "pathlab-forge-conversion");
        thread.setDaemon(true);
        return thread;
    });

    public ConversionService(
            DatasetRepository repository,
            ConversionEngine engine,
            DerivativeEngine derivativeEngine,
            Path managedRoot) {
        this.repository = repository;
        this.engine = engine;
        this.derivativeEngine = derivativeEngine;
        this.managedRoot = managedRoot.toAbsolutePath().normalize();
        this.artifactRepository = new ArtifactRevisionRepository(this.managedRoot);
    }

    public ConversionEngine engine() {
        return engine;
    }

    public DerivativeEngine derivativeEngine() {
        return derivativeEngine;
    }

    public LocalArtifacts artifacts(String id) {
        var dataset = requireDataset(id);
        if (dataset.currentArtifactRevision().isBlank()) {
            throw new IllegalStateException("No artifact exists for the current configuration");
        }
        try {
            var revision = artifactRepository
                    .find(id, dataset.currentArtifactRevision())
                    .orElseThrow(() -> new IllegalStateException(
                            "The current artifact revision is missing"));
            var derivative = Path.of(revision.derivativePath());
            return new LocalArtifacts(
                    Path.of(revision.omePath()),
                    derivative,
                    derivative.resolve("slide.dzi"),
                    derivative.resolve("thumbnail.jpg"),
                    Path.of(revision.packagePath()));
        } catch (IOException error) {
            throw new IllegalStateException("Artifact revisions could not be read", error);
        }
    }

    public List<ArtifactRevision> revisions(String id) throws IOException {
        requireDataset(id);
        return artifactRepository.list(id);
    }

    public ArtifactRevision approvedRevision(String id) throws IOException {
        var dataset = requireDataset(id);
        if (dataset.approvedArtifactRevision().isBlank()
                || !dataset.approvedArtifactRevision().equals(
                        dataset.currentArtifactRevision())) {
            throw new IllegalStateException("Approve the current artifact before upload");
        }
        var revision = artifactRepository
                .find(id, dataset.approvedArtifactRevision())
                .orElseThrow(() -> new IllegalStateException(
                        "The approved artifact revision is missing"));
        if (revision.status() != ArtifactRevisionStatus.APPROVED) {
            throw new IllegalStateException("Artifact approval is no longer valid");
        }
        return revision;
    }

    public synchronized LocalPreview preview(String id) throws IOException {
        var dataset = requireDataset(id);
        var availableSeries = restoreSeries(id, dataset);
        var series = availableSeries.stream()
                .filter(item -> item.index() == dataset.selectedSeries())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Inspect and select an image series before preview"));
        var previewRoot = managedRoot
                .resolve(id)
                .resolve("previews")
                .resolve(dataset.configurationRevision())
                .normalize();
        if (!previewRoot.startsWith(managedRoot.resolve(id).normalize())) {
            throw new IllegalStateException("Preview path escapes managed storage");
        }
        var descriptor = previewRoot.resolve("slide.dzi");
        if (Files.isRegularFile(descriptor)) {
            var dimensions = readPreviewDimensions(previewRoot, series);
            return new LocalPreview(
                    previewRoot,
                    dimensions[0],
                    dimensions[1],
                    series.width(),
                    series.height());
        }
        Files.createDirectories(previewRoot);
        PreviewSource source;
        if (dataset.format() == DatasetFormat.OME_TIFF) {
            source = new PreviewSource(
                    Path.of(dataset.sourcePath()), series.width(), series.height());
        } else {
            source = engine.renderPreview(
                    Path.of(dataset.sourcePath()),
                    dataset.selectedSeries(),
                    previewRoot.resolve("source-preview.ome.tif"),
                    4096);
        }
        derivativeEngine.generateDzi(
                source.path(), previewRoot, source.width(), source.height());
        Files.writeString(
                previewRoot.resolve("preview-dimensions.txt"),
                source.width() + "," + source.height());
        return new LocalPreview(
                previewRoot,
                source.width(),
                source.height(),
                series.width(),
                series.height());
    }

    private static int[] readPreviewDimensions(Path root, SeriesInfo fallback)
            throws IOException {
        var file = root.resolve("preview-dimensions.txt");
        if (!Files.isRegularFile(file)) {
            return new int[] {fallback.width(), fallback.height()};
        }
        var parts = Files.readString(file).strip().split(",", 2);
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    public LocalDataset approve(String id, String revisionId) throws IOException {
        var dataset = requireDataset(id);
        if (!revisionId.equals(dataset.currentArtifactRevision())) {
            throw new IllegalStateException("Only the current artifact revision can be approved");
        }
        var revision = artifactRepository
                .find(id, revisionId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact revision was not found"));
        if (!revision.configurationRevision().equals(dataset.configurationRevision())
                || !revision.sourceFingerprint().equals(dataset.sourceFingerprint())) {
            throw new IllegalStateException("Artifact provenance no longer matches the dataset");
        }
        if (revision.status() != ArtifactRevisionStatus.READY) {
            throw new IllegalStateException("Artifact must pass validation before approval");
        }
        var ome = Path.of(revision.omePath());
        var packagePath = Path.of(revision.packagePath());
        if (!Files.isRegularFile(ome)
                || !Files.isRegularFile(packagePath)
                || !revision.omeSha256().equals(sha256(ome))
                || !revision.packageSha256().equals(sha256(packagePath))) {
            throw new IllegalStateException("Artifact files changed after validation");
        }
        artifactRepository.save(revision.approved(System.currentTimeMillis()));
        var approved = dataset.withApprovedArtifact(revisionId);
        repository.save(approved);
        return approved;
    }

    public List<SeriesInfo> inspect(String id) throws IOException {
        var dataset = requireDataset(id);
        var inspecting = dataset.withConversion(
                DatasetStatus.INSPECTING,
                "Reading bounded image metadata with Bio-Formats",
                dataset.outputPath(),
                dataset.sha256(),
                dataset.selectedSeries(),
                dataset.width(),
                dataset.height(),
                dataset.downsample(),
                dataset.estimatedOutputBytes());
        repository.save(inspecting);
        try {
            var series = engine.inspect(Path.of(dataset.sourcePath()));
            inspectedSeries.put(id, series);
            var previouslySelected = series.stream()
                    .filter(item -> item.index() == dataset.selectedSeries())
                    .filter(SeriesInfo::isRgbPlane)
                    .findFirst();
            var selected = previouslySelected.orElseGet(() -> series.stream()
                    .filter(SeriesInfo::isRgbPlane)
                    .max(Comparator.comparingLong(
                            item -> (long) item.width() * (long) item.height()))
                    .orElse(series.get(0)));
            var preserveConfiguration = previouslySelected.isPresent()
                    && dataset.cropWidth() > 0
                    && dataset.cropHeight() > 0
                    && (long) dataset.cropX() + dataset.cropWidth() <= selected.width()
                    && (long) dataset.cropY() + dataset.cropHeight() <= selected.height();
            var downsample = preserveConfiguration ? dataset.downsample() : 1.0;
            var cropX = preserveConfiguration ? dataset.cropX() : 0;
            var cropY = preserveConfiguration ? dataset.cropY() : 0;
            var cropWidth = preserveConfiguration ? dataset.cropWidth() : selected.width();
            var cropHeight = preserveConfiguration ? dataset.cropHeight() : selected.height();
            var ready = inspecting.withExportConfiguration(
                    DatasetStatus.READY_TO_CONVERT,
                    series.size() + " image series found; select a series and export",
                    selected.index(),
                    selected.width(),
                    selected.height(),
                    downsample,
                    OutputSizeEstimator.rgbPyramidUpperBound(
                            cropWidth, cropHeight, downsample),
                    cropX,
                    cropY,
                    cropWidth,
                    cropHeight);
            repository.save(ready);
            return series;
        } catch (IOException | RuntimeException error) {
            repository.save(inspecting.withConversion(
                    DatasetStatus.FAILED,
                    concise(error.getMessage()),
                    "",
                    "",
                    -1,
                    0,
                    0,
                    1,
                    0));
            throw error;
        }
    }

    public synchronized List<SeriesInfo> series(String id) throws IOException {
        var dataset = requireDataset(id);
        return restoreSeries(id, dataset);
    }

    private List<SeriesInfo> restoreSeries(String id, LocalDataset dataset)
            throws IOException {
        var existing = inspectedSeries.getOrDefault(id, List.of());
        if (!existing.isEmpty()) {
            return existing;
        }
        if (dataset.selectedSeries() < 0) {
            return List.of();
        }
        verifySourceFingerprint(dataset);
        var restored = engine.inspect(Path.of(dataset.sourcePath()));
        inspectedSeries.put(id, restored);
        return restored;
    }

    public LocalDataset selectSeries(String id, int seriesIndex, double downsample)
            throws IOException {
        var info = requireSeriesInfo(id, seriesIndex);
        return selectSeries(
                id, seriesIndex, downsample, 0, 0, info.width(), info.height());
    }

    public LocalDataset selectSeries(
            String id,
            int seriesIndex,
            double downsample,
            int cropX,
            int cropY,
            int cropWidth,
            int cropHeight)
            throws IOException {
        var dataset = requireDataset(id);
        var info = requireSeriesInfo(id, seriesIndex);
        if (!info.isRgbPlane()) {
            throw new IllegalArgumentException(
                    "Select a 2D RGB series (3 channels, one Z plane, one timepoint)");
        }
        var request = new ConversionRequest(
                Path.of(dataset.sourcePath()),
                seriesIndex,
                cropX,
                cropY,
                cropWidth,
                cropHeight,
                info.width(),
                info.height(),
                downsample);
        var updated = dataset.withExportConfiguration(
                DatasetStatus.READY_TO_CONVERT,
                "Series " + info.index() + " · "
                        + request.outputWidth() + " × " + request.outputHeight()
                        + " RGB export at " + downsample + "x",
                info.index(),
                info.width(),
                info.height(),
                downsample,
                request.estimatedRgbPyramidBytes(),
                cropX,
                cropY,
                cropWidth,
                cropHeight);
        repository.save(updated);
        return updated;
    }

    public LocalDataset start(String id) throws IOException {
        var dataset = requireDataset(id);
        if (dataset.status() == DatasetStatus.CONVERTING
                || dataset.status() == DatasetStatus.VALIDATING) {
            return dataset;
        }
        if (dataset.selectedSeries() < 0) {
            throw new IllegalStateException("Inspect and select an image series first");
        }
        if (activeConversions.entrySet().stream()
                .anyMatch(item -> !item.getKey().equals(id) && !item.getValue().isDone())) {
            throw new IllegalStateException("Another conversion is already active");
        }
        Files.createDirectories(managedRoot);
        DiskPreflight.requireCapacity(
                Files.getFileStore(managedRoot).getUsableSpace(),
                dataset.estimatedOutputBytes());
        verifySourceFingerprint(dataset);
        var request = request(dataset);
        var revision = artifactRepository.create(
                dataset, request.outputWidth(), request.outputHeight());
        var converting = dataset.withArtifactRevision(
                DatasetStatus.CONVERTING,
                "Exporting QuPath-style rendered RGB with JPEG compression",
                "",
                "",
                revision.id());
        repository.save(converting);
        cancelled.remove(id);
        activeConversions.put(id, conversionExecutor.submit(() -> convert(converting)));
        return converting;
    }

    public LocalDataset cancel(String id) throws IOException {
        var dataset = requireDataset(id);
        cancelled.add(id);
        var future = activeConversions.get(id);
        if (future != null) {
            future.cancel(true);
            for (var attempt = 0;
                    attempt < 500 && activeConversions.get(id) == future;
                    attempt++) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        cleanupCancelledRevision(dataset);
        var cancelledDataset = dataset.withPreparation(
                DatasetStatus.CANCELLED,
                "Conversion cancelled; completed outputs were preserved",
                dataset.outputPath(),
                dataset.sha256());
        repository.save(cancelledDataset);
        return cancelledDataset;
    }

    private void cleanupCancelledRevision(LocalDataset dataset) throws IOException {
        var revision = artifactRepository
                .find(dataset.id(), dataset.currentArtifactRevision())
                .orElse(null);
        if (revision == null || revision.omePath().isBlank()) {
            return;
        }
        var output = Path.of(revision.omePath());
        var outputDirectory = output.getParent();
        Files.deleteIfExists(output.resolveSibling("export.partial.ome.tif"));
        Files.deleteIfExists(output.resolveSibling("render.partial.ome.tif"));
        deleteTree(outputDirectory, outputDirectory.resolve("derivative.partial"));
    }

    private void convert(LocalDataset dataset) {
        ArtifactRevision revision;
        try {
            revision = artifactRepository
                    .find(dataset.id(), dataset.currentArtifactRevision())
                    .orElseThrow(() -> new IOException("Artifact revision was not created"));
        } catch (IOException error) {
            failRevision(dataset, null, error);
            return;
        }
        var output = Path.of(revision.omePath());
        var outputDirectory = output.getParent();
        var partial = output.resolveSibling("export.partial.ome.tif");
        var rendered = output.resolveSibling("render.partial.ome.tif");
        var derivativePartial = outputDirectory.resolve("derivative.partial");
        var derivative = outputDirectory.resolve("derivative");
        try {
            Files.createDirectories(outputDirectory);
            Files.deleteIfExists(partial);
            Files.deleteIfExists(rendered);
            var request = request(dataset);
            if (dataset.format() == DatasetFormat.OME_TIFF
                    && derivativeEngine.supportsOmeRendering()) {
                derivativeEngine.renderOme(request, rendered);
            } else {
                engine.convert(request, rendered);
            }
            if (derivativeEngine.available()) {
                derivativeEngine.optimizeOme(
                        rendered, partial, request.outputWidth(), request.outputHeight());
                Files.deleteIfExists(rendered);
            } else {
                Files.move(rendered, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            repository.save(dataset.withConversion(
                    DatasetStatus.VALIDATING,
                    "Validating rendered-RGB size, TIFF signature and SHA-256",
                    dataset.outputPath(),
                    dataset.sha256(),
                    dataset.selectedSeries(),
                    dataset.width(),
                    dataset.height(),
                    dataset.downsample(),
                    dataset.estimatedOutputBytes()));
            verifyTiff(partial);
            OutputSizeGuard.requireSuitable(
                    Files.size(partial),
                    dataset.sourceBytes(),
                    request.outputWidth(),
                    request.outputHeight(),
                    request.downsample());
            var digest = sha256(partial);
            atomicReplace(partial, output);
            if (!derivativeEngine.available()) {
                repository.save(dataset.withConversion(
                        DatasetStatus.CONVERSION_READY,
                        "Validated RGB OME-BigTIFF ready; libvips is required for DZI",
                        output.toString(),
                        digest,
                        dataset.selectedSeries(),
                        dataset.width(),
                        dataset.height(),
                        dataset.downsample(),
                        dataset.estimatedOutputBytes()));
                return;
            }
            repository.save(dataset.withConversion(
                    DatasetStatus.GENERATING_DZI,
                    "Generating optimized Viewer-compatible DZI and thumbnail",
                    output.toString(),
                    digest,
                    dataset.selectedSeries(),
                    dataset.width(),
                    dataset.height(),
                    dataset.downsample(),
                    dataset.estimatedOutputBytes()));
            deleteTree(outputDirectory, derivativePartial);
            var derivativeInfo = derivativeEngine.generateDzi(
                    output,
                    derivativePartial,
                    request.outputWidth(),
                    request.outputHeight());
            installDirectory(outputDirectory, derivativePartial, derivative);
            repository.save(dataset.withConversion(
                    DatasetStatus.DZI_READY,
                    derivativeInfo.tileCount() + " validated DZI tiles; building upload package",
                    output.toString(),
                    digest,
                    dataset.selectedSeries(),
                    dataset.width(),
                    dataset.height(),
                    dataset.downsample(),
                    dataset.estimatedOutputBytes()));
            var seriesInfo = inspectedSeries.getOrDefault(dataset.id(), List.of()).stream()
                    .filter(item -> item.index() == dataset.selectedSeries())
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "Selected-series calibration is unavailable"));
            var packageInfo = PreparedPackageBuilder.build(
                    derivative,
                    request.outputWidth(),
                    request.outputHeight(),
                    new PackageMetadata(
                            revision.id(),
                            revision.configurationRevision(),
                            revision.sourceFingerprint(),
                            dataset.selectedSeries(),
                            request.cropX(),
                            request.cropY(),
                            request.cropWidth(),
                            request.cropHeight(),
                            request.downsample(),
                            seriesInfo.physicalSizeX(),
                            seriesInfo.physicalSizeY(),
                            seriesInfo.physicalUnit(),
                            "0.1.0-rc"),
                    outputDirectory.resolve("slide.plslide"));
            artifactRepository.save(revision.ready(digest, packageInfo.sha256()));
            repository.save(dataset.withConversion(
                    DatasetStatus.PACKAGE_READY,
                    derivativeInfo.tileCount() + " DZI tiles · "
                            + derivativeInfo.fileCount() + " files · "
                            + derivativeInfo.bytes() + " derivative bytes · "
                            + packageInfo.bytes() + " package bytes; viewer and upload package ready",
                    output.toString(),
                    digest,
                    dataset.selectedSeries(),
                    dataset.width(),
                    dataset.height(),
                    dataset.downsample(),
                    dataset.estimatedOutputBytes()));
        } catch (Exception error) {
            try {
                Files.deleteIfExists(partial);
                Files.deleteIfExists(rendered);
                deleteTree(outputDirectory, derivativePartial);
                if (cancelled.remove(dataset.id())) {
                    repository.save(dataset.withPreparation(
                            DatasetStatus.CANCELLED,
                            "Conversion cancelled; incomplete output removed",
                            dataset.outputPath(),
                            dataset.sha256()));
                } else {
                    artifactRepository.save(revision.failed(concise(error.getMessage())));
                    repository.save(dataset.withConversion(
                            DatasetStatus.FAILED,
                            concise(error.getMessage()),
                            dataset.outputPath(),
                            dataset.sha256(),
                            dataset.selectedSeries(),
                            dataset.width(),
                            dataset.height(),
                            dataset.downsample(),
                            dataset.estimatedOutputBytes()));
                }
            } catch (IOException ignored) {
                // The original conversion error remains the useful diagnostic.
            }
        } finally {
            activeConversions.remove(dataset.id());
        }
    }

    private void failRevision(LocalDataset dataset, ArtifactRevision revision, Exception error) {
        try {
            if (revision != null) {
                artifactRepository.save(revision.failed(concise(error.getMessage())));
            }
            repository.save(dataset.withConversion(
                    DatasetStatus.FAILED,
                    concise(error.getMessage()),
                    "",
                    "",
                    dataset.selectedSeries(),
                    dataset.width(),
                    dataset.height(),
                    dataset.downsample(),
                    dataset.estimatedOutputBytes()));
        } catch (IOException ignored) {
            // The original artifact failure remains the useful diagnostic.
        }
    }

    private static ConversionRequest request(LocalDataset dataset) {
        return new ConversionRequest(
                Path.of(dataset.sourcePath()),
                dataset.selectedSeries(),
                dataset.cropX(),
                dataset.cropY(),
                dataset.cropWidth(),
                dataset.cropHeight(),
                dataset.width(),
                dataset.height(),
                dataset.downsample());
    }

    private static void verifySourceFingerprint(LocalDataset dataset) throws IOException {
        if (dataset.sourceFingerprint().isBlank()) {
            throw new IllegalStateException("Reinspect the source before conversion");
        }
        try {
            var current = dataset.format() == DatasetFormat.VSI
                    ? DatasetSourceInventory.forVsi(Path.of(dataset.sourcePath()))
                    : DatasetSourceInventory.singleFile(Path.of(dataset.sourcePath()));
            if (!dataset.sourceFingerprint().equals(current.fingerprint())) {
                throw new IllegalStateException(
                        "Source or companion files changed; reinspect before conversion");
            }
        } catch (org.pathlab.forge.library.DatasetInspectionException error) {
            throw new IllegalStateException(error.getMessage(), error);
        }
    }

    private SeriesInfo requireSeriesInfo(String id, int seriesIndex) throws IOException {
        return series(id).stream()
                .filter(item -> item.index() == seriesIndex)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Inspect this dataset before selecting a series"));
    }

    private LocalDataset requireDataset(String id) {
        return repository.find(id)
                .orElseThrow(() -> new IllegalArgumentException("Dataset was not found"));
    }

    private Path managedDirectory(String id) {
        var directory = managedRoot.resolve(id).normalize();
        if (!directory.startsWith(managedRoot)) {
            throw new IllegalArgumentException("Dataset identifier escapes the managed root");
        }
        return directory;
    }

    private static void verifyTiff(Path path) throws IOException {
        var signature = new byte[4];
        try (InputStream input = Files.newInputStream(path)) {
            if (input.read(signature) != 4) {
                throw new IOException("Converted OME-TIFF is too short");
            }
        }
        var byteOrder = (signature[0] == 'I' && signature[1] == 'I')
                || (signature[0] == 'M' && signature[1] == 'M');
        if (!byteOrder) {
            throw new IOException("Converted output has an invalid TIFF signature");
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                var buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void atomicReplace(Path partial, Path output) throws IOException {
        try {
            Files.move(
                    partial,
                    output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void installDirectory(Path root, Path staging, Path destination)
            throws IOException {
        var previous = root.resolve("derivative.previous");
        deleteTree(root, previous);
        if (Files.exists(destination)) {
            Files.move(destination, previous, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            deleteTree(root, previous);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
            deleteTree(root, previous);
        } catch (IOException error) {
            if (!Files.exists(destination) && Files.exists(previous)) {
                Files.move(previous, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            throw error;
        }
    }

    private static void deleteTree(Path root, Path target) throws IOException {
        var normalizedRoot = root.toAbsolutePath().normalize();
        var normalizedTarget = target.toAbsolutePath().normalize();
        if (normalizedTarget.equals(normalizedRoot) || !normalizedTarget.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Cleanup target escapes the managed dataset");
        }
        if (!Files.exists(normalizedTarget, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(normalizedTarget)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String concise(String value) {
        if (value == null || value.isBlank()) {
            return "Conversion failed";
        }
        var normalized = value.strip();
        return normalized.length() <= 600 ? normalized : normalized.substring(0, 600);
    }

    @Override
    public void close() {
        conversionExecutor.shutdownNow();
    }
}

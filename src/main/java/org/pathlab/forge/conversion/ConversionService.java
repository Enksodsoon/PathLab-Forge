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
    private static final int PREVIEW_DOWNSAMPLE = 2;
    private static final String PREVIEW_CACHE_VERSION = "efficient-rgb-2x-v4";
    private static final long PARALLEL_RGB_MINIMUM_PIXELS = 250_000_000L;
    private static final int MAX_READER_SESSIONS = 2;
    private static final long READER_SESSION_BYTES = 256L * 1024 * 1024;
    private final DatasetRepository repository;
    private final ConversionEngine engine;
    private final DerivativeEngine derivativeEngine;
    private final Path managedRoot;
    private final ArtifactRevisionRepository artifactRepository;
    private final SeriesMetadataCache seriesMetadataCache;
    private final QuPathRuntime quPathRuntime;
    private final Map<String, List<SeriesInfo>> inspectedSeries = new ConcurrentHashMap<>();
    private final Map<String, ReaderSession> readerSessions = new ConcurrentHashMap<>();
    private final Map<String, DirectTileSource> directSources = new ConcurrentHashMap<>();
    private final Map<String, Path> readerSourcePaths = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> activeConversions = new ConcurrentHashMap<>();
    private final Map<String, ConversionProgress> progress = new ConcurrentHashMap<>();
    private final java.util.Set<Path> cleanedPreviewRoots =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<Path> scheduledPreviewCleanups =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> cancelled =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ExecutorService conversionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "pathlab-forge-conversion");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.concurrent.ScheduledExecutorService memorySampler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                var thread = new Thread(runnable, "pathlab-memory-sampler");
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
        this.seriesMetadataCache = new SeriesMetadataCache(this.managedRoot);
        this.quPathRuntime = QuPathRuntime.discover();
        memorySampler.scheduleAtFixedRate(
                this::sampleActiveMemory,
                0,
                200,
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public ConversionEngine engine() {
        return engine;
    }

    public DerivativeEngine derivativeEngine() {
        return derivativeEngine;
    }

    public ConversionProgress progress(String id) {
        var profile = org.pathlab.forge.runtime.RuntimeProfile.system();
        return progress.getOrDefault(
                id,
                new ConversionProgress(
                        "", 0, 0, 0, currentWorkingSet(), profile.name(), ""));
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

    public LocalArtifacts revisionArtifacts(String id, String revisionId) {
        requireDataset(id);
        try {
            var revision = artifactRepository
                    .find(id, revisionId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Artifact revision was not found"));
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

    public byte[] derivativeEntry(String id, String relative) throws IOException {
        return derivativeEntry(artifacts(id), relative);
    }

    public byte[] derivativeEntry(String id, String revisionId, String relative)
            throws IOException {
        return derivativeEntry(revisionArtifacts(id, revisionId), relative);
    }

    private static byte[] derivativeEntry(LocalArtifacts artifacts, String relative)
            throws IOException {
        if (!relative.matches("slide\\.dzi|thumbnail\\.jpg|slide_files/\\d+/\\d+_\\d+\\.jpg")) {
            throw new IllegalArgumentException("Invalid derivative entry");
        }
        var loose = artifacts.derivativeRoot()
                .resolve(relative.replace('/', java.io.File.separatorChar))
                .normalize();
        if (loose.startsWith(artifacts.derivativeRoot())
                && Files.isRegularFile(loose, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(loose)) {
            return Files.readAllBytes(loose);
        }
        var indexPath = artifacts.packagePath()
                .resolveSibling(artifacts.packagePath().getFileName() + ".index");
        var entry = org.pathlab.forge.packageformat.PackageEntryIndex.read(indexPath)
                .require("derivative/" + relative);
        if (entry.size() > 32L * 1024 * 1024) {
            throw new IOException("Derivative package entry exceeds the local serving limit");
        }
        var bytes = java.nio.ByteBuffer.allocate(Math.toIntExact(entry.size()));
        try (var channel = java.nio.channels.FileChannel.open(artifacts.packagePath())) {
            channel.position(entry.offset());
            while (bytes.hasRemaining()) {
                if (channel.read(bytes) < 0) {
                    throw new IOException("Derivative package entry is truncated");
                }
            }
        }
        return bytes.array();
    }

    public List<ArtifactRevision> revisions(String id) throws IOException {
        requireDataset(id);
        return artifactRepository.list(id);
    }

    public ArtifactRevision renameRevision(String id, String revisionId, String name)
            throws IOException {
        requireDataset(id);
        var revision = artifactRepository
                .find(id, revisionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Artifact revision was not found"));
        var renamed = revision.renamed(name);
        artifactRepository.save(renamed);
        return renamed;
    }

    public LocalDataset deleteRevision(String id, String revisionId) throws IOException {
        var dataset = requireDataset(id);
        var revision = artifactRepository
                .find(id, revisionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Artifact revision was not found"));
        if (revision.status() == ArtifactRevisionStatus.CONVERTING
                && activeConversions.containsKey(id)) {
            throw new IllegalStateException("Cancel the active conversion before deleting it");
        }
        artifactRepository.delete(id, revisionId);
        if (!revisionId.equals(dataset.currentArtifactRevision())
                && !revisionId.equals(dataset.approvedArtifactRevision())) {
            return dataset;
        }
        var replacement = artifactRepository.list(id).stream()
                .filter(candidate -> candidate.configurationRevision()
                        .equals(dataset.configurationRevision()))
                .filter(candidate -> candidate.status() == ArtifactRevisionStatus.READY
                        || candidate.status() == ArtifactRevisionStatus.APPROVED)
                .filter(candidate -> Files.isRegularFile(Path.of(candidate.packagePath())))
                .findFirst();
        var updated = replacement
                .map(candidate -> dataset.withArtifactPointers(
                        DatasetStatus.PACKAGE_READY,
                        "Saved conversion restored from History",
                        candidate.packagePath(),
                        candidate.omeSha256(),
                        candidate.id(),
                        candidate.status() == ArtifactRevisionStatus.APPROVED
                                ? candidate.id()
                                : ""))
                .orElseGet(() -> dataset.withArtifactPointers(
                        DatasetStatus.READY_TO_CONVERT,
                        "Conversion deleted; the source settings are preserved",
                        "",
                        "",
                        "",
                        ""));
        repository.save(updated);
        return updated;
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
        if (dataset.selectedSeries() < 0) {
            throw new IllegalStateException("Inspect and select an image series before preview");
        }
        var previewRoot = previewRoot(id, dataset);
        if (!previewRoot.startsWith(managedRoot.resolve(id).normalize())) {
            throw new IllegalStateException("Preview path escapes managed storage");
        }
        var descriptor = previewRoot.resolve("slide.dzi");
        if (Files.isRegularFile(descriptor)) {
            var dimensions =
                    readPreviewDimensions(previewRoot, dataset.width(), dataset.height());
            scheduleObsoletePreviewCleanup(id, previewRoot);
            return new LocalPreview(
                    previewRoot,
                    dimensions[0],
                    dimensions[1],
                    dataset.width(),
                    dataset.height());
        }
        var availableSeries = restoreSeries(id, dataset);
        var series = availableSeries.stream()
                .filter(item -> item.index() == dataset.selectedSeries())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Selected image series is no longer available"));
        Files.createDirectories(previewRoot);
        var targetWidth = Math.max(1, divideRoundUp(series.width(), PREVIEW_DOWNSAMPLE));
        var targetHeight = Math.max(1, divideRoundUp(series.height(), PREVIEW_DOWNSAMPLE));
        var estimatedPyramidBytes =
                OutputSizeEstimator.rgbPyramidUpperBound(targetWidth, targetHeight, 1);
        var estimatedPeakBytes = Math.multiplyExact(estimatedPyramidBytes, 2);
        DiskPreflight.requireCapacity(
                Files.getFileStore(previewRoot).getUsableSpace(), estimatedPeakBytes);
        PreviewSource source;
        Path temporaryOme = null;
        if (dataset.format() == DatasetFormat.OME_TIFF) {
            source = new PreviewSource(
                    Path.of(dataset.sourcePath()), series.width(), series.height());
        } else {
            temporaryOme = previewRoot.resolve("source-preview.ome.tif");
            source = engine.renderPreview(
                    Path.of(dataset.sourcePath()),
                    dataset.selectedSeries(),
                    temporaryOme,
                    Math.max(targetWidth, targetHeight));
        }
        try {
            derivativeEngine.generateDzi(
                    source.path(), previewRoot, source.width(), source.height());
        } finally {
            if (temporaryOme != null) {
                Files.deleteIfExists(temporaryOme);
            }
        }
        Files.writeString(
                previewRoot.resolve("preview-dimensions.txt"),
                source.width() + "," + source.height());
        cleanupObsoletePreviews(id, previewRoot);
        return new LocalPreview(
                previewRoot,
                source.width(),
                source.height(),
                series.width(),
                series.height());
    }

    public synchronized java.util.Optional<LocalPreview> cachedPreview(String id)
            throws IOException {
        var dataset = requireDataset(id);
        if (dataset.selectedSeries() < 0) {
            return java.util.Optional.empty();
        }
        var root = previewRoot(id, dataset);
        if (!Files.isRegularFile(root.resolve("slide.dzi"))) {
            return java.util.Optional.empty();
        }
        var dimensions = readPreviewDimensions(root, dataset.width(), dataset.height());
        scheduleObsoletePreviewCleanup(id, root);
        return java.util.Optional.of(new LocalPreview(
                root,
                dimensions[0],
                dimensions[1],
                dataset.width(),
                dataset.height()));
    }

    public boolean supportsDirectPreview() {
        return engine.supportsDirectTiles();
    }

    public DirectTileSource directPreview(String id) throws IOException {
        var dataset = requireDataset(id);
        if (dataset.selectedSeries() < 0) {
            throw new IllegalStateException("Inspect and select an image series before preview");
        }
        evictIdleReaderSessions();
        var key = readerSessionKey(dataset);
        ensureReaderSession(dataset);
        var existing = directSources.get(key);
        if (existing != null) {
            return existing;
        }
        var opened = engine.directTileSource(
                Path.of(dataset.sourcePath()), dataset.selectedSeries());
        var raced = directSources.putIfAbsent(key, opened);
        return raced == null ? opened : raced;
    }

    public byte[] directPreviewTile(
            String id, int level, int tileX, int tileY) throws IOException {
        var dataset = requireDataset(id);
        if (dataset.selectedSeries() < 0) {
            throw new IllegalStateException("Inspect and select an image series before preview");
        }
        evictIdleReaderSessions();
        var session = ensureReaderSession(dataset);
        try {
            return session.tile(
                    new ReaderSession.TileKey(dataset.selectedSeries(), level, tileX, tileY),
                    () -> engine.readDirectTile(
                            Path.of(dataset.sourcePath()),
                            dataset.selectedSeries(),
                            level,
                            tileX,
                            tileY));
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Direct preview tile read failed", error);
        }
    }

    private static String readerSessionKey(LocalDataset dataset) {
        return dataset.id() + "|" + dataset.sourceFingerprint() + "|" + dataset.selectedSeries();
    }

    private synchronized ReaderSession ensureReaderSession(LocalDataset dataset)
            throws IOException {
        var key = readerSessionKey(dataset);
        var existing = readerSessions.get(key);
        if (existing != null) {
            return existing;
        }
        if (readerSessions.size() >= MAX_READER_SESSIONS) {
            var oldest = readerSessions.entrySet().stream()
                    .min(Comparator.comparingLong(
                            entry -> entry.getValue().lastAccessNanos()))
                    .map(Map.Entry::getKey)
                    .orElseThrow();
            evictReaderSession(oldest);
        }
        var opened = new ReaderSession(READER_SESSION_BYTES);
        readerSessions.put(key, opened);
        readerSourcePaths.put(key, Path.of(dataset.sourcePath()).toAbsolutePath().normalize());
        return opened;
    }

    private synchronized void evictIdleReaderSessions() throws IOException {
        var idleNanos = java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
        var expired = readerSessions.entrySet().stream()
                .filter(entry -> entry.getValue().idleFor(idleNanos))
                .map(Map.Entry::getKey)
                .toList();
        for (var key : expired) {
            evictReaderSession(key);
        }
    }

    private void evictReaderSession(String key) throws IOException {
        var removed = readerSessions.remove(key);
        if (removed != null) {
            removed.clear();
        }
        directSources.remove(key);
        var source = readerSourcePaths.remove(key);
        if (source != null && readerSourcePaths.values().stream().noneMatch(source::equals)) {
            engine.closeDirectSource(source);
        }
    }

    private Path previewRoot(String id, LocalDataset dataset) {
        var root = managedRoot
                .resolve(id)
                .resolve("previews")
                .resolve(PREVIEW_CACHE_VERSION)
                .resolve(dataset.configurationRevision())
                .normalize();
        if (!root.startsWith(managedRoot.resolve(id).normalize())) {
            throw new IllegalStateException("Preview path escapes managed storage");
        }
        return root;
    }

    private void cleanupObsoletePreviews(String id, Path currentPreview) {
        var previewBase = managedRoot.resolve(id).resolve("previews").normalize();
        if (cleanedPreviewRoots.contains(currentPreview)
                || !currentPreview.startsWith(previewBase)
                || !Files.isDirectory(previewBase)) {
            return;
        }
        try {
            try (var versions = Files.list(previewBase)) {
                for (var version : versions.filter(Files::isDirectory).toList()) {
                    if (!currentPreview.startsWith(version)) {
                        deleteTree(version);
                        continue;
                    }
                    try (var revisions = Files.list(version)) {
                        for (var revision : revisions.filter(Files::isDirectory).toList()) {
                            if (!revision.equals(currentPreview)) {
                                deleteTree(revision);
                            }
                        }
                    }
                }
            }
            cleanedPreviewRoots.add(currentPreview);
        } catch (IOException ignored) {
            // A cancelled converter may still hold a Windows file handle briefly.
            // Cache-hit requests retry cleanup after that process exits.
        }
    }

    private void scheduleObsoletePreviewCleanup(String id, Path currentPreview) {
        if (cleanedPreviewRoots.contains(currentPreview)
                || !scheduledPreviewCleanups.add(currentPreview)) {
            return;
        }
        conversionExecutor.execute(() -> {
            try {
                cleanupObsoletePreviews(id, currentPreview);
            } finally {
                scheduledPreviewCleanups.remove(currentPreview);
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static int[] readPreviewDimensions(Path root, int fallbackWidth, int fallbackHeight)
            throws IOException {
        var file = root.resolve("preview-dimensions.txt");
        if (!Files.isRegularFile(file)) {
            return new int[] {fallbackWidth, fallbackHeight};
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
        if (!Files.isRegularFile(packagePath)
                || !ArtifactIntegrityStamp.matches(revision)
                || (Files.exists(ome) && !revision.omeSha256().equals(sha256(ome)))
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
        verifySourceFingerprint(dataset);
        var preservePackagedResult = hasReviewableCurrentArtifact(dataset);
        if (preservePackagedResult && dataset.status() != DatasetStatus.PACKAGE_READY) {
            dataset = dataset.withConversion(
                    DatasetStatus.PACKAGE_READY,
                    "Validated compact DZI package ready; review before approval",
                    dataset.outputPath(),
                    dataset.sha256(),
                    dataset.selectedSeries(),
                    dataset.width(),
                    dataset.height(),
                    dataset.downsample(),
                    dataset.estimatedOutputBytes());
            repository.save(dataset);
        }
        var cached = seriesMetadataCache.load(id, dataset.sourceFingerprint());
        if (cached.isPresent()) {
            inspectedSeries.put(id, cached.get());
            if (!preservePackagedResult) {
                updateInspectedDataset(dataset, cached.get(), true);
            }
            return cached.get();
        }
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
        if (!preservePackagedResult) {
            repository.save(inspecting);
        }
        try {
            var series = engine.inspect(Path.of(dataset.sourcePath()));
            inspectedSeries.put(id, series);
            seriesMetadataCache.save(id, dataset.sourceFingerprint(), series);
            if (!preservePackagedResult) {
                updateInspectedDataset(dataset, series, false);
            }
            return series;
        } catch (IOException | RuntimeException error) {
            if (!preservePackagedResult) {
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
            }
            throw error;
        }
    }

    private boolean hasReviewableCurrentArtifact(LocalDataset dataset) throws IOException {
        if (dataset.currentArtifactRevision().isBlank()) {
            return false;
        }
        var revision = artifactRepository
                .find(dataset.id(), dataset.currentArtifactRevision())
                .orElse(null);
        return revision != null
                && revision.configurationRevision().equals(dataset.configurationRevision())
                && (revision.status() == ArtifactRevisionStatus.READY
                        || revision.status() == ArtifactRevisionStatus.APPROVED)
                && Files.isRegularFile(Path.of(revision.packagePath()));
    }

    public List<SeriesInfo> inspectWhileVerifying(String id) throws IOException {
        var dataset = requireDataset(id);
        if (dataset.status() != DatasetStatus.VERIFYING_SOURCE
                || !dataset.sourceFingerprint().isBlank()) {
            return inspect(id);
        }
        var series = engine.inspect(Path.of(dataset.sourcePath()));
        inspectedSeries.put(id, series);
        return series;
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
        var cached = seriesMetadataCache.load(id, dataset.sourceFingerprint());
        if (cached.isPresent()) {
            inspectedSeries.put(id, cached.get());
            return cached.get();
        }
        var restored = engine.inspect(Path.of(dataset.sourcePath()));
        seriesMetadataCache.save(id, dataset.sourceFingerprint(), restored);
        inspectedSeries.put(id, restored);
        return restored;
    }

    public synchronized byte[] seriesThumbnail(String id, int seriesIndex) throws IOException {
        var dataset = requireDataset(id);
        verifySourceFingerprint(dataset);
        var item = restoreSeries(id, dataset).stream()
                .filter(series -> series.index() == seriesIndex)
                .filter(SeriesInfo::isRgbPlane)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Image series was not found"));
        var file = seriesMetadataCache.thumbnailFile(
                id, dataset.sourceFingerprint(), item.index());
        if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(file)) {
            return Files.readAllBytes(file);
        }
        var bytes = engine.seriesThumbnail(Path.of(dataset.sourcePath()), item.index(), 320);
        Files.createDirectories(file.getParent());
        var partial = file.resolveSibling(file.getFileName() + ".partial");
        Files.write(partial, bytes);
        atomicReplace(partial, file);
        return bytes;
    }

    private void updateInspectedDataset(
            LocalDataset dataset, List<SeriesInfo> series, boolean cacheHit) throws IOException {
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
        var cropX = preserveConfiguration ? dataset.cropX() : 0;
        var cropY = preserveConfiguration ? dataset.cropY() : 0;
        var cropWidth = preserveConfiguration ? dataset.cropWidth() : selected.width();
        var cropHeight = preserveConfiguration ? dataset.cropHeight() : selected.height();
        var downsample = preserveConfiguration ? dataset.downsample() : 1.0;
        repository.save(dataset.withExportConfiguration(
                DatasetStatus.READY_TO_CONVERT,
                cacheHit
                        ? series.size() + " image series loaded instantly from verified cache"
                        : series.size() + " image series found; thumbnails are ready on demand",
                selected.index(),
                selected.width(),
                selected.height(),
                downsample,
                OutputSizeEstimator.rgbPyramidUpperBound(cropWidth, cropHeight, downsample),
                cropX,
                cropY,
                cropWidth,
                cropHeight));
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
        if (dataset.sourceFingerprint().isBlank()) {
            throw new IllegalStateException(
                    "Source verification must complete before export configuration");
        }
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
                || dataset.status() == DatasetStatus.OPTIMIZING_OME
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
        verifySourceFingerprint(dataset);
        var request = request(dataset);
        var secondsBudgetEnabled = Boolean.parseBoolean(
                System.getProperty("pathlab.forge.secondsBudget.enabled", "false"));
        if (secondsBudgetEnabled
                && Boolean.parseBoolean(
                        System.getProperty("pathlab.forge.fastProfile.enabled", "false"))) {
            var fastDownsample = QuPathRuntime.fastProfileDownsample(request);
            if (fastDownsample > dataset.downsample()) {
                var previousDownsample = dataset.downsample();
                var fastRequest = new ConversionRequest(
                        Path.of(dataset.sourcePath()),
                        dataset.selectedSeries(),
                        dataset.cropX(),
                        dataset.cropY(),
                        dataset.cropWidth(),
                        dataset.cropHeight(),
                        dataset.width(),
                        dataset.height(),
                        fastDownsample);
                dataset = dataset.withExportConfiguration(
                        DatasetStatus.READY_TO_CONVERT,
                        "Fast one-minute profile adjusted "
                                + previousDownsample
                                + "x to "
                                + fastDownsample
                                + "x for this export area",
                        dataset.selectedSeries(),
                        dataset.width(),
                        dataset.height(),
                        fastDownsample,
                        fastRequest.estimatedRgbPyramidBytes(),
                        dataset.cropX(),
                        dataset.cropY(),
                        dataset.cropWidth(),
                        dataset.cropHeight());
                repository.save(dataset);
                request = fastRequest;
            }
        }
        var reusable = reusableArtifact(dataset, request);
        if (reusable != null) {
            var detail = "Instant cache hit: verified OME-TIFF, DZI and upload package reused";
            progress.put(
                    id,
                    new ConversionProgress(
                            "PACKAGE_COMMITTED",
                            1,
                            1,
                            System.currentTimeMillis(),
                            currentWorkingSet(),
                            org.pathlab.forge.runtime.RuntimeProfile.system().name(),
                            "configuration and source fingerprint matched verified artifact"));
            var cached = reusable.id().equals(dataset.currentArtifactRevision())
                    ? dataset.withConversion(
                            DatasetStatus.PACKAGE_READY,
                            detail,
                            reusable.omePath(),
                            reusable.omeSha256(),
                            dataset.selectedSeries(),
                            dataset.width(),
                            dataset.height(),
                            dataset.downsample(),
                            dataset.estimatedOutputBytes())
                    : dataset.withArtifactRevision(
                            DatasetStatus.PACKAGE_READY,
                            detail,
                            reusable.omePath(),
                            reusable.omeSha256(),
                            reusable.id());
            repository.save(cached);
            return cached;
        }
        if (secondsBudgetEnabled) {
            QuPathRuntime.requireSecondsBudget(
                    request, quPathRuntime.supports(dataset.format()));
        }
        org.pathlab.forge.runtime.ResourceGovernor.system().requireConversionStart();
        Files.createDirectories(managedRoot);
        var peakWorkspace = OutputSizeEstimator.managedPeakWorkspace(
                dataset.cropWidth(),
                dataset.cropHeight(),
                dataset.downsample(),
                dataset.sourceBytes(),
                dataset.format() == DatasetFormat.OME_TIFF);
        DiskPreflight.requireCapacity(
                Files.getFileStore(managedRoot).getUsableSpace(),
                peakWorkspace);
        var resumable = resumableRevision(dataset);
        var revision = resumable.isPresent()
                ? resumable.get()
                : artifactRepository.create(
                        dataset, request.outputWidth(), request.outputHeight());
        var converting = dataset.withArtifactRevision(
                DatasetStatus.CONVERTING,
                revision.id().equals(dataset.currentArtifactRevision())
                        ? "Resuming last verified conversion checkpoint"
                        : quPathRuntime.supports(dataset.format())
                        ? "Direct tiled OME export using "
                                + org.pathlab.forge.runtime.RuntimeProfile.system().name()
                        : useParallelRgb(dataset, request)
                        ? "Lightning RGB: decoding "
                                + parallelRgbWorkers(Path.of(dataset.sourcePath()))
                                + " image regions in parallel with "
                                + org.pathlab.forge.runtime.RuntimeProfile.system().name()
                        : "Exporting QuPath-style rendered RGB with JPEG compression",
                "",
                "",
                revision.id());
        repository.save(converting);
        progress.put(
                id,
                new ConversionProgress(
                        "SOURCE_VERIFIED",
                        1,
                        1,
                        System.currentTimeMillis(),
                        currentWorkingSet(),
                        org.pathlab.forge.runtime.RuntimeProfile.system().name(),
                        resumable.isPresent() ? "verified checkpoint" : ""));
        cancelled.remove(id);
        activeConversions.put(id, conversionExecutor.submit(() -> convert(converting)));
        return converting;
    }

    private java.util.Optional<ArtifactRevision> resumableRevision(LocalDataset dataset)
            throws IOException {
        if (dataset.currentArtifactRevision().isBlank()) {
            return java.util.Optional.empty();
        }
        var revision = artifactRepository
                .find(dataset.id(), dataset.currentArtifactRevision())
                .orElse(null);
        if (revision == null
                || revision.status() == ArtifactRevisionStatus.READY
                || revision.status() == ArtifactRevisionStatus.APPROVED
                || !revision.configurationRevision().equals(dataset.configurationRevision())
                || !revision.sourceFingerprint().equals(dataset.sourceFingerprint())) {
            return java.util.Optional.empty();
        }
        var checkpoint = new StageCheckpointStore(Path.of(revision.omePath()).getParent())
                .load()
                .orElse(null);
        return checkpoint != null
                        && checkpoint.artifactRevisionId().equals(revision.id())
                        && checkpoint.configurationRevision().equals(dataset.configurationRevision())
                        && checkpoint.sourceFingerprint().equals(dataset.sourceFingerprint())
                ? java.util.Optional.of(revision)
                : java.util.Optional.empty();
    }

    private ArtifactRevision reusableArtifact(
            LocalDataset dataset, ConversionRequest request) throws IOException {
        if (!Boolean.parseBoolean(
                System.getProperty("pathlab.forge.artifactReuse.enabled", "true"))) {
            return null;
        }
        if (!dataset.currentArtifactRevision().isBlank()) {
            var current = artifactRepository
                    .find(dataset.id(), dataset.currentArtifactRevision())
                    .orElse(null);
            if (current != null && isReusableArtifact(dataset, request, current)) {
                return current;
            }
        }
        for (var historical : artifactRepository.list(dataset.id())) {
            if (isReusableArtifact(dataset, request, historical)) {
                return historical;
            }
        }
        return null;
    }

    static boolean isReusableArtifact(
            LocalDataset dataset, ConversionRequest request, ArtifactRevision revision)
            throws IOException {
        if (!revision.configurationRevision().equals(dataset.configurationRevision())
                || !revision.sourceFingerprint().equals(dataset.sourceFingerprint())
                || revision.outputWidth() != request.outputWidth()
                || revision.outputHeight() != request.outputHeight()
                || (revision.status() != ArtifactRevisionStatus.READY
                        && revision.status() != ArtifactRevisionStatus.APPROVED)
                || revision.omeSha256().isBlank()
                || revision.packageSha256().isBlank()) {
            return false;
        }
        var ome = Path.of(revision.omePath());
        var preparedPackage = Path.of(revision.packagePath());
        var packageIndex = preparedPackage.resolveSibling(
                preparedPackage.getFileName() + ".index");
        if (!Files.isRegularFile(preparedPackage)
                || !Files.isRegularFile(packageIndex)) {
            return false;
        }
        if (ArtifactIntegrityStamp.matches(revision)) {
            return true;
        }
        var verified = (!Files.exists(ome) || revision.omeSha256().equals(sha256(ome)))
                && revision.packageSha256().equals(sha256(preparedPackage));
        if (verified) {
            ArtifactIntegrityStamp.write(revision);
        }
        return verified;
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
        var regions = outputDirectory.resolve("regions.partial");
        if (Files.isDirectory(regions)) {
            try (var files = Files.list(regions)) {
                for (var partial : files.filter(path -> path.getFileName()
                                .toString()
                                .contains(".partial."))
                        .toList()) {
                    Files.deleteIfExists(partial);
                }
            }
        }
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
        Path regionRoot = null;
        var checkpoints = new StageCheckpointStore(outputDirectory);
        try {
            boolean finalOmeWritten = false;
            var existingCheckpoint = checkpoints.load().orElse(null);
            var matchingCheckpoint = existingCheckpoint != null
                    && existingCheckpoint.configurationRevision()
                            .equals(revision.configurationRevision())
                    && existingCheckpoint.sourceFingerprint()
                            .equals(revision.sourceFingerprint());
            if (!matchingCheckpoint) {
                saveCheckpoint(
                        checkpoints,
                        revision,
                        StageCheckpoint.Stage.SOURCE_VERIFIED,
                        1,
                        1);
            }
            Files.createDirectories(outputDirectory);
            Files.deleteIfExists(partial);
            Files.deleteIfExists(rendered);
            var request = request(dataset);
            String digest;
            var resumeOme = existingCheckpoint != null
                    && existingCheckpoint.configurationRevision()
                            .equals(revision.configurationRevision())
                    && existingCheckpoint.stage().ordinal()
                            >= StageCheckpoint.Stage.OME_VERIFIED.ordinal()
                    && Files.isRegularFile(output);
            if (!resumeOme) {
            if (quPathRuntime.supports(dataset.format())) {
                repository.save(dataset.withConversion(
                        DatasetStatus.OPTIMIZING_OME,
                        "Writing the final OME pyramid directly from bounded source tiles",
                        dataset.outputPath(),
                        dataset.sha256(),
                        dataset.selectedSeries(),
                        dataset.width(),
                        dataset.height(),
                        dataset.downsample(),
                        dataset.estimatedOutputBytes()));
                updateProgress(dataset.id(), "DIRECT_OME", 0, 1);
                var projectedBytes = OutputSizeEstimator.compressedOmeTiff(
                                dataset.cropWidth(),
                                dataset.cropHeight(),
                                dataset.downsample(),
                                dataset.sourceBytes(),
                                false)
                        .expectedBytes();
                quPathRuntime.writePyramidalOme(
                        request,
                        partial,
                        bytes -> updateProgress(
                                dataset.id(),
                                "DIRECT_OME",
                                Math.min(bytes, projectedBytes),
                                projectedBytes));
                finalOmeWritten = true;
            } else if (dataset.format() == DatasetFormat.OME_TIFF
                    && derivativeEngine.supportsOmeRendering()) {
                derivativeEngine.renderOme(request, rendered);
            } else if (useParallelRgb(dataset, request)) {
                regionRoot = outputDirectory.resolve("regions.partial");
                List<Path> regions;
                if (existingCheckpoint != null
                        && existingCheckpoint.stage() == StageCheckpoint.Stage.REGIONS_VERIFIED
                        && existingCheckpoint.configurationRevision()
                                .equals(revision.configurationRevision())
                        && Files.isDirectory(regionRoot)) {
                    try (var files = Files.list(regionRoot)) {
                        regions = files.filter(Files::isRegularFile).sorted().toList();
                    }
                    if (regions.size() < 2) {
                        throw new IOException("Verified region checkpoint is incomplete");
                    }
                } else {
                    var resumePartialRegions = matchingCheckpoint
                            && existingCheckpoint.stage().ordinal()
                                    >= StageCheckpoint.Stage.REGIONS_RENDERING.ordinal();
                    if (!resumePartialRegions) {
                        deleteTree(outputDirectory, regionRoot);
                    }
                    Files.createDirectories(regionRoot);
                    var progressLock = new Object();
                    regions = engine.convertRegions(
                            request,
                            regionRoot,
                            parallelRgbWorkers(request.source()),
                            (completed, total) -> {
                                synchronized (progressLock) {
                                    updateProgress(
                                            dataset.id(),
                                            "REGIONS_RENDERING",
                                            completed,
                                            total);
                                    try {
                                        saveCheckpoint(
                                                checkpoints,
                                                revision,
                                                StageCheckpoint.Stage.REGIONS_RENDERING,
                                                completed,
                                                total);
                                    } catch (IOException error) {
                                        throw new java.io.UncheckedIOException(error);
                                    }
                                }
                            });
                }
                saveCheckpoint(
                        checkpoints,
                        revision,
                        StageCheckpoint.Stage.REGIONS_VERIFIED,
                        regions.size(),
                        regions.size());
                updateProgress(
                        dataset.id(), "REGIONS_VERIFIED", regions.size(), regions.size());
                repository.save(dataset.withConversion(
                        DatasetStatus.CONVERTING,
                        useDirectFinalOme(request)
                                ? "Parallel RGB decode complete; writing final OME pyramid"
                                : "Parallel RGB decode complete; assembling exact slide geometry",
                        dataset.outputPath(),
                        dataset.sha256(),
                        dataset.selectedSeries(),
                        dataset.width(),
                        dataset.height(),
                        dataset.downsample(),
                        dataset.estimatedOutputBytes()));
                updateProgress(dataset.id(), "ASSEMBLING_OME", 0, 1);
                if (useDirectFinalOme(request)) {
                    derivativeEngine.assembleRegionsFinal(
                            regions,
                            partial,
                            request.outputWidth(),
                            request.outputHeight(),
                            request.downsample());
                    finalOmeWritten = true;
                } else {
                    derivativeEngine.assembleRegions(regions, rendered);
                }
            } else {
                engine.convert(request, rendered);
            }
            if (derivativeEngine.available() && !finalOmeWritten) {
                repository.save(dataset.withConversion(
                        DatasetStatus.OPTIMIZING_OME,
                        "Rendered RGB complete; compressing the tiled OME-BigTIFF pyramid",
                        dataset.outputPath(),
                        dataset.sha256(),
                        dataset.selectedSeries(),
                        dataset.width(),
                        dataset.height(),
                        dataset.downsample(),
                        dataset.estimatedOutputBytes()));
                updateProgress(dataset.id(), "OPTIMIZING_OME", 0, 1);
                derivativeEngine.optimizeOme(
                        rendered, partial, request.outputWidth(), request.outputHeight());
                Files.deleteIfExists(rendered);
            } else if (!finalOmeWritten) {
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
            updateProgress(dataset.id(), "VALIDATING_OME", 0, 1);
            verifyTiff(partial);
            derivativeEngine.validateOmeGeometry(
                    partial, request.outputWidth(), request.outputHeight());
            OutputSizeGuard.requireSuitable(
                    Files.size(partial),
                    dataset.sourceBytes(),
                    request.outputWidth(),
                    request.outputHeight(),
                    request.downsample());
            digest = sha256(partial);
            atomicReplace(partial, output);
            saveCheckpoint(
                    checkpoints,
                    revision,
                    StageCheckpoint.Stage.OME_VERIFIED,
                    1,
                    1);
            updateProgress(dataset.id(), "OME_VERIFIED", 1, 1);
            if (regionRoot != null) {
                deleteTree(outputDirectory, regionRoot);
                regionRoot = null;
            }
            } else {
                try {
                    verifyTiff(output);
                    derivativeEngine.validateOmeGeometry(
                            output, request.outputWidth(), request.outputHeight());
                } catch (IOException invalidOme) {
                    var quarantine = outputDirectory.resolve("quarantine");
                    Files.createDirectories(quarantine);
                    Files.move(
                            output,
                            quarantine.resolve(
                                    "invalid-ome-" + System.currentTimeMillis() + ".tif"),
                            StandardCopyOption.REPLACE_EXISTING);
                    var recoverableRegions = outputDirectory.resolve("regions.partial");
                    if (Files.isDirectory(recoverableRegions)) {
                        try (var files = Files.list(recoverableRegions)) {
                            var count = files.filter(Files::isRegularFile).count();
                            if (count >= 2) {
                                saveCheckpoint(
                                        checkpoints,
                                        revision,
                                        StageCheckpoint.Stage.REGIONS_VERIFIED,
                                        count,
                                        count);
                            }
                        }
                    }
                    throw invalidOme;
                }
                digest = sha256(output);
                updateProgress(dataset.id(), "OME_VERIFIED", 1, 1);
            }
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
            updateProgress(dataset.id(), "GENERATING_DZI", 0, 1);
            deleteTree(outputDirectory, derivativePartial);
            var derivativeInfo = derivativeEngine.generateDzi(
                    output,
                    derivativePartial,
                    request.outputWidth(),
                    request.outputHeight(),
                    derivativeProgress -> updateProgress(
                            dataset.id(),
                            derivativeProgress.stage(),
                            derivativeProgress.completedUnits(),
                            derivativeProgress.totalUnits()));
            installDirectory(outputDirectory, derivativePartial, derivative);
            derivativeInfo = new org.pathlab.forge.derivative.DerivativeInfo(
                    derivative,
                    derivativeInfo.bytes(),
                    derivativeInfo.fileCount(),
                    derivativeInfo.tileCount(),
                    derivativeInfo.sha256(),
                    derivativeInfo.ledger(),
                    derivativeInfo.jpegQuality(),
                    derivativeInfo.minimumWindowedSsim(),
                    derivativeInfo.meanDeltaE00(),
                    derivativeInfo.minimumEdgeDetailRetention(),
                    derivativeInfo.encoderProfile());
            saveCheckpoint(
                    checkpoints,
                    revision,
                    StageCheckpoint.Stage.DZI_LEDGER_VERIFIED,
                    derivativeInfo.tileCount(),
                    derivativeInfo.tileCount());
            updateProgress(
                    dataset.id(),
                    "DZI_LEDGER_VERIFIED",
                    derivativeInfo.tileCount(),
                    derivativeInfo.tileCount());
            artifactRepository.save(revision.ready(digest, ""));
            repository.save(dataset.withConversion(
                    DatasetStatus.DZI_READY,
                    derivativeInfo.tileCount()
                            + " validated DZI tiles; result is viewable while the upload package builds",
                    output.toString(),
                    digest,
                    dataset.selectedSeries(),
                    dataset.width(),
                    dataset.height(),
                    dataset.downsample(),
                    dataset.estimatedOutputBytes()));
            updateProgress(
                    dataset.id(), "PACKAGING", 0, derivativeInfo.fileCount());
            var seriesInfo = requireSeriesInfo(dataset.id(), dataset.selectedSeries());
            var packageMetadata = new PackageMetadata(
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
                            "0.1.0-rc");
            var stagingOmeBytes = Files.size(output);
            var predictedPackageBytes = PreparedPackageBuilder.predictBytes(
                    derivativeInfo,
                    request.outputWidth(),
                    request.outputHeight(),
                    packageMetadata,
                    stagingOmeBytes);
            var exceedsSizeReference =
                    predictedPackageBytes > Math.floor(stagingOmeBytes * 1.25d);
            var packageInfo = PreparedPackageBuilder.build(
                    derivativeInfo,
                    request.outputWidth(),
                    request.outputHeight(),
                    packageMetadata,
                    outputDirectory.resolve("slide.plslide"),
                    stagingOmeBytes,
                    (completed, total) -> updateProgress(
                            dataset.id(), "PACKAGING", completed, total));
            saveCheckpoint(
                    checkpoints,
                    revision,
                    StageCheckpoint.Stage.PACKAGE_COMMITTED,
                    derivativeInfo.fileCount(),
                    derivativeInfo.fileCount());
            updateProgress(
                    dataset.id(),
                    "PACKAGE_COMMITTED",
                    derivativeInfo.fileCount(),
                    derivativeInfo.fileCount());
            var readyRevision = revision.ready(digest, packageInfo.sha256());
            artifactRepository.save(readyRevision);
            ArtifactIntegrityStamp.write(readyRevision);
            Files.deleteIfExists(output);
            deleteTree(outputDirectory, derivative);
            repository.save(dataset.withConversion(
                    DatasetStatus.PACKAGE_READY,
                    derivativeInfo.tileCount() + " DZI tiles · "
                            + derivativeInfo.fileCount() + " files · "
                            + derivativeInfo.bytes() + " derivative bytes · "
                            + packageInfo.bytes() + " package bytes · "
                            + String.format(
                                    java.util.Locale.ROOT,
                                    "%.1f%% of staging OME",
                                    packageInfo.bytes() * 100.0 / stagingOmeBytes)
                            + (exceedsSizeReference
                                    ? "; exceeds the 1.25x size reference; review before approval"
                                    : "; compact DZI package ready"),
                    packageInfo.path().toString(),
                    digest,
                    dataset.selectedSeries(),
                    dataset.width(),
                    dataset.height(),
                    dataset.downsample(),
                    dataset.estimatedOutputBytes()));
        } catch (Exception error) {
            var failure = concise(error.getMessage());
            var wasCancelled = cancelled.remove(dataset.id());
            cleanupFailedOutput(partial);
            cleanupFailedOutput(rendered);
            try {
                deleteTree(outputDirectory, derivativePartial);
            } catch (IOException ignored) {
                // Status persistence must not depend on immediate Windows handle release.
            }
            try {
                if (!wasCancelled) {
                    artifactRepository.save(revision.failed(concise(error.getMessage())));
                }
            } catch (IOException ignored) {
                // Dataset status below remains the authoritative user-visible failure.
            }
            try {
                if (wasCancelled) {
                    repository.save(dataset.withPreparation(
                            DatasetStatus.CANCELLED,
                            "Conversion cancelled; incomplete output cleanup is pending",
                            dataset.outputPath(),
                            dataset.sha256()));
                } else {
                    repository.save(dataset.withConversion(
                            DatasetStatus.FAILED,
                            failure,
                            dataset.outputPath(),
                            dataset.sha256(),
                            dataset.selectedSeries(),
                            dataset.width(),
                            dataset.height(),
                            dataset.downsample(),
                            dataset.estimatedOutputBytes()));
                }
            } catch (IOException ignored) {
                // The executor cannot recover from a repository write failure.
            }
        } finally {
            if (regionRoot != null && cancelled.contains(dataset.id())) {
                try {
                    deleteTree(outputDirectory, regionRoot);
                } catch (IOException ignored) {
                    // A cancelled Bio-Formats process may release its final handle shortly.
                }
            }
            activeConversions.remove(dataset.id());
        }
    }

    private static void cleanupFailedOutput(Path path) {
        for (var attempt = 0; attempt < 20; attempt++) {
            try {
                if (Files.deleteIfExists(path) || !Files.exists(path)) {
                    return;
                }
            } catch (IOException ignored) {
                // A terminated child may retain a Windows file handle briefly.
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void saveCheckpoint(
            StageCheckpointStore store,
            ArtifactRevision revision,
            StageCheckpoint.Stage stage,
            long completedUnits,
            long totalUnits)
            throws IOException {
        store.save(new StageCheckpoint(
                revision.id(),
                revision.configurationRevision(),
                revision.sourceFingerprint(),
                stage,
                completedUnits,
                totalUnits,
                System.currentTimeMillis()));
    }

    private void updateProgress(String id, String stage, long completed, long total) {
        progress.compute(id, (ignored, current) -> {
            var now = System.currentTimeMillis();
            var sameStage = current != null && current.stage().equals(stage);
            return new ConversionProgress(
                    stage,
                    completed,
                    total,
                    current == null ? now : current.startedAt(),
                    sameStage ? current.stageStartedAt() : now,
                    Math.max(
                            current == null ? 0 : current.peakWorkingSetBytes(),
                            currentWorkingSet()),
                    org.pathlab.forge.runtime.RuntimeProfile.system().name(),
                    current == null ? "" : current.cacheHitReason());
        });
    }

    private static long currentWorkingSet() {
        return org.pathlab.forge.runtime.ProcessTreeMemory.workingSetBytes();
    }

    private void sampleActiveMemory() {
        if (activeConversions.isEmpty()) {
            return;
        }
        var workingSet = currentWorkingSet();
        progress.replaceAll((ignored, current) -> new ConversionProgress(
                current.stage(),
                current.completedUnits(),
                current.totalUnits(),
                current.startedAt(),
                current.stageStartedAt(),
                Math.max(current.peakWorkingSetBytes(), workingSet),
                current.resourceProfile(),
                current.cacheHitReason()));
    }

    private boolean useDirectFinalOme(ConversionRequest request) {
        return derivativeEngine.supportsDirectFinalOme()
                && (request.downsample() == 1.0
                        || Boolean.getBoolean(
                                "pathlab.forge.directFinalDownsample")
                        || Boolean.getBoolean(
                                "pathlab.forge.experimentalNativeFallback"));
    }

    private boolean useParallelRgb(LocalDataset dataset, ConversionRequest request) {
        return shouldUseParallelRgb(
                dataset.format(),
                engine.supportsParallelRegions(),
                derivativeEngine.available(),
                parallelRgbWorkers(request.source()),
                request);
    }

    static boolean shouldUseParallelRgb(
            DatasetFormat format,
            boolean engineSupportsParallelRegions,
            boolean derivativeAvailable,
            int workers,
            ConversionRequest request) {
        return format == DatasetFormat.VSI
                && engineSupportsParallelRegions
                && derivativeAvailable
                && workers >= 2
                && (long) request.outputWidth() * request.outputHeight()
                        >= PARALLEL_RGB_MINIMUM_PIXELS;
    }

    private static int parallelRgbWorkers(Path source) {
        var profile = org.pathlab.forge.runtime.RuntimeProfile.system();
        var configured = Integer.getInteger(
                "pathlab.forge.rgb.workers", profile.maxConversionWorkers());
        var slowSource = Boolean.getBoolean("pathlab.forge.source.slow")
                || source.toString().startsWith("\\\\");
        return parallelRgbWorkers(
                Runtime.getRuntime().availableProcessors(),
                configured,
                slowSource,
                profile.maxConversionWorkers());
    }

    static int parallelRgbWorkers(
            int availableProcessors, int configuredWorkers, boolean slowSource) {
        return parallelRgbWorkers(availableProcessors, configuredWorkers, slowSource, 5);
    }

    static int parallelRgbWorkers(
            int availableProcessors,
            int configuredWorkers,
            boolean slowSource,
            int profileWorkers) {
        var processorLimit = Math.min(
                Math.max(1, availableProcessors - 1),
                org.pathlab.forge.runtime.RuntimeProfile.effectiveCpuParallelism(
                        availableProcessors));
        var profileLimit = slowSource ? 2 : Math.max(1, profileWorkers);
        return Math.max(1, Math.min(Math.min(configuredWorkers, profileLimit), processorLimit));
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
        if (dataset.sourceFingerprint().isBlank() || dataset.sourceInventory().isBlank()) {
            throw new IllegalStateException("Reinspect the source before conversion");
        }
        if (!DatasetSourceInventory.matchesSnapshot(
                Path.of(dataset.sourcePath()), dataset.sourceInventory())) {
            throw new IllegalStateException(
                    "Source or companion files changed; reinspect before conversion");
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
        memorySampler.shutdownNow();
        for (var key : List.copyOf(readerSessions.keySet())) {
            try {
                evictReaderSession(key);
            } catch (IOException ignored) {
                // Shutdown remains best-effort after all bounded caches are cleared.
            }
        }
        try {
            engine.close();
        } catch (IOException ignored) {
            // The process is shutting down; child containment remains authoritative.
        }
    }
}

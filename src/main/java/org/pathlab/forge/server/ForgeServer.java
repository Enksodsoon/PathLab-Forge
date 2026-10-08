package org.pathlab.forge.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import org.pathlab.forge.annotation.AnnotationRecord;
import org.pathlab.forge.annotation.AnnotationRepository;
import org.pathlab.forge.analysis.GeometryMeasurements;
import org.pathlab.forge.analysis.AnalysisRun;
import org.pathlab.forge.analysis.AnalysisReview;
import org.pathlab.forge.analysis.DeterministicAnalysisService;
import org.pathlab.forge.conversion.CompositeConversionEngine;
import org.pathlab.forge.conversion.ConversionEngine;
import org.pathlab.forge.conversion.ConversionService;
import org.pathlab.forge.conversion.SeriesInfo;
import org.pathlab.forge.derivative.DerivativeEngine;
import org.pathlab.forge.derivative.VipsRuntime;
import org.pathlab.forge.feature.CapabilityRegistry;
import org.pathlab.forge.feature.FeaturePackDescriptor;
import org.pathlab.forge.feature.FeaturePackManager;
import org.pathlab.forge.library.DatasetInspectionException;
import org.pathlab.forge.library.DatasetInspector;
import org.pathlab.forge.library.DatasetPicker;
import org.pathlab.forge.library.DatasetPreparationService;
import org.pathlab.forge.library.DatasetRepository;
import org.pathlab.forge.library.ForgePaths;
import org.pathlab.forge.library.LocalDataset;
import org.pathlab.forge.library.ProjectFolderScanner;
import org.pathlab.forge.library.SqliteDatasetRepository;
import org.pathlab.forge.library.SwingDatasetPicker;
import org.pathlab.forge.library.SourceVerificationService;
import org.pathlab.forge.viewer.ViewerConnection;
import org.pathlab.forge.viewer.ViewerPairingService;
import org.pathlab.forge.viewer.SqliteViewerDeliveryStore;
import org.pathlab.forge.viewer.SqliteViewerSyncStore;
import org.pathlab.forge.viewer.ViewerSyncService;
import org.pathlab.forge.viewer.ViewerTileCache;
import org.pathlab.forge.viewer.ViewerUploadStatus;
import org.pathlab.forge.viewer.CredentialStore;
import org.pathlab.forge.reader.UniversalDatasetImporter;
import org.pathlab.forge.reader.ImportDiagnostic;
import org.pathlab.forge.reader.AxisMode;
import org.pathlab.forge.reader.AxisSelection;
import org.pathlab.forge.reader.ChannelRender;
import org.pathlab.forge.reader.RenderProfile;
import org.pathlab.forge.reader.ViewDefinition;
import org.pathlab.forge.runtime.ReaderRuntimeInventory;

public final class ForgeServer implements AutoCloseable {
    private static final int MAX_WRITE_BYTES = 65_536;
    private static final int DEFAULT_DESKTOP_PORT = 51_274;
    private static final long SESSION_MAX_AGE_SECONDS = 315_360_000L;
    private final HttpServer server;
    private final ExecutorService executor;
    private final String launchToken;
    private final String sessionToken;
    private final String csrfToken;
    private final String desktopSecret = LocalBrowserSession.randomToken();
    private final java.util.Map<String, java.util.Map<Path, Long>> desktopSelections =
            new java.util.HashMap<>();
    private final URI baseUri;
    private volatile org.pathlab.forge.runtime.ManagedStorageUsage managedUsage;
    private java.util.concurrent.CompletableFuture<Void> managedUsageScan;
    private long managedUsageScanStarted;

    private synchronized void refreshManagedUsage() {
        var now = System.currentTimeMillis();
        if (managedUsageScan == null || (managedUsageScan.isDone() && now - managedUsageScanStarted > 60_000)) {
            managedUsageScanStarted = now;
            managedUsageScan = java.util.concurrent.CompletableFuture.runAsync(() -> managedUsage = org.pathlab.forge.runtime.ManagedStorageUsage.measure(dataRoot), executor);
        }
    }
    private final DatasetRepository repository;
    private final DatasetPicker picker;
    private final DatasetInspector inspector = new DatasetInspector();
    private final DatasetPreparationService preparationService;
    private final SourceVerificationService sourceVerificationService;
    private final ConversionService conversionService;
    private final DerivativeEngine derivativeEngine;
    private final AnnotationRepository annotationRepository;
    private final FeaturePackManager featurePackManager;
    private final CapabilityRegistry capabilityRegistry;
    private final DeterministicAnalysisService analysisService;
    private final VerifiedExportService exportService = new VerifiedExportService();
    private final org.pathlab.forge.study.StudyAuthoringService studyService;
    private final org.pathlab.forge.batch.BatchService batchService;
    private final ViewerPairingService viewerPairingService;
    private final ViewerSyncService viewerSyncService;
    private final ViewerTileCache viewerTileCache;
    private final UniversalDatasetImporter universalDatasetImporter;
    private final boolean universalReaderAvailable;
    private final Path dataRoot;
    private final java.util.concurrent.atomic.AtomicBoolean launchTokenAvailable =
            new java.util.concurrent.atomic.AtomicBoolean(true);

    private ForgeServer(
            HttpServer server,
            ExecutorService executor,
            DatasetRepository repository,
            DatasetPicker picker,
            Path managedRoot,
            ConversionEngine conversionEngine,
            DerivativeEngine derivativeEngine,
            String sessionToken) throws IOException {
        this.server = server;
        this.executor = executor;
        launchToken = LocalBrowserSession.randomToken();
        this.sessionToken = sessionToken;
        csrfToken = LocalBrowserSession.randomToken();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        this.repository = repository;
        this.picker = picker;
        this.derivativeEngine = derivativeEngine;
        preparationService = new DatasetPreparationService(repository, managedRoot);
        sourceVerificationService = new SourceVerificationService(repository);
        conversionService =
                new ConversionService(repository, conversionEngine, derivativeEngine, managedRoot, true);
        universalDatasetImporter = new UniversalDatasetImporter(repository,
                source -> probeDataset(conversionEngine, derivativeEngine, source));
        universalReaderAvailable = conversionEngine.available();
        annotationRepository = new AnnotationRepository(managedRoot);
        studyService = new org.pathlab.forge.study.StudyAuthoringService(managedRoot);
        featurePackManager = new FeaturePackManager(managedRoot.toAbsolutePath().normalize().getParent());
        capabilityRegistry = new CapabilityRegistry(featurePackManager);
        analysisService = new DeterministicAnalysisService(
                repository, annotationRepository, managedRoot, conversionService::readRgbRegion,
                () -> conversionService.activeConversionCount() > 0,
                tool -> featurePackManager.isInstalled(java.util.Set.of("he", "stain_vector", "normalize_preview", "tma")
                        .contains(tool) ? "pathology-tools" : "classical-analysis"));
        viewerPairingService = new ViewerPairingService(
                CredentialStore.platformDefault(),
                new SqliteViewerDeliveryStore(
                        managedRoot.toAbsolutePath().normalize().getParent().resolve("forge.db")));
        featurePackManager.setViewerTransport(viewerPairingService);
        viewerPairingService.setAcceptedAnalysisProvider(revision -> {
            var dataset = repository.find(revision.datasetId()).orElseThrow(() -> new IOException("Analysis source is unavailable"));
            if (!dataset.configurationRevision().equals(revision.configurationRevision())
                    || !dataset.sourceFingerprint().equals(revision.sourceFingerprint())) {
                throw new IOException("Select and approve the current source/view before delivering its results");
            }
            var scope = annotationScope(dataset);
            var accepted = new java.util.ArrayList<org.pathlab.forge.viewer.PrivateResultsBundleBuilder.AcceptedAnalysis>();
            for (var run : analysisService.list(dataset.id())) {
                if (!"SUCCEEDED".equals(run.status()) || run.stale() || run.provenance().series() != scope.series()
                        || run.provenance().z() != scope.z() || run.provenance().t() != scope.t()
                        || !run.provenance().viewRevision().equals(scope.viewRevision())) continue;
                var review = analysisService.review(run.id());
                if (review.revision() > 0) accepted.add(new org.pathlab.forge.viewer.PrivateResultsBundleBuilder.AcceptedAnalysis(run, review, scope.viewRevision()));
            }
            return List.copyOf(accepted);
        });
        dataRoot = managedRoot.toAbsolutePath().normalize().getParent();
        viewerSyncService = new ViewerSyncService(
                viewerPairingService, new SqliteViewerSyncStore(dataRoot.resolve("viewer-sync.db")),
                dataRoot.resolve("viewer-offline"));
        viewerTileCache = new ViewerTileCache(viewerPairingService, dataRoot.resolve("viewer-cache"));
        batchService = new org.pathlab.forge.batch.BatchService(repository, conversionService,
                new org.pathlab.forge.batch.BatchStore(dataRoot.resolve("forge.db")), artifactId -> {
                    try {
                        return viewerPairingService.deliveryForArtifact(artifactId).map(job ->
                                new org.pathlab.forge.batch.BatchService.Delivery(job.state().name(),
                                        switch (job.state()) {
                                            case COMPLETE -> "No action required";
                                            case IMAGE_READY, SYNCING_RESULTS -> "Wait for structured results to finish";
                                            case FAILED, PAUSED, CANCELLED -> "Reconnect and retry this verified artifact's delivery";
                                            default -> "Wait for the current delivery";
                                        }, job.detail())).orElse(new org.pathlab.forge.batch.BatchService.Delivery(
                                                "NOT_SENT", "Select this verified artifact for Viewer delivery", "No delivery for this artifact in the current connection"));
                    } catch (IOException error) {
                        return new org.pathlab.forge.batch.BatchService.Delivery("UNAVAILABLE", "Reconnect to inspect delivery", "Current connection delivery status is unavailable");
                    }
                });
        batchService.recoverPending();
        conversionService.resumeQueueDispatch();
    }

    public static ForgeServer start() throws IOException {
        var paths = ForgePaths.defaults();
        return start(
                paths,
                new SqliteDatasetRepository(
                        paths.repositoryFile(),
                        paths.dataRoot().resolve("library.properties")));
    }

    public static ForgeServer start(ForgePaths paths, DatasetRepository repository)
            throws IOException {
        if (Boolean.getBoolean("pathlab.forge.runtime.requireProduction")
                && org.pathlab.forge.runtime.ReaderRuntimeLocator.activeRoot(paths.dataRoot()).isEmpty()) {
            throw new IOException("The installed production reader bundle is missing, altered or unapproved. Reinstall Forge.");
        }
        var desktop = Boolean.getBoolean("pathlab.forge.desktop");
        var configuredPort = Integer.getInteger("pathlab.forge.port", desktop ? 0 : DEFAULT_DESKTOP_PORT);
        return startConfigured(
                repository,
                desktop ? () -> { throw new IOException("Use the desktop native file dialog"); }
                        : new SwingDatasetPicker(),
                paths.managedRoot(),
                CompositeConversionEngine.discover(paths.dataRoot()),
                VipsRuntime.discover(paths.dataRoot()),
                configuredPort,
                desktop ? LocalBrowserSession.randomToken() : LocalBrowserSession.loadOrCreate(
                        paths.dataRoot().resolve("browser-session.token")));
    }

    public static ForgeServer start(
            DatasetRepository repository, DatasetPicker picker, Path managedRoot)
            throws IOException {
        return start(
                repository,
                picker,
                managedRoot,
                CompositeConversionEngine.discover(managedRoot.toAbsolutePath().normalize().getParent()),
                VipsRuntime.discover(managedRoot.toAbsolutePath().normalize().getParent()));
    }

    public static ForgeServer start(
            DatasetRepository repository,
            DatasetPicker picker,
            Path managedRoot,
            ConversionEngine conversionEngine)
            throws IOException {
        return start(
                repository,
                picker,
                managedRoot,
                conversionEngine,
                VipsRuntime.discover(managedRoot.toAbsolutePath().normalize().getParent()));
    }

    public static ForgeServer start(
            DatasetRepository repository,
            DatasetPicker picker,
            Path managedRoot,
            ConversionEngine conversionEngine,
            DerivativeEngine derivativeEngine)
            throws IOException {
        var configuredPort = Integer.getInteger("pathlab.forge.port", 0);
        return startConfigured(
                repository,
                picker,
                managedRoot,
                conversionEngine,
                derivativeEngine,
                configuredPort,
                LocalBrowserSession.randomToken());
    }

    static ForgeServer startOnPort(
            DatasetRepository repository,
            DatasetPicker picker,
            Path managedRoot,
            int port,
            String sessionToken)
            throws IOException {
        var runtimeRoot = managedRoot.toAbsolutePath().normalize().getParent();
        return startConfigured(
                repository,
                picker,
                managedRoot,
                CompositeConversionEngine.discover(runtimeRoot),
                VipsRuntime.discover(runtimeRoot),
                port,
                sessionToken);
    }

    private static ForgeServer startConfigured(
            DatasetRepository repository,
            DatasetPicker picker,
            Path managedRoot,
            ConversionEngine conversionEngine,
            DerivativeEngine derivativeEngine,
            int port,
            String sessionToken)
            throws IOException {
        var address = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        var httpServer = HttpServer.create(address, 32);
        var executor = Executors.newFixedThreadPool(
                recommendedHttpWorkers(
                        org.pathlab.forge.runtime.RuntimeProfile.configuredLogicalProcessors()),
                runnable -> {
            var thread = new Thread(runnable, "pathlab-forge-http");
            thread.setDaemon(true);
            return thread;
        });
        var forgeServer =
                new ForgeServer(
                        httpServer,
                        executor,
                        repository,
                        picker,
                        managedRoot,
                        conversionEngine,
                        derivativeEngine,
                        sessionToken);
        httpServer.createContext("/", forgeServer::handle);
        httpServer.setExecutor(executor);
        httpServer.start();
        forgeServer.resumePendingViewerDelivery();
        return forgeServer;
    }

    private void resumePendingViewerDelivery() {
        for (var dataset : repository.list()) {
            try {
                var revision = conversionService.approvedRevision(dataset.id());
                if (!viewerPairingService.hasResumableDelivery(revision.id())) {
                    continue;
                }
                startArtifactUpload(dataset, revision, revision.format() == org.pathlab.forge.conversion.ArtifactRevisionFormat.PREPARED_DZI_V2);
                return;
            } catch (IOException | IllegalStateException ignored) {
                // Persisted state remains resumable; the UI exposes the paused reason.
            }
        }
    }

    private ViewerUploadStatus startArtifactUpload(LocalDataset dataset, org.pathlab.forge.conversion.ArtifactRevision revision, boolean teaching) throws IOException {
        if (teaching) return viewerPairingService.startTeachingUpload(dataset.displayName(), revision, annotationsForCurrentView(dataset), dataset.cropX(), dataset.cropY(), dataset.cropWidth(), dataset.cropHeight(), dataset.downsample());
        return viewerPairingService.startUpload(
                        dataset.displayName(), revision, annotationsForCurrentView(dataset),
                        dataset.cropX(), dataset.cropY(), dataset.cropWidth(), dataset.cropHeight(),
                        dataset.downsample());
    }

    static int recommendedHttpWorkers(int logicalProcessors) {
        if (logicalProcessors < 1) {
            throw new IllegalArgumentException("Detected CPU capacity is invalid");
        }
        return Math.max(2, Math.min(8, logicalProcessors));
    }

    public URI baseUri() {
        return baseUri;
    }

    public URI launchUri() {
        return URI.create(baseUri + "/?launchToken=" + launchToken);
    }

    public URI appUri() {
        return baseUri.resolve("/app");
    }

    /** Sent only over the private desktop process pipe, never to the renderer. */
    public String desktopSecret() { return desktopSecret; }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            addSecurityHeaders(exchange);
            if ("/api/desktop/lifecycle".equals(exchange.getRequestURI().getPath())) {
                desktopLifecycle(exchange);
                return;
            }
            if ("/api/desktop/selections".equals(exchange.getRequestURI().getPath())) {
                approveDesktopSelections(exchange);
                return;
            }
            var path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) {
                bootstrap(exchange);
            } else if ("/app".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                appResource(exchange);
            } else if ("/assets/app.css".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                authenticatedResource(exchange, "/web/app.css", "text/css; charset=utf-8");
            } else if ("/assets/app.js".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                authenticatedResource(
                        exchange, "/web/app.js", "text/javascript; charset=utf-8");
            } else if (path.matches("/assets/chunk-[A-Za-z0-9._-]+\\.js")
                    && "GET".equals(exchange.getRequestMethod())) {
                authenticatedResource(
                        exchange,
                        "/web/" + path.substring("/assets/".length()),
                        "text/javascript; charset=utf-8");
            } else if ("/assets/openseadragon.min.js".equals(path)
                    && "GET".equals(exchange.getRequestMethod())) {
                authenticatedResource(
                        exchange,
                        "/web/openseadragon.min.js",
                        "text/javascript; charset=utf-8");
            } else if ("/api/session".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                session(exchange);
            } else if ("/api/capabilities".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                capabilities(exchange);
            } else if ("/api/queue".equals(path)
                    && ("GET".equals(exchange.getRequestMethod()) || "POST".equals(exchange.getRequestMethod()))) {
                conversionQueue(exchange);
            } else if ("/api/exports".equals(path) || "/api/exports/cancel".equals(path)) {
                nativeExport(exchange, path);
            } else if ("/api/features".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                features(exchange);
            } else if ("/api/features/progress".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                if (requireAuthenticated(exchange)) respond(exchange, 200, "application/json",
                        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(featurePackManager.progress()));
            } else if (path.matches("/api/features/[a-z0-9][a-z0-9-]{1,63}/(enable|activate|rollback|cancel)")
                    && "POST".equals(exchange.getRequestMethod())) {
                featureAction(exchange, path);
            } else if (path.matches("/api/features/[a-z0-9][a-z0-9-]{1,63}/install")
                    && "POST".equals(exchange.getRequestMethod())) {
                installFeature(exchange, path);
            } else if (path.matches("/api/features/[a-z0-9][a-z0-9-]{1,63}/disable")
                    && "POST".equals(exchange.getRequestMethod())) {
                disableFeature(exchange, path);
            } else if (path.matches("/api/features/[a-z0-9][a-z0-9-]{1,63}")
                    && "DELETE".equals(exchange.getRequestMethod())) {
                uninstallFeature(exchange, path);
            } else if (path.equals("/api/study/drafts") || path.equals("/api/study/import") || path.equals("/api/study/viewer/slides")
                    || path.matches("/api/study/drafts/[0-9a-fA-F-]{36}(/duplicate|/history|/recover|/preview|/review|/approve|/questions|/export|/publish)?")) {
                studyAuthoring(exchange, path);
            } else if (path.equals("/api/analysis/runs") || path.matches("/api/analysis/runs/[0-9a-fA-F-]{36}(/cancel|/export|/review|/cores|/core-analysis)?")) {
                analysisRuns(exchange, path);
            } else if ("/api/analysis/jobs".equals(path)
                    && "POST".equals(exchange.getRequestMethod())) {
                createAnalysisJob(exchange);
            } else if (path.matches("/api/analysis/jobs/[0-9a-fA-F-]{36}")
                    && "GET".equals(exchange.getRequestMethod())) {
                analysisJob(exchange, path);
            } else if (path.matches("/api/analysis/jobs/[0-9a-fA-F-]{36}/cancel")
                    && "POST".equals(exchange.getRequestMethod())) {
                cancelAnalysisJob(exchange, path);
            } else if ("/api/viewer/connection".equals(path)
                    && "GET".equals(exchange.getRequestMethod())) {
                viewerConnection(exchange);
            } else if ("/api/viewer/pairing/start".equals(path)
                    && "POST".equals(exchange.getRequestMethod())) {
                startViewerPairing(exchange);
            } else if ("/api/viewer/pairing/exchange".equals(path)
                    && "POST".equals(exchange.getRequestMethod())) {
                exchangeViewerPairing(exchange);
            } else if ("/api/viewer/connection/revoke".equals(path)
                    && "POST".equals(exchange.getRequestMethod())) {
                revokeViewerConnection(exchange);
            } else if ("/api/viewer/upload".equals(path)
                    && "GET".equals(exchange.getRequestMethod())) {
                viewerUploadStatus(exchange);
            } else if ("/api/viewer/upload/cancel".equals(path)
                    && "POST".equals(exchange.getRequestMethod())) {
                cancelViewerUpload(exchange);
            } else if ("/api/viewer/library".equals(path)
                    && "GET".equals(exchange.getRequestMethod())) {
                viewerLibrary(exchange);
            } else if ("/api/viewer/sync".equals(path)
                    && "POST".equals(exchange.getRequestMethod())) {
                syncViewerLibrary(exchange);
            } else if ("/api/viewer/preview".equals(path)
                    && "GET".equals(exchange.getRequestMethod())) {
                viewerPreview(exchange);
            } else if (path.matches("/api/viewer/slides/[A-Za-z0-9_-]{1,128}/preview/.+")
                    && "GET".equals(exchange.getRequestMethod())) {
                viewerSlidePreview(exchange, path);
            } else if (path.matches("/api/viewer/slides/[A-Za-z0-9_-]{1,128}/offline")
                    && "POST".equals(exchange.getRequestMethod())) {
                keepViewerSlideOffline(exchange, path);
            } else if (path.matches("/api/viewer/slides/[A-Za-z0-9_-]{1,128}/offline")
                    && "DELETE".equals(exchange.getRequestMethod())) {
                removeViewerSlideOffline(exchange, path);
            } else if (path.matches("/api/viewer/slides/[A-Za-z0-9_-]{1,128}/offline/cancel")
                    && "POST".equals(exchange.getRequestMethod())) {
                if (requireWrite(exchange)) {
                    viewerSyncService.cancelOffline(path.substring("/api/viewer/slides/".length(), path.length() - "/offline/cancel".length()));
                    respond(exchange, 202, "application/json", "{\"state\":\"CANCELLING\"}");
                }
            } else if (path.matches("/api/viewer/slides/[A-Za-z0-9_-]{1,128}/metadata")
                    && "POST".equals(exchange.getRequestMethod())) {
                updateViewerSlideMetadata(exchange, path);
            } else if (path.matches("/api/viewer/slides/[A-Za-z0-9_-]{1,128}/annotations")
                    && "GET".equals(exchange.getRequestMethod())) {
                viewerSlideAnnotations(exchange, path, false);
            } else if (path.matches("/api/viewer/slides/[A-Za-z0-9_-]{1,128}/annotations")
                    && "POST".equals(exchange.getRequestMethod())) {
                viewerSlideAnnotations(exchange, path, true);
            } else if (path.matches("/api/viewer/conflicts/[A-Za-z0-9_-]{1,128}/resolve")
                    && "POST".equals(exchange.getRequestMethod())) {
                resolveViewerConflict(exchange, path);
            } else if ("/api/datasets".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                listDatasets(exchange);
            } else if ("/api/v2/desktop/formats".equals(path)
                    && "GET".equals(exchange.getRequestMethod())) {
                formats(exchange);
            } else if ("/api/local-files".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                browseLocalFiles(exchange);
            } else if ("/api/datasets/select".equals(path)
                    && "POST".equals(exchange.getRequestMethod())) {
                selectDatasets(exchange);
            } else if ("/api/datasets/import".equals(path)
                    && "POST".equals(exchange.getRequestMethod())) {
                importDataset(exchange);
            } else if ("/api/v2/desktop/imports".equals(path)
                    && "POST".equals(exchange.getRequestMethod())) {
                importDatasetsV2(exchange);
            } else if (path.matches("/api/v2/desktop/datasets/[^/]+/images")
                    && "GET".equals(exchange.getRequestMethod())) {
                datasetImagesV2(exchange, path.substring(
                        "/api/v2/desktop/datasets/".length(), path.length() - "/images".length()));
            } else if (path.matches("/api/v2/desktop/datasets/[^/]+/view")
                    && "PUT".equals(exchange.getRequestMethod())) {
                updateDatasetViewV2(exchange, path.substring(
                        "/api/v2/desktop/datasets/".length(), path.length() - "/view".length()));
            } else if (path.matches(
                    "/api/v2/desktop/datasets/[^/]+/views/[a-f0-9]{64}/(?:slide\\.dzi|slide_files/\\d+/\\d+_\\d+\\.jpg)")
                    && "GET".equals(exchange.getRequestMethod())) {
                serveDatasetViewV2(exchange, path);
            } else if ("/api/v2/desktop/projects/import-folder".equals(path)
                    && "POST".equals(exchange.getRequestMethod())) {
                importProjectFolder(exchange);
            } else if (path.matches("/api/datasets/[^/]+/prepare")
                    && "POST".equals(exchange.getRequestMethod())) {
                prepareDataset(exchange, path.substring("/api/datasets/".length(), path.length() - "/prepare".length()));
            } else if (path.matches("/api/datasets/[^/]+/inspect")
                    && "POST".equals(exchange.getRequestMethod())) {
                inspectDataset(
                        exchange,
                        path.substring(
                                "/api/datasets/".length(), path.length() - "/inspect".length()));
            } else if (path.matches("/api/datasets/[^/]+/series")
                    && "GET".equals(exchange.getRequestMethod())) {
                datasetSeries(
                        exchange,
                        path.substring(
                                "/api/datasets/".length(), path.length() - "/series".length()));
            } else if (path.matches("/api/datasets/[^/]+/series/\\d+/thumbnail")
                    && "GET".equals(exchange.getRequestMethod())) {
                seriesThumbnail(exchange, path);
            } else if (path.matches("/api/datasets/[^/]+/estimate")
                    && "GET".equals(exchange.getRequestMethod())) {
                estimateDataset(
                        exchange,
                        path.substring(
                                "/api/datasets/".length(), path.length() - "/estimate".length()));
            } else if (path.matches("/api/datasets/[^/]+/series")
                    && "POST".equals(exchange.getRequestMethod())) {
                selectSeries(
                        exchange,
                        path.substring(
                                "/api/datasets/".length(), path.length() - "/series".length()));
            } else if (path.matches("/api/datasets/[^/]+/convert")
                    && "POST".equals(exchange.getRequestMethod())) {
                convertDataset(
                        exchange,
                        path.substring(
                                "/api/datasets/".length(), path.length() - "/convert".length()));
            } else if (path.matches("/api/datasets/[^/]+/teaching") && "POST".equals(exchange.getRequestMethod())) {
                convertDataset(exchange, path.substring("/api/datasets/".length(), path.length() - "/teaching".length()), true);
            } else if (path.matches("/api/datasets/[^/]+/cancel")
                    && "POST".equals(exchange.getRequestMethod())) {
                cancelConversion(
                        exchange,
                        path.substring(
                                "/api/datasets/".length(), path.length() - "/cancel".length()));
            } else if (path.matches("/api/datasets/[^/]+/artifacts")
                    && "GET".equals(exchange.getRequestMethod())) {
                datasetArtifacts(
                        exchange,
                        path.substring(
                                "/api/datasets/".length(), path.length() - "/artifacts".length()));
            } else if (path.matches("/api/datasets/[^/]+/artifacts/[^/]+/approve")
                    && "POST".equals(exchange.getRequestMethod())) {
                approveArtifact(exchange, path);
            } else if (path.matches("/api/datasets/[^/]+/artifacts/[^/]+/rename")
                    && "POST".equals(exchange.getRequestMethod())) {
                renameArtifact(exchange, path);
            } else if (path.matches("/api/datasets/[^/]+/artifacts/[^/]+/ome-preview/.+")
                    && "GET".equals(exchange.getRequestMethod())) {
                artifactOmePreviewResource(exchange, path);
            } else if (path.matches("/api/datasets/[^/]+/artifacts/[^/]+/derivative/.+")
                    && "GET".equals(exchange.getRequestMethod())) {
                artifactDerivativeResource(exchange, path);
            } else if (path.matches("/api/datasets/[^/]+/artifacts/[^/]+/package")
                    && "GET".equals(exchange.getRequestMethod())) {
                artifactPackageResource(exchange, path);
            } else if (path.matches("/api/datasets/[^/]+/artifacts/[^/]+")
                    && "DELETE".equals(exchange.getRequestMethod())) {
                deleteArtifact(exchange, path);
            } else if (path.matches("/api/datasets/[^/]+/derivative/.+")
                    && "GET".equals(exchange.getRequestMethod())) {
                derivativeResource(exchange, path);
            } else if (path.matches("/api/datasets/[^/]+/preview/.+")
                    && "GET".equals(exchange.getRequestMethod())) {
                previewResource(exchange, path);
            } else if (path.matches("/api/datasets/[^/]+/package")
                    && "GET".equals(exchange.getRequestMethod())) {
                packageResource(
                        exchange,
                        path.substring(
                                "/api/datasets/".length(), path.length() - "/package".length()));
            } else if (path.matches("/api/datasets/[^/]+/teaching-upload") && "POST".equals(exchange.getRequestMethod())) {
                uploadApprovedArtifact(exchange, path.substring("/api/datasets/".length(), path.length() - "/teaching-upload".length()), true);
            } else if (path.matches("/api/datasets/[^/]+/upload")
                    && "POST".equals(exchange.getRequestMethod())) {
                uploadApprovedArtifact(
                        exchange,
                        path.substring(
                                "/api/datasets/".length(), path.length() - "/upload".length()), false);
            } else if (path.matches("/api/datasets/[^/]+/annotations")
                    && "GET".equals(exchange.getRequestMethod())) {
                listAnnotations(
                        exchange,
                        path.substring(
                                "/api/datasets/".length(),
                                path.length() - "/annotations".length()));
            } else if (path.matches("/api/datasets/[^/]+/annotations")
                    && "POST".equals(exchange.getRequestMethod())) {
                createAnnotation(
                        exchange,
                        path.substring(
                                "/api/datasets/".length(),
                                path.length() - "/annotations".length()));
            } else if (path.matches("/api/datasets/[^/]+/annotations/[^/]+/measurements")
                    && "GET".equals(exchange.getRequestMethod())) {
                annotationMeasurements(exchange, path);
            } else if (path.matches("/api/datasets/[^/]+/measurements.csv")
                    && "GET".equals(exchange.getRequestMethod())) {
                annotationMeasurementsCsv(exchange, path);
            } else if (path.matches("/api/datasets/[^/]+/annotations/[^/]+")
                    && "PATCH".equals(exchange.getRequestMethod())) {
                updateAnnotation(exchange, path);
            } else if (path.matches("/api/datasets/[^/]+/annotations/[^/]+")
                    && "DELETE".equals(exchange.getRequestMethod())) {
                deleteAnnotation(exchange, path);
            } else if (path.matches("/api/datasets/[^/]+")
                    && "DELETE".equals(exchange.getRequestMethod())) {
                deleteDataset(exchange, path.substring("/api/datasets/".length()));
            } else if ("/api/batches".equals(path) || path.matches("/api/batches/[0-9a-fA-F-]{36}(/report|/retry|/cancel|/export)?")) {
                batches(exchange, path);
            } else {
                respond(exchange, 404, "application/json", "{\"error\":\"not_found\"}");
            }
        } catch (RuntimeException error) {
            respond(exchange, 500, "application/json", "{\"error\":\"internal_error\"}");
        }
    }

    private void bootstrap(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        if (authenticated(exchange)) {
            exchange.getResponseHeaders().set("Location", "/app");
            exchange.sendResponseHeaders(303, -1);
            return;
        }
        var suppliedToken = queryValue(exchange, "launchToken", null);
        if (!constantTimeEquals(launchToken, suppliedToken) || !launchTokenAvailable.compareAndSet(true, false)) {
            respond(exchange, 401, "application/json", "{\"error\":\"invalid_launch_token\"}");
            return;
        }
        setSessionCookie(exchange);
        exchange.getResponseHeaders().set("Location", "/app");
        exchange.sendResponseHeaders(303, -1);
    }

    private void features(HttpExchange exchange) throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        try {
            var refresh = "true".equalsIgnoreCase(queryValue(exchange, "refresh", "false"));
            var packs = refresh ? featurePackManager.refresh() : featurePackManager.list();
            respond(exchange, 200, "application/json", featuresJson(packs));
        } catch (IOException error) {
            respond(exchange, 409, "application/json",
                    "{\"error\":\"feature_catalog_unavailable\",\"detail\":"
                            + json(error.getMessage()) + ",\"features\":"
                            + featuresJson(featurePackManager.list()) + "}");
        }
    }

    private void featureAction(HttpExchange exchange, String path) throws IOException {
        if (!requireWrite(exchange)) return;
        var parts = path.split("/");
        try {
            switch (parts[4]) {
                case "enable" -> featurePackManager.enable(parts[3]);
                case "activate" -> featurePackManager.activate(parts[3], queryValue(exchange, "version", ""));
                case "rollback" -> featurePackManager.rollback(parts[3]);
                case "cancel" -> featurePackManager.cancelInstall(parts[3]);
                default -> throw new IllegalArgumentException("Unsupported feature action");
            }
            respond(exchange, 204, "application/json", "");
        } catch (IOException | IllegalArgumentException | IllegalStateException error) {
            respond(exchange, 409, "application/json", "{\"error\":\"feature_action_failed\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void installFeature(HttpExchange exchange, String path) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        var id = path.substring("/api/features/".length(), path.length() - "/install".length());
        if (!List.of("pathology-tools", "classical-analysis").contains(id)) {
            respond(exchange, 404, "application/json", "{\"error\":\"feature_excluded\"}");
            return;
        }
        try {
            respond(exchange, 200, "application/json",
                    featureJson(featurePackManager.install(id)));
        } catch (IOException error) {
            respond(exchange, 409, "application/json",
                    "{\"error\":\"feature_install_failed\",\"detail\":"
                            + json(error.getMessage()) + "}");
        }
    }

    private void uninstallFeature(HttpExchange exchange, String path) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        var id = path.substring("/api/features/".length());
        try {
            featurePackManager.uninstall(id);
            exchange.sendResponseHeaders(204, -1);
        } catch (IOException error) {
            respond(exchange, 409, "application/json",
                    "{\"error\":\"feature_uninstall_failed\",\"detail\":"
                            + json(error.getMessage()) + "}");
        }
    }

    private void disableFeature(HttpExchange exchange, String path) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        var id = path.substring("/api/features/".length(), path.length() - "/disable".length());
        try {
            featurePackManager.disable(id);
            exchange.sendResponseHeaders(204, -1);
        } catch (IOException error) {
            respond(exchange, 409, "application/json",
                    "{\"error\":\"feature_disable_failed\",\"detail\":"
                            + json(error.getMessage()) + "}");
        }
    }

    private void studyAuthoring(HttpExchange exchange, String path) throws IOException {
        var method = exchange.getRequestMethod();
        if (path.equals("/api/study/import") && !"POST".equals(method)) {
            respond(exchange, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        if ("GET".equals(method)) { if (!requireAuthenticated(exchange)) return; }
        else if (!requireWriteHeaders(exchange)) return;
        var mapper = org.pathlab.forge.study.StudyPackCanonicalJson.mapper();
        var parts = path.split("/");
        try {
            var body = mapper.createObjectNode();
            if (!"GET".equals(method)) {
                var bytes = exchange.getRequestBody().readNBytes(8 * 1024 * 1024 + 1);
                if (bytes.length > 8 * 1024 * 1024) throw new IllegalArgumentException("Study request exceeds bounded size");
                if (bytes.length > 0) {
                    var parsed = mapper.readTree(bytes);
                    if (!(parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) throw new IllegalArgumentException("Study request must be an object");
                    body = object;
                }
            }
            Object result;
            if (path.equals("/api/study/viewer/slides") && "GET".equals(method)) {
                var key = teachingConnection();
                result = viewerStudy(key, "GET", "/api/v2/desktop/study/authoring/slides", new byte[0]);
                if (!((com.fasterxml.jackson.databind.JsonNode) result).isArray()) throw new IOException("Viewer teaching discovery response must be an array");
            } else if (path.equals("/api/study/import") && "POST".equals(method)) {
                result = "csv".equals(body.path("format").asText()) ? studyService.importCsv(body.path("text").asText()) : studyService.importDraft(body.path("text").asText());
            } else if (parts.length == 4 && "GET".equals(method)) result = studyService.listDrafts();
            else if (parts.length == 4 && "POST".equals(method)) result = studyService.createDraft(body.path("name").asText(), body.path("definition").isObject() ? body.path("definition").toString() : "", "{}");
            else if (parts.length == 5 && "GET".equals(method)) result = studyService.getDraft(parts[4]);
            else if (parts.length == 5 && "PUT".equals(method)) result = studyService.saveDraft(parts[4], body.path("name").asText(), body.path("revision").asLong(), body.path("definition").toString(), body.path("associations").toString());
            else if (parts.length == 6) {
                var id = parts[4];
                var revision = body.path("revision").asLong();
                var checksum = body.path("checksum").asText();
                var action = parts[5];
                if ("GET".equals(method) && "history".equals(action)) result = studyService.history(id);
                else if ("GET".equals(method) && "export".equals(action)) {
                    var format = queryValue(exchange, "format", "json");
                    var content = studyExport(id, format, queryValue(exchange, "checksum", ""));
                    var csv = "csv".equals(format);
                    exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"study-pack." + (csv ? "csv" : "json") + "\"");
                    respond(exchange, 200, csv ? "text/csv; charset=utf-8" : "application/json", content);
                    return;
                } else if ("POST".equals(method)) result = switch (action) {
                    case "duplicate" -> studyService.duplicate(id, body.path("name").asText(), body.path("nextVersion").asBoolean());
                    case "recover" -> studyService.recover(id, body.path("historicalRevision").asLong(), revision);
                    case "preview" -> studyService.preview(id, revision);
                    case "review" -> studyService.reviewTask(id, revision, checksum, body.path("taskId").asText());
                    case "approve" -> studyService.approve(id, revision, checksum);
                    case "questions" -> studyService.importQuestions(id, revision, body.path("format").asText(), body.path("text").asText(), body.path("slideId").asText());
                    case "publish" -> publishStudy(id, revision, checksum);
                    default -> throw new IllegalArgumentException("Unsupported Study action");
                };
                else throw new IllegalArgumentException("Unsupported Study method");
            } else throw new IllegalArgumentException("Unsupported Study route");
            respond(exchange, 200, "application/json", mapper.writeValueAsString(result));
        } catch (IllegalArgumentException | IllegalStateException | IOException error) {
            respond(exchange, 409, "application/json", "{\"error\":\"study_action_failed\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private String teachingConnection() throws IOException {
        var key = viewerPairingService.connectionKey();
        if (key.isBlank() || !viewerPairingService.status().scopes().contains("study-packs:write")
                || !key.equals(viewerPairingService.connectionKey())) throw new IOException("Pair with current teaching authority and study-packs:write before publishing");
        return key;
    }
    private com.fasterxml.jackson.databind.JsonNode viewerStudy(String key, String method, String path, byte[] body) throws IOException {
        try (var response = viewerPairingService.requestBound(key, method, path, java.util.Map.of("Content-Type", "application/json"), body)) {
            var bytes = response.body().readNBytes(org.pathlab.forge.study.StudyPackContract.MAX_PACK_BYTES + 1);
            if (bytes.length > org.pathlab.forge.study.StudyPackContract.MAX_PACK_BYTES) throw new IOException("Viewer teaching response exceeds size limit");
            if (!key.equals(viewerPairingService.connectionKey())) throw new IOException("Viewer account changed during teaching request");
            var value = org.pathlab.forge.study.StudyPackCanonicalJson.mapper().readTree(bytes);
            if (response.status() < 200 || response.status() >= 300) {
                throw new IOException("Viewer teaching request rejected (" + response.status() + "); drafts remain local. "
                        + (value == null ? "Reconnect and retry" : value.path("error").asText("Review permissions, privacy and slide hashes")));
            }
            if (value == null) throw new IOException("Viewer teaching response was empty");
            return value;
        }
    }
    private com.fasterxml.jackson.databind.JsonNode publishStudy(String id, long revision, String checksum) throws IOException {
        var current = studyService.getDraft(id);
        if (current.revision() != revision) throw new IllegalStateException("Draft changed since publication was requested");
        var approved = studyExport(id, "approved", checksum);
        var key = teachingConnection(); var bytes = approved.getBytes(StandardCharsets.UTF_8);
        var validation = viewerStudy(key, "POST", "/api/v2/desktop/study/packs/validate", bytes);
        if (!checksum.equals(validation.path("checksum").asText())) throw new IOException("Viewer canonical checksum differs; resolve the contract mismatch before publishing");
        var checked = studyService.getDraft(id);
        if (checked.revision() != revision || !checksum.equals(checked.approvedChecksum())) throw new IllegalStateException("Draft changed while Viewer validated it");
        var published = viewerStudy(key, "POST", "/api/v2/desktop/study/packs", bytes);
        if (!checksum.equals(published.path("checksum").asText())) throw new IOException("Viewer publication acknowledgement checksum differs; reconcile this immutable version before retrying");
        return published;
    }

    private String studyExport(String id, String format, String checksum) throws IOException {
        return switch (format) {
            case "csv" -> studyService.exportCsv(id);
            case "json" -> studyService.exportDraft(id);
            case "approved" -> {
                var draft = studyService.getDraft(id);
                if (!checksum.equals(draft.approvedChecksum())) throw new IllegalStateException("Draft changed since approval");
                yield studyService.approved(checksum);
            }
            default -> throw new IllegalArgumentException("Unsupported Study export format");
        };
    }

    private void analysisRuns(HttpExchange exchange, String path) throws IOException {
        var method = exchange.getRequestMethod();
        if ("GET".equals(method)) { if (!requireAuthenticated(exchange)) return; }
        else if (!requireWriteHeaders(exchange)) return;
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var parts = path.split("/");
        try {
            Object result;
            var status = 200;
            if (parts.length == 4 && "GET".equals(method)) {
                var page = analysisService.page(queryValue(exchange, "datasetId", ""),
                        Integer.parseInt(queryValue(exchange, "limit", "100")), Integer.parseInt(queryValue(exchange, "offset", "0")));
                result = "true".equals(queryValue(exchange, "page", "false")) ? page : page.runs();
            } else if (parts.length == 4 && "POST".equals(method)) {
                result = analysisService.submit(mapper.readValue(boundedAnalysisBody(exchange), DeterministicAnalysisService.Request.class));
                status = 202;
            } else if (parts.length == 5 && "GET".equals(method)) {
                result = analysisService.get(parts[4]);
            } else if (parts.length == 6 && "cancel".equals(parts[5]) && "POST".equals(method)) {
                result = analysisService.cancel(parts[4]);
            } else if (parts.length == 6 && "review".equals(parts[5]) && "GET".equals(method)) {
                result = analysisService.review(parts[4]);
            } else if (parts.length == 6 && "review".equals(parts[5]) && "PUT".equals(method)) {
                result = analysisService.saveReview(parts[4], mapper.readValue(boundedAnalysisBody(exchange), AnalysisReview.class));
            } else if (parts.length == 6 && "cores".equals(parts[5]) && "POST".equals(method)) {
                var body = mapper.readTree(boundedAnalysisBody(exchange));
                result = analysisService.persistReviewedTma(parts[4], body.path("reviewRevision").asLong());
            } else if (parts.length == 6 && "core-analysis".equals(parts[5]) && "POST".equals(method)) {
                var body = mapper.readTree(boundedAnalysisBody(exchange));
                var configuration = mapper.convertValue(body.path("configuration"), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Double>>() {});
                result = analysisService.submitTmaCore(parts[4], body.path("reviewRevision").asLong(), body.path("coreId").asText(), body.path("tool").asText(), configuration); status = 202;
            } else if (parts.length == 6 && "export".equals(parts[5]) && "GET".equals(method)) {
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"analysis-" + parts[4] + ".json\"");
                respond(exchange, 200, "application/json", analysisService.exportJson(parts[4]));
                return;
            } else {
                respond(exchange, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
                return;
            }
            respond(exchange, status, "application/json", mapper.writeValueAsString(result));
        } catch (IllegalArgumentException | IllegalStateException | IOException error) {
            respond(exchange, 409, "application/json", "{\"error\":\"analysis_unavailable\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private byte[] boundedAnalysisBody(HttpExchange exchange) throws IOException {
        var bytes = exchange.getRequestBody().readNBytes(MAX_WRITE_BYTES + 1);
        if (bytes.length > MAX_WRITE_BYTES) throw new IllegalArgumentException("Analysis request exceeds size limit");
        return bytes;
    }

    private void createAnalysisJob(HttpExchange exchange) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        var moduleId = queryValue(exchange, "moduleId", "");
        if (!"pathology.he".equals(moduleId)) {
            respond(exchange, 422, "application/json",
                    "{\"error\":\"unsupported_analysis_module\"}");
            return;
        }
        try {
            var job = analysisService.submit(new DeterministicAnalysisService.Request(
                    queryValue(exchange, "datasetId", ""),
                    queryValue(exchange, "annotationId", ""),
                    "he", java.util.Map.of(
                        "hematoxylinThreshold", optionalDoubleQuery(exchange, "hematoxylinThreshold", 0.15),
                        "eosinThreshold", optionalDoubleQuery(exchange, "eosinThreshold", 0.15))));
            respond(exchange, 202, "application/json", analysisJobJson(job));
        } catch (IllegalArgumentException | IllegalStateException error) {
            respond(exchange, 409, "application/json",
                    "{\"error\":\"analysis_unavailable\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void analysisJob(HttpExchange exchange, String path) throws IOException {
        if (!requireAuthenticated(exchange)) return;
        try {
            respond(exchange, 200, "application/json",
                    analysisJobJson(analysisService.get(path.substring("/api/analysis/jobs/".length()))));
        } catch (IllegalArgumentException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"analysis_job_not_found\"}");
        }
    }

    private void cancelAnalysisJob(HttpExchange exchange, String path) throws IOException {
        if (!requireWrite(exchange)) return;
        var id = path.substring("/api/analysis/jobs/".length(), path.length() - "/cancel".length());
        try {
            respond(exchange, 200, "application/json", analysisJobJson(analysisService.cancel(id)));
        } catch (IllegalArgumentException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"analysis_job_not_found\"}");
        }
    }

    private void appResource(HttpExchange exchange) throws IOException {
        if (authenticated(exchange)) {
            serveResource(exchange, "/web/index.html", "text/html; charset=utf-8");
            return;
        }
        if (Boolean.getBoolean("pathlab.forge.desktop") || !trustedLocalDocumentNavigation(exchange)) {
            respond(exchange, 401, "application/json", "{\"error\":\"unauthorized\"}");
            return;
        }
        setSessionCookie(exchange);
        exchange.getResponseHeaders().set("Location", "/app");
        exchange.sendResponseHeaders(303, -1);
    }

    private boolean trustedLocalDocumentNavigation(HttpExchange exchange) {
        var remoteAddress = exchange.getRemoteAddress().getAddress();
        if (remoteAddress == null || !remoteAddress.isLoopbackAddress()) {
            return false;
        }
        var host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || !host.equalsIgnoreCase(baseUri.getAuthority())) {
            return false;
        }
        var fetchSite = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if (fetchSite != null
                && !"none".equalsIgnoreCase(fetchSite)
                && !"same-origin".equalsIgnoreCase(fetchSite)) {
            return false;
        }
        var fetchMode = exchange.getRequestHeaders().getFirst("Sec-Fetch-Mode");
        if (fetchMode != null && !"navigate".equalsIgnoreCase(fetchMode)) {
            return false;
        }
        var fetchDestination = exchange.getRequestHeaders().getFirst("Sec-Fetch-Dest");
        return fetchDestination == null || "document".equalsIgnoreCase(fetchDestination);
    }

    private void setSessionCookie(HttpExchange exchange) {
        exchange.getResponseHeaders().add(
                "Set-Cookie",
                "forge_session="
                        + sessionToken
                        + "; Path=/; Max-Age="
                        + SESSION_MAX_AGE_SECONDS
                        + "; HttpOnly; SameSite=Strict");
    }

    private void viewerConnection(HttpExchange exchange) throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        try {
            respond(
                    exchange,
                    200,
                    "application/json",
                    viewerConnectionJson(viewerPairingService.status()));
        } catch (IOException | RuntimeException error) {
            respond(
                    exchange,
                    503,
                    "application/json",
                    "{\"error\":\"viewer_unavailable\",\"detail\":"
                            + json(error.getMessage()) + "}");
        }
    }

    private void startViewerPairing(HttpExchange exchange) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        try {
            var pairing = viewerPairingService.start(queryValue(exchange, "viewerUrl", ""));
            respond(
                    exchange,
                    201,
                    "application/json",
                    "{\"userCode\":" + json(pairing.userCode())
                            + ",\"verificationUrl\":" + json(pairing.verificationUrl())
                            + ",\"verificationUrlComplete\":"
                            + json(pairing.verificationUrlComplete())
                            + ",\"pollIntervalSeconds\":" + pairing.pollIntervalSeconds()
                            + ",\"expiresAt\":" + json(pairing.expiresAt()) + "}");
        } catch (IOException | IllegalArgumentException error) {
            respond(
                    exchange,
                    422,
                    "application/json",
                    "{\"error\":\"pairing_failed\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void exchangeViewerPairing(HttpExchange exchange) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        try {
            respond(
                    exchange,
                    200,
                    "application/json",
                    viewerConnectionJson(viewerPairingService.exchange()));
        } catch (IOException | IllegalStateException error) {
            respond(
                    exchange,
                    409,
                    "application/json",
                    "{\"error\":\"pairing_pending\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void revokeViewerConnection(HttpExchange exchange) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        try {
            viewerPairingService.revoke();
            exchange.sendResponseHeaders(204, -1);
        } catch (IOException error) {
            respond(
                    exchange,
                    503,
                    "application/json",
                    "{\"error\":\"viewer_revoke_failed\",\"detail\":"
                            + json(error.getMessage()) + "}");
        }
    }

    private void viewerUploadStatus(HttpExchange exchange) throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        respond(
                exchange,
                200,
                "application/json",
                viewerUploadJson(viewerPairingService.uploadStatus()));
    }

    private void uploadApprovedArtifact(HttpExchange exchange, String id, boolean teaching)
            throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        try {
            var dataset = repository.find(id)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Dataset was not found"));
            var revision = conversionService.approvedRevision(id);
            respond(
                    exchange,
                    202,
                    "application/json",
                    viewerUploadJson(startArtifactUpload(dataset, revision, teaching)));
        } catch (IOException | IllegalStateException | IllegalArgumentException error) {
            respond(
                    exchange,
                    409,
                    "application/json",
                    "{\"error\":\"viewer_upload_rejected\",\"detail\":"
                            + json(error.getMessage()) + "}");
        }
    }

    private void authenticatedResource(HttpExchange exchange, String resource, String type)
            throws IOException {
        if (!authenticated(exchange)) {
            respond(exchange, 401, "application/json", "{\"error\":\"unauthorized\"}");
            return;
        }
        serveResource(exchange, resource, type);
    }

    private void serveResource(HttpExchange exchange, String resource, String type)
            throws IOException {
        try (InputStream input = ForgeServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                respond(exchange, 404, "application/json", "{\"error\":\"asset_not_found\"}");
                return;
            }
            respond(exchange, 200, type, input.readAllBytes());
        }
    }

    private void session(HttpExchange exchange) throws IOException {
        if (!authenticated(exchange)) {
            respond(exchange, 401, "application/json", "{\"error\":\"unauthorized\"}");
            return;
        }
        exchange.getResponseHeaders().set("X-Forge-CSRF", csrfToken);
        respond(exchange, 200, "application/json", "{\"authenticated\":true}");
    }

    private void capabilities(HttpExchange exchange) throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        var storage = Files.getFileStore(dataRoot);
        refreshManagedUsage();
        var usage = managedUsage;
        respond(
                exchange,
                200,
                "application/json",
                "{\"persistentLibrary\":true,\"nativeFilePicker\":true,"
                        + "\"omeManagedCopy\":true,\"vsiConversion\":"
                        + conversionService.engine().available()
                        + ",\"conversionRuntime\":"
                        + json(conversionService.engine().runtimeDescription())
                        + ",\"dziGeneration\":"
                        + conversionService.derivativeEngine().available()
                        + ",\"derivativeRuntime\":"
                        + json(conversionService.derivativeEngine().description())
                        + ",\"activeConversions\":" + conversionService.activeConversionCount()
                        + ",\"queuedConversions\":" + conversionService.queuedConversionCount()
                        + ",\"queuePaused\":" + conversionService.queuePaused()
                        + ",\"maximumConcurrentConversions\":"
                        + conversionService.maximumConcurrentConversions()
                        + ",\"usableBytes\":" + storage.getUsableSpace()
                        + ",\"effectiveCapacityBytes\":" + storage.getTotalSpace()
                        + ",\"managedUsage\":" + (usage == null ? "null" : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(usage))
                        + ",\"processContainment\":" + json(org.pathlab.forge.runtime.ChildProcessContainment.global().mode())
                        + ",\"projectFolderImport\":true,\"downsamples\":[1,1.5,2,4,8,16,32]}");
    }

    private void listDatasets(HttpExchange exchange) throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        var body = datasetsJson(repository.list());
        var etag = "\"" + sha256(body.getBytes(StandardCharsets.UTF_8)) + "\"";
        exchange.getResponseHeaders().set("ETag", etag);
        exchange.getResponseHeaders().set("Cache-Control", "private, no-cache");
        if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        respond(exchange, 200, "application/json", body);
    }

    private void conversionQueue(HttpExchange exchange) throws IOException {
        if ("POST".equals(exchange.getRequestMethod())) {
            if (!requireWrite(exchange)) return;
            var paused = queryValue(exchange, "paused", "");
            if (!"true".equals(paused) && !"false".equals(paused)) {
                respond(exchange, 400, "application/json", "{\"error\":\"invalid_pause_state\"}");
                return;
            }
            conversionService.setQueuePaused(Boolean.parseBoolean(paused));
        } else if (!requireAuthenticated(exchange)) return;
        respond(exchange, 200, "application/json", "{\"paused\":" + conversionService.queuePaused()
                + ",\"active\":" + conversionService.activeConversionCount()
                + ",\"queued\":" + conversionService.queuedConversionCount() + "}");
    }

    private void nativeExport(HttpExchange exchange, String path) throws IOException {
        var method = exchange.getRequestMethod();
        if ("GET".equals(method)) { if (!requireAuthenticated(exchange)) return; }
        else if (!requireWriteHeaders(exchange)) return;
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            Object result;
            if ("/api/exports/cancel".equals(path) && "POST".equals(method)) result = exportService.cancel(queryValue(exchange, "id", ""));
            else if ("GET".equals(method) && "/api/exports".equals(path)) result = exportService.state();
            else if ("POST".equals(method) && "/api/exports".equals(path)) {
                var body = mapper.readTree(boundedAnalysisBody(exchange));
                var destination = Path.of(body.path("destination").asText());
                requireDesktopSelection(destination, "export");
                var datasetId = body.path("datasetId").asText();
                var kind = body.path("kind").asText();
                if (java.util.Set.of("analysis", "measurements", "study", "batch").contains(kind)) {
                    var content = switch (kind) {
                        case "analysis" -> analysisService.exportJson(body.path("runId").asText());
                        case "study" -> studyExport(body.path("draftId").asText(), body.path("format").asText(), body.path("checksum").asText());
                        case "batch" -> batchExport(body.path("batchId").asText(), body.path("format").asText());
                        default -> measurementsCsv(datasetId);
                    };
                    result = exportService.submitBytes(content.getBytes(StandardCharsets.UTF_8), destination);
                    respond(exchange, 200, "application/json", mapper.writeValueAsString(result));
                    return;
                }
                var revisionId = body.path("revisionId").asText();
                var revision = conversionService.revisions(datasetId).stream()
                        .filter(item -> item.id().equals(revisionId)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Artifact revision was not found"));
                if (!java.util.Set.of("READY", "APPROVED").contains(revision.status().name())) throw new IllegalStateException("Artifact is not verified");
                var artifacts = conversionService.revisionArtifacts(datasetId, revisionId);
                if (!java.util.Set.of("ome", "package").contains(kind)) throw new IllegalArgumentException("Unsupported export kind");
                result = exportService.submit("ome".equals(kind) ? artifacts.omeTiff() : artifacts.packagePath(),
                        "ome".equals(kind) ? revision.omeSha256() : revision.packageSha256(), destination);
            } else { respond(exchange, 405, "application/json", "{\"error\":\"method_not_allowed\"}"); return; }
            respond(exchange, 200, "application/json", mapper.writeValueAsString(result));
        } catch (IllegalArgumentException | IllegalStateException | IOException error) {
            respond(exchange, 409, "application/json", "{\"error\":\"export_unavailable\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void desktopLifecycle(HttpExchange exchange) throws IOException {
        if (!Boolean.getBoolean("pathlab.forge.desktop")
                || !constantTimeEquals(desktopSecret,
                        exchange.getRequestHeaders().getFirst("X-Forge-Desktop-Secret"))) {
            respond(exchange, 403, "application/json", "{\"error\":\"forbidden\"}");
            return;
        }
        if ("POST".equals(exchange.getRequestMethod())) conversionService.setQueuePaused(true);
        else if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        var uploading = java.util.Set.of("UPLOADING", "VERIFYING_OME", "SYNCING_RESULTS", "RETRYING")
                .contains(viewerPairingService.uploadStatus().state());
        var featureBusy = !java.util.Set.of("IDLE", "COMPLETE", "CANCELLED", "FAILED")
                .contains(featurePackManager.progress().phase());
        respond(exchange, 200, "application/json", "{\"active\":"
                + (conversionService.activeConversionCount() + analysisService.activeCount() + (uploading ? 1 : 0) + (exportService.active() ? 1 : 0) + (featureBusy ? 1 : 0))
                + ",\"paused\":" + conversionService.queuePaused() + "}");
    }

    private void approveDesktopSelections(HttpExchange exchange) throws IOException {
        if (!Boolean.getBoolean("pathlab.forge.desktop")
                || !"POST".equals(exchange.getRequestMethod())
                || !constantTimeEquals(desktopSecret,
                        exchange.getRequestHeaders().getFirst("X-Forge-Desktop-Secret"))) {
            respond(exchange, 403, "application/json", "{\"error\":\"forbidden\"}");
            return;
        }
        var bytes = exchange.getRequestBody().readNBytes(MAX_WRITE_BYTES + 1);
        if (bytes.length > MAX_WRITE_BYTES) {
            respond(exchange, 413, "application/json", "{\"error\":\"request_too_large\"}");
            return;
        }
        try {
            var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes);
            var purpose = body.path("purpose").asText();
            var paths = body.path("paths");
            if (!List.of("import", "directory", "export").contains(purpose)
                    || !paths.isArray() || paths.isEmpty() || paths.size() > 256) {
                throw new IllegalArgumentException("Invalid native selection");
            }
            var approved = new java.util.HashMap<Path, Long>();
            for (var item : paths) {
                if (!item.isTextual() || item.asText().isBlank()) {
                    throw new IllegalArgumentException("Invalid selection path");
                }
                var path = Path.of(item.asText()).toAbsolutePath().normalize();
                var real = "export".equals(purpose)
                        ? path.getParent().toRealPath().resolve(path.getFileName()) : path.toRealPath();
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Symlink selection rejected");
                approved.put(real, System.currentTimeMillis() + 10 * 60_000L);
            }
            synchronized (desktopSelections) {
                var selections = desktopSelections.computeIfAbsent(purpose, ignored -> new java.util.HashMap<>());
                selections.entrySet().removeIf(entry -> entry.getValue() < System.currentTimeMillis());
                if (selections.size() + approved.size() > 1024) selections.clear();
                selections.putAll(approved);
            }
            respond(exchange, 204, "application/json", "");
        } catch (IOException | RuntimeException error) {
            respond(exchange, 422, "application/json", "{\"error\":\"invalid_selection\"}");
        }
    }

    private void requireDesktopSelection(Path path, String purpose) throws IOException {
        if (!Boolean.getBoolean("pathlab.forge.desktop")) return;
        var real = "export".equals(purpose)
                ? path.toAbsolutePath().getParent().toRealPath().resolve(path.getFileName()) : path.toRealPath();
        synchronized (desktopSelections) {
            if (desktopSelections.getOrDefault(purpose, java.util.Map.of())
                    .getOrDefault(real, 0L) < System.currentTimeMillis()) {
                throw new IllegalArgumentException("Choose this path through the native dialog first");
            }
        }
    }

    private void formats(HttpExchange exchange) throws IOException {
        if (!requireAuthenticated(exchange)) return;
        var catalogs = new java.util.ArrayList<org.pathlab.forge.reader.RuntimeCatalog>();
        conversionService.engine().runtimeCatalog().ifPresent(catalogs::add);
        if (derivativeEngine instanceof VipsRuntime vips) {
            vips.runtimeCatalog().ifPresent(catalogs::add);
        }
        var components = ReaderRuntimeInventory.inspect(dataRoot).stream().map(component ->
                "{\"id\":" + json(component.id())
                        + ",\"available\":" + component.available()
                        + ",\"source\":" + json(component.source())
                        + ",\"version\":" + json(component.version())
                        + ",\"fingerprint\":" + json(component.fingerprint())
                        + ",\"platform\":" + json(component.platform())
                        + ",\"diagnosticCode\":" + json(component.diagnosticCode())
                        + ",\"detail\":" + json(component.detail()) + "}")
                .collect(java.util.stream.Collectors.joining(","));
        if (catalogs.isEmpty()) {
            respond(exchange, 200, "application/json",
                    "{\"policy\":\"BEST_EFFORT\",\"runtimeVersion\":"
                            + json(conversionService.engine().runtimeDescription())
                            + ",\"runtimeFingerprint\":\"\",\"components\":[" + components
                            + "],\"formats\":[]}");
            return;
        }
        var formats = catalogs.stream().flatMap(catalog -> catalog.formats().stream()).map(format ->
                "{\"engine\":" + json(format.engine())
                        + ",\"readerId\":" + json(format.readerId())
                        + ",\"displayName\":" + json(format.displayName())
                        + ",\"extensions\":[" + format.extensions().stream()
                                .map(ForgeServer::json)
                                .collect(java.util.stream.Collectors.joining(",")) + "]"
                        + ",\"multidimensional\":" + format.multidimensional()
                        + ",\"nativePyramid\":" + format.nativePyramid()
                        + ",\"groupedFiles\":" + format.groupedFiles()
                        + ",\"randomRegions\":" + format.randomRegions() + "}")
                .collect(java.util.stream.Collectors.joining(","));
        respond(exchange, 200, "application/json",
                "{\"policy\":\"BEST_EFFORT\",\"runtimeVersion\":"
                        + json(catalogs.stream().map(org.pathlab.forge.reader.RuntimeCatalog::runtimeVersion)
                                .collect(java.util.stream.Collectors.joining(" + ")))
                        + ",\"runtimeFingerprint\":" + json(sha256(catalogs.stream()
                                .map(org.pathlab.forge.reader.RuntimeCatalog::fingerprint)
                                .collect(java.util.stream.Collectors.joining("|"))
                                .getBytes(StandardCharsets.UTF_8)))
                        + ",\"components\":[" + components + "]"
                        + ",\"formats\":[" + formats + "]}");
    }

    private static org.pathlab.forge.reader.DatasetProbe.Result probeDataset(
            ConversionEngine conversionEngine, DerivativeEngine derivativeEngine, Path source)
            throws IOException, org.pathlab.forge.reader.ImportProbeException {
        org.pathlab.forge.reader.DatasetProbe.Result bioFormats = null;
        Exception bioFormatsFailure = null;
        try {
            bioFormats = conversionEngine.probe(source);
        } catch (IOException | org.pathlab.forge.reader.ImportProbeException error) {
            bioFormatsFailure = error;
        }
        org.pathlab.forge.reader.DatasetProbe.Result vips = null;
        if (derivativeEngine instanceof VipsRuntime runtime) {
            try { vips = runtime.probe(source); } catch (IOException ignored) { }
        }
        if (bioFormats != null && (bioFormats.descriptor().multidimensional()
                || bioFormats.descriptor().groupedFiles()
                || bioFormats.descriptor().nativePyramid())) return bioFormats;
        if (vips != null) return vips;
        if (bioFormats != null) return bioFormats;
        if (bioFormatsFailure instanceof org.pathlab.forge.reader.ImportProbeException probe) {
            throw probe;
        }
        if (bioFormatsFailure instanceof IOException io) throw io;
        throw new IOException("No installed reader could open this source");
    }

    private void browseLocalFiles(HttpExchange exchange) throws IOException {
        if (Boolean.getBoolean("pathlab.forge.desktop")) {
            respond(exchange, 403, "application/json", "{\"error\":\"use_native_dialog\"}");
            return;
        }
        if (!requireAuthenticated(exchange)) {
            return;
        }
        try {
            var requested = queryValue(exchange, "path", "").trim();
            var home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
            var downloads = home.resolve("Downloads");
            var directory = requested.isEmpty() && Files.isDirectory(downloads)
                    ? downloads
                    : requested.isEmpty() ? home : Path.of(requested).toAbsolutePath().normalize();
            if (!Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(directory)) {
                respond(exchange, 422, "application/json",
                        "{\"error\":\"folder_unavailable\",\"detail\":\"Choose a readable local folder\"}");
                return;
            }
            var entries = new java.util.ArrayList<Path>();
            try (var stream = Files.list(directory)) {
                stream.filter(item -> Files.isDirectory(item, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                                || (Files.isRegularFile(item, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                                        && !Files.isSymbolicLink(item)))
                        .sorted(java.util.Comparator
                                .comparing((Path item) -> !Files.isDirectory(item, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                                .thenComparing(item -> item.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .limit(501)
                        .forEach(entries::add);
            }
            var truncated = entries.size() > 500;
            if (truncated) entries.remove(entries.size() - 1);
            var body = new StringBuilder("{\"path\":")
                    .append(json(directory.toString()))
                    .append(",\"parent\":")
                    .append(directory.getParent() == null ? "null" : json(directory.getParent().toString()))
                    .append(",\"locations\":[");
            var locations = new java.util.LinkedHashSet<Path>();
            locations.add(home);
            for (var name : List.of("Desktop", "Documents", "Downloads")) {
                var location = home.resolve(name);
                if (Files.isDirectory(location)) locations.add(location);
            }
            for (var root : java.io.File.listRoots()) {
                if (root.isDirectory()) locations.add(root.toPath().toAbsolutePath().normalize());
            }
            var first = true;
            for (var location : locations) {
                if (!first) body.append(',');
                first = false;
                var name = location.equals(home) ? "Home"
                        : location.getFileName() == null ? location.toString() : location.getFileName().toString();
                body.append("{\"name\":").append(json(name))
                        .append(",\"path\":").append(json(location.toString())).append('}');
            }
            body.append("],\"entries\":[");
            first = true;
            for (var entry : entries) {
                if (!first) body.append(',');
                first = false;
                var directoryEntry = Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                body.append("{\"name\":").append(json(entry.getFileName().toString()))
                        .append(",\"path\":").append(json(entry.toAbsolutePath().normalize().toString()))
                        .append(",\"directory\":").append(directoryEntry)
                        .append(",\"bytes\":");
                try {
                    body.append(directoryEntry ? 0 : Files.size(entry));
                } catch (IOException ignored) {
                    body.append(0);
                }
                body.append('}');
            }
            body.append("],\"truncated\":").append(truncated).append('}');
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            respond(exchange, 200, "application/json", body.toString());
        } catch (IOException | RuntimeException error) {
            respond(exchange, 422, "application/json",
                    "{\"error\":\"folder_unavailable\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void selectDatasets(HttpExchange exchange) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        var result = importWithLegacyFallback(picker.select());
        scheduleSourceVerification(result.datasets());
        respond(exchange, 200, "application/json", datasetsJson(repository.list()));
    }

    private void importDataset(HttpExchange exchange) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        var rawPath = queryValue(exchange, "path", "").trim();
        if (rawPath.isEmpty()) {
            respond(
                    exchange,
                    422,
                    "application/json",
                    "{\"error\":\"path_required\",\"detail\":\"Enter a local image or WSI path\"}");
            return;
        }
        try {
            var selected = java.nio.file.Path.of(rawPath).toAbsolutePath().normalize();
            requireDesktopSelection(selected, "import");
            var result = importWithLegacyFallback(java.util.List.of(selected));
            scheduleSourceVerification(result.datasets());
            if (result.datasets().isEmpty() && !result.diagnostics().isEmpty()) {
                var diagnostic = result.diagnostics().get(0);
                respond(exchange, 422, "application/json",
                        "{\"error\":" + json(diagnostic.code().name())
                                + ",\"detail\":" + json(diagnostic.detail()) + "}");
                return;
            }
            respond(exchange, 200, "application/json", datasetsJson(repository.list()));
        } catch (IllegalArgumentException error) {
            respond(
                    exchange,
                    422,
                    "application/json",
                    "{\"error\":\"import_failed\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void importDatasetsV2(HttpExchange exchange) throws IOException {
        if (!requireWriteHeaders(exchange)) return;
        var bytes = exchange.getRequestBody().readNBytes(MAX_WRITE_BYTES + 1);
        if (bytes.length > MAX_WRITE_BYTES) {
            respond(exchange, 413, "application/json", "{\"error\":\"request_too_large\"}");
            return;
        }
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes);
            var pathsNode = root.get("paths");
            if (pathsNode == null || !pathsNode.isArray()
                    || pathsNode.isEmpty() || pathsNode.size() > 256) {
                throw new IllegalArgumentException("paths must contain 1 to 256 local files");
            }
            var paths = new java.util.ArrayList<Path>();
            for (var node : pathsNode) {
                if (!node.isTextual() || node.textValue().isBlank()) {
                    throw new IllegalArgumentException("Each import path must be text");
                }
                var selected = Path.of(node.textValue()).toAbsolutePath().normalize();
                requireDesktopSelection(selected, "import");
                paths.add(selected);
            }
            var result = importWithLegacyFallback(paths);
            scheduleSourceVerification(result.datasets());
            var datasets = result.datasets().stream().map(this::datasetJson)
                    .collect(java.util.stream.Collectors.joining(","));
            var diagnostics = result.diagnostics().stream().map(this::diagnosticJson)
                    .collect(java.util.stream.Collectors.joining(","));
            respond(exchange, 200, "application/json",
                    "{\"datasets\":[" + datasets + "],\"diagnostics\":[" + diagnostics + "]}");
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException error) {
            respond(exchange, 422, "application/json",
                    "{\"error\":\"invalid_import_request\",\"detail\":"
                            + json(error.getMessage()) + "}");
        }
    }

    private String diagnosticJson(ImportDiagnostic diagnostic) {
        return "{\"code\":" + json(diagnostic.code().name())
                + ",\"detail\":" + json(diagnostic.detail())
                + ",\"repairable\":" + diagnostic.code().repairable()
                + ",\"paths\":[" + diagnostic.paths().stream()
                        .map(path -> json(path.toString()))
                .collect(java.util.stream.Collectors.joining(",")) + "]}";
    }

    private void datasetImagesV2(HttpExchange exchange, String id) throws IOException {
        if (!requireAuthenticated(exchange)) return;
        try {
            var dataset = repository.find(id).orElseThrow(
                    () -> new IllegalArgumentException("Dataset was not found"));
            var series = dataset.status()
                            == org.pathlab.forge.library.DatasetStatus.VERIFYING_SOURCE
                    ? conversionService.inspectWhileVerifying(id)
                    : conversionService.inspect(id);
            var body = seriesJson(series);
            respond(exchange, 200, "application/json",
                    body.substring(0, body.length() - 1)
                            + ",\"viewDefinition\":"
                            + (dataset.viewDefinitionJson().isBlank()
                                    ? "null" : dataset.viewDefinitionJson()) + "}");
        } catch (IllegalArgumentException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"dataset_not_found\"}");
        } catch (IllegalStateException error) {
            respond(exchange, 409, "application/json",
                    "{\"error\":\"reader_required\",\"detail\":" + json(error.getMessage()) + "}");
        } catch (IOException error) {
            respond(exchange, 422, "application/json",
                    "{\"error\":\"inspection_failed\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void updateDatasetViewV2(HttpExchange exchange, String id) throws IOException {
        if (!requireWriteHeaders(exchange)) return;
        var bytes = exchange.getRequestBody().readNBytes(MAX_WRITE_BYTES + 1);
        if (bytes.length > MAX_WRITE_BYTES) {
            respond(exchange, 413, "application/json", "{\"error\":\"request_too_large\"}");
            return;
        }
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(bytes);
            var view = parseViewDefinition(node);
            var series = conversionService.inspectWhileVerifying(id);
            validateView(view, series);
            var canonical = mapper.writeValueAsString(node);
            var image = series.get(view.series());
            var updated = repository.update(id, current -> current.withExportConfiguration(
                            org.pathlab.forge.library.DatasetStatus.READY_TO_CONVERT,
                            "Selected multidimensional view is ready to render", view.series(),
                            image.width(), image.height(), 1, 0, 0, 0,
                            image.width(), image.height())
                    .withViewDefinition(canonical, view.revision()));
            var body = datasetJson(updated);
            respond(exchange, 200, "application/json", body.substring(0, body.length() - 1)
                    + ",\"viewRevision\":" + json(view.revision()) + "}");
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException error) {
            respond(exchange, 422, "application/json",
                    "{\"error\":\"invalid_view\",\"detail\":" + json(error.getMessage()) + "}");
        } catch (IllegalStateException error) {
            respond(exchange, 409, "application/json",
                    "{\"error\":\"reader_required\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private static ViewDefinition parseViewDefinition(com.fasterxml.jackson.databind.JsonNode node) {
        var z = node.required("z");
        var t = node.required("t");
        var channels = new java.util.ArrayList<ChannelRender>();
        for (var channel : node.required("channels")) {
            channels.add(new ChannelRender(
                    channel.required("channel").asInt(), channel.required("enabled").asBoolean(),
                    channel.required("color").asText(), channel.required("minimum").asDouble(),
                    channel.required("maximum").asDouble()));
        }
        return new ViewDefinition(
                node.required("series").asInt(),
                new AxisSelection(AxisMode.valueOf(z.required("mode").asText()),
                        z.required("start").asInt(), z.required("end").asInt()),
                new AxisSelection(AxisMode.valueOf(t.required("mode").asText()),
                        t.required("start").asInt(), t.required("end").asInt()),
                channels, RenderProfile.valueOf(node.required("profile").asText()));
    }

    private static void validateView(ViewDefinition view, List<SeriesInfo> images) {
        if (view.series() >= images.size()) throw new IllegalArgumentException("Series is out of range");
        var image = images.get(view.series());
        if (view.z().end() >= image.sizeZ() || view.t().end() >= image.sizeT()
                || view.channels().stream().anyMatch(channel -> channel.channel() >= image.channels())) {
            throw new IllegalArgumentException("View axis or channel is out of range");
        }
    }

    private void serveDatasetViewV2(HttpExchange exchange, String path) throws IOException {
        if (!requireAuthenticated(exchange)) return;
        var matcher = java.util.regex.Pattern.compile(
                "/api/v2/desktop/datasets/([^/]+)/views/([a-f0-9]{64})/(.+)").matcher(path);
        if (!matcher.matches()) {
            respond(exchange, 404, "application/json", "{\"error\":\"not_found\"}");
            return;
        }
        try {
            var dataset = repository.find(matcher.group(1)).orElseThrow(
                    () -> new IllegalArgumentException("Dataset was not found"));
            if (dataset.viewDefinitionJson().isBlank()) {
                throw new IllegalStateException("Dataset has no saved view");
            }
            var node = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(dataset.viewDefinitionJson());
            var view = parseViewDefinition(node);
            var revision = view.revision();
            if (!revision.equals(matcher.group(2))) {
                respond(exchange, 404, "application/json", "{\"error\":\"view_not_found\"}");
                return;
            }
            exchange.getResponseHeaders().set("X-PathLab-View-Revision", revision);
            var relative = matcher.group(3);
            if (immutableNotModified(exchange,
                    dataset.sourceFingerprint() + "|" + revision + "|" + relative)) return;
            var source = conversionService.directView(dataset.id(), view);
            if (relative.equals("slide.dzi")) {
                var descriptor = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Image xmlns=\"http://schemas.microsoft.com/deepzoom/2008\""
                        + " Format=\"jpg\" Overlap=\"0\" TileSize=\"" + source.tileSize() + "\">"
                        + "<Size Width=\"" + source.width() + "\" Height=\"" + source.height()
                        + "\"/></Image>";
                respond(exchange, 200, "application/xml; charset=utf-8", descriptor);
                return;
            }
            var tileMatcher = java.util.regex.Pattern
                    .compile("slide_files/(\\d+)/(\\d+)_(\\d+)\\.jpg").matcher(relative);
            if (!tileMatcher.matches()) {
                respond(exchange, 404, "application/json", "{\"error\":\"asset_not_found\"}");
                return;
            }
            var tile = conversionService.directViewTile(dataset.id(), view,
                    Integer.parseInt(tileMatcher.group(1)),
                    Integer.parseInt(tileMatcher.group(2)),
                    Integer.parseInt(tileMatcher.group(3)));
            respond(exchange, 200, "image/jpeg", tile);
        } catch (IllegalArgumentException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"dataset_not_found\"}");
        } catch (IllegalStateException error) {
            respond(exchange, 409, "application/json",
                    "{\"error\":\"view_not_ready\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void importProjectFolder(HttpExchange exchange) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        try {
            var rawPath = queryValue(exchange, "path", "").trim();
            var folder = rawPath.isEmpty() ? picker.selectFolder() : Path.of(rawPath);
            if (folder == null) {
                respond(exchange, 200, "application/json", datasetsJson(repository.list()));
                return;
            }
            requireDesktopSelection(folder, "directory");
            var imported = 0;
            var failed = new java.util.ArrayList<String>();
            var candidates = ProjectFolderScanner.findSlides(folder);
            var result = importWithLegacyFallback(candidates);
            scheduleSourceVerification(result.datasets());
            imported = result.datasets().size();
            result.diagnostics().forEach(diagnostic -> failed.add(
                    diagnostic.paths().stream().findFirst().map(path -> path.getFileName().toString())
                            .orElse("source") + ": " + diagnostic.detail()));
            var body = datasetsJson(repository.list());
            body = body.substring(0, body.length() - 1)
                    + ",\"project\":{\"root\":" + json(folder.toAbsolutePath().normalize().toString())
                    + ",\"imported\":" + imported + ",\"failed\":" + json(String.join("; ", failed))
                    + "}}";
            respond(exchange, 200, "application/json", body);
        } catch (IOException | IllegalArgumentException error) {
            respond(exchange, 422, "application/json",
                    "{\"error\":\"project_import_failed\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void scheduleSourceVerification(List<LocalDataset> datasets) {
        datasets.stream()
                .filter(dataset -> dataset.status()
                        == org.pathlab.forge.library.DatasetStatus.VERIFYING_SOURCE)
                .forEach(sourceVerificationService::verifyAsync);
    }

    private UniversalDatasetImporter.Result importWithLegacyFallback(List<Path> paths)
            throws IOException {
        var result = universalDatasetImporter.importPaths(paths);
        if (universalReaderAvailable || result.datasets().size() == paths.size()) return result;
        var datasets = new java.util.ArrayList<>(result.datasets());
        var diagnostics = new java.util.ArrayList<>(result.diagnostics());
        for (var path : paths) {
            var normalized = path.toAbsolutePath().normalize();
            if (repository.findBySourcePath(normalized.toString()).isPresent()) continue;
            try {
                var pending = inspector.inspectFast(normalized);
                repository.save(pending);
                datasets.add(pending);
                diagnostics.removeIf(diagnostic -> diagnostic.paths().contains(normalized));
            } catch (DatasetInspectionException ignored) {
                // Keep the capability probe diagnostic for unsupported input.
            }
        }
        return new UniversalDatasetImporter.Result(List.copyOf(datasets), List.copyOf(diagnostics));
    }

    private void prepareDataset(HttpExchange exchange, String id) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        try {
            respond(exchange, 200, "application/json", datasetJson(preparationService.prepare(id)));
        } catch (IllegalArgumentException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"dataset_not_found\"}");
        } catch (IllegalStateException error) {
            respond(
                    exchange,
                    409,
                    "application/json",
                    "{\"error\":\"reader_required\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void inspectDataset(HttpExchange exchange, String id) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        try {
            var dataset = repository.find(id)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Dataset was not found"));
            var series = dataset.status()
                            == org.pathlab.forge.library.DatasetStatus.VERIFYING_SOURCE
                    ? conversionService.inspectWhileVerifying(id)
                    : conversionService.inspect(id);
            respond(
                    exchange,
                    200,
                    "application/json",
                    seriesJson(series));
        } catch (IllegalArgumentException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"dataset_not_found\"}");
        } catch (IllegalStateException error) {
            respond(
                    exchange,
                    409,
                    "application/json",
                    "{\"error\":\"reader_required\",\"detail\":" + json(error.getMessage()) + "}");
        } catch (IOException error) {
            respond(
                    exchange,
                    422,
                    "application/json",
                    "{\"error\":\"inspection_failed\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void datasetSeries(HttpExchange exchange, String id) throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        var dataset = repository.find(id).orElse(null);
        if (dataset == null) {
            respond(exchange, 404, "application/json", "{\"error\":\"dataset_not_found\"}");
            return;
        }
        respond(
                exchange,
                200,
                "application/json",
                seriesJson(conversionService.series(id)));
    }

    private void seriesThumbnail(HttpExchange exchange, String path) throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        var matcher = java.util.regex.Pattern
                .compile("/api/datasets/([^/]+)/series/(\\d+)/thumbnail")
                .matcher(path);
        if (!matcher.matches()) {
            respond(exchange, 404, "application/json", "{\"error\":\"not_found\"}");
            return;
        }
        try {
            var bytes = conversionService.seriesThumbnail(
                    matcher.group(1), Integer.parseInt(matcher.group(2)));
            exchange.getResponseHeaders().set("Cache-Control", "private, max-age=31536000");
            respond(exchange, 200, "image/jpeg", bytes);
        } catch (IllegalArgumentException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"series_not_found\"}");
        } catch (IllegalStateException error) {
            respond(
                    exchange,
                    409,
                    "application/json",
                    "{\"error\":\"source_changed\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void selectSeries(HttpExchange exchange, String id) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        try {
            var series = integerQuery(exchange, "series");
            var downsample = optionalDoubleQuery(exchange, "downsample", 1.0);
            var info = conversionService.series(id).stream()
                    .filter(item -> item.index() == series)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Inspect this dataset before selecting a series"));
            var cropX = optionalIntegerQuery(exchange, "x", 0);
            var cropY = optionalIntegerQuery(exchange, "y", 0);
            var cropWidth = optionalIntegerQuery(exchange, "width", info.width());
            var cropHeight = optionalIntegerQuery(exchange, "height", info.height());
            respond(
                    exchange,
                    200,
                    "application/json",
                    datasetJson(conversionService.selectSeries(
                            id,
                            series,
                            downsample,
                            cropX,
                            cropY,
                            cropWidth,
                            cropHeight)));
        } catch (IllegalArgumentException error) {
            respond(
                    exchange,
                    422,
                    "application/json",
                    "{\"error\":\"invalid_series\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void estimateDataset(HttpExchange exchange, String id) throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        try {
            var dataset = repository.find(id).orElseThrow(
                    () -> new IllegalArgumentException("Dataset was not found"));
            var downsample = optionalDoubleQuery(exchange, "downsample", dataset.downsample());
            var width = optionalIntegerQuery(exchange, "width", dataset.cropWidth());
            var height = optionalIntegerQuery(exchange, "height", dataset.cropHeight());
            var estimate = org.pathlab.forge.conversion.OutputSizeEstimator.compressedOmeTiff(
                    width,
                    height,
                    downsample,
                    dataset.sourceBytes(),
                    dataset.format().isSingleFileTiff());
            respond(
                    exchange,
                    200,
                    "application/json",
                    "{\"outputWidth\":" + Math.max(1, (long) Math.floor(width / downsample))
                            + ",\"outputHeight\":"
                            + Math.max(1, (long) Math.floor(height / downsample))
                            + ",\"fileBytes\":" + estimate.expectedBytes()
                            + ",\"fileLowerBytes\":" + estimate.lowerBytes()
                            + ",\"fileUpperBytes\":" + estimate.upperBytes()
                            + ",\"workspaceBytes\":"
                            + org.pathlab.forge.conversion.OutputSizeEstimator
                                    .managedPeakWorkspace(
                                            width,
                                            height,
                                            downsample,
                                            dataset.sourceBytes(),
                                            dataset.format().isSingleFileTiff())
                            + "}");
        } catch (IllegalArgumentException error) {
            respond(
                    exchange,
                    422,
                    "application/json",
                    "{\"error\":\"invalid_estimate\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void convertDataset(HttpExchange exchange, String id) throws IOException {
        convertDataset(exchange, id, false);
    }
    private void convertDataset(HttpExchange exchange, String id, boolean teaching) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        try {
            respond(
                    exchange,
                    202,
                    "application/json",
                    datasetJson(teaching ? conversionService.startTeaching(id) : conversionService.start(id)));
        } catch (IllegalArgumentException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"dataset_not_found\"}");
        } catch (IllegalStateException error) {
            respond(
                    exchange,
                    409,
                    "application/json",
                    "{\"error\":\"not_ready\",\"detail\":" + json(error.getMessage()) + "}");
        } catch (IOException error) {
            respond(
                    exchange,
                    422,
                    "application/json",
                    "{\"error\":\"conversion_preflight_failed\",\"detail\":"
                            + json(error.getMessage()) + "}");
        }
    }

    private void cancelConversion(HttpExchange exchange, String id) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        try {
            respond(
                    exchange,
                    200,
                    "application/json",
                    datasetJson(conversionService.cancel(id)));
        } catch (IllegalArgumentException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"dataset_not_found\"}");
        }
    }

    private void datasetArtifacts(HttpExchange exchange, String id) throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        try {
            var dataset = repository.find(id).orElseThrow(
                    () -> new IllegalArgumentException("Dataset was not found"));
            var revisions = conversionService.revisions(id);
            respond(
                    exchange,
                    200,
                    "application/json",
                    "{\"currentRevision\":" + json(dataset.currentArtifactRevision())
                            + ",\"approvedRevision\":"
                            + json(dataset.approvedArtifactRevision())
                            + ",\"revisions\":["
                            + revisions.stream()
                                    .map(ForgeServer::artifactRevisionJson)
                                    .collect(java.util.stream.Collectors.joining(","))
                            + "]}");
        } catch (IllegalArgumentException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"dataset_not_found\"}");
        }
    }

    private void approveArtifact(HttpExchange exchange, String path) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        var remainder = path.substring("/api/datasets/".length());
        var separator = remainder.indexOf("/artifacts/");
        var datasetId = remainder.substring(0, separator);
        var revisionId = remainder.substring(
                separator + "/artifacts/".length(),
                remainder.length() - "/approve".length());
        try {
            respond(
                    exchange,
                    200,
                    "application/json",
                    datasetJson(conversionService.approve(datasetId, revisionId)));
        } catch (IllegalArgumentException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"artifact_not_found\"}");
        } catch (IllegalStateException error) {
            respond(
                    exchange,
                    409,
                    "application/json",
                    "{\"error\":\"artifact_not_approvable\",\"detail\":"
                            + json(error.getMessage()) + "}");
        }
    }

    private void renameArtifact(HttpExchange exchange, String path) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        var identifiers = artifactIdentifiers(path, "/rename");
        try {
            respond(
                    exchange,
                    200,
                    "application/json",
                    artifactRevisionJson(conversionService.renameRevision(
                            identifiers[0],
                            identifiers[1],
                            queryValue(exchange, "name", ""))));
        } catch (IllegalArgumentException error) {
            respond(
                    exchange,
                    422,
                    "application/json",
                    "{\"error\":\"invalid_artifact_name\",\"detail\":"
                            + json(error.getMessage()) + "}");
        }
    }

    private void deleteArtifact(HttpExchange exchange, String path) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        var identifiers = artifactIdentifiers(path, "");
        try {
            respond(
                    exchange,
                    200,
                    "application/json",
                    datasetJson(conversionService.deleteRevision(
                            identifiers[0], identifiers[1])));
        } catch (IllegalArgumentException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"artifact_not_found\"}");
        } catch (IllegalStateException error) {
            respond(
                    exchange,
                    409,
                    "application/json",
                    "{\"error\":\"artifact_not_deletable\",\"detail\":"
                            + json(error.getMessage()) + "}");
        }
    }

    private void artifactDerivativeResource(HttpExchange exchange, String path)
            throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        var remainder = path.substring("/api/datasets/".length());
        var artifactSeparator = remainder.indexOf("/artifacts/");
        var derivativeSeparator = remainder.indexOf("/derivative/");
        var datasetId = remainder.substring(0, artifactSeparator);
        var revisionId = remainder.substring(
                artifactSeparator + "/artifacts/".length(), derivativeSeparator);
        var relative = remainder.substring(derivativeSeparator + "/derivative/".length());
        if (!relative.matches("slide\\.dzi|thumbnail\\.jpg|slide_files/\\d+/\\d+_\\d+\\.jpg")) {
            respond(exchange, 404, "application/json", "{\"error\":\"asset_not_found\"}");
            return;
        }
        try {
            if (immutableNotModified(exchange, revisionId + "|" + relative)) {
                return;
            }
            var type = relative.endsWith(".dzi")
                    ? "application/xml; charset=utf-8"
                    : "image/jpeg";
            respond(
                    exchange,
                    200,
                    type,
                    conversionService.derivativeEntry(datasetId, revisionId, relative));
        } catch (IllegalArgumentException | IllegalStateException
                | java.nio.file.NoSuchFileException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"artifact_not_found\"}");
        }
    }

    private void artifactPackageResource(HttpExchange exchange, String path)
            throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        var identifiers = artifactIdentifiers(path, "/package");
        try {
            var revision = conversionService.revisions(identifiers[0]).stream()
                    .filter(item -> item.id().equals(identifiers[1]))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Artifact revision was not found"));
            var file = conversionService
                    .revisionArtifacts(identifiers[0], identifiers[1])
                    .packagePath();
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file)) {
                respond(exchange, 404, "application/json", "{\"error\":\"package_not_found\"}");
                return;
            }
            exchange.getResponseHeaders().set(
                    "Content-Disposition",
                    "attachment; filename=\"" + safeFilename(revision.name()) + ".plslide\"");
            respondFile(exchange, "application/x-tar", file);
        } catch (IllegalArgumentException | IllegalStateException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"artifact_not_found\"}");
        }
    }

    private static String[] artifactIdentifiers(String path, String suffix) {
        var remainder = path.substring("/api/datasets/".length());
        var separator = remainder.indexOf("/artifacts/");
        var datasetId = remainder.substring(0, separator);
        var revisionId = remainder.substring(
                separator + "/artifacts/".length(),
                suffix.isEmpty() ? remainder.length() : remainder.length() - suffix.length());
        return new String[] {datasetId, revisionId};
    }

    private static String safeFilename(String value) {
        var sanitized = value.replaceAll("[^A-Za-z0-9._ -]", "_").strip();
        return sanitized.isBlank() ? "slide" : sanitized;
    }

    private void derivativeResource(HttpExchange exchange, String path) throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        var remainder = path.substring("/api/datasets/".length());
        var separator = remainder.indexOf("/derivative/");
        if (separator <= 0) {
            respond(exchange, 404, "application/json", "{\"error\":\"not_found\"}");
            return;
        }
        var id = remainder.substring(0, separator);
        try {
            var relative = remainder.substring(separator + "/derivative/".length());
            if (!relative.matches("slide\\.dzi|thumbnail\\.jpg|slide_files/\\d+/\\d+_\\d+\\.jpg")) {
                respond(exchange, 404, "application/json", "{\"error\":\"asset_not_found\"}");
                return;
            }
            var type = relative.endsWith(".dzi")
                    ? "application/xml; charset=utf-8"
                    : "image/jpeg";
            var dataset = repository.find(id).orElseThrow(
                    () -> new IllegalArgumentException("Dataset was not found"));
            if (immutableNotModified(
                    exchange, dataset.currentArtifactRevision() + "|" + relative)) {
                return;
            }
            respond(exchange, 200, type, conversionService.derivativeEntry(id, relative));
        } catch (IllegalArgumentException | IllegalStateException | java.nio.file.NoSuchFileException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"dataset_not_found\"}");
        }
    }

    private void previewResource(HttpExchange exchange, String path) throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        var remainder = path.substring("/api/datasets/".length());
        var separator = remainder.indexOf("/preview/");
        if (separator <= 0) {
            respond(exchange, 404, "application/json", "{\"error\":\"not_found\"}");
            return;
        }
        var id = remainder.substring(0, separator);
        var relative = remainder.substring(separator + "/preview/".length());
        if (!relative.matches("slide\\.dzi|thumbnail\\.jpg|slide_files/\\d+/\\d+_\\d+\\.jpg")) {
            respond(exchange, 404, "application/json", "{\"error\":\"asset_not_found\"}");
            return;
        }
        try {
            var cached = conversionService.cachedPreview(id);
            if (cached.isPresent()) {
                exchange.getResponseHeaders().set("X-PathLab-Preview-Mode", "persistent");
                serveCachedPreview(exchange, id, relative, cached.orElseThrow());
                return;
            }
            if (conversionService.supportsDirectPreview()) {
                exchange.getResponseHeaders().set("X-PathLab-Preview-Mode", "direct");
                serveDirectPreview(exchange, id, relative);
                return;
            }
            var state = conversionService.ensurePreviewAsync(id);
            if (state.status().equals("BUILDING")) {
                exchange.getResponseHeaders().set("X-PathLab-Preview-Mode", "preparing");
                respond(
                        exchange,
                        202,
                        "application/json",
                        "{\"status\":\"preparing\",\"detail\":\"Building reusable OME-TIFF viewer pyramid\"}");
                return;
            }
            if (state.status().equals("FAILED")) {
                exchange.getResponseHeaders().set("X-PathLab-Preview-Mode", "failed");
                respond(
                        exchange,
                        409,
                        "application/json",
                        "{\"error\":\"preview_failed\",\"detail\":" + json(state.detail()) + "}");
                return;
            }
            var preview = conversionService.preview(id);
            serveCachedPreview(exchange, id, relative, preview);
        } catch (IllegalArgumentException | IllegalStateException error) {
            respond(
                    exchange,
                    409,
                    "application/json",
                    "{\"error\":\"preview_not_ready\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void serveCachedPreview(
            HttpExchange exchange, String id, String relative, org.pathlab.forge.conversion.LocalPreview preview)
            throws IOException {
        try {
            var root = preview.root().toAbsolutePath().normalize();
            var file = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
            if (!file.startsWith(root)
                    || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file)) {
                respond(exchange, 404, "application/json", "{\"error\":\"asset_not_found\"}");
                return;
            }
            exchange.getResponseHeaders().set(
                    "X-PathLab-Source-Geometry",
                    preview.sourceWidth() + "x" + preview.sourceHeight());
            exchange.getResponseHeaders().set(
                    "X-PathLab-Preview-Geometry", preview.width() + "x" + preview.height());
            var dataset = repository.find(id).orElseThrow(
                    () -> new IllegalArgumentException("Dataset was not found"));
            if (immutableNotModified(
                    exchange, dataset.configurationRevision() + "|" + relative)) {
                return;
            }
            respondFile(
                    exchange,
                    relative.endsWith(".dzi") ? "application/xml; charset=utf-8" : "image/jpeg",
                    file);
        } catch (IllegalArgumentException | IllegalStateException error) {
            respond(
                    exchange,
                    409,
                    "application/json",
                    "{\"error\":\"preview_not_ready\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void serveDirectPreview(HttpExchange exchange, String id, String relative)
            throws IOException {
        var dataset = repository.find(id).orElseThrow(
                () -> new IllegalArgumentException("Dataset was not found"));
        if (immutableNotModified(
                exchange,
                "responsive-v2|" + dataset.sourceFingerprint() + "|"
                        + dataset.selectedSeries() + "|" + relative)) {
            return;
        }
        var source = conversionService.directPreview(id);
        exchange.getResponseHeaders().set(
                "X-PathLab-Source-Geometry", source.width() + "x" + source.height());
        exchange.getResponseHeaders().set(
                "X-PathLab-Preview-Geometry", source.width() + "x" + source.height());
        exchange.getResponseHeaders().set("Cache-Control", "private, max-age=3600");
        if (relative.equals("slide.dzi")) {
            var descriptor = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Image xmlns=\"http://schemas.microsoft.com/deepzoom/2008\""
                    + " Format=\"jpg\" Overlap=\"0\" TileSize=\"" + source.tileSize() + "\">"
                    + "<Size Width=\"" + source.width() + "\" Height=\"" + source.height()
                    + "\"/></Image>";
            respond(exchange, 200, "application/xml; charset=utf-8", descriptor);
            return;
        }
        var matcher = java.util.regex.Pattern
                .compile("slide_files/(\\d+)/(\\d+)_(\\d+)\\.jpg")
                .matcher(relative);
        if (!matcher.matches()) {
            respond(exchange, 404, "application/json", "{\"error\":\"asset_not_found\"}");
            return;
        }
        var tile = conversionService.directPreviewTile(
                id,
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
        respond(exchange, 200, "image/jpeg", tile);
    }

    private static boolean immutableNotModified(HttpExchange exchange, String identity)
            throws IOException {
        var etag = "\"" + sha256(identity.getBytes(StandardCharsets.UTF_8)) + "\"";
        exchange.getResponseHeaders().set("ETag", etag);
        exchange.getResponseHeaders().set(
                "Cache-Control", "private, max-age=31536000, immutable");
        if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            exchange.sendResponseHeaders(304, -1);
            return true;
        }
        return false;
    }

    private void packageResource(HttpExchange exchange, String id) throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        try {
            var file = conversionService.artifacts(id).packagePath();
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file)) {
                respond(exchange, 404, "application/json", "{\"error\":\"package_not_found\"}");
                return;
            }
            exchange.getResponseHeaders().set(
                    "Content-Disposition", "attachment; filename=\"slide.plslide\"");
            respondFile(exchange, "application/x-tar", file);
        } catch (IllegalArgumentException | IllegalStateException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"dataset_not_found\"}");
        }
    }

    private void listAnnotations(HttpExchange exchange, String id) throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        var dataset = repository.find(id).orElse(null);
        if (dataset == null) {
            respond(exchange, 404, "application/json", "{\"error\":\"dataset_not_found\"}");
            return;
        }
        respond(
                exchange,
                200,
                "application/json",
                annotationsJson(annotationsForCurrentView(dataset)));
    }

    private void createAnnotation(HttpExchange exchange, String id) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        var dataset = repository.find(id).orElse(null);
        if (dataset == null) {
            respond(exchange, 404, "application/json", "{\"error\":\"dataset_not_found\"}");
            return;
        }
        try {
            var scope = annotationScope(dataset);
            var expectedConfiguration = queryValue(exchange, "configurationRevision", "");
            if (!expectedConfiguration.isBlank() && !expectedConfiguration.equals(dataset.configurationRevision())) {
                respond(exchange, 409, "application/json", "{\"error\":\"view_changed\"}");
                return;
            }
            var annotation = annotationRepository.create(
                    id,
                    queryValue(exchange, "type", ""),
                    queryValue(exchange, "geometry", ""),
                    queryValue(exchange, "label", ""),
                    queryValue(exchange, "color", "#f3b33d"),
                    scope.series(), scope.z(), scope.t(), scope.viewRevision());
            respond(exchange, 201, "application/json", annotationJson(annotation));
        } catch (IllegalArgumentException error) {
            respond(
                    exchange,
                    422,
                    "application/json",
                    "{\"error\":\"invalid_annotation\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void deleteAnnotation(HttpExchange exchange, String path) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        var remainder = path.substring("/api/datasets/".length());
        var separator = remainder.indexOf("/annotations/");
        var datasetId = remainder.substring(0, separator);
        var annotationId = remainder.substring(separator + "/annotations/".length());
        if (repository.find(datasetId).isEmpty()) {
            respond(exchange, 404, "application/json", "{\"error\":\"dataset_not_found\"}");
            return;
        }
        if (!annotationRepository.delete(datasetId, annotationId)) {
            respond(exchange, 404, "application/json", "{\"error\":\"annotation_not_found\"}");
            return;
        }
        exchange.sendResponseHeaders(204, -1);
    }

    private void deleteDataset(HttpExchange exchange, String id) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        if (repository.find(id).isEmpty()) {
            respond(exchange, 404, "application/json", "{\"error\":\"dataset_not_found\"}");
            return;
        }
        repository.delete(id);
        exchange.sendResponseHeaders(204, -1);
    }

    private void batches(HttpExchange exchange, String path) throws IOException {
        var method = exchange.getRequestMethod();
        if ("GET".equals(method)) { if (!requireAuthenticated(exchange)) return; }
        else if (!requireWriteHeaders(exchange)) return;
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            var parts = path.split("/"); Object result; var status = 200;
            if (parts.length == 3 && "GET".equals(method)) result = batchService.list(
                    Integer.parseInt(queryValue(exchange, "limit", "50")), Integer.parseInt(queryValue(exchange, "offset", "0")));
            else if (parts.length == 3 && "POST".equals(method)) {
                var body = mapper.readTree(boundedAnalysisBody(exchange));
                if (!body.path("datasetIds").isArray()) throw new IllegalArgumentException("Select datasetIds for this batch");
                if (body.has("format") && !"OME_DYNAMIC_V1".equals(body.path("format").asText())) throw new IllegalArgumentException("Select Teaching delivery separately; normal batches use direct OME");
                var ids = new java.util.ArrayList<String>();
                for (var id : body.path("datasetIds")) { if (!id.isTextual()) throw new IllegalArgumentException("Dataset IDs must be strings"); ids.add(id.asText()); }
                result = batchService.create(ids, org.pathlab.forge.conversion.ArtifactRevisionFormat.OME_DYNAMIC_V1); status = 201;
            } else if (parts.length == 4 && "GET".equals(method)) result = batchService.get(parts[3]);
            else if (parts.length == 5 && "report".equals(parts[4]) && "GET".equals(method)) result = batchService.report(parts[3]);
            else if (parts.length == 5 && "cancel".equals(parts[4]) && "POST".equals(method)) result = batchService.cancel(parts[3]);
            else if (parts.length == 5 && "retry".equals(parts[4]) && "POST".equals(method)) {
                var body = mapper.readTree(boundedAnalysisBody(exchange)); result = batchService.retry(parts[3], body.path("datasetId").asText());
            } else if (parts.length == 5 && "export".equals(parts[4]) && "GET".equals(method)) {
                var format = queryValue(exchange, "format", "json"); var content = batchExport(parts[3], format);
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"batch-" + parts[3] + "." + format + "\"");
                respond(exchange, 200, "csv".equals(format) ? "text/csv; charset=utf-8" : "application/json", content); return;
            } else { respond(exchange, 405, "application/json", "{\"error\":\"method_not_allowed\"}"); return; }
            respond(exchange, status, "application/json", mapper.writeValueAsString(result));
        } catch (IllegalArgumentException | IllegalStateException | IOException error) {
            respond(exchange, 409, "application/json", "{\"error\":\"batch_action_failed\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }
    private String batchExport(String id, String format) throws IOException {
        return switch (format) { case "csv" -> batchService.reportCsv(id); case "json" -> batchService.reportJson(id);
            default -> throw new IllegalArgumentException("Batch report format must be csv or json"); };
    }

    private boolean requireAuthenticated(HttpExchange exchange) throws IOException {
        if (authenticated(exchange)) {
            return true;
        }
        respond(exchange, 401, "application/json", "{\"error\":\"unauthorized\"}");
        return false;
    }

    private boolean requireWrite(HttpExchange exchange) throws IOException {
        if (!requireWriteHeaders(exchange)) return false;
        if (exchange.getRequestBody().readNBytes(MAX_WRITE_BYTES + 1).length > MAX_WRITE_BYTES) {
            respond(exchange, 413, "application/json", "{\"error\":\"request_too_large\"}");
            return false;
        }
        return true;
    }

    private boolean requireWriteHeaders(HttpExchange exchange) throws IOException {
        if (!requireAuthenticated(exchange)) {
            return false;
        }
        var origin = exchange.getRequestHeaders().getFirst("Origin");
        var csrf = exchange.getRequestHeaders().getFirst("X-Forge-CSRF");
        if (!constantTimeEquals(baseUri.toString(), origin)
                || !constantTimeEquals(csrfToken, csrf)) {
            respond(exchange, 403, "application/json", "{\"error\":\"forbidden\"}");
            return false;
        }
        return true;
    }

    private String datasetsJson(List<LocalDataset> datasets) {
        return "{\"datasets\":["
                + datasets.stream().map(this::datasetJson).collect(java.util.stream.Collectors.joining(","))
                + "]}";
    }

    private String datasetJson(LocalDataset dataset) {
        long unscopedAnnotations = -1;
        try { unscopedAnnotations = annotationRepository.list(dataset.id()).stream().filter(item -> item.series() < 0 || item.z() < 0 || item.t() < 0 || item.viewRevision().isBlank()).count(); }
        catch (IOException ignored) { /* Unknown remains unknown; originals are not reassigned. */ }
        var progress = conversionService.progress(dataset.id());
        var estimate = dataset.cropWidth() > 0 && dataset.cropHeight() > 0
                ? org.pathlab.forge.conversion.OutputSizeEstimator.compressedOmeTiff(
                        dataset.cropWidth(),
                        dataset.cropHeight(),
                        dataset.downsample(),
                        dataset.sourceBytes(),
                        dataset.format().isSingleFileTiff())
                : null;
        return "{\"id\":" + json(dataset.id())
                + ",\"unscopedAnnotationCount\":" + unscopedAnnotations
                + ",\"displayName\":" + json(dataset.displayName())
                + ",\"sourceBytes\":" + dataset.sourceBytes()
                + ",\"format\":" + json(dataset.format().name())
                + ",\"readerEngine\":" + json(dataset.readerEngine())
                + ",\"readerId\":" + json(dataset.readerId())
                + ",\"formatName\":" + json(dataset.formatName())
                + ",\"runtimeFingerprint\":" + json(dataset.runtimeFingerprint())
                + ",\"viewDefinitionJson\":" + json(dataset.viewDefinitionJson())
                + ",\"viewRevision\":" + json(savedViewRevision(dataset))
                + ",\"status\":" + json(dataset.status().name())
                + ",\"detail\":" + json(dataset.detail())
                + ",\"outputPath\":" + json(dataset.outputPath())
                + ",\"sha256\":" + json(dataset.sha256())
                + ",\"selectedSeries\":" + dataset.selectedSeries()
                + ",\"width\":" + dataset.width()
                + ",\"height\":" + dataset.height()
                + ",\"downsample\":" + dataset.downsample()
                + ",\"estimatedOutputBytes\":" + dataset.estimatedOutputBytes()
                + ",\"projectedFileBytes\":"
                + (estimate == null ? 0 : estimate.expectedBytes())
                + ",\"projectedFileLowerBytes\":"
                + (estimate == null ? 0 : estimate.lowerBytes())
                + ",\"projectedFileUpperBytes\":"
                + (estimate == null ? 0 : estimate.upperBytes())
                + ",\"cropX\":" + dataset.cropX()
                + ",\"cropY\":" + dataset.cropY()
                + ",\"cropWidth\":" + dataset.cropWidth()
                + ",\"cropHeight\":" + dataset.cropHeight()
                + ",\"sourceFingerprint\":" + json(dataset.sourceFingerprint())
                + ",\"sourceInventory\":" + json(dataset.sourceInventory())
                + ",\"configurationRevision\":" + json(dataset.configurationRevision())
                + ",\"currentArtifactRevision\":" + json(dataset.currentArtifactRevision())
                + ",\"approvedArtifactRevision\":" + json(dataset.approvedArtifactRevision())
                + ",\"workspaceRevision\":"
                + Integer.toUnsignedLong(dataset.hashCode())
                + ",\"verificationState\":"
                + json(dataset.sourceFingerprint().isBlank() ? "PENDING" : "VERIFIED")
                + ",\"stage\":"
                + json(progress.stage().isBlank() ? dataset.status().name() : progress.stage())
                + ",\"completedUnits\":" + progress.completedUnits()
                + ",\"totalUnits\":" + progress.totalUnits()
                + ",\"elapsedMs\":" + progress.elapsedMs()
                + ",\"estimatedRemainingMs\":" + progress.estimatedRemainingMs()
                + ",\"unitsPerSecond\":" + progress.unitsPerSecond()
                + ",\"peakWorkingSetBytes\":" + progress.peakWorkingSetBytes()
                + ",\"resourceProfile\":" + json(progress.resourceProfile())
                + ",\"cacheHitReason\":" + json(progress.cacheHitReason())
                + "}";
    }

    private static String savedViewRevision(LocalDataset dataset) {
        if (dataset.viewDefinitionJson().isBlank()) return "";
        try {
            return parseViewDefinition(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(dataset.viewDefinitionJson())).revision();
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    private static String seriesJson(List<SeriesInfo> series) {
        return "{\"series\":["
                + series.stream()
                        .map(item -> "{\"index\":" + item.index()
                                + ",\"name\":" + json(item.name())
                                + ",\"width\":" + item.width()
                                + ",\"height\":" + item.height()
                                + ",\"channels\":" + item.channels()
                                + ",\"sizeZ\":" + item.sizeZ()
                                + ",\"sizeT\":" + item.sizeT()
                                + ",\"pixelType\":" + json(item.pixelType())
                                + ",\"physicalSizeX\":" + item.physicalSizeX()
                                + ",\"physicalSizeY\":" + item.physicalSizeY()
                                + ",\"physicalUnit\":" + json(item.physicalUnit())
                                + ",\"resolutionCount\":" + item.resolutionCount()
                                + ",\"rgbPlane\":" + item.isRgbPlane() + "}")
                        .collect(java.util.stream.Collectors.joining(","))
                + "]}";
    }

    private static String annotationsJson(List<AnnotationRecord> annotations) {
        return "{\"annotations\":["
                + annotations.stream()
                        .map(ForgeServer::annotationJson)
                        .collect(java.util.stream.Collectors.joining(","))
                + "]}";
    }

    private List<AnnotationRecord> annotationsForCurrentView(LocalDataset dataset)
            throws IOException {
        var scope = annotationScope(dataset);
        return annotationRepository.list(dataset.id()).stream()
                .filter(annotation -> annotation.series() == scope.series()
                        && annotation.z() == scope.z()
                        && annotation.t() == scope.t()
                        && annotation.viewRevision().equals(scope.viewRevision()))
                .toList();
    }

    private static AnnotationScope annotationScope(LocalDataset dataset) {
        if (!dataset.viewDefinitionJson().isBlank()) {
            try {
                var view = parseViewDefinition(new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(dataset.viewDefinitionJson()));
                return new AnnotationScope(
                        view.series(), view.z().start(), view.t().start(), view.revision());
            } catch (IOException | RuntimeException ignored) {
                // Fall through to the lossless legacy 2D scope.
            }
        }
        return new AnnotationScope(Math.max(0, dataset.selectedSeries()), 0, 0,
                dataset.configurationRevision());
    }

    private record AnnotationScope(int series, int z, int t, String viewRevision) {}

    private void viewerLibrary(HttpExchange exchange) throws IOException {
        if (!requireAuthenticated(exchange)) return;
        try {
            var items = viewerSyncService.library().stream().map(record -> {
                var slide = record.remote();
                return "{\"id\":" + json(slide.id()) + ",\"displayName\":" + json(slide.displayName())
                        + ",\"folderId\":" + json(slide.folderId()) + ",\"state\":" + json(slide.status())
                        + ",\"visibility\":" + json(viewerVisibility(slide.status()))
                        + ",\"annotationRevision\":" + slide.annotationRevision()
                        + ",\"metadataRevision\":" + slide.metadataRevision()
                        + ",\"updatedAt\":" + json(slide.updatedAt().toString())
                        + ",\"metadata\":" + jsonValue(slide.metadata())
                        + ",\"contentBytes\":" + slide.contentBytes()
                        + ",\"width\":" + slide.metadata().getOrDefault("width", 1)
                        + ",\"height\":" + slide.metadata().getOrDefault("height", 1)
                        + ",\"thumbnailUrl\":\"/api/viewer/preview?path="
                        + java.net.URLEncoder.encode(slide.thumbnailUrl(), StandardCharsets.UTF_8)
                        + "\",\"tileSourceUrl\":" + json("/api/viewer/slides/" + slide.id()
                                + "/preview/slide.dzi") + ",\"offlineBytes\":" + record.downloadOffset()
                        + ",\"offlineComplete\":" + "READY".equals(record.downloadState())
                        + ",\"downloadState\":" + json(record.downloadState())
                        + ",\"downloadDetail\":" + json(record.downloadDetail()) + "}";
            }).collect(java.util.stream.Collectors.joining(","));
            var folders = viewerSyncService.folders().stream()
                    .map(folder -> "{\"id\":" + json(folder.id()) + ",\"name\":" + json(folder.name())
                            + ",\"parentId\":" + json(folder.parentId()) + "}")
                    .collect(java.util.stream.Collectors.joining(","));
            var conflicts = viewerSyncService.conflicts().stream()
                    .map(conflict -> "{\"slideId\":" + json(conflict.slideId())
                            + ",\"field\":" + json(conflict.field())
                            + ",\"localValue\":" + json(conflict.localValue())
                            + ",\"remoteValue\":" + json(conflict.remoteValue())
                            + ",\"baseRevision\":" + conflict.baseRevision()
                            + ",\"remoteRevision\":" + conflict.remoteRevision() + "}")
                    .collect(java.util.stream.Collectors.joining(","));
            respond(exchange, 200, "application/json", "{\"items\":[" + items
                    + "],\"folders\":[" + folders + "],\"conflicts\":[" + conflicts + "]}");
        } catch (IOException error) {
            respond(exchange, 503, "application/json", "{\"error\":\"viewer_sync_unavailable\",\"detail\":"
                    + json(error.getMessage()) + "}");
        }
    }

    private void syncViewerLibrary(HttpExchange exchange) throws IOException {
        if (!requireWrite(exchange)) return;
        try {
            viewerSyncService.syncNow();
            viewerLibrary(exchange);
        } catch (IOException | RuntimeException error) {
            respond(exchange, 503, "application/json", "{\"error\":\"viewer_sync_failed\",\"detail\":"
                    + json(error.getMessage()) + "}");
        }
    }

    private void viewerPreview(HttpExchange exchange) throws IOException {
        if (!requireAuthenticated(exchange)) return;
        try {
            var resource = viewerTileCache.get(queryValue(exchange, "path", ""));
            respondFile(exchange, resource.contentType(), resource.path());
        } catch (IOException | IllegalArgumentException error) {
            respond(exchange, 502, "application/json", "{\"error\":\"viewer_preview_failed\",\"detail\":"
                    + json(error.getMessage()) + "}");
        }
    }

    private void viewerSlidePreview(HttpExchange exchange, String path) throws IOException {
        if (!requireAuthenticated(exchange)) return;
        var remainder = path.substring("/api/viewer/slides/".length());
        var separator = remainder.indexOf("/preview/");
        var id = remainder.substring(0, separator);
        var relative = remainder.substring(separator + "/preview/".length());
        if (!relative.matches("slide\\.dzi|slide_files/\\d+/\\d+_\\d+\\.(?:jpg|jpeg|png)")) {
            respond(exchange, 404, "application/json", "{\"error\":\"viewer_preview_not_found\"}");
            return;
        }
        try {
            var connectionKey = viewerPairingService.connectionKey();
            var retained = viewerSyncService.offlineFile(id);
            if (retained.isPresent()) {
                var source = conversionService.retainedCopyPreview(retained.get());
                byte[] content;
                String contentType;
                if (relative.equals("slide.dzi")) {
                    content = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Image xmlns=\"http://schemas.microsoft.com/deepzoom/2008\" Format=\"jpg\" Overlap=\"0\" TileSize=\""
                            + source.tileSize() + "\"><Size Width=\"" + source.width() + "\" Height=\"" + source.height() + "\"/></Image>")
                            .getBytes(StandardCharsets.UTF_8);
                    contentType = "application/xml; charset=utf-8";
                } else {
                    var match = java.util.regex.Pattern.compile("slide_files/(\\d+)/(\\d+)_(\\d+)\\.(?:jpg|jpeg|png)").matcher(relative);
                    if (!match.matches()) throw new IllegalArgumentException("Invalid tile request");
                    content = conversionService.retainedCopyTile(retained.get(), connectionKey,
                            Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)));
                    contentType = "image/jpeg";
                }
                if (!connectionKey.equals(viewerPairingService.connectionKey())) throw new IOException("Viewer account changed");
                if (!retained.equals(viewerSyncService.offlineFile(id))) throw new IOException("Retained content changed during rendering");
                exchange.getResponseHeaders().set("X-PathLab-Preview-Mode", "verified-offline-ome");
                respond(exchange, 200, contentType, content);
                return;
            }
            var resource = viewerTileCache.get(
                    "/api/v1/desktop/slides/" + id + "/preview/" + relative);
            respondFile(exchange, resource.contentType(), resource.path());
        } catch (IOException | IllegalArgumentException error) {
            respond(exchange, 502, "application/json", "{\"error\":\"viewer_preview_failed\",\"detail\":"
                    + json(error.getMessage()) + "}");
        }
    }

    private void keepViewerSlideOffline(HttpExchange exchange, String path) throws IOException {
        if (!requireWrite(exchange)) return;
        var id = path.substring("/api/viewer/slides/".length(), path.length() - "/offline".length());
        viewerSyncService.keepOfflineAsync(id);
        respond(exchange, 202, "application/json", "{\"state\":\"DOWNLOADING\"}");
    }

    private void removeViewerSlideOffline(HttpExchange exchange, String path) throws IOException {
        if (!requireWrite(exchange)) return;
        var id = path.substring("/api/viewer/slides/".length(), path.length() - "/offline".length());
        viewerSyncService.removeOffline(id);
        exchange.sendResponseHeaders(204, -1);
    }

    private void updateViewerSlideMetadata(HttpExchange exchange, String path) throws IOException {
        if (!requireWrite(exchange)) return;
        var id = path.substring("/api/viewer/slides/".length(), path.length() - "/metadata".length());
        try {
            var updated = viewerSyncService.updateMetadata(id,
                    queryValue(exchange, "displayName", null), queryValue(exchange, "folderId", null));
            respond(exchange, 200, "application/json", "{\"id\":" + json(updated.id())
                    + ",\"displayName\":" + json(updated.displayName()) + "}");
        } catch (IOException | IllegalArgumentException error) {
            respond(exchange, 409, "application/json", "{\"error\":\"viewer_sync_conflict\",\"detail\":"
                    + json(error.getMessage()) + "}");
        }
    }

    private void viewerSlideAnnotations(HttpExchange exchange, String path, boolean write)
            throws IOException {
        if (write ? !requireWrite(exchange) : !requireAuthenticated(exchange)) return;
        var id = path.substring("/api/viewer/slides/".length(), path.length() - "/annotations".length());
        var remotePath = "/api/v1/desktop/slides/" + id + "/annotations" + (write ? "/batch" : "");
        var payload = write ? queryValue(exchange, "payload", "").getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        if (payload.length > MAX_WRITE_BYTES) {
            respond(exchange, 413, "application/json", "{\"error\":\"request_too_large\"}");
            return;
        }
        try (var response = viewerPairingService.request(write ? "POST" : "GET", remotePath,
                write ? java.util.Map.of("Content-Type", "application/json") : java.util.Map.of(), payload)) {
            var bytes = response.body().readNBytes(2 * 1024 * 1024 + 1);
            if (bytes.length > 2 * 1024 * 1024) {
                respond(exchange, 502, "application/json", "{\"error\":\"viewer_response_too_large\"}");
                return;
            }
            respond(exchange, response.status(), "application/json", bytes);
        }
    }

    private void resolveViewerConflict(HttpExchange exchange, String path) throws IOException {
        if (!requireWrite(exchange)) return;
        var id = path.substring("/api/viewer/conflicts/".length(), path.length() - "/resolve".length());
        try {
            viewerSyncService.resolveConflict(id, queryValue(exchange, "field", ""),
                    queryValue(exchange, "resolution", ""));
            exchange.sendResponseHeaders(204, -1);
        } catch (IOException | IllegalArgumentException error) {
            respond(exchange, 409, "application/json", "{\"error\":\"conflict_resolution_failed\",\"detail\":"
                    + json(error.getMessage()) + "}");
        }
    }

    private void cancelViewerUpload(HttpExchange exchange) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        try {
            respond(exchange, 200, "application/json",
                    viewerUploadJson(viewerPairingService.cancelUpload()));
        } catch (IOException | IllegalStateException error) {
            respond(exchange, 409, "application/json",
                    "{\"error\":\"viewer_cancel_failed\",\"detail\":"
                            + json(error.getMessage()) + "}");
        }
    }

    private void artifactOmePreviewResource(HttpExchange exchange, String path)
            throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        var remainder = path.substring("/api/datasets/".length());
        var artifactSeparator = remainder.indexOf("/artifacts/");
        var previewSeparator = remainder.indexOf("/ome-preview/");
        var datasetId = remainder.substring(0, artifactSeparator);
        var revisionId = remainder.substring(
                artifactSeparator + "/artifacts/".length(), previewSeparator);
        var relative = remainder.substring(previewSeparator + "/ome-preview/".length());
        if (!relative.matches("slide\\.dzi|slide_files/\\d+/\\d+_\\d+\\.jpg")) {
            respond(exchange, 404, "application/json", "{\"error\":\"asset_not_found\"}");
            return;
        }
        try {
            if (immutableNotModified(
                    exchange, "direct-ome-v1|" + revisionId + "|" + relative)) {
                return;
            }
            var source = conversionService.directArtifactPreview(datasetId, revisionId);
            exchange.getResponseHeaders().set("X-PathLab-Preview-Mode", "direct-ome");
            exchange.getResponseHeaders().set(
                    "X-PathLab-Preview-Geometry", source.width() + "x" + source.height());
            if (relative.equals("slide.dzi")) {
                var descriptor = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<Image xmlns=\"http://schemas.microsoft.com/deepzoom/2008\""
                        + " Format=\"jpg\" Overlap=\"0\" TileSize=\"" + source.tileSize() + "\">"
                        + "<Size Width=\"" + source.width() + "\" Height=\"" + source.height()
                        + "\"/></Image>";
                respond(exchange, 200, "application/xml; charset=utf-8", descriptor);
                return;
            }
            var matcher = java.util.regex.Pattern
                    .compile("slide_files/(\\d+)/(\\d+)_(\\d+)\\.jpg")
                    .matcher(relative);
            if (!matcher.matches()) {
                respond(exchange, 404, "application/json", "{\"error\":\"asset_not_found\"}");
                return;
            }
            var tile = conversionService.directArtifactPreviewTile(
                    datasetId,
                    revisionId,
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
            respond(exchange, 200, "image/jpeg", tile);
        } catch (IllegalArgumentException | IllegalStateException error) {
            respond(
                    exchange,
                    409,
                    "application/json",
                    "{\"error\":\"ome_preview_not_ready\",\"detail\":"
                            + json(error.getMessage()) + "}");
        }
    }

    private void updateAnnotation(HttpExchange exchange, String path) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        var identifiers = annotationIdentifiers(path, "");
        try {
            var existing = annotationRepository.list(identifiers[0]).stream()
                    .filter(item -> item.id().equals(identifiers[1])).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Annotation was not found"));
            var dataset = repository.find(identifiers[0]).orElseThrow();
            if (annotationsForCurrentView(dataset).stream().noneMatch(item -> item.id().equals(existing.id()))) {
                throw new IllegalStateException("Select the annotation's original view before editing");
            }
            var geometry = queryValue(exchange, "geometry", null);
            var updated = geometry == null ? annotationRepository.updateMetadata(
                    identifiers[0], identifiers[1],
                    queryValue(exchange, "parentId", ""),
                    queryValue(exchange, "classification", ""),
                    Long.parseLong(queryValue(exchange, "revision", "0")))
                    : annotationRepository.updateGeometry(identifiers[0], identifiers[1], geometry,
                            queryValue(exchange, "label", existing.label()),
                            queryValue(exchange, "color", existing.color()),
                            Long.parseLong(queryValue(exchange, "revision", "0")));
            respond(exchange, 200, "application/json", annotationJson(updated));
        } catch (IllegalArgumentException error) {
            respond(exchange, 422, "application/json",
                    "{\"error\":\"invalid_annotation\",\"detail\":" + json(error.getMessage()) + "}");
        } catch (IllegalStateException error) {
            respond(exchange, 409, "application/json",
                    "{\"error\":\"annotation_conflict\",\"detail\":" + json(error.getMessage()) + "}");
        }
    }

    private void annotationMeasurements(HttpExchange exchange, String path) throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        var identifiers = annotationIdentifiers(path, "/measurements");
        var annotation = annotationRepository.list(identifiers[0]).stream()
                .filter(item -> item.id().equals(identifiers[1]))
                .findFirst();
        if (annotation.isEmpty()) {
            respond(exchange, 404, "application/json", "{\"error\":\"annotation_not_found\"}");
            return;
        }
        var values = measuredValues(identifiers[0], annotation.get());
        respond(exchange, 200, "application/json", measurementsJson(annotation.get(), values));
    }

    private void annotationMeasurementsCsv(HttpExchange exchange, String path) throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        var datasetId = path.substring("/api/datasets/".length(), path.length() - "/measurements.csv".length());
        if (repository.find(datasetId).isEmpty()) {
            respond(exchange, 404, "application/json", "{\"error\":\"dataset_not_found\"}");
            return;
        }
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=measurements.csv");
        respond(exchange, 200, "text/csv; charset=utf-8", measurementsCsv(datasetId));
    }

    private String measurementsCsv(String datasetId) throws IOException {
        if (repository.find(datasetId).isEmpty()) throw new IllegalArgumentException("Dataset was not found");
        var rows = new StringBuilder("annotation_id,type,geometry,classification,metric,value,unit,series,z,t,view_revision\r\n");
        for (var annotation : annotationRepository.list(datasetId)) {
            for (var value : measuredValues(datasetId, annotation).entrySet()) {
                var unit = value.getKey().endsWith("Um2") ? "um2" : value.getKey().endsWith("Um") ? "um"
                        : value.getKey().endsWith("Px2") ? "px2" : value.getKey().endsWith("Px") ? "px" : "";
                rows.append(csv(annotation.id())).append(',').append(csv(annotation.type())).append(',').append(csv(annotation.geometry())).append(',')
                        .append(csv(annotation.classification())).append(',').append(csv(value.getKey())).append(',')
                        .append(value.getValue()).append(',').append(csv(unit)).append(',')
                        .append(annotation.series()).append(',').append(annotation.z()).append(',').append(annotation.t())
                        .append(',').append(csv(annotation.viewRevision())).append("\r\n");
                if (rows.length() > 8 * 1024 * 1024) throw new IOException("Measurement export exceeds bounded size");
            }
        }
        return rows.toString();
    }

    private static String[] annotationIdentifiers(String path, String suffix) {
        var remainder = path.substring("/api/datasets/".length(), path.length() - suffix.length());
        var separator = remainder.indexOf("/annotations/");
        return new String[] {
            remainder.substring(0, separator),
            remainder.substring(separator + "/annotations/".length())
        };
    }

    private static String measurementsJson(
            AnnotationRecord annotation, java.util.Map<String, Double> values) {
        return "{\"annotationId\":" + json(annotation.id()) + ",\"units\":"
                + json(values.keySet().stream().anyMatch(key -> key.endsWith("Um") || key.endsWith("Um2"))
                        ? "pixels-and-micrometres" : "pixels") + ",\"values\":{"
                + values.entrySet().stream()
                        .map(entry -> json(entry.getKey()) + ":" + entry.getValue())
                        .collect(java.util.stream.Collectors.joining(","))
                + "}}";
    }

    private java.util.Map<String, Double> measuredValues(String datasetId, AnnotationRecord annotation)
            throws IOException {
        var dataset = repository.find(datasetId).orElseThrow();
        if (annotation.series() < 0 || annotation.z() < 0 || annotation.t() < 0 || annotation.viewRevision().isBlank())
            return GeometryMeasurements.measure(annotation.type(), annotation.geometry());
        var info = conversionService.series(datasetId).stream()
                .filter(item -> item.index() == annotation.series())
                .findFirst().orElse(null);
        var multiplier = info == null ? 0 : switch (info.physicalUnit().toLowerCase(java.util.Locale.ROOT)) {
            case "µm", "μm", "um", "micrometer", "micrometre" -> 1.0;
            case "nm" -> 0.001;
            case "mm" -> 1000.0;
            default -> 0.0;
        };
        return GeometryMeasurements.measure(annotation.type(), annotation.geometry(),
                info == null ? 0 : info.physicalSizeX() * multiplier,
                info == null ? 0 : info.physicalSizeY() * multiplier);
    }

    private static String csv(String value) {
        if (value.matches("(?s)^[\\s]*[=+@-].*") || value.startsWith("\t") || value.startsWith("\r")) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String featuresJson(List<FeaturePackDescriptor> features) {
        return "{\"features\":["
                + features.stream().filter(item -> List.of("pathology-tools", "classical-analysis").contains(item.id()))
                        .map(ForgeServer::featureJson)
                        .collect(java.util.stream.Collectors.joining(","))
                + "],\"capabilities\":["
                + capabilityRegistry.list().stream()
                        .map(item -> "{\"id\":" + json(item.id())
                                + ",\"provider\":" + json(item.provider())
                                + ",\"state\":" + json(item.state())
                                + ",\"detail\":" + json(item.detail()) + "}")
                        .collect(java.util.stream.Collectors.joining(","))
                + "]}";
    }

    private static String featureJson(FeaturePackDescriptor feature) {
        return "{\"id\":" + json(feature.id())
                + ",\"version\":" + json(feature.version())
                + ",\"name\":" + json(feature.name())
                + ",\"kind\":" + json(feature.kind())
                + ",\"state\":" + json(feature.state())
                + ",\"downloadBytes\":" + feature.downloadBytes()
                + ",\"installedBytes\":" + feature.installedBytes()
                + ",\"minimumMemoryBytes\":" + feature.minimumMemoryBytes()
                + ",\"minimumProcessors\":" + feature.minimumProcessors()
                + ",\"pretrained\":" + feature.pretrained()
                + ",\"trainingOnly\":" + feature.trainingOnly()
                + ",\"license\":" + json(feature.license())
                + ",\"activeVersion\":" + json(feature.activeVersion())
                + ",\"installedVersions\":[" + feature.installedVersions().stream().map(ForgeServer::json).collect(java.util.stream.Collectors.joining(",")) + "]"
                + ",\"platforms\":[" + feature.platforms().stream().map(ForgeServer::json).collect(java.util.stream.Collectors.joining(",")) + "]"
                + ",\"minimumCoreVersion\":" + json(feature.minimumCoreVersion())
                + ",\"licenseReviewStatus\":" + json(feature.licenseReviewStatus())
                + ",\"detail\":" + json(feature.detail()) + "}";
    }

    private static String analysisJobJson(AnalysisRun job) {
        return "{\"id\":" + json(job.id())
                + ",\"moduleId\":" + json("pathology." + job.tool())
                + ",\"datasetId\":" + json(job.datasetId())
                + ",\"annotationId\":" + json(job.annotationId())
                + ",\"status\":" + json(job.status())
                + ",\"progress\":" + ("SUCCEEDED".equals(job.status()) ? 1 : 0)
                + ",\"detail\":" + json(job.detail())
                + ",\"createdAt\":" + job.createdAt()
                + ",\"startedAt\":" + job.startedAt()
                + ",\"finishedAt\":" + job.finishedAt() + "}";
    }

    private String viewerConnectionJson(ViewerConnection connection) throws IOException {
        return "{\"connected\":" + connection.connected()
                + ",\"connectionRevision\":" + json(viewerPairingService.connectionKey())
                + ",\"viewerUrl\":" + json(connection.viewerUrl())
                + ",\"deviceName\":" + json(connection.deviceName())
                + ",\"conversionMode\":" + json("OME_DYNAMIC_V1")
                + ",\"scopes\":["
                + connection.scopes().stream()
                        .map(ForgeServer::json)
                        .collect(java.util.stream.Collectors.joining(","))
                + "]}";
    }

    private static String viewerUploadJson(ViewerUploadStatus upload) {
        return "{\"state\":" + json(upload.state())
                + ",\"artifactRevisionId\":" + json(upload.artifactRevisionId())
                + ",\"uploadedBytes\":" + upload.uploadedBytes()
                + ",\"totalBytes\":" + upload.totalBytes()
                + ",\"viewerSlideId\":" + json(upload.viewerSlideId())
                + ",\"viewerSlideSha256\":" + json(upload.viewerSlideSha256())
                + ",\"uploadMode\":" + json(upload.uploadMode())
                + ",\"detail\":" + json(upload.detail()) + "}";
    }

    private static String annotationJson(AnnotationRecord annotation) {
        return "{\"id\":" + json(annotation.id())
                + ",\"type\":" + json(annotation.type())
                + ",\"geometry\":" + json(annotation.geometry())
                + ",\"label\":" + json(annotation.label())
                + ",\"color\":" + json(annotation.color())
                + ",\"createdAt\":" + annotation.createdAt()
                + ",\"parentId\":" + json(annotation.parentId())
                + ",\"classification\":" + json(annotation.classification())
                + ",\"updatedAt\":" + annotation.updatedAt()
                + ",\"revision\":" + annotation.revision()
                + ",\"series\":" + annotation.series()
                + ",\"z\":" + annotation.z()
                + ",\"t\":" + annotation.t()
                + ",\"viewRevision\":" + json(annotation.viewRevision()) + "}";
    }

    private static String artifactRevisionJson(
            org.pathlab.forge.conversion.ArtifactRevision revision) {
        var manifest = packageManifest(revision.packagePath());
        return "{\"id\":" + json(revision.id())
                + ",\"name\":" + json(revision.name())
                + ",\"configurationRevision\":" + json(revision.configurationRevision())
                + ",\"sourceFingerprint\":" + json(revision.sourceFingerprint())
                + ",\"createdAt\":" + revision.createdAt()
                + ",\"status\":" + json(revision.status().name())
                + ",\"format\":" + json(revision.format().name())
                + ",\"omePath\":" + json(revision.omePath())
                + ",\"derivativePath\":" + json(revision.derivativePath())
                + ",\"packagePath\":" + json(revision.packagePath())
                + ",\"omeSha256\":" + json(revision.omeSha256())
                + ",\"omeBytes\":"
                + (revision.format()
                                == org.pathlab.forge.conversion.ArtifactRevisionFormat.OME_DYNAMIC_V1
                        ? regularFileSize(revision.omePath())
                        : manifestLong(manifest, "stagingOmeBytes"))
                + ",\"dziBytes\":" + manifestLong(manifest, "derivativeBytes")
                + ",\"packageBytes\":" + regularFileSize(revision.packagePath())
                + ",\"jpegQuality\":"
                + (revision.format()
                                == org.pathlab.forge.conversion.ArtifactRevisionFormat.OME_DYNAMIC_V1
                        ? revision.omeJpegQuality()
                        : manifestLong(manifest, "quality"))
                + ",\"omeProfile\":" + json(revision.omeProfile())
                + ",\"minimumWindowedSsim\":" + manifestDouble(manifest, "minimumWindowedSsim")
                + ",\"maximumRoiMeanDeltaE00\":" + manifestDouble(manifest, "maximumRoiMeanDeltaE00")
                + ",\"minimumEdgeDetailRetention\":" + manifestDouble(manifest, "minimumEdgeDetailRetention")
                + ",\"encoderProfile\":" + json(manifestString(manifest, "encoderProfile"))
                + ",\"sizeReferenceKind\":"
                + json(manifestString(manifest, "sizeReferenceKind"))
                + ",\"series\":" + manifestLong(manifest, "series")
                + ",\"cropX\":" + manifestLong(manifest, "x")
                + ",\"cropY\":" + manifestLong(manifest, "y")
                + ",\"cropWidth\":" + manifestLong(manifest, "width")
                + ",\"cropHeight\":" + manifestLong(manifest, "height")
                + ",\"downsample\":" + manifestDouble(manifest, "downsample")
                + ",\"packageSha256\":" + json(revision.packageSha256())
                + ",\"outputWidth\":" + revision.outputWidth()
                + ",\"outputHeight\":" + revision.outputHeight()
                + ",\"approvedAt\":" + revision.approvedAt()
                + ",\"failure\":" + json(revision.failure()) + "}";
    }

    private static String packageManifest(String value) {
        try {
            var path = Path.of(value);
            if (!Files.isRegularFile(path) || Files.size(path) < 1024) {
                return "";
            }
            try (var channel = java.nio.channels.FileChannel.open(path)) {
                var header = java.nio.ByteBuffer.allocate(512);
                channel.read(header);
                var raw = header.array();
                var sizeText = new String(
                                raw, 124, 12, java.nio.charset.StandardCharsets.US_ASCII)
                        .replace("\0", "")
                        .trim();
                var size = Long.parseLong(sizeText, 8);
                if (size < 2 || size > 256 * 1024) {
                    return "";
                }
                var content = java.nio.ByteBuffer.allocate(Math.toIntExact(size));
                channel.position(512);
                while (content.hasRemaining() && channel.read(content) >= 0) {
                    // bounded manifest entry
                }
                return new String(
                        content.array(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException ignored) {
            return "";
        }
    }

    private static long manifestLong(String manifest, String name) {
        var match = java.util.regex.Pattern.compile(
                        "\"" + java.util.regex.Pattern.quote(name) + "\"\\s*:\\s*([0-9]+)")
                .matcher(manifest);
        return match.find() ? Long.parseLong(match.group(1)) : 0;
    }

    private static double manifestDouble(String manifest, String name) {
        var match = java.util.regex.Pattern.compile(
                        "\"" + java.util.regex.Pattern.quote(name)
                                + "\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)")
                .matcher(manifest);
        return match.find() ? Double.parseDouble(match.group(1)) : 0;
    }

    private static String manifestString(String manifest, String name) {
        var match = java.util.regex.Pattern.compile(
                        "\"" + java.util.regex.Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(manifest);
        return match.find() ? match.group(1) : "";
    }

    private static long regularFileSize(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            var path = Path.of(value);
            return Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            && !Files.isSymbolicLink(path)
                    ? Files.size(path)
                    : 0;
        } catch (IOException | RuntimeException ignored) {
            return 0;
        }
    }

    private static int integerQuery(HttpExchange exchange, String name) {
        return optionalIntegerQuery(exchange, name, Integer.MIN_VALUE);
    }

    private static int optionalIntegerQuery(
            HttpExchange exchange, String name, int fallback) {
        var value = queryValue(exchange, name, null);
        if (value != null) {
            return Integer.parseInt(value);
        }
        if (fallback != Integer.MIN_VALUE) {
            return fallback;
        }
        throw new IllegalArgumentException("Missing query parameter: " + name);
    }

    private static double optionalDoubleQuery(
            HttpExchange exchange, String name, double fallback) {
        var value = queryValue(exchange, name, null);
        return value == null ? fallback : Double.parseDouble(value);
    }

    private static String queryValue(HttpExchange exchange, String name, String fallback) {
        var query = exchange.getRequestURI().getRawQuery();
        if (query != null) {
            for (var pair : query.split("&")) {
                var parts = pair.split("=", 2);
                if (parts.length == 2
                        && URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals(name)) {
                    return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                }
            }
        }
        return fallback;
    }

    private static String json(String value) {
        var escaped = new StringBuilder(value.length() + 8).append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (current < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) current));
                    } else {
                        escaped.append(current);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static String viewerVisibility(String status) {
        var normalized = status == null ? "" : status.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "published", "public", "shared" -> "published";
            default -> "private";
        };
    }

    private static String jsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return json(text);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof java.util.Map<?, ?> map) {
            return "{" + map.entrySet().stream()
                    .map(entry -> json(String.valueOf(entry.getKey())) + ":" + jsonValue(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",")) + "}";
        }
        if (value instanceof Iterable<?> iterable) {
            var values = new java.util.ArrayList<String>();
            for (var item : iterable) values.add(jsonValue(item));
            return "[" + String.join(",", values) + "]";
        }
        return json(String.valueOf(value));
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private boolean authenticated(HttpExchange exchange) {
        var cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) {
            return false;
        }
        for (var part : cookie.split(";")) {
            var pair = part.trim().split("=", 2);
            if (pair.length == 2
                    && "forge_session".equals(pair[0])
                    && constantTimeEquals(sessionToken, unquoteCookieValue(pair[1]))) {
                return true;
            }
        }
        return false;
    }

    private static String unquoteCookieValue(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void addSecurityHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set(
                "Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                        + "script-src 'self'; connect-src 'self'; frame-ancestors 'none'");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cache-Control", "no-store");
    }

    private static void respond(HttpExchange exchange, int status, String type, String body)
            throws IOException {
        respond(exchange, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String type, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void respondFile(HttpExchange exchange, String type, Path file)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, Files.size(file));
        try (InputStream input = Files.newInputStream(file)) {
            input.transferTo(exchange.getResponseBody());
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        server.stop(0);
        sourceVerificationService.close();
        conversionService.close();
        analysisService.close();
        exportService.close();
        try { featurePackManager.cancelInstall(featurePackManager.progress().id()); } catch (IOException ignored) { /* No active installation. */ }
        viewerSyncService.close();
        viewerPairingService.close();
        executor.shutdownNow();
    }
}

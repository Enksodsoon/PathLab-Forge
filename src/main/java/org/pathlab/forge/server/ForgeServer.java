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
import org.pathlab.forge.analysis.AnalysisJob;
import org.pathlab.forge.analysis.AnalysisJobService;
import org.pathlab.forge.analysis.HeAnalysisService;
import org.pathlab.forge.conversion.BioFormatsEngine;
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
import org.pathlab.forge.model.BatchId;
import org.pathlab.forge.viewer.ViewerConnection;
import org.pathlab.forge.viewer.ViewerPairingService;
import org.pathlab.forge.viewer.SqliteViewerDeliveryStore;
import org.pathlab.forge.viewer.SqliteViewerSyncStore;
import org.pathlab.forge.viewer.ViewerSyncService;
import org.pathlab.forge.viewer.ViewerTileCache;
import org.pathlab.forge.viewer.ViewerUploadStatus;
import org.pathlab.forge.viewer.WindowsCredentialStore;

public final class ForgeServer implements AutoCloseable {
    private static final int MAX_WRITE_BYTES = 65_536;
    private static final int DEFAULT_DESKTOP_PORT = 51_274;
    private static final long SESSION_MAX_AGE_SECONDS = 315_360_000L;
    private final HttpServer server;
    private final ExecutorService executor;
    private final String launchToken;
    private final String sessionToken;
    private final String csrfToken;
    private final URI baseUri;
    private final DatasetRepository repository;
    private final DatasetPicker picker;
    private final DatasetInspector inspector = new DatasetInspector();
    private final DatasetPreparationService preparationService;
    private final SourceVerificationService sourceVerificationService;
    private final ConversionService conversionService;
    private final AnnotationRepository annotationRepository;
    private final FeaturePackManager featurePackManager;
    private final CapabilityRegistry capabilityRegistry;
    private final AnalysisJobService analysisJobService;
    private final ViewerPairingService viewerPairingService;
    private final ViewerSyncService viewerSyncService;
    private final ViewerTileCache viewerTileCache;
    private volatile boolean launchTokenAvailable = true;

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
        preparationService = new DatasetPreparationService(repository, managedRoot);
        sourceVerificationService = new SourceVerificationService(repository);
        conversionService =
                new ConversionService(repository, conversionEngine, derivativeEngine, managedRoot);
        annotationRepository = new AnnotationRepository(managedRoot);
        featurePackManager = new FeaturePackManager(managedRoot.toAbsolutePath().normalize().getParent());
        capabilityRegistry = new CapabilityRegistry(featurePackManager);
        analysisJobService = new AnalysisJobService(
                new HeAnalysisService(repository, annotationRepository, conversionService, managedRoot),
                () -> conversionService.activeConversionCount() > 0,
                () -> featurePackManager.isInstalled("pathology-tools"));
        viewerPairingService = new ViewerPairingService(
                new WindowsCredentialStore(),
                new SqliteViewerDeliveryStore(
                        managedRoot.toAbsolutePath().normalize().getParent().resolve("forge.db")));
        var dataRoot = managedRoot.toAbsolutePath().normalize().getParent();
        viewerSyncService = new ViewerSyncService(
                viewerPairingService, new SqliteViewerSyncStore(dataRoot.resolve("viewer-sync.db")),
                dataRoot.resolve("viewer-offline"));
        viewerTileCache = new ViewerTileCache(viewerPairingService, dataRoot.resolve("viewer-cache"));
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
        var configuredPort = Integer.getInteger("pathlab.forge.port", DEFAULT_DESKTOP_PORT);
        return startConfigured(
                repository,
                new SwingDatasetPicker(),
                paths.managedRoot(),
                BioFormatsEngine.discover(paths.dataRoot()),
                VipsRuntime.discover(paths.dataRoot()),
                configuredPort,
                LocalBrowserSession.loadOrCreate(
                        paths.dataRoot().resolve("browser-session.token")));
    }

    public static ForgeServer start(
            DatasetRepository repository, DatasetPicker picker, Path managedRoot)
            throws IOException {
        return start(
                repository,
                picker,
                managedRoot,
                BioFormatsEngine.discover(managedRoot.toAbsolutePath().normalize().getParent()),
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
                BioFormatsEngine.discover(runtimeRoot),
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
                viewerPairingService.startUpload(
                        dataset.displayName(), revision, annotationRepository.list(dataset.id()),
                        dataset.cropX(), dataset.cropY(), dataset.cropWidth(), dataset.cropHeight(),
                        dataset.downsample());
                return;
            } catch (IOException | IllegalStateException ignored) {
                // Persisted state remains resumable; the UI exposes the paused reason.
            }
        }
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

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            addSecurityHeaders(exchange);
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
            } else if ("/api/features".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                features(exchange);
            } else if (path.matches("/api/features/[a-z0-9][a-z0-9-]{1,63}/install")
                    && "POST".equals(exchange.getRequestMethod())) {
                installFeature(exchange, path);
            } else if (path.matches("/api/features/[a-z0-9][a-z0-9-]{1,63}/disable")
                    && "POST".equals(exchange.getRequestMethod())) {
                disableFeature(exchange, path);
            } else if (path.matches("/api/features/[a-z0-9][a-z0-9-]{1,63}")
                    && "DELETE".equals(exchange.getRequestMethod())) {
                uninstallFeature(exchange, path);
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
            } else if (path.matches("/api/viewer/slides/[A-Za-z0-9_-]{1,128}/offline")
                    && "POST".equals(exchange.getRequestMethod())) {
                keepViewerSlideOffline(exchange, path);
            } else if (path.matches("/api/viewer/slides/[A-Za-z0-9_-]{1,128}/metadata")
                    && "POST".equals(exchange.getRequestMethod())) {
                updateViewerSlideMetadata(exchange, path);
            } else if ("/api/datasets".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                listDatasets(exchange);
            } else if ("/api/datasets/select".equals(path)
                    && "POST".equals(exchange.getRequestMethod())) {
                selectDatasets(exchange);
            } else if ("/api/datasets/import".equals(path)
                    && "POST".equals(exchange.getRequestMethod())) {
                importDataset(exchange);
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
            } else if (path.matches("/api/datasets/[^/]+/upload")
                    && "POST".equals(exchange.getRequestMethod())) {
                uploadApprovedArtifact(
                        exchange,
                        path.substring(
                                "/api/datasets/".length(), path.length() - "/upload".length()));
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
            } else if ("/api/batches".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                createBatch(exchange);
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
        if (!launchTokenAvailable || !constantTimeEquals(launchToken, suppliedToken)) {
            respond(exchange, 401, "application/json", "{\"error\":\"invalid_launch_token\"}");
            return;
        }
        launchTokenAvailable = false;
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

    private void installFeature(HttpExchange exchange, String path) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        var id = path.substring("/api/features/".length(), path.length() - "/install".length());
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
            var job = analysisJobService.submitHe(
                    queryValue(exchange, "datasetId", ""),
                    queryValue(exchange, "annotationId", ""),
                    optionalDoubleQuery(exchange, "hematoxylinThreshold", 0.15),
                    optionalDoubleQuery(exchange, "eosinThreshold", 0.15));
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
                    analysisJobJson(analysisJobService.get(path.substring("/api/analysis/jobs/".length()))));
        } catch (IllegalArgumentException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"analysis_job_not_found\"}");
        }
    }

    private void cancelAnalysisJob(HttpExchange exchange, String path) throws IOException {
        if (!requireWrite(exchange)) return;
        var id = path.substring("/api/analysis/jobs/".length(), path.length() - "/cancel".length());
        try {
            respond(exchange, 200, "application/json", analysisJobJson(analysisJobService.cancel(id)));
        } catch (IllegalArgumentException error) {
            respond(exchange, 404, "application/json", "{\"error\":\"analysis_job_not_found\"}");
        }
    }

    private void appResource(HttpExchange exchange) throws IOException {
        if (authenticated(exchange)) {
            serveResource(exchange, "/web/index.html", "text/html; charset=utf-8");
            return;
        }
        if (!trustedLocalDocumentNavigation(exchange)) {
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

    private void uploadApprovedArtifact(HttpExchange exchange, String id)
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
                    viewerUploadJson(viewerPairingService.startUpload(
                            dataset.displayName(),
                            revision,
                            annotationRepository.list(id),
                            dataset.cropX(),
                            dataset.cropY(),
                            dataset.cropWidth(),
                            dataset.cropHeight(),
                            dataset.downsample())));
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
                        + ",\"maximumConcurrentConversions\":"
                        + conversionService.maximumConcurrentConversions()
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

    private void selectDatasets(HttpExchange exchange) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        for (var selected : picker.select()) {
            try {
                var normalized = selected.toAbsolutePath().normalize().toString();
                if (repository.findBySourcePath(normalized).isEmpty()) {
                    var pending = inspector.inspectFast(selected);
                    repository.save(pending);
                    if (pending.status()
                            == org.pathlab.forge.library.DatasetStatus.VERIFYING_SOURCE) {
                        sourceVerificationService.verifyAsync(pending);
                    }
                }
            } catch (DatasetInspectionException error) {
                respond(
                        exchange,
                        422,
                        "application/json",
                        "{\"error\":" + json(error.code()) + ",\"detail\":"
                                + json(error.getMessage()) + "}");
                return;
            }
        }
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
                    "{\"error\":\"path_required\",\"detail\":\"Enter a local SVS, OME-TIFF or VSI path\"}");
            return;
        }
        try {
            var selected = java.nio.file.Path.of(rawPath).toAbsolutePath().normalize();
            if (repository.findBySourcePath(selected.toString()).isEmpty()) {
                var pending = inspector.inspectFast(selected);
                repository.save(pending);
                if (pending.status()
                        == org.pathlab.forge.library.DatasetStatus.VERIFYING_SOURCE) {
                    sourceVerificationService.verifyAsync(pending);
                }
            }
            respond(exchange, 200, "application/json", datasetsJson(repository.list()));
        } catch (DatasetInspectionException | IllegalArgumentException error) {
            respond(
                    exchange,
                    422,
                    "application/json",
                    "{\"error\":\"import_failed\",\"detail\":" + json(error.getMessage()) + "}");
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
            var imported = 0;
            var failed = new java.util.ArrayList<String>();
            for (var slide : ProjectFolderScanner.findSlides(folder)) {
                try {
                    var normalized = slide.toAbsolutePath().normalize().toString();
                    if (repository.findBySourcePath(normalized).isEmpty()) {
                        var pending = inspector.inspectFast(slide);
                        repository.save(pending);
                        imported++;
                        if (pending.status()
                                == org.pathlab.forge.library.DatasetStatus.VERIFYING_SOURCE) {
                            sourceVerificationService.verifyAsync(pending);
                        }
                    }
                } catch (DatasetInspectionException error) {
                    failed.add(slide.getFileName() + ": " + error.getMessage());
                }
            }
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
        if (repository.find(id).isEmpty()) {
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
        if (!requireWrite(exchange)) {
            return;
        }
        try {
            respond(
                    exchange,
                    202,
                    "application/json",
                    datasetJson(conversionService.start(id)));
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
        if (repository.find(id).isEmpty()) {
            respond(exchange, 404, "application/json", "{\"error\":\"dataset_not_found\"}");
            return;
        }
        respond(
                exchange,
                200,
                "application/json",
                annotationsJson(annotationRepository.list(id)));
    }

    private void createAnnotation(HttpExchange exchange, String id) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        if (repository.find(id).isEmpty()) {
            respond(exchange, 404, "application/json", "{\"error\":\"dataset_not_found\"}");
            return;
        }
        try {
            var annotation = annotationRepository.create(
                    id,
                    queryValue(exchange, "type", ""),
                    queryValue(exchange, "geometry", ""),
                    queryValue(exchange, "label", ""),
                    queryValue(exchange, "color", "#f3b33d"));
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

    private void createBatch(HttpExchange exchange) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        var batchId = BatchId.of(UUID.randomUUID().toString());
        respond(
                exchange,
                201,
                "application/json",
                "{\"batchId\":\"" + batchId.value() + "\",\"state\":\"staged\"}");
    }

    private boolean requireAuthenticated(HttpExchange exchange) throws IOException {
        if (authenticated(exchange)) {
            return true;
        }
        respond(exchange, 401, "application/json", "{\"error\":\"unauthorized\"}");
        return false;
    }

    private boolean requireWrite(HttpExchange exchange) throws IOException {
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
        if (exchange.getRequestBody().readNBytes(MAX_WRITE_BYTES + 1).length > MAX_WRITE_BYTES) {
            respond(exchange, 413, "application/json", "{\"error\":\"request_too_large\"}");
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
                + ",\"displayName\":" + json(dataset.displayName())
                + ",\"sourceBytes\":" + dataset.sourceBytes()
                + ",\"format\":" + json(dataset.format().name())
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

    private void viewerLibrary(HttpExchange exchange) throws IOException {
        if (!requireAuthenticated(exchange)) return;
        try {
            var items = viewerSyncService.library().stream().map(record -> {
                var slide = record.remote();
                return "{\"id\":" + json(slide.id()) + ",\"displayName\":" + json(slide.displayName())
                        + ",\"folderId\":" + json(slide.folderId()) + ",\"state\":" + json(slide.status())
                        + ",\"contentBytes\":" + slide.contentBytes()
                        + ",\"thumbnailUrl\":\"/api/viewer/preview?path="
                        + java.net.URLEncoder.encode(slide.thumbnailUrl(), StandardCharsets.UTF_8)
                        + "\",\"offlineBytes\":" + record.downloadOffset()
                        + ",\"offlineComplete\":" + (record.downloadBytes() > 0
                                && record.downloadOffset() == record.downloadBytes()) + "}";
            }).collect(java.util.stream.Collectors.joining(","));
            var folders = viewerSyncService.folders().stream()
                    .map(folder -> "{\"id\":" + json(folder.id()) + ",\"name\":" + json(folder.name())
                            + ",\"parentId\":" + json(folder.parentId()) + "}")
                    .collect(java.util.stream.Collectors.joining(","));
            var conflicts = viewerSyncService.conflicts().stream()
                    .map(conflict -> "{\"slideId\":" + json(conflict.slideId())
                            + ",\"field\":" + json(conflict.field()) + "}")
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

    private void keepViewerSlideOffline(HttpExchange exchange, String path) throws IOException {
        if (!requireWrite(exchange)) return;
        var id = path.substring("/api/viewer/slides/".length(), path.length() - "/offline".length());
        viewerSyncService.keepOfflineAsync(id);
        respond(exchange, 202, "application/json", "{\"state\":\"DOWNLOADING\"}");
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
            var updated = annotationRepository.updateMetadata(
                    identifiers[0], identifiers[1],
                    queryValue(exchange, "parentId", ""),
                    queryValue(exchange, "classification", ""),
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
        var values = GeometryMeasurements.measure(annotation.get().type(), annotation.get().geometry());
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
        var rows = new StringBuilder("annotation_id,type,classification,metric,value,unit\r\n");
        for (var annotation : annotationRepository.list(datasetId)) {
            for (var value : GeometryMeasurements.measure(annotation.type(), annotation.geometry()).entrySet()) {
                var unit = value.getKey().endsWith("Px2") ? "px2" : value.getKey().endsWith("Px") ? "px" : "";
                rows.append(csv(annotation.id())).append(',').append(csv(annotation.type())).append(',')
                        .append(csv(annotation.classification())).append(',').append(csv(value.getKey())).append(',')
                        .append(value.getValue()).append(',').append(csv(unit)).append("\r\n");
            }
        }
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=measurements.csv");
        respond(exchange, 200, "text/csv; charset=utf-8", rows.toString());
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
        return "{\"annotationId\":" + json(annotation.id()) + ",\"units\":\"pixels\",\"values\":{"
                + values.entrySet().stream()
                        .map(entry -> json(entry.getKey()) + ":" + entry.getValue())
                        .collect(java.util.stream.Collectors.joining(","))
                + "}}";
    }

    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String featuresJson(List<FeaturePackDescriptor> features) {
        return "{\"features\":["
                + features.stream().map(ForgeServer::featureJson)
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
                + ",\"detail\":" + json(feature.detail()) + "}";
    }

    private static String analysisJobJson(AnalysisJob job) {
        return "{\"id\":" + json(job.id())
                + ",\"moduleId\":" + json(job.moduleId())
                + ",\"datasetId\":" + json(job.datasetId())
                + ",\"annotationId\":" + json(job.annotationId())
                + ",\"status\":" + json(job.status())
                + ",\"progress\":" + job.progress()
                + ",\"detail\":" + json(job.detail())
                + ",\"createdAt\":" + job.createdAt()
                + ",\"startedAt\":" + job.startedAt()
                + ",\"finishedAt\":" + job.finishedAt() + "}";
    }

    private static String viewerConnectionJson(ViewerConnection connection) {
        return "{\"connected\":" + connection.connected()
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
                + ",\"revision\":" + annotation.revision() + "}";
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
        analysisJobService.close();
        viewerSyncService.close();
        viewerPairingService.close();
        executor.shutdownNow();
    }
}

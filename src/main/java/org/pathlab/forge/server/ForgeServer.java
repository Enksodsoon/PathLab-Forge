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
import org.pathlab.forge.conversion.BioFormatsEngine;
import org.pathlab.forge.conversion.ConversionEngine;
import org.pathlab.forge.conversion.ConversionService;
import org.pathlab.forge.conversion.SeriesInfo;
import org.pathlab.forge.derivative.DerivativeEngine;
import org.pathlab.forge.derivative.VipsRuntime;
import org.pathlab.forge.library.DatasetInspectionException;
import org.pathlab.forge.library.DatasetInspector;
import org.pathlab.forge.library.DatasetPicker;
import org.pathlab.forge.library.DatasetPreparationService;
import org.pathlab.forge.library.DatasetRepository;
import org.pathlab.forge.library.ForgePaths;
import org.pathlab.forge.library.LocalDataset;
import org.pathlab.forge.library.SqliteDatasetRepository;
import org.pathlab.forge.library.SwingDatasetPicker;
import org.pathlab.forge.library.SourceVerificationService;
import org.pathlab.forge.model.BatchId;
import org.pathlab.forge.viewer.ViewerConnection;
import org.pathlab.forge.viewer.ViewerPairingService;
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
    private final ViewerPairingService viewerPairingService;
    private volatile boolean launchTokenAvailable = true;

    private ForgeServer(
            HttpServer server,
            ExecutorService executor,
            DatasetRepository repository,
            DatasetPicker picker,
            Path managedRoot,
            ConversionEngine conversionEngine,
            DerivativeEngine derivativeEngine,
            String sessionToken) {
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
        viewerPairingService = new ViewerPairingService(new WindowsCredentialStore());
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
        var executor = Executors.newFixedThreadPool(4, runnable -> {
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
        return forgeServer;
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
            } else if ("/api/datasets".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                listDatasets(exchange);
            } else if ("/api/datasets/select".equals(path)
                    && "POST".equals(exchange.getRequestMethod())) {
                selectDatasets(exchange);
            } else if ("/api/datasets/import".equals(path)
                    && "POST".equals(exchange.getRequestMethod())) {
                importDataset(exchange);
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
                        + ",\"activeConversions\":1,\"downsamples\":[1,1.5,2,4,8,16,32]}");
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
                    "{\"error\":\"path_required\",\"detail\":\"Enter a local OME-TIFF or VSI path\"}");
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
                    dataset.format() == org.pathlab.forge.library.DatasetFormat.OME_TIFF);
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
                                            dataset.format()
                                                    == org.pathlab.forge.library.DatasetFormat.OME_TIFF)
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
            if (conversionService.supportsDirectPreview()) {
                serveDirectPreview(exchange, id, relative);
                return;
            }
            var preview = conversionService.preview(id);
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
                dataset.sourceFingerprint() + "|" + dataset.selectedSeries() + "|" + relative)) {
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
                        dataset.format() == org.pathlab.forge.library.DatasetFormat.OME_TIFF)
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

    private static String viewerConnectionJson(ViewerConnection connection) {
        return "{\"connected\":" + connection.connected()
                + ",\"viewerUrl\":" + json(connection.viewerUrl())
                + ",\"deviceName\":" + json(connection.deviceName())
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
                + ",\"detail\":" + json(upload.detail()) + "}";
    }

    private static String annotationJson(AnnotationRecord annotation) {
        return "{\"id\":" + json(annotation.id())
                + ",\"type\":" + json(annotation.type())
                + ",\"geometry\":" + json(annotation.geometry())
                + ",\"label\":" + json(annotation.label())
                + ",\"color\":" + json(annotation.color())
                + ",\"createdAt\":" + annotation.createdAt() + "}";
    }

    private static String artifactRevisionJson(
            org.pathlab.forge.conversion.ArtifactRevision revision) {
        return "{\"id\":" + json(revision.id())
                + ",\"configurationRevision\":" + json(revision.configurationRevision())
                + ",\"sourceFingerprint\":" + json(revision.sourceFingerprint())
                + ",\"createdAt\":" + revision.createdAt()
                + ",\"status\":" + json(revision.status().name())
                + ",\"omePath\":" + json(revision.omePath())
                + ",\"derivativePath\":" + json(revision.derivativePath())
                + ",\"packagePath\":" + json(revision.packagePath())
                + ",\"omeSha256\":" + json(revision.omeSha256())
                + ",\"omeBytes\":" + regularFileSize(revision.omePath())
                + ",\"packageSha256\":" + json(revision.packageSha256())
                + ",\"outputWidth\":" + revision.outputWidth()
                + ",\"outputHeight\":" + revision.outputHeight()
                + ",\"approvedAt\":" + revision.approvedAt()
                + ",\"failure\":" + json(revision.failure()) + "}";
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
                "default-src 'self'; img-src 'self' data:; style-src 'self'; "
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
        viewerPairingService.close();
        executor.shutdownNow();
    }
}

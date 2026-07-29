package org.pathlab.forge.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.file.Path;
import java.util.List;
import org.pathlab.forge.library.DatasetInspectionException;
import org.pathlab.forge.library.DatasetInspector;
import org.pathlab.forge.library.DatasetPicker;
import org.pathlab.forge.library.DatasetPreparationService;
import org.pathlab.forge.library.DatasetRepository;
import org.pathlab.forge.library.ForgePaths;
import org.pathlab.forge.library.LocalDataset;
import org.pathlab.forge.library.PropertiesDatasetRepository;
import org.pathlab.forge.library.SwingDatasetPicker;
import org.pathlab.forge.model.BatchId;

public final class ForgeServer implements AutoCloseable {
    private static final int MAX_WRITE_BYTES = 65_536;
    private static final SecureRandom RANDOM = new SecureRandom();
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
    private volatile boolean launchTokenAvailable = true;

    private ForgeServer(
            HttpServer server,
            ExecutorService executor,
            DatasetRepository repository,
            DatasetPicker picker,
            Path managedRoot) {
        this.server = server;
        this.executor = executor;
        launchToken = randomToken();
        sessionToken = randomToken();
        csrfToken = randomToken();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        this.repository = repository;
        this.picker = picker;
        preparationService = new DatasetPreparationService(repository, managedRoot);
    }

    public static ForgeServer start() throws IOException {
        var paths = ForgePaths.defaults();
        return start(
                new PropertiesDatasetRepository(paths.repositoryFile()),
                new SwingDatasetPicker(),
                paths.managedRoot());
    }

    public static ForgeServer start(
            DatasetRepository repository, DatasetPicker picker, Path managedRoot)
            throws IOException {
        var configuredPort = Integer.getInteger("pathlab.forge.port", 0);
        var address = new InetSocketAddress(InetAddress.getLoopbackAddress(), configuredPort);
        var httpServer = HttpServer.create(address, 32);
        var executor = Executors.newFixedThreadPool(4, runnable -> {
            var thread = new Thread(runnable, "pathlab-forge-http");
            thread.setDaemon(true);
            return thread;
        });
        var forgeServer = new ForgeServer(httpServer, executor, repository, picker, managedRoot);
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

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            addSecurityHeaders(exchange);
            var path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) {
                bootstrap(exchange);
            } else if ("/app".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                authenticatedResource(exchange, "/web/app.html", "text/html; charset=utf-8");
            } else if ("/assets/app.css".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                authenticatedResource(exchange, "/web/app.css", "text/css; charset=utf-8");
            } else if ("/assets/app.js".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                authenticatedResource(
                        exchange, "/web/app.js", "text/javascript; charset=utf-8");
            } else if ("/api/session".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                session(exchange);
            } else if ("/api/capabilities".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                capabilities(exchange);
            } else if ("/api/datasets".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                listDatasets(exchange);
            } else if ("/api/datasets/select".equals(path)
                    && "POST".equals(exchange.getRequestMethod())) {
                selectDatasets(exchange);
            } else if (path.matches("/api/datasets/[^/]+/prepare")
                    && "POST".equals(exchange.getRequestMethod())) {
                prepareDataset(exchange, path.substring("/api/datasets/".length(), path.length() - "/prepare".length()));
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
        var expected = "launchToken=" + launchToken;
        if (!launchTokenAvailable || !constantTimeEquals(expected, exchange.getRequestURI().getQuery())) {
            respond(exchange, 401, "application/json", "{\"error\":\"invalid_launch_token\"}");
            return;
        }
        launchTokenAvailable = false;
        exchange.getResponseHeaders().add(
                "Set-Cookie",
                "forge_session=" + sessionToken + "; Path=/; HttpOnly; SameSite=Strict");
        exchange.getResponseHeaders().set("Location", "/app");
        exchange.sendResponseHeaders(303, -1);
    }

    private void authenticatedResource(HttpExchange exchange, String resource, String type)
            throws IOException {
        if (!authenticated(exchange)) {
            respond(exchange, 401, "application/json", "{\"error\":\"unauthorized\"}");
            return;
        }
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
                        + "\"omeManagedCopy\":true,\"vsiConversion\":false}");
    }

    private void listDatasets(HttpExchange exchange) throws IOException {
        if (!requireAuthenticated(exchange)) {
            return;
        }
        respond(exchange, 200, "application/json", datasetsJson(repository.list()));
    }

    private void selectDatasets(HttpExchange exchange) throws IOException {
        if (!requireWrite(exchange)) {
            return;
        }
        for (var selected : picker.select()) {
            try {
                var normalized = selected.toAbsolutePath().normalize().toString();
                if (repository.findBySourcePath(normalized).isEmpty()) {
                    repository.save(inspector.inspect(selected));
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

    private static String datasetsJson(List<LocalDataset> datasets) {
        return "{\"datasets\":["
                + datasets.stream().map(ForgeServer::datasetJson).collect(java.util.stream.Collectors.joining(","))
                + "]}";
    }

    private static String datasetJson(LocalDataset dataset) {
        return "{\"id\":" + json(dataset.id())
                + ",\"displayName\":" + json(dataset.displayName())
                + ",\"sourceBytes\":" + dataset.sourceBytes()
                + ",\"format\":" + json(dataset.format().name())
                + ",\"status\":" + json(dataset.status().name())
                + ",\"detail\":" + json(dataset.detail())
                + ",\"outputPath\":" + json(dataset.outputPath())
                + ",\"sha256\":" + json(dataset.sha256()) + "}";
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

    private boolean authenticated(HttpExchange exchange) {
        var cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) {
            return false;
        }
        for (var part : cookie.split(";")) {
            var pair = part.trim().split("=", 2);
            if (pair.length == 2
                    && "forge_session".equals(pair[0])
                    && constantTimeEquals(sessionToken, pair[1])) {
                return true;
            }
        }
        return false;
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

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}

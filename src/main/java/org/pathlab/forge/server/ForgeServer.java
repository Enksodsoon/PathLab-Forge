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
    private volatile boolean launchTokenAvailable = true;

    private ForgeServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
        launchToken = randomToken();
        sessionToken = randomToken();
        csrfToken = randomToken();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public static ForgeServer start() throws IOException {
        var address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        var httpServer = HttpServer.create(address, 32);
        var executor = Executors.newFixedThreadPool(4, runnable -> {
            var thread = new Thread(runnable, "pathlab-forge-http");
            thread.setDaemon(true);
            return thread;
        });
        var forgeServer = new ForgeServer(httpServer, executor);
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

    private void createBatch(HttpExchange exchange) throws IOException {
        if (!authenticated(exchange)) {
            respond(exchange, 401, "application/json", "{\"error\":\"unauthorized\"}");
            return;
        }
        var origin = exchange.getRequestHeaders().getFirst("Origin");
        var csrf = exchange.getRequestHeaders().getFirst("X-Forge-CSRF");
        if (!constantTimeEquals(baseUri.toString(), origin) || !constantTimeEquals(csrfToken, csrf)) {
            respond(exchange, 403, "application/json", "{\"error\":\"forbidden\"}");
            return;
        }
        if (exchange.getRequestBody().readNBytes(MAX_WRITE_BYTES + 1).length > MAX_WRITE_BYTES) {
            respond(exchange, 413, "application/json", "{\"error\":\"request_too_large\"}");
            return;
        }
        var batchId = BatchId.of(UUID.randomUUID().toString());
        respond(
                exchange,
                201,
                "application/json",
                "{\"batchId\":\"" + batchId.value() + "\",\"state\":\"staged\"}");
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

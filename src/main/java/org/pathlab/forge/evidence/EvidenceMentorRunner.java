package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Standalone authenticated loopback process for unattended Evidence Mentor jobs. */
public final class EvidenceMentorRunner implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path stateRoot;
    private final String token;
    private final EvidenceJobQueue queue;
    private final HttpServer server;
    private final ScheduledExecutorService worker;
    private final java.util.concurrent.ExecutorService http;

    private EvidenceMentorRunner(Path stateRoot, int port, String token, boolean workerEnabled)
            throws IOException {
        this.stateRoot = stateRoot.toAbsolutePath().normalize();
        this.token = token;
        require(token != null && token.length() >= 32, "Loopback token is too short");
        Files.createDirectories(this.stateRoot);
        queue = new EvidenceJobQueue(this.stateRoot.resolve("jobs.sqlite3"));
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        server.createContext("/health", this::handle);
        server.createContext("/v1/jobs", this::handle);
        http = Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "pathlab-evidence-ipc");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(http);
        server.start();
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "pathlab-evidence-worker");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        if (workerEnabled) worker.scheduleWithFixedDelay(this::processOne, 0, 1, TimeUnit.SECONDS);
    }

    public static EvidenceMentorRunner start(Path stateRoot, int port, String token, boolean worker)
            throws IOException {
        return new EvidenceMentorRunner(stateRoot, port, token, worker);
    }

    public URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    Optional<EvidenceJob> find(String id) throws IOException {
        return queue.find(id);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                respond(exchange, 403, java.util.Map.of("error", "loopback_required"));
                return;
            }
            if (!authorized(exchange)) {
                respond(exchange, 401, java.util.Map.of("error", "authentication_required"));
                return;
            }
            var path = exchange.getRequestURI().getPath();
            if ("/health".equals(path) && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, java.util.Map.of(
                        "schema", "pathlab.evidence-runner-status/1", "status", "ready"));
                return;
            }
            if ("/v1/jobs".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                submit(exchange);
                return;
            }
            var prefix = "/v1/jobs/";
            if (path.startsWith(prefix)) {
                var id = path.substring(prefix.length());
                if (!id.matches("[A-Za-z0-9._-]{1,120}")) {
                    respond(exchange, 404, java.util.Map.of("error", "job_not_found"));
                } else if ("GET".equals(exchange.getRequestMethod())) {
                    var job = queue.find(id);
                    if (job.isEmpty()) respond(exchange, 404, java.util.Map.of("error", "job_not_found"));
                    else respond(exchange, 200, jobJson(job.get()));
                } else if ("DELETE".equals(exchange.getRequestMethod())) {
                    queue.requestCancel(id, Instant.now());
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    respond(exchange, 405, java.util.Map.of("error", "method_not_allowed"));
                }
                return;
            }
            respond(exchange, 404, java.util.Map.of("error", "not_found"));
        } catch (IllegalArgumentException error) {
            respond(exchange, 422, java.util.Map.of("error", "invalid_request", "detail", error.getMessage()));
        } catch (Exception error) {
            respond(exchange, 500, java.util.Map.of("error", "runner_error"));
        } finally {
            exchange.close();
        }
    }

    private void submit(HttpExchange exchange) throws IOException {
        var raw = exchange.getRequestBody().readNBytes(65_537);
        require(raw.length <= 65_536, "Job request is too large");
        JsonNode value = JSON.readTree(raw);
        require(value != null && value.isObject() && fieldNames(value).equals(Set.of("id", "requestPath")),
                "Job submission fields are invalid");
        var id = text(value, "id");
        require(id.matches("[A-Za-z0-9._-]{1,120}"), "Job id is invalid");
        var job = queue.submit(id, Path.of(text(value, "requestPath")), Instant.now());
        respond(exchange, 202, jobJson(job));
    }

    private boolean authorized(HttpExchange exchange) {
        var expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        var supplied = exchange.getRequestHeaders().getFirst("Authorization");
        return supplied != null && MessageDigest.isEqual(
                expected, supplied.getBytes(StandardCharsets.UTF_8));
    }

    private void processOne() {
        var workerId = "cpu-" + ProcessHandle.current().pid();
        try {
            var claimed = queue.claimNext(workerId, Instant.now(), Duration.ofMinutes(2));
            if (claimed.isEmpty()) return;
            try {
                new EvidenceJobProcessor(queue, stateRoot).process(claimed.get(), workerId, Instant.now());
            } catch (EvidenceJobProcessor.CancellationException ignored) {
                // Cancellation was persisted at a checkpoint.
            } catch (IllegalArgumentException permanent) {
                var current = queue.find(claimed.get().id()).orElseThrow();
                var state = permanent.getMessage().contains("not installed")
                        || permanent.getMessage().contains("unsupported")
                        ? EvidenceJobState.UNSUPPORTED : EvidenceJobState.FAILED;
                queue.checkpoint(current.id(), workerId, state, state.name().toLowerCase(),
                        current.progress(), permanent.getMessage(), Instant.now(), Duration.ofMinutes(2));
            } catch (IOException transientFailure) {
                var current = queue.find(claimed.get().id()).orElseThrow();
                if (!current.state().terminal()) {
                    queue.retryOrFail(current.id(), workerId, transientFailure.getMessage(), Instant.now());
                }
            }
        } catch (Exception ignored) {
            // Durable lease expiry permits recovery on next iteration or restart.
        }
    }

    private static java.util.Map<String, Object> jobJson(EvidenceJob job) {
        return java.util.Map.of(
                "id", job.id(), "state", job.state().name().toLowerCase(),
                "stage", job.stage(), "progress", job.progress(),
                "retryCount", job.retryCount(), "cancelRequested", job.cancelRequested(),
                "detail", job.detail(), "updatedAt", job.updatedAt().toString());
    }

    private static Set<String> fieldNames(JsonNode value) {
        var result = new java.util.HashSet<String>();
        value.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static String text(JsonNode value, String field) {
        var node = value.path(field);
        require(node.isTextual() && !node.textValue().isBlank(), "Job field is invalid: " + field);
        return node.textValue();
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        if (exchange.getResponseCode() != -1) return;
        var payload = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    @Override
    public void close() throws IOException {
        server.stop(0);
        worker.shutdownNow();
        http.shutdownNow();
        queue.close();
    }

    public static void main(String[] args) throws Exception {
        var state = defaultStateRoot();
        var port = 8765;
        for (var index = 0; index < args.length; index++) {
            if ("--state".equals(args[index]) && index + 1 < args.length) state = Path.of(args[++index]);
            else if ("--port".equals(args[index]) && index + 1 < args.length) port = Integer.parseInt(args[++index]);
            else throw new IllegalArgumentException("Usage: EvidenceMentorRunner [--state PATH] [--port PORT]");
        }
        Files.createDirectories(state);
        var tokenPath = state.resolve("ipc-token");
        if (!Files.exists(tokenPath)) {
            var bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            Files.writeString(tokenPath, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        }
        var runner = start(state, port, Files.readString(tokenPath).trim(), true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { runner.close(); } catch (IOException ignored) { }
        }));
        new CountDownLatch(1).await();
    }

    public static Path defaultStateRoot() {
        var configured = System.getenv("PATHLAB_EVIDENCE_STATE");
        if (configured != null && !configured.isBlank()) return Path.of(configured);
        var dataDrive = Path.of("D:\\PathLabData");
        if (Files.isDirectory(dataDrive)) return dataDrive.resolve("EvidenceMentor/state");
        var local = System.getenv("LOCALAPPDATA");
        return Path.of(local == null ? System.getProperty("user.home") : local)
                .resolve("PathLab/EvidenceMentor/state");
    }
}

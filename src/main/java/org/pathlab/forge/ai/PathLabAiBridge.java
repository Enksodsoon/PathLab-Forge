package org.pathlab.forge.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.pathlab.forge.viewer.CredentialStore;
import org.pathlab.forge.viewer.ViewerPairingService;

/** Polls the PathLab AI control plane and runs exactly one bounded local job at a time. */
public final class PathLabAiBridge implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> REQUIRED_SCOPES = Set.of(
            "forge:jobs:poll", "forge:jobs:claim", "forge:progress:write", "forge:results:write");
    private final HttpClient client;
    private final CredentialStore credentials;
    private final ViewerPairingService viewer;
    private final Path root;
    private final Path cli;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile String detail = "PathLab AI is not paired";

    public PathLabAiBridge(
            CredentialStore credentials,
            ViewerPairingService viewer,
            Path managedRoot) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),
                credentials,
                viewer,
                managedRoot.resolve("morphology-workflow"),
                configuredCli());
    }

    PathLabAiBridge(
            HttpClient client,
            CredentialStore credentials,
            ViewerPairingService viewer,
            Path root,
            Path cli) {
        this.client = client;
        this.credentials = credentials;
        this.viewer = viewer;
        this.root = root.toAbsolutePath().normalize();
        this.cli = cli;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "pathlab-forge-ai-lab-bridge");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::pollQuietly, 2, 3, TimeUnit.SECONDS);
    }

    public synchronized Connection pair(String serverUrl, String code) throws IOException {
        var base = validateBase(serverUrl);
        if (code == null || !code.matches("[0-9A-HJKMNP-TV-Z]{10}")) {
            throw new IllegalArgumentException("A valid ten-character PathLab AI pairing code is required");
        }
        var body = JSON.createObjectNode();
        body.put("code", code);
        body.put("deviceName", "PathLab Forge morphology worker");
        var response = send(base.resolve("/api/v1/pairing/exchange"), "POST", body.toString(), "");
        require(response, 201, "PathLab AI pairing failed");
        var value = JSON.readTree(response.body());
        List<String> scopes = JSON.readerForListOf(String.class).readValue(value.path("scopes"));
        if (!scopes.containsAll(REQUIRED_SCOPES)) {
            throw new IOException("PathLab AI did not grant the required Forge job scopes");
        }
        var token = requiredText(value, "token");
        credentials.write(base + "\n" + token);
        detail = "Paired and waiting for a PathLab AI job";
        return new Connection(true, base.toString(), scopes, busy.get(), detail);
    }

    public synchronized Connection status() throws IOException {
        var stored = stored();
        return stored == null
                ? new Connection(false, "", List.of(), busy.get(), detail)
                : new Connection(true, stored.base().toString(), List.copyOf(REQUIRED_SCOPES), busy.get(), detail);
    }

    public synchronized void revokeLocal() throws IOException {
        credentials.delete();
        detail = "PathLab AI pairing removed locally";
    }

    void pollOnce() throws IOException {
        var stored = stored();
        if (stored == null || busy.get()) return;
        var response = send(stored.base().resolve("/api/v1/forge/jobs/next"), "GET", "", stored.token());
        if (response.statusCode() == 204) {
            detail = "Paired and waiting for a PathLab AI job";
            return;
        }
        if (response.statusCode() == 401) {
            credentials.delete();
            detail = "PathLab AI credential expired or was revoked";
            return;
        }
        require(response, 200, "PathLab AI job polling failed");
        var job = JSON.readTree(response.body());
        var jobId = requiredText(job, "id");
        if (!busy.compareAndSet(false, true)) return;
        try {
            require(send(stored.base().resolve("/api/v1/forge/jobs/" + jobId + "/claim"), "POST", "{}", stored.token()), 200, "PathLab AI job claim failed");
            execute(stored, job);
        } finally {
            busy.set(false);
        }
    }

    private void execute(Stored stored, JsonNode job) throws IOException {
        var jobId = requiredText(job, "id");
        var kind = requiredText(job, "kind");
        var directory = root.resolve(jobId).normalize();
        if (!directory.startsWith(root)) throw new IOException("Job path escaped the managed root");
        Files.createDirectories(directory);
        var request = directory.resolve("request.json");
        writeAtomically(request, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(job));
        progress(stored, jobId, 1, "request-persisted", kind);
        if (cli == null || !Files.isRegularFile(cli)) {
            fail(stored, jobId, "MORPHOLOGY_RUNTIME_NOT_CONFIGURED", "Configure a pinned PATHLAB_MORPHOLOGY_CLI before running this job.", true);
            detail = "Job blocked: pinned morphology runtime is not configured";
            return;
        }
        var expectedCliSha256 = configuredCliSha256();
        if (expectedCliSha256 == null
                || !sha256(Files.readAllBytes(cli)).equals(expectedCliSha256)) {
            fail(
                    stored,
                    jobId,
                    "MORPHOLOGY_RUNTIME_HASH_INVALID",
                    "The configured morphology worker is not pinned by the expected SHA-256.",
                    true);
            detail = "Job blocked: morphology runtime hash is missing or mismatched";
            return;
        }
        var output = directory.resolve("result.json.partial");
        var log = directory.resolve("worker.log");
        var process = new ProcessBuilder(cli.toString(), "run-job", "--request", request.toString(), "--output", output.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        process.environment().put("OMP_NUM_THREADS", "4");
        process.environment().put("MKL_NUM_THREADS", "4");
        process.environment().put("OPENBLAS_NUM_THREADS", "4");
        process.environment().put("PATHLAB_RESEARCH_ONLY", "1");
        process.environment().put("PATHLAB_NOT_DIAGNOSTIC", "1");
        process.environment().put("PATHLAB_NETWORK_DISABLED", "1");
        progress(stored, jobId, 10, "worker-starting", kind);
        var running = process.start();
        boolean completed = false;
        try {
            for (var poll = 0; poll < 600 && !completed; poll++) {
                completed = running.waitFor(2, TimeUnit.SECONDS);
                if (!completed && cancelRequested(stored, jobId)) {
                    running.destroyForcibly();
                    progress(stored, jobId, 10, "cancelled", kind);
                    detail = "Cancelled " + kind + " for PathLab AI";
                    return;
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            running.destroyForcibly();
            throw new IOException("Morphology job was interrupted", error);
        }
        if (!completed) {
            running.destroyForcibly();
            fail(stored, jobId, "MORPHOLOGY_JOB_TIMEOUT", "The local job exceeded the 20-minute safety limit.", false);
            return;
        }
        if (running.exitValue() != 0 || !Files.isRegularFile(output)) {
            fail(stored, jobId, "MORPHOLOGY_WORKER_FAILED", boundedLog(log), false);
            return;
        }
        progress(stored, jobId, 95, "validating-result", kind);
        var result = JSON.readTree(Files.readString(output, StandardCharsets.UTF_8));
        if (result.path("research_only").asBoolean(false) != true
                || result.path("not_diagnostic").asBoolean(false) != true) {
            fail(stored, jobId, "MORPHOLOGY_SAFETY_MARKERS_REQUIRED", "The worker result omitted mandatory safety markers.", false);
            return;
        }
        var finalResult = directory.resolve("result.json");
        move(output, finalResult);
        if (result.has("ai_lab_schema")) {
            var wrapper = JSON.createObjectNode().set("manifest", result);
            require(send(stored.base().resolve("/api/v1/forge/jobs/" + jobId + "/result"), "POST", wrapper.toString(), stored.token()), 201, "PathLab AI rejected the signed result");
            publishViewerCandidate(job, result);
        } else {
            var complete = JSON.createObjectNode();
            var artifacts = complete.putArray("artifactRefs");
            var artifact = artifacts.addObject();
            artifact.put("kind", "local-result");
            artifact.put("sha256", sha256(Files.readAllBytes(finalResult)));
            require(send(stored.base().resolve("/api/v1/forge/jobs/" + jobId + "/complete"), "POST", complete.toString(), stored.token()), 200, "PathLab AI rejected job completion");
        }
        detail = "Completed " + kind + " for PathLab AI";
    }

    private void publishViewerCandidate(JsonNode job, JsonNode result) throws IOException {
        var slideId = job.path("payload").path("viewerSlideId").asText("");
        var matches = result.path("matches");
        if (slideId.isBlank() || !matches.isArray() || matches.isEmpty()) return;
        var candidate = JSON.createObjectNode();
        candidate.put("sourceFingerprintSha256", requiredText(result, "source_fingerprint_sha256"));
        candidate.put("resultManifestSha256", sha256(JSON.writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsBytes(JSON.convertValue(result, Object.class))));
        candidate.put("adapter", "morphology");
        candidate.put("modelId", result.path("model").path("id").asText("pinned-morphology"));
        candidate.put("researchOnly", true); candidate.put("notDiagnostic", true);
        candidate.put("reviewRequired", true); candidate.put("containsDiagnosis", false);
        candidate.set("resultManifest", result);
        var regions = candidate.putArray("regions");
        for (var index = 0; index < Math.min(5, matches.size()); index++) {
            var match = matches.get(index); var rectangle = match.path("source_rectangle");
            var region = regions.addObject(); region.put("id", java.util.UUID.randomUUID().toString());
            var geometry = region.putObject("geometry"); geometry.put("type", "rectangle");
            geometry.put("x", rectangle.path("x").asDouble()); geometry.put("y", rectangle.path("y").asDouble());
            geometry.put("width", rectangle.path("width").asDouble()); geometry.put("height", rectangle.path("height").asDouble());
            region.put("evidenceKind", match.path("evidence_kind").asText("similar"));
            region.put("similarity", match.path("similarity").asDouble()); region.put("rank", index + 1);
            region.put("crossStain", match.path("cross_stain").asBoolean(false)); region.putArray("morphologyTags");
        }
        viewer.publishMorphologyEvidence(slideId, candidate.toString());
    }

    private void progress(Stored stored, String jobId, int percent, String stage, String kind) throws IOException {
        var body = JSON.createObjectNode(); body.put("progress", percent);
        body.putObject("checkpoint").put("stage", stage).put("kind", kind);
        require(send(stored.base().resolve("/api/v1/forge/jobs/" + jobId + "/progress"), "POST", body.toString(), stored.token()), 200, "PathLab AI rejected job progress");
    }

    private boolean cancelRequested(Stored stored, String jobId) throws IOException {
        var response = send(
                stored.base().resolve("/api/v1/forge/jobs/" + jobId),
                "GET",
                "",
                stored.token());
        require(response, 200, "PathLab AI job status failed");
        return JSON.readTree(response.body()).path("cancelRequested").asBoolean(false);
    }

    private void fail(Stored stored, String jobId, String code, String failureDetail, boolean blocked) throws IOException {
        var body = JSON.createObjectNode(); body.put("code", code); body.put("detail", failureDetail); body.put("blocked", blocked);
        require(send(stored.base().resolve("/api/v1/forge/jobs/" + jobId + "/fail"), "POST", body.toString(), stored.token()), 200, "PathLab AI rejected the job failure");
    }

    private void pollQuietly() {
        try { pollOnce(); } catch (Exception error) { detail = "PathLab AI bridge: " + bounded(error.getMessage()); }
    }

    private HttpResponse<String> send(URI uri, String method, String body, String token) throws IOException {
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(method.equals("GET") ? 20 : 60));
        if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
        if (method.equals("GET")) builder.GET();
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        try { return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("PathLab AI request was interrupted", error); }
    }

    private Stored stored() throws IOException {
        var value = credentials.read(); if (value.isEmpty()) return null;
        var separator = value.get().indexOf('\n');
        if (separator < 1 || separator == value.get().length() - 1) throw new IOException("Stored PathLab AI credential is invalid");
        return new Stored(validateBase(value.get().substring(0, separator)), value.get().substring(separator + 1));
    }

    static URI validateBase(String value) {
        var uri = URI.create(value == null ? "" : value.trim());
        if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null || uri.getHost() == null) throw new IllegalArgumentException("PathLab AI URL is invalid");
        var host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        var loopback = host.equals("localhost")
                || host.equals("::1")
                || host.matches("127(?:\\.[0-9]{1,3}){3}");
        if (!("https".equalsIgnoreCase(uri.getScheme()) || ("http".equalsIgnoreCase(uri.getScheme()) && loopback))) throw new IllegalArgumentException("PathLab AI requires HTTPS except on loopback");
        var path = uri.getPath(); if (path != null && !path.isBlank() && !"/".equals(path)) throw new IllegalArgumentException("PathLab AI URL must not contain a path");
        return URI.create(uri.getScheme().toLowerCase() + "://" + uri.getAuthority() + "/");
    }

    private static void require(HttpResponse<String> response, int expected, String message) throws IOException {
        if (response.statusCode() != expected) throw new IOException(message + " (" + response.statusCode() + "): " + bounded(response.body()));
    }
    private static String requiredText(JsonNode value, String field) throws IOException { var text = value.path(field).asText(""); if (text.isBlank()) throw new IOException("Response omitted " + field); return text; }
    private static String bounded(String value) { if (value == null || value.isBlank()) return "no detail"; return value.length() <= 500 ? value : value.substring(0, 500); }
    private static String boundedLog(Path path) { try { return bounded(Files.readString(path)); } catch (IOException error) { return "Worker failed without a readable log"; } }
    private static String sha256(byte[] bytes) throws IOException { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception error) { throw new IOException("SHA-256 unavailable", error); } }
    private static void writeAtomically(Path target, String value) throws IOException { var partial = target.resolveSibling(target.getFileName() + ".partial"); Files.writeString(partial, value, StandardCharsets.UTF_8); move(partial, target); }
    private static void move(Path source, Path target) throws IOException { try { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); } }
    private static Path configuredCli() { var value = System.getProperty("pathlab.forge.morphologyCli", System.getenv("PATHLAB_MORPHOLOGY_CLI")); return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize(); }
    private static String configuredCliSha256() {
        var value = System.getProperty(
                "pathlab.forge.morphologyCliSha256",
                System.getenv("PATHLAB_MORPHOLOGY_CLI_SHA256"));
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) return null;
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    @Override public void close() { scheduler.shutdownNow(); }
    public record Connection(boolean connected, String serverUrl, List<String> scopes, boolean busy, String detail) { }
    private record Stored(URI base, String token) { }
}

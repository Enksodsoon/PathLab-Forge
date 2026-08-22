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
import java.nio.file.attribute.AclFileAttributeView;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Standalone authenticated loopback process for unattended Evidence Mentor jobs. */
public final class EvidenceMentorRunner implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VERSION = "2.0.11";
    private static final Duration LEASE = Duration.ofSeconds(45);
    private final Path stateRoot;
    private final String token;
    private final EvidenceJobQueue queue;
    private final HttpServer server;
    private final ScheduledExecutorService gpuWorker;
    private final ScheduledExecutorService cpuWorker;
    private final java.util.concurrent.ExecutorService http;
    private final DashboardSessions dashboardSessions = new DashboardSessions();
    private final Instant startedAt = Instant.now();
    private final String bootId = UUID.randomUUID().toString();
    private final Path endpointPath;
    private final String dashboardHtml;

    private EvidenceMentorRunner(Path stateRoot, int port, String token, boolean workerEnabled) throws IOException {
        this.stateRoot = stateRoot.toAbsolutePath().normalize();
        this.token = token;
        require(token != null && token.length() >= 32, "Loopback token is too short");
        Files.createDirectories(this.stateRoot);
        queue = new EvidenceJobQueue(this.stateRoot.resolve("jobs.sqlite3"));
        queue.recoverOrphanedActiveJobs(startedAt);
        dashboardHtml = readResource("/evidence-dashboard/index.html");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32);
        server.createContext("/", this::handle);
        http = Executors.newFixedThreadPool(4, runnable -> daemon(runnable, "pathlab-evidence-ipc", Thread.NORM_PRIORITY));
        server.setExecutor(http);
        server.start();
        endpointPath = this.stateRoot.resolve("endpoint.json");
        publishEndpoint();
        gpuWorker = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "pathlab-evidence-gpu", Thread.NORM_PRIORITY));
        cpuWorker = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "pathlab-evidence-cpu-io", Thread.MIN_PRIORITY));
        if (workerEnabled) {
            gpuWorker.scheduleWithFixedDelay(() -> processOne(EvidenceExecutionLane.GPU), 0, 1, TimeUnit.SECONDS);
            cpuWorker.scheduleWithFixedDelay(() -> processOne(EvidenceExecutionLane.CPU_IO), 0, 1, TimeUnit.SECONDS);
        }
    }

    public static EvidenceMentorRunner start(Path stateRoot, int port, String token, boolean worker) throws IOException {
        return new EvidenceMentorRunner(stateRoot, port, token, worker);
    }

    public URI uri(String path) { return URI.create(origin() + path); }
    Optional<EvidenceJob> find(String id) throws IOException { return queue.find(id); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) returnJson(exchange, 403, Map.of("error", "loopback_required"));
            else if (!validHost(exchange)) returnJson(exchange, 403, Map.of("error", "host_mismatch"));
            else if (!validOriginWhenPresent(exchange)) returnJson(exchange, 403, Map.of("error", "origin_mismatch"));
            else route(exchange);
        } catch (SecurityException error) {
            returnJson(exchange, 403, Map.of("error", "request_forbidden", "detail", safe(error.getMessage())));
        } catch (IllegalArgumentException error) {
            returnJson(exchange, 422, Map.of("error", "invalid_request", "detail", safe(error.getMessage())));
        } catch (IllegalStateException error) {
            returnJson(exchange, 409, Map.of("error", "invalid_state", "detail", safe(error.getMessage())));
        } catch (Exception error) {
            returnJson(exchange, 500, Map.of("error", "runner_error"));
        } finally { exchange.close(); }
    }

    private void route(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath();
        var method = exchange.getRequestMethod();
        if ("/dashboard/".equals(path) && "GET".equals(method)) { returnHtml(exchange); return; }
        if ("/v1/dashboard-session/exchange".equals(path) && "POST".equals(method)) { exchangeDashboardSession(exchange); return; }
        if (!authenticated(exchange)) { returnJson(exchange, 401, Map.of("error", "authentication_required")); return; }
        if ("/health".equals(path) && "GET".equals(method)) {
            returnJson(exchange, 200, Map.of("schema", "pathlab.evidence-runner-status/2", "status", "ready", "bootId", bootId)); return;
        }
        if ("/v1/status".equals(path) && "GET".equals(method)) { status(exchange); return; }
        if ("/v1/jobs".equals(path) && "POST".equals(method)) { submit(exchange); return; }
        if ("/v1/jobs".equals(path) && "GET".equals(method)) { listJobs(exchange); return; }
        if ("/v1/dashboard-sessions".equals(path) && "POST".equals(method)) {
            if (!bearerAuthorized(exchange)) { returnJson(exchange, 401, Map.of("error", "bearer_required")); return; }
            returnJson(exchange, 201, Map.of("code", dashboardSessions.create(Instant.now()), "expiresInSeconds", 60)); return;
        }
        if ("/v1/control/pause".equals(path) && "POST".equals(method)) { mutate(exchange); queue.setAcceptingJobs(false, Instant.now()); control(exchange); return; }
        if ("/v1/control/resume".equals(path) && "POST".equals(method)) { mutate(exchange); queue.setAcceptingJobs(true, Instant.now()); control(exchange); return; }
        if (path.startsWith("/v1/jobs/")) { jobRoute(exchange, path.substring("/v1/jobs/".length())); return; }
        returnJson(exchange, 404, Map.of("error", "not_found"));
    }

    private void jobRoute(HttpExchange exchange, String suffix) throws IOException {
        var parts = suffix.split("/", -1); var id = parts[0];
        if (!id.matches("[A-Za-z0-9._-]{1,120}")) { returnJson(exchange, 404, Map.of("error", "job_not_found")); return; }
        if (parts.length == 1 && "GET".equals(exchange.getRequestMethod())) {
            var job = queue.snapshot(id); if (job.isEmpty()) returnJson(exchange, 404, Map.of("error", "job_not_found")); else returnJson(exchange, 200, jobJson(job.get())); return;
        }
        var cancel = (parts.length == 1 && "DELETE".equals(exchange.getRequestMethod()))
                || (parts.length == 2 && "cancel".equals(parts[1]) && "POST".equals(exchange.getRequestMethod()));
        if (cancel) { mutate(exchange); queue.requestCancel(id, Instant.now()); returnJson(exchange, 202, Map.of("status", "cancellation_requested")); return; }
        if (parts.length == 2 && "retry".equals(parts[1]) && "POST".equals(exchange.getRequestMethod())) {
            mutate(exchange); returnJson(exchange, 202, jobJson(queue.snapshot(queue.retry(id, Instant.now()).id()).orElseThrow())); return;
        }
        returnJson(exchange, 405, Map.of("error", "method_not_allowed"));
    }

    private void exchangeDashboardSession(HttpExchange exchange) throws IOException {
        secure(origin().equals(exchange.getRequestHeaders().getFirst("Origin")), "Exact loopback Origin is required");
        var body = jsonBody(exchange, 1024); require(fieldNames(body).equals(Set.of("code")), "Dashboard exchange fields are invalid");
        var session = dashboardSessions.exchange(text(body, "code"), Instant.now());
        if (session.isEmpty()) { returnJson(exchange, 401, Map.of("error", "invalid_or_expired_code")); return; }
        exchange.getResponseHeaders().add("Set-Cookie", "PathLabEvidenceSession=" + session.get().token() + "; HttpOnly; SameSite=Strict; Path=/");
        returnJson(exchange, 200, Map.of("csrfToken", session.get().csrfToken(), "expiresAt", session.get().expiresAt().toString()));
    }

    private void mutate(HttpExchange exchange) {
        if (bearerAuthorized(exchange)) return;
        var session = session(exchange).orElseThrow(() -> new IllegalArgumentException("Dashboard session is required"));
        secure(origin().equals(exchange.getRequestHeaders().getFirst("Origin")), "Exact loopback Origin is required");
        var supplied = exchange.getRequestHeaders().getFirst("X-PathLab-CSRF");
        secure(supplied != null && MessageDigest.isEqual(session.csrfToken().getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)), "CSRF token is invalid");
    }

    private void status(HttpExchange exchange) throws IOException {
        var jobs = queue.list(null, null, 200);
        long queued = jobs.stream().filter(j -> j.state() == EvidenceJobState.QUEUED).count();
        long active = jobs.stream().filter(j -> !j.state().terminal() && j.state() != EvidenceJobState.QUEUED).count();
        var runtime = Runtime.getRuntime(); var telemetry = new LinkedHashMap<String,Object>();
        telemetry.put("processMemoryUsedBytes", runtime.totalMemory() - runtime.freeMemory());
        telemetry.put("processMemoryLimitBytes", 16L * 1024 * 1024 * 1024);
        var operatingSystem = (com.sun.management.OperatingSystemMXBean)
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        telemetry.put("serviceCpuPercent", Math.max(0, operatingSystem.getProcessCpuLoad() * 100));
        telemetry.put("processCommittedVirtualMemoryBytes", operatingSystem.getCommittedVirtualMemorySize());
        telemetry.put("serviceCpuSeconds", ProcessHandle.current().info().totalCpuDuration()
                .map(Duration::toMillis).orElse(0L) / 1000.0);
        telemetry.put("workers", ProcessHandle.current().children().map(process -> Map.of(
                "pid", process.pid(), "cpuSeconds", process.info().totalCpuDuration()
                        .map(Duration::toMillis).orElse(0L) / 1000.0)).toList());
        telemetry.put("diskUsableBytes", Files.getFileStore(stateRoot).getUsableSpace());
        telemetry.put("gpu", gpuTelemetry()); telemetry.put("quota", new EvidenceQuotaManager(stateRoot).snapshot().buckets());
        var status = new LinkedHashMap<String,Object>();
        status.put("schema", "pathlab.evidence-runner-status/2"); status.put("status", "ready");
        status.put("serviceVersion", VERSION); status.put("bootId", bootId); status.put("startedAt", startedAt.toString());
        status.put("heartbeat", Instant.now().toString()); status.put("networkDisabledForAnalysis", true);
        status.put("acceptingJobs", queue.acceptingJobs());
        status.put("queue", Map.of("queued", queued, "active", active,
                "gpu", jobs.stream().filter(j -> j.lane() == EvidenceExecutionLane.GPU && !j.state().terminal()).count(),
                "cpuIo", jobs.stream().filter(j -> j.lane() == EvidenceExecutionLane.CPU_IO && !j.state().terminal()).count()));
        jobs.stream().filter(j -> !j.state().terminal()).findFirst().ifPresent(job -> status.put("leadingJob", Map.of(
                "id", job.id(), "state", job.state().name().toLowerCase(), "stage", job.stage(),
                "progress", job.progress(), "lane", job.lane().wire())));
        status.put("gpuConcurrency", 1); status.put("cpuIoConcurrency", 1); status.put("vramLimitMiB", 4608);
        status.put("telemetry", telemetry);
        status.put("processMemoryUsedBytes", telemetry.get("processMemoryUsedBytes"));
        status.put("processMemoryLimitBytes", telemetry.get("processMemoryLimitBytes"));
        status.put("diskUsableBytes", telemetry.get("diskUsableBytes")); status.put("quota", telemetry.get("quota"));
        returnJsonCached(exchange, status);
    }

    private void listJobs(HttpExchange exchange) throws IOException {
        var query = query(exchange.getRequestURI().getRawQuery());
        var allowed = Set.of("state", "lane", "limit"); require(allowed.containsAll(query.keySet()), "Unknown job-list filter");
        EvidenceJobState state = query.containsKey("state") ? EvidenceJobState.valueOf(query.get("state").toUpperCase().replace('-', '_')) : null;
        EvidenceExecutionLane lane = query.containsKey("lane") ? EvidenceExecutionLane.fromWire(query.get("lane")) : null;
        var limit = query.containsKey("limit") ? Integer.parseInt(query.get("limit")) : 50;
        returnJsonCached(exchange, Map.of("schema", "pathlab.evidence-job-list/2", "jobs", queue.list(state, lane, limit).stream().map(EvidenceMentorRunner::jobJson).toList()));
    }

    private void submit(HttpExchange exchange) throws IOException {
        var value = jsonBody(exchange, 65_536); require(fieldNames(value).equals(Set.of("id", "requestPath")), "Job submission fields are invalid");
        var id = text(value, "id"); require(id.matches("[A-Za-z0-9._-]{1,120}"), "Job id is invalid");
        var requestPath = Path.of(text(value, "requestPath"));
        var plan = EvidenceJobProcessor.executionPlan(requestPath, id);
        var job = queue.submit(id, requestPath, plan.lane(), plan.packSha256(), Instant.now());
        returnJson(exchange, 202, jobJson(queue.snapshot(job.id()).orElseThrow()));
    }

    private void control(HttpExchange exchange) throws IOException { returnJson(exchange, 200, Map.of("acceptingJobs", queue.acceptingJobs())); }

    private void processOne(EvidenceExecutionLane lane) {
        if (lane == EvidenceExecutionLane.GPU) {
            try (var channel = java.nio.channels.FileChannel.open(stateRoot.resolve("gpu.lock"),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock != null) processClaimed(lane);
            } catch (java.nio.channels.OverlappingFileLockException ignored) {
                // Another runner thread or process owns the single GPU lane.
            } catch (IOException ignored) {
                // Failure to establish the cross-process mutex fails closed for this iteration.
            }
            return;
        }
        processClaimed(lane);
    }

    private void processClaimed(EvidenceExecutionLane lane) {
        var workerId = lane.wire() + "-" + ProcessHandle.current().pid();
        try {
            var claimed = queue.claimNext(lane, workerId, Instant.now(), LEASE); if (claimed.isEmpty()) return;
            try { new EvidenceJobProcessor(queue, stateRoot).process(claimed.get(), workerId, Instant.now()); }
            catch (EvidenceJobProcessor.CancellationException ignored) { }
            catch (ExternalModelWorker.ResourceLimitException resourceFailure) {
                var current = queue.find(claimed.get().id()).orElseThrow();
                if (!current.state().terminal()) queue.fail(current.id(), workerId, "resource", "RESOURCE_LIMIT",
                        resourceFailure.getMessage(), false, Instant.now());
            }
            catch (IllegalArgumentException permanent) {
                var current = queue.find(claimed.get().id()).orElseThrow();
                var state = permanent.getMessage().contains("not installed") || permanent.getMessage().contains("unsupported") ? EvidenceJobState.UNSUPPORTED : EvidenceJobState.FAILED;
                queue.terminate(current.id(), workerId, state, "permanent", permanentCode(permanent.getMessage()),
                        permanent.getMessage(), Instant.now(), LEASE);
            } catch (IOException transientFailure) {
                var current = queue.find(claimed.get().id()).orElseThrow();
                if (!current.state().terminal()) queue.fail(current.id(), workerId, "transient_io", "IO_TRANSIENT", transientFailure.getMessage(), true, Instant.now());
            } catch (Exception unexpected) {
                var current = queue.find(claimed.get().id()).orElseThrow();
                if (!current.state().terminal()) queue.fail(current.id(), workerId, "internal", "RUNNER_INTERNAL",
                        "Evidence worker stopped at a durable checkpoint", false, Instant.now());
            }
        } catch (Exception ignored) { /* lease expiry is the durable recovery path */ }
    }

    private static Map<String,Object> jobJson(EvidenceJobSnapshot job) {
        var result = new LinkedHashMap<String,Object>();
        result.put("schema", "pathlab.evidence-job/2"); result.put("id",job.id()); result.put("state",job.state().name().toLowerCase());
        result.put("stage",job.stage()); result.put("progress",job.progress()); result.put("lane",job.lane().wire());
        result.put("completedUnits",job.completedUnits()); result.put("totalUnits",job.totalUnits()); result.put("throughput",job.throughput());
        result.put("etaSeconds",job.etaSeconds()); result.put("lastHeartbeat",job.lastHeartbeat().toString());
        result.put("leaseExpiresAt",job.leaseExpiresAt().toString()); result.put("retryCount",job.retryCount());
        result.put("nextRetryAt",job.nextRetryAt().toString()); result.put("cancelRequested",job.cancelRequested());
        result.put("failureClass",job.failureClass()); result.put("failureCode",job.failureCode());
        result.put("requestSha256",job.requestSha256()); result.put("checkpointSha256",job.checkpointSha256());
        result.put("packSha256",job.packSha256()); result.put("finalArtifactSha256",job.finalArtifactSha256());
        result.put("detail",job.detail()); result.put("updatedAt",job.updatedAt().toString()); return result;
    }

    private boolean authenticated(HttpExchange exchange) { return bearerAuthorized(exchange) || session(exchange).isPresent(); }
    private boolean bearerAuthorized(HttpExchange exchange) {
        var supplied=exchange.getRequestHeaders().getFirst("Authorization"); if(supplied==null)return false;
        return MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8));
    }
    private Optional<DashboardSessions.Session> session(HttpExchange exchange) {
        var cookie=exchange.getRequestHeaders().getFirst("Cookie"); if(cookie==null)return Optional.empty();
        for(var part:cookie.split(";")){var item=part.trim();if(item.startsWith("PathLabEvidenceSession="))return dashboardSessions.find(item.substring(item.indexOf('=')+1),Instant.now());}
        return Optional.empty();
    }
    private boolean validHost(HttpExchange exchange){return ("127.0.0.1:"+server.getAddress().getPort()).equals(exchange.getRequestHeaders().getFirst("Host"));}
    private boolean validOriginWhenPresent(HttpExchange exchange){var origin=exchange.getRequestHeaders().getFirst("Origin");return origin==null||origin().equals(origin);}
    private String origin(){return "http://127.0.0.1:"+server.getAddress().getPort();}

    private void publishEndpoint() throws IOException {
        var body=JSON.writeValueAsBytes(Map.of("schema","pathlab.runner-endpoint/1","port",server.getAddress().getPort(),"serviceVersion",VERSION,
                "bootId",bootId,"pid",ProcessHandle.current().pid(),"startedAt",startedAt.toString()));
        var partial=endpointPath.resolveSibling("endpoint.json.partial"); Files.write(partial,body);
        preserveEndpointAcl(endpointPath, partial);
        try{Files.move(partial,endpointPath,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
        catch(java.nio.file.AtomicMoveNotSupportedException ignored){Files.move(partial,endpointPath,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
    }

    private static void preserveEndpointAcl(Path endpoint, Path partial) throws IOException {
        if (!Files.exists(endpoint)) return;
        var source = Files.getFileAttributeView(endpoint, AclFileAttributeView.class);
        var target = Files.getFileAttributeView(partial, AclFileAttributeView.class);
        if (source != null && target != null) target.setAcl(source.getAcl());
    }

    private static Map<String,String> query(String raw){var result=new java.util.HashMap<String,String>();if(raw==null||raw.isBlank())return result;for(var part:raw.split("&")){var pair=part.split("=",2);require(pair.length==2&&!pair[0].isBlank(),"Invalid query");result.put(pair[0],java.net.URLDecoder.decode(pair[1],StandardCharsets.UTF_8));}return result;}
    private static JsonNode jsonBody(HttpExchange exchange,int limit)throws IOException{var raw=exchange.getRequestBody().readNBytes(limit+1);require(raw.length<=limit,"Request body is too large");var value=JSON.readTree(raw);require(value!=null&&value.isObject(),"JSON object required");return value;}
    private static Set<String> fieldNames(JsonNode value){var result=new java.util.HashSet<String>();value.fieldNames().forEachRemaining(result::add);return result;}
    private static String text(JsonNode value,String field){var node=value.path(field);require(node.isTextual()&&!node.textValue().isBlank(),"Field is invalid: "+field);return node.textValue();}
    private static void returnJson(HttpExchange exchange,int status,Object body)throws IOException{if(exchange.getResponseCode()!=-1)return;var payload=JSON.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");exchange.sendResponseHeaders(status,payload.length);exchange.getResponseBody().write(payload);}
    private static void returnJsonCached(HttpExchange exchange,Object body)throws IOException{var payload=JSON.writeValueAsBytes(body);var etag="\""+digest(payload)+"\"";exchange.getResponseHeaders().set("ETag",etag);exchange.getResponseHeaders().set("Cache-Control","private, max-age=0, must-revalidate");if(etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))){exchange.sendResponseHeaders(304,-1);return;}exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");exchange.sendResponseHeaders(200,payload.length);exchange.getResponseBody().write(payload);}
    private void returnHtml(HttpExchange exchange)throws IOException{var payload=dashboardHtml.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'");exchange.sendResponseHeaders(200,payload.length);exchange.getResponseBody().write(payload);}
    private static String readResource(String name)throws IOException{try(var input=EvidenceMentorRunner.class.getResourceAsStream(name)){if(input==null)throw new IOException("Dashboard resource missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);}}
    private static Map<String,Object> gpuTelemetry(){try{var process=new ProcessBuilder("nvidia-smi","--query-gpu=utilization.gpu,memory.used,memory.total","--format=csv,noheader,nounits").redirectErrorStream(true).start();if(!process.waitFor(2,TimeUnit.SECONDS)){process.destroyForcibly();return Map.of("available",false);}var parts=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim().split(",");if(process.exitValue()!=0||parts.length<3)return Map.of("available",false);return Map.of("available",true,"utilizationPercent",Integer.parseInt(parts[0].trim()),"vramUsedMiB",Integer.parseInt(parts[1].trim()),"vramTotalMiB",Integer.parseInt(parts[2].trim()));}catch(Exception ignored){return Map.of("available",false);}}
    private static Thread daemon(Runnable runnable,String name,int priority){var thread=new Thread(runnable,name);thread.setDaemon(true);thread.setPriority(priority);return thread;}
    private static String safe(String value){return value==null?"Request was refused":value;}
    private static String digest(byte[] value){try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(java.security.GeneralSecurityException impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}}
    private static String permanentCode(String detail){var value=detail==null?"":detail.toLowerCase();if(value.contains("license")||value.contains("rights"))return "RIGHTS_OR_LICENSE";if(value.contains("checksum"))return "CHECKSUM_MISMATCH";if(value.contains("stain"))return "STAIN_UNSUPPORTED";if(value.contains("schema"))return "SCHEMA_UNSUPPORTED";if(value.contains("not installed"))return "PACK_NOT_INSTALLED";if(value.contains("unsupported"))return "CAPABILITY_UNSUPPORTED";return "VALIDATION_FAILED";}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
    private static void secure(boolean condition,String message){if(!condition)throw new SecurityException(message);}

    @Override public void close()throws IOException{server.stop(0);gpuWorker.shutdownNow();cpuWorker.shutdownNow();http.shutdownNow();try{if(Files.isRegularFile(endpointPath)){var endpoint=JSON.readTree(endpointPath.toFile());if(bootId.equals(endpoint.path("bootId").asText()))Files.deleteIfExists(endpointPath);}}finally{queue.close();}}

    public static void main(String[] args)throws Exception{
        var state=defaultStateRoot();var port=0;
        for(var i=0;i<args.length;i++){if("--state".equals(args[i])&&i+1<args.length)state=Path.of(args[++i]);else if("--port".equals(args[i])&&i+1<args.length)port=Integer.parseInt(args[++i]);else throw new IllegalArgumentException("Usage: EvidenceMentorRunner [--state PATH] [--port PORT]");}
        Files.createDirectories(state);var tokenPath=state.resolve("ipc-token");
        if(!Files.exists(tokenPath)){var bytes=new byte[32];new SecureRandom().nextBytes(bytes);Files.writeString(tokenPath,Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));}
        var runner=start(state,port,Files.readString(tokenPath).trim(),true);Runtime.getRuntime().addShutdownHook(new Thread(()->{try{runner.close();}catch(IOException ignored){}}));new CountDownLatch(1).await();
    }
    public static Path defaultStateRoot(){var configured=System.getenv("PATHLAB_EVIDENCE_STATE");if(configured!=null&&!configured.isBlank())return Path.of(configured);var data=Path.of("D:\\PathLabData");if(Files.isDirectory(data))return data.resolve("EvidenceMentor/state");var local=System.getenv("LOCALAPPDATA");return Path.of(local==null?System.getProperty("user.home"):local).resolve("PathLab/EvidenceMentor/state");}
}

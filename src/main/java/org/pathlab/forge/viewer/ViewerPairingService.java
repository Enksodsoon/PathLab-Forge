package org.pathlab.forge.viewer;

import java.io.IOException;
import java.net.URI;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.pathlab.forge.annotation.AnnotationRecord;
import org.pathlab.forge.annotation.AnnotationTransformer;
import org.pathlab.forge.conversion.ArtifactRevision;

public final class ViewerPairingService implements AutoCloseable {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int UPLOAD_CHUNK_BYTES = 16 * 1024 * 1024;
    private static final ProxySelector DIRECT_LOOPBACK = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException error) {
            // A loopback Viewer has no proxy fallback by design.
        }
    };
    private final HttpClient client;
    private final CredentialStore credentialStore;
    private final ExecutorService uploadExecutor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "pathlab-forge-viewer-upload");
        thread.setDaemon(true);
        return thread;
    });
    private PendingPairing pending;
    private volatile ActiveUpload activeUpload;
    private volatile ViewerUploadStatus uploadStatus = ViewerUploadStatus.idle();

    public ViewerPairingService(CredentialStore credentialStore) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .proxy(DIRECT_LOOPBACK)
                        .version(HttpClient.Version.HTTP_1_1)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                credentialStore);
    }

    ViewerPairingService(HttpClient client, CredentialStore credentialStore) {
        this.client = client;
        this.credentialStore = credentialStore;
    }

    public synchronized ViewerPairing start(String viewerUrl) throws IOException {
        var base = validateBase(viewerUrl);
        var response = sendJson(
                base.resolve("/api/v1/desktop/pairings"),
                "{\"deviceName\":\"PathLab Forge on Windows\"}",
                "");
        requireStatus(response, 201, "Viewer rejected the pairing request");
        var body = response.body();
        pending = new PendingPairing(
                base,
                string(body, "deviceCode"),
                string(body, "deviceSecret"));
        return new ViewerPairing(
                string(body, "userCode"),
                string(body, "verificationUrl"),
                string(body, "expiresAt"));
    }

    public synchronized ViewerConnection exchange() throws IOException {
        if (pending == null) {
            throw new IllegalStateException("Start Viewer pairing first");
        }
        var response = sendJson(
                pending.base().resolve("/api/v1/desktop/pairings/exchange"),
                "{\"deviceCode\":\""
                        + escape(pending.deviceCode())
                        + "\",\"deviceSecret\":\""
                        + escape(pending.deviceSecret())
                        + "\"}",
                "");
        requireStatus(response, 200, "Viewer pairing is not approved yet");
        var accessToken = string(response.body(), "accessToken");
        credentialStore.write(pending.base() + "\n" + accessToken);
        var result = new ViewerConnection(
                true,
                pending.base().toString(),
                "PathLab Forge on Windows",
                strings(response.body(), "scopes"));
        pending = null;
        return result;
    }

    public synchronized ViewerConnection status() throws IOException {
        var stored = credentialStore.read();
        if (stored.isEmpty()) {
            return new ViewerConnection(false, "", "", List.of());
        }
        var separator = stored.get().indexOf('\n');
        if (separator <= 0 || separator == stored.get().length() - 1) {
            throw new IOException("Stored Viewer credential is invalid");
        }
        var base = validateBase(stored.get().substring(0, separator));
        var token = stored.get().substring(separator + 1);
        var request = HttpRequest.newBuilder(base.resolve("/api/v1/desktop/credential"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        var response = send(request);
        if (response.statusCode() == 401) {
            credentialStore.delete();
            return new ViewerConnection(false, base.toString(), "", List.of());
        }
        requireStatus(response, 200, "Viewer credential status failed");
        return new ViewerConnection(
                true,
                base.toString(),
                string(response.body(), "deviceName"),
                strings(response.body(), "scopes"));
    }

    public synchronized void revoke() throws IOException {
        var stored = storedCredential();
        if (stored == null) {
            return;
        }
        var response = sendJson(
                stored.base().resolve("/api/v1/desktop/credential/revoke"),
                "{}",
                "Bearer " + stored.token());
        requireStatus(response, 204, "Viewer credential revocation failed");
        credentialStore.delete();
        activeUpload = null;
    }

    public synchronized ViewerUploadStatus startUpload(
            String displayName,
            ArtifactRevision revision,
            List<AnnotationRecord> annotations,
            int cropX,
            int cropY,
            int cropWidth,
            int cropHeight,
            double downsample)
            throws IOException {
        if ("UPLOADING".equals(uploadStatus.state())) {
            throw new IllegalStateException("A Viewer upload is already active");
        }
        var credential = storedCredential();
        if (credential == null) {
            throw new IllegalStateException("Connect to Viewer before uploading");
        }
        var packagePath = Path.of(revision.packagePath());
        if (!Files.isRegularFile(packagePath)
                || !revision.packageSha256().equals(sha256(packagePath))) {
            throw new IllegalStateException("Approved package hash no longer matches");
        }
        var total = Files.size(packagePath);
        var manifestSha256 = tarText(packagePath, "manifest.sha256", 64);
        uploadStatus = new ViewerUploadStatus(
                "UPLOADING",
                revision.id(),
                0,
                total,
                "",
                "Creating prepared ingest");
        uploadExecutor.submit(() -> upload(
                credential,
                displayName,
                revision,
                packagePath,
                manifestSha256,
                annotations,
                cropX,
                cropY,
                cropWidth,
                cropHeight,
                downsample));
        return uploadStatus;
    }

    public ViewerUploadStatus uploadStatus() {
        return uploadStatus;
    }

    private void upload(
            StoredCredential credential,
            String displayName,
            ArtifactRevision revision,
            Path packagePath,
            String manifestSha256,
            List<AnnotationRecord> annotations,
            int cropX,
            int cropY,
            int cropWidth,
            int cropHeight,
            double downsample) {
        try {
            var length = Files.size(packagePath);
            var session = activeUpload;
            if (session == null || !session.revisionId().equals(revision.id())) {
                var create = sendJson(
                        credential.base().resolve("/api/v1/desktop/ingests"),
                        "{\"displayName\":\"" + escape(displayName)
                                + "\",\"artifactRevisionId\":\"" + escape(revision.id())
                                + "\",\"packageLength\":" + length
                                + ",\"packageSha256\":\"" + revision.packageSha256()
                                + "\",\"manifestSha256\":\"" + manifestSha256 + "\"}",
                        "Bearer " + credential.token());
                requireStatus(create, 201, "Viewer could not create the prepared ingest");
                session = new ActiveUpload(
                        revision.id(),
                        credential.base().resolve(string(create.body(), "uploadUrl")));
                activeUpload = session;
            }
            var uploadUri = session.uploadUri();
            var head = HttpRequest.newBuilder(uploadUri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + credential.token())
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            var headResponse = send(head);
            requireStatus(headResponse, 200, "Viewer could not resume the prepared ingest");
            long offset = headResponse.headers()
                    .firstValueAsLong("Upload-Offset")
                    .orElseThrow(() -> new IOException("Viewer omitted the upload offset"));
            if (offset < 0 || offset > length) {
                throw new IOException("Viewer returned an invalid upload offset");
            }
            try (var input = Files.newInputStream(packagePath)) {
                input.skipNBytes(offset);
                var buffer = new byte[UPLOAD_CHUNK_BYTES];
                while (offset < length) {
                    var wanted = (int) Math.min(buffer.length, length - offset);
                    var read = input.readNBytes(buffer, 0, wanted);
                    if (read != wanted) {
                        throw new IOException("Prepared package ended during upload");
                    }
                    var request = HttpRequest.newBuilder(uploadUri)
                            .timeout(Duration.ofHours(24))
                            .header("Authorization", "Bearer " + credential.token())
                            .header("Upload-Offset", Long.toString(offset))
                            .header("Content-Type", "application/offset+octet-stream")
                            .method(
                                    "PATCH",
                                    HttpRequest.BodyPublishers.ofByteArray(
                                            read == buffer.length
                                                    ? buffer
                                                    : Arrays.copyOf(buffer, read)))
                            .build();
                    var response = send(request);
                    requireStatus(response, 200, "Viewer rejected an upload chunk");
                    offset += read;
                    uploadStatus = new ViewerUploadStatus(
                            "UPLOADING",
                            revision.id(),
                            offset,
                            length,
                            stringOrEmpty(response.body(), "slideId"),
                            offset == length
                                    ? "Viewer is finalizing the prepared package"
                                    : "Uploading prepared package");
                    if (response.body().contains("\"status\":\"ready_private\"")) {
                        var slideId = string(response.body(), "slideId");
                        syncAnnotations(
                                credential,
                                slideId,
                                revision,
                                annotations,
                                cropX,
                                cropY,
                                cropWidth,
                                cropHeight,
                                downsample);
                        uploadStatus = new ViewerUploadStatus(
                                "READY_PRIVATE",
                                revision.id(),
                                length,
                                length,
                                slideId,
                                annotations.isEmpty()
                                        ? "Viewer private slide is ready"
                                        : "Viewer private slide and annotations are synchronized");
                        activeUpload = null;
                    }
                }
            }
            if (!"READY_PRIVATE".equals(uploadStatus.state()) && offset == length) {
                var statusUri = URI.create(
                        uploadUri.toString().substring(
                                0, uploadUri.toString().length() - "/content".length()));
                var statusRequest = HttpRequest.newBuilder(statusUri)
                        .timeout(Duration.ofHours(24))
                        .header("Authorization", "Bearer " + credential.token())
                        .GET()
                        .build();
                var statusResponse = send(statusRequest);
                requireStatus(statusResponse, 200, "Viewer could not recover ingest finalization");
                if (statusResponse.body().contains("\"status\":\"ready_private\"")) {
                    var slideId = string(statusResponse.body(), "slideId");
                    syncAnnotations(
                            credential,
                            slideId,
                            revision,
                            annotations,
                            cropX,
                            cropY,
                            cropWidth,
                            cropHeight,
                            downsample);
                    uploadStatus = new ViewerUploadStatus(
                            "READY_PRIVATE",
                            revision.id(),
                            length,
                            length,
                            slideId,
                            annotations.isEmpty()
                                    ? "Viewer private slide is ready"
                                    : "Viewer private slide and annotations are synchronized");
                    activeUpload = null;
                }
            }
            if (!"READY_PRIVATE".equals(uploadStatus.state())) {
                throw new IOException("Viewer did not confirm ready_private");
            }
        } catch (Exception error) {
            uploadStatus = new ViewerUploadStatus(
                    "FAILED",
                    revision.id(),
                    uploadStatus.uploadedBytes(),
                    uploadStatus.totalBytes(),
                    uploadStatus.viewerSlideId(),
                    error.getMessage() == null ? "Viewer upload failed" : error.getMessage());
        }
    }

    private void syncAnnotations(
            StoredCredential credential,
            String slideId,
            ArtifactRevision revision,
            List<AnnotationRecord> annotations,
            int cropX,
            int cropY,
            int cropWidth,
            int cropHeight,
            double downsample)
            throws IOException {
        var transformed = annotations.stream()
                .map(annotation -> AnnotationTransformer.transform(
                                annotation.geometry(),
                                cropX,
                                cropY,
                                cropWidth,
                                cropHeight,
                                downsample)
                        .map(geometry -> new AnnotationRecord(
                                annotation.id(),
                                annotation.type(),
                                geometry,
                                annotation.label(),
                                annotation.color(),
                                annotation.createdAt())))
                .flatMap(java.util.Optional::stream)
                .toList();
        if (transformed.isEmpty()) {
            return;
        }
        var layerId = UUID.nameUUIDFromBytes(
                        ("pathlab-forge-layer:" + revision.id()).getBytes(StandardCharsets.UTF_8))
                .toString();
        var baseVersion = 0;
        for (var start = 0; start < transformed.size(); start += 50) {
            var end = Math.min(start + 50, transformed.size());
            var operations = transformed.subList(start, end).stream()
                    .map(annotation -> annotationOperation(
                            annotation, layerId, revision.outputWidth(), revision.outputHeight()))
                    .collect(java.util.stream.Collectors.joining(","));
            var ensureLayer = start == 0
                    ? ",\"ensureLayer\":{\"id\":\"" + layerId
                            + "\",\"name\":\"Layer 1\",\"sortOrder\":0,"
                            + "\"visible\":true,\"locked\":false,\"opacity\":1}"
                    : "";
            var body = "{\"mutationId\":\"" + UUID.randomUUID()
                    + "\",\"baseVersion\":" + baseVersion
                    + ensureLayer
                    + ",\"operations\":[" + operations + "]}";
            var response = sendJson(
                    credential.base().resolve(
                            "/api/v1/desktop/slides/" + slideId + "/annotations/batch"),
                    body,
                    "Bearer " + credential.token());
            requireStatus(response, 200, "Viewer annotation synchronization failed");
            baseVersion = integer(response.body(), "version");
        }
        var request = HttpRequest.newBuilder(credential.base().resolve(
                        "/api/v1/desktop/slides/" + slideId + "/annotations"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + credential.token())
                .GET()
                .build();
        var verification = send(request);
        requireStatus(verification, 200, "Viewer annotation verification failed");
        if (integer(verification.body(), "total") != transformed.size()) {
            throw new IOException("Viewer annotation persistence count did not match");
        }
        if (!verification.body().contains("\"visible\":true")) {
            throw new IOException("Viewer annotation layer visibility was not preserved");
        }
    }

    private static String annotationOperation(
            AnnotationRecord annotation, String layerId, int width, int height) {
        return "{\"type\":\"create\",\"item\":{\"id\":\"" + annotation.id()
                + "\",\"layerId\":\"" + layerId + "\",\"geometry\":"
                + geometryJson(annotation.type(), annotation.geometry(), annotation.label(), width, height)
                + ",\"style\":{\"strokeColor\":\"" + annotation.color()
                + "\",\"fillColor\":\"" + annotation.color()
                + "\",\"strokeWidth\":2,\"opacity\":0.35,\"labelVisible\":true},"
                + "\"metadata\":{\"title\":\"" + escape(annotation.label())
                + "\",\"classification\":\"\",\"tags\":[],\"notes\":\"\"}}}";
    }

    private static String geometryJson(
            String type, String geometry, String label, int width, int height) {
        var points = Arrays.stream(geometry.split(";"))
                .map(value -> value.split(",", -1))
                .map(value -> new double[] {
                    clamp(Double.parseDouble(value[0]), 0, width),
                    clamp(Double.parseDouble(value[1]), 0, height)
                })
                .toList();
        var first = points.get(0);
        if ("point".equals(type)) {
            return "{\"type\":\"point\",\"x\":" + first[0] + ",\"y\":" + first[1] + "}";
        }
        if ("rectangle".equals(type) || "ellipse".equals(type)) {
            var last = points.get(points.size() - 1);
            var x = Math.min(first[0], last[0]);
            var y = Math.min(first[1], last[1]);
            var w = Math.max(0.000001, Math.abs(last[0] - first[0]));
            var h = Math.max(0.000001, Math.abs(last[1] - first[1]));
            if ("ellipse".equals(type)) {
                return "{\"type\":\"ellipse\",\"cx\":" + (x + w / 2)
                        + ",\"cy\":" + (y + h / 2)
                        + ",\"rx\":" + (w / 2) + ",\"ry\":" + (h / 2) + "}";
            }
            return "{\"type\":\"rectangle\",\"x\":" + x + ",\"y\":" + y
                    + ",\"width\":" + w + ",\"height\":" + h + "}";
        }
        if ("text".equals(type)) {
            return "{\"type\":\"text\",\"x\":" + first[0] + ",\"y\":" + first[1]
                    + ",\"text\":\"" + escape(label.isBlank() ? "Annotation" : label) + "\"}";
        }
        var viewerType = "angle".equals(type)
                ? "angle"
                : "polygon".equals(type) ? "polygon" : "polyline";
        return "{\"type\":\"" + viewerType + "\",\"points\":["
                + points.stream()
                        .map(point -> "{\"x\":" + point[0] + ",\"y\":" + point[1] + "}")
                        .collect(java.util.stream.Collectors.joining(","))
                + "]}";
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private HttpResponse<String> sendJson(
            URI uri, String body, String authorization) throws IOException {
        var builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (!authorization.isBlank()) {
            builder.header("Authorization", authorization);
        }
        return send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            var response = client.send(
                    request,
                    responseInfo -> {
                        if (responseInfo.headers()
                                .firstValueAsLong("Content-Length")
                                .orElse(0)
                                > MAX_RESPONSE_BYTES) {
                            return HttpResponse.BodySubscribers.replacing("");
                        }
                        return HttpResponse.BodySubscribers.mapping(
                                HttpResponse.BodySubscribers.ofByteArray(),
                                bytes -> {
                                    if (bytes.length > MAX_RESPONSE_BYTES) {
                                        return "";
                                    }
                                    return new String(bytes, StandardCharsets.UTF_8);
                                });
                    });
            if (response.body().isEmpty()
                    && response.statusCode() != 204
                    && !"HEAD".equals(request.method())) {
                throw new IOException("Viewer response was empty or too large");
            }
            return response;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Viewer request was interrupted", error);
        }
    }

    private StoredCredential storedCredential() throws IOException {
        var stored = credentialStore.read();
        if (stored.isEmpty()) {
            return null;
        }
        var separator = stored.get().indexOf('\n');
        if (separator <= 0 || separator == stored.get().length() - 1) {
            throw new IOException("Stored Viewer credential is invalid");
        }
        return new StoredCredential(
                validateBase(stored.get().substring(0, separator)),
                stored.get().substring(separator + 1));
    }

    private static String tarText(Path archive, String expected, int maximum)
            throws IOException {
        try (var input = Files.newInputStream(archive)) {
            while (true) {
                var header = input.readNBytes(512);
                if (header.length < 512 || header[0] == 0) {
                    throw new IOException("Prepared package omitted " + expected);
                }
                var nameEnd = 0;
                while (nameEnd < 100 && header[nameEnd] != 0) {
                    nameEnd++;
                }
                var name = new String(header, 0, nameEnd, StandardCharsets.UTF_8);
                var sizeText = new String(header, 124, 12, StandardCharsets.US_ASCII)
                        .replace("\0", "")
                        .trim();
                var size = Long.parseLong(sizeText, 8);
                if (size > Integer.MAX_VALUE) {
                    throw new IOException("Prepared package control entry is too large");
                }
                var bytes = input.readNBytes((int) size);
                input.skipNBytes((512 - size % 512) % 512);
                if (name.equals(expected)) {
                    if (bytes.length > maximum) {
                        throw new IOException("Prepared package control entry is too large");
                    }
                    return new String(bytes, StandardCharsets.US_ASCII);
                }
            }
        }
    }

    private static String sha256(Path path) throws IOException {
        var digest = sha256Digest();
        try (var input = Files.newInputStream(path)) {
            input.transferTo(new java.security.DigestOutputStream(
                    java.io.OutputStream.nullOutputStream(), digest));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    @Override
    public void close() {
        uploadExecutor.shutdownNow();
    }

    private static URI validateBase(String value) {
        var uri = URI.create(value.strip());
        var scheme = uri.getScheme();
        var host = uri.getHost();
        var loopback = "127.0.0.1".equals(host)
                || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host);
        if (host == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || (!"https".equalsIgnoreCase(scheme)
                        && !("http".equalsIgnoreCase(scheme) && loopback))) {
            throw new IllegalArgumentException(
                    "Viewer URL must use HTTPS, except loopback HTTP is allowed");
        }
        var normalized = uri.resolve("/");
        return URI.create(normalized.toString().replaceAll("/$", ""));
    }

    private static void requireStatus(
            HttpResponse<String> response, int expected, String message) throws IOException {
        if (response.statusCode() != expected) {
            var detail = response.body().length() > 1_000
                    ? response.body().substring(0, 1_000)
                    : response.body();
            throw new IOException(
                    message + " (" + response.statusCode() + "): " + detail);
        }
    }

    private static String string(String json, String key) throws IOException {
        var pattern = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        var match = pattern.matcher(json);
        if (!match.find()) {
            throw new IOException("Viewer response omitted " + key);
        }
        return match.group(1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static List<String> strings(String json, String key) throws IOException {
        var pattern = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[([^]]*)]");
        var match = pattern.matcher(json);
        if (!match.find()) {
            throw new IOException("Viewer response omitted " + key);
        }
        var values = new ArrayList<String>();
        var item = Pattern.compile("\"([^\"]+)\"").matcher(match.group(1));
        while (item.find()) {
            values.add(item.group(1));
        }
        return List.copyOf(values);
    }

    private static int integer(String json, String key) throws IOException {
        var pattern = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)");
        var match = pattern.matcher(json);
        if (!match.find()) {
            throw new IOException("Viewer response omitted " + key);
        }
        return Integer.parseInt(match.group(1));
    }

    private static String stringOrEmpty(String json, String key) throws IOException {
        if (Pattern.compile(
                        "\"" + Pattern.quote(key) + "\"\\s*:\\s*null")
                .matcher(json)
                .find()) {
            return "";
        }
        return string(json, key);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record PendingPairing(URI base, String deviceCode, String deviceSecret) {}

    private record StoredCredential(URI base, String token) {}

    private record ActiveUpload(String revisionId, URI uploadUri) {}
}

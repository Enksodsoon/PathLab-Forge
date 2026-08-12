package org.pathlab.forge.viewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ViewerSyncService implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final int BUFFER_BYTES = 1024 * 1024;
    private static final int MAX_LIBRARY_PAGES = 100;
    private final ViewerAuthorizedClient client;
    private final ViewerSyncStore store;
    private final Path offlineRoot;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "pathlab-forge-viewer-sync");
        thread.setDaemon(true);
        return thread;
    });

    public ViewerSyncService(ViewerAuthorizedClient client, ViewerSyncStore store, Path offlineRoot)
            throws IOException {
        this.client = client;
        this.store = store;
        this.offlineRoot = offlineRoot.toAbsolutePath().normalize();
        Files.createDirectories(this.offlineRoot);
    }

    public CompletableFuture<Void> sync() {
        return CompletableFuture.runAsync(() -> {
            try { syncNow(); } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
        }, executor);
    }

    public synchronized void syncNow() throws IOException {
        var after = store.cursor();
        var changes = json("GET", "/api/v2/desktop/library/changes?after=" + after + "&limit=500",
                Map.of(), new byte[0]);
        requireSchema(changes);
        if (after == 0 || !changes.path("changes").isEmpty()) refreshLibrary();
        store.saveCursor(changes.path("nextCursor").asLong(after));
    }

    private void refreshLibrary() throws IOException {
        var folders = new LinkedHashMap<String, ViewerRemoteFolder>();
        String cursor = "";
        for (int page = 0; page < MAX_LIBRARY_PAGES; page++) {
            var path = "/api/v2/desktop/library/items?limit=100";
            if (!cursor.isBlank()) path += "&cursor=" + java.net.URLEncoder.encode(cursor,
                    java.nio.charset.StandardCharsets.UTF_8);
            var document = json("GET", path, Map.of(), new byte[0]);
            requireSchema(document);
            for (var item : document.path("items")) store.upsertRemote(parseSlide(item));
            for (var folder : document.path("folders")) {
                var parsed = new ViewerRemoteFolder(text(folder, "id"), text(folder, "name"),
                        nullableText(folder, "parentId"), folder.path("revision").asLong());
                folders.put(parsed.id(), parsed);
            }
            var next = document.path("nextCursor");
            if (next.isMissingNode() || next.isNull() || next.asText().isBlank()) {
                store.replaceFolders(List.copyOf(folders.values()));
                return;
            }
            cursor = next.asText();
        }
        throw new IOException("Viewer library exceeded the bounded sync page limit");
    }

    public CompletableFuture<Path> keepOfflineAsync(String slideId) {
        return CompletableFuture.supplyAsync(() -> {
            try { return keepOffline(slideId); }
            catch (IOException error) { throw new java.io.UncheckedIOException(error); }
        }, executor);
    }

    public synchronized Path keepOffline(String slideId) throws IOException {
        var record = store.find(slideId).orElseThrow(() -> new IOException("Unknown remote slide"));
        var endpoint = "/api/v2/desktop/slides/" + slideId + "/content";
        long bytes;
        String sha;
        try (var head = client.request("HEAD", endpoint, Map.of(), new byte[0])) {
            requireStatus(head, 200);
            bytes = positiveLong(head.header("Content-Length"), "Viewer omitted content length");
            sha = head.header("X-PathLab-SHA256");
        }
        if (!sha.matches("[0-9a-fA-F]{64}")) throw new IOException("Viewer returned an invalid content hash");
        ensureSpace(bytes);
        var partial = offlineRoot.resolve(safeName(slideId) + ".ome.tif.partial");
        var target = offlineRoot.resolve(safeName(slideId) + ".ome.tif");
        var offset = 0L;
        if (record.downloadBytes() == bytes && record.downloadSha256().equalsIgnoreCase(sha)
                && Files.isRegularFile(partial)) {
            offset = Math.min(Files.size(partial), bytes);
        } else {
            Files.deleteIfExists(partial);
            store.beginDownload(slideId, partial, bytes, sha);
        }
        var headers = offset == 0 ? Map.<String, String>of() : Map.of("Range", "bytes=" + offset + "-");
        try (var response = client.request("GET", endpoint, headers, new byte[0])) {
            requireStatus(response, offset == 0 ? 200 : 206);
            try (var output = Files.newOutputStream(partial, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                var buffer = new byte[BUFFER_BYTES];
                int count;
                while ((count = response.body().read(buffer)) >= 0) {
                    if (count == 0) continue;
                    output.write(buffer, 0, count);
                    offset += count;
                    if (offset > bytes) throw new IOException("Viewer sent more content than declared");
                    store.advanceDownload(slideId, offset);
                }
            }
        }
        if (offset != bytes || !sha.equalsIgnoreCase(sha256(partial))) {
            throw new IOException("Offline slide failed length or SHA-256 verification");
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        store.advanceDownload(slideId, bytes);
        return target;
    }

    public List<ViewerSyncRecord> library() throws IOException { return store.all(); }
    public List<ViewerRemoteFolder> folders() throws IOException { return store.folders(); }
    public List<ViewerSyncConflict> conflicts() throws IOException { return store.conflicts(); }

    private JsonNode json(String method, String path, Map<String, String> headers, byte[] body)
            throws IOException {
        try (var response = client.request(method, path, headers, body)) {
            requireStatus(response, 200);
            var bytes = response.body().readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) throw new IOException("Viewer JSON response is too large");
            return JSON.readTree(bytes);
        }
    }

    private static ViewerRemoteSlide parseSlide(JsonNode item) throws IOException {
        var metadata = new LinkedHashMap<String, Object>();
        for (var key : List.of("description", "caseId", "organSite", "stain", "diagnosis",
                "course", "tags", "teachingNote", "adminNotes")) {
            var value = item.get(key);
            if (value != null && !value.isNull()) metadata.put(key, JSON.convertValue(value, Object.class));
        }
        return new ViewerRemoteSlide(text(item, "id"), text(item, "displayName"),
                nullableText(item, "folderId"), text(item, "state"), item.path("imageRevision").asLong(),
                item.path("annotationRevision").asLong(), item.path("metadataRevision").asLong(),
                item.path("folderRevision").asLong(), text(item, "thumbnailUrl"),
                text(item, "tileSourceUrl"), item.path("contentBytes").asLong(),
                nullableText(item, "contentSha256"), metadata, Instant.parse(text(item, "updatedAt")));
    }

    private void ensureSpace(long bytes) throws IOException {
        var required = bytes + Math.max(1, bytes / 10);
        if (Files.getFileStore(offlineRoot).getUsableSpace() < required) {
            throw new IOException("Not enough disk space to keep this slide offline");
        }
    }

    private static long positiveLong(String value, String error) throws IOException {
        try { var parsed = Long.parseLong(value); if (parsed >= 0) return parsed; }
        catch (NumberFormatException ignored) { }
        throw new IOException(error);
    }

    private static void requireStatus(ViewerHttpResponse response, int expected) throws IOException {
        if (response.status() != expected) throw new IOException("Viewer request failed (" + response.status() + ")");
    }

    private static void requireSchema(JsonNode document) throws IOException {
        if (!"desktop-sync/v1".equals(document.path("schema").asText())) {
            throw new IOException("Viewer returned an unsupported sync schema");
        }
    }

    private static String text(JsonNode node, String key) throws IOException {
        var value = node.get(key);
        if (value == null || value.isNull() || !value.isTextual()) throw new IOException("Viewer omitted " + key);
        return value.asText();
    }

    private static String nullableText(JsonNode node, String key) {
        var value = node.get(key);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static String safeName(String value) throws IOException {
        if (!value.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("Remote slide ID is invalid");
        return value;
    }

    private static String sha256(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                var buffer = new byte[BUFFER_BYTES];
                int count;
                while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override public void close() { executor.shutdownNow(); }
}

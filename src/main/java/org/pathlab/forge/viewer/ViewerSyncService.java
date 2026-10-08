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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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
    private volatile String scopeKey = "";
    private volatile String activeDownload = "";
    private volatile java.io.InputStream activeStream;
    private volatile Thread downloadThread;
    private final LinkedHashMap<Path, VerifiedOffline> verifiedOffline = new LinkedHashMap<>();
    private record VerifiedOffline(long bytes, java.nio.file.attribute.FileTime modified, Object fileKey, String sha256) {}
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "pathlab-forge-viewer-sync");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.Set<String> cancelledDownloads = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ViewerSyncService(ViewerAuthorizedClient client, ViewerSyncStore store, Path offlineRoot)
            throws IOException {
        this.client = client;
        this.store = store;
        this.offlineRoot = offlineRoot.toAbsolutePath().normalize();
        Files.createDirectories(this.offlineRoot);
        bindAccount();
    }

    public CompletableFuture<Void> sync() {
        return CompletableFuture.runAsync(() -> {
            try { syncNow(); } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
        }, executor);
    }

    public synchronized void syncNow() throws IOException {
        requireAccount();
        var after = store.cursor();
        var changes = json("GET", "/api/v2/desktop/library/changes?after=" + after + "&limit=500",
                Map.of(), new byte[0]);
        requireSchema(changes);
        // An explicit desktop sync is a bounded authoritative reconciliation. The change
        // cursor can already have advanced after an earlier refresh, so relying on a new
        // event here would leave remotely deleted slides in the local cache forever.
        var snapshot = refreshLibrary();
        var cursor = changes.path("nextCursor");
        long next;
        try { next = Long.parseLong(cursor.asText()); }
        catch (NumberFormatException error) { throw new IOException("Invalid Viewer change cursor", error); }
        if (next < after) throw new IOException("Viewer change cursor moved backwards");
        store.replaceSnapshot(snapshot.slides(), snapshot.folders(), next);
    }

    private record Snapshot(List<ViewerRemoteSlide> slides, List<ViewerRemoteFolder> folders) {}

    private Snapshot refreshLibrary() throws IOException {
        var folders = new LinkedHashMap<String, ViewerRemoteFolder>();
        var slides = new LinkedHashMap<String, ViewerRemoteSlide>();
        String cursor = "";
        for (int page = 0; page < MAX_LIBRARY_PAGES; page++) {
            var path = "/api/v2/desktop/library/items?limit=100";
            if (!cursor.isBlank()) path += "&cursor=" + java.net.URLEncoder.encode(cursor,
                    java.nio.charset.StandardCharsets.UTF_8);
            var document = json("GET", path, Map.of(), new byte[0]);
            requireSchema(document);
            for (var item : document.path("items")) {
                var parsed = parseSlide(item);
                slides.put(parsed.id(), parsed);
            }
            for (var folder : document.path("folders")) {
                var parsed = new ViewerRemoteFolder(text(folder, "id"), text(folder, "name"),
                        nullableText(folder, "parentId"), folder.path("revision").asLong());
                folders.put(parsed.id(), parsed);
            }
            var next = document.path("nextCursor");
            if (next.isMissingNode() || next.isNull() || next.asText().isBlank()) {
                return new Snapshot(List.copyOf(slides.values()), List.copyOf(folders.values()));
            }
            cursor = next.asText();
        }
        throw new IOException("Viewer library exceeded the bounded sync page limit");
    }

    public CompletableFuture<Path> keepOfflineAsync(String slideId) {
        cancelledDownloads.remove(slideId);
        return CompletableFuture.supplyAsync(() -> {
            try { return keepOffline(slideId, false); }
            catch (IOException error) { throw new java.io.UncheckedIOException(error); }
        }, executor);
    }

    public Path keepOffline(String slideId) throws IOException { return keepOffline(slideId, true); }

    private synchronized Path keepOffline(String slideId, boolean clearCancellation) throws IOException {
        requireAccount();
        safeName(slideId);
        if (clearCancellation) cancelledDownloads.remove(slideId);
        activeDownload = slideId;
        downloadThread = Thread.currentThread();
        var scope = scopeKey;
        var accountRoot = offlineRoot.resolve(scope);
        Files.createDirectories(accountRoot);
        if (!safeParents(accountRoot)) throw new IOException("Unsafe offline storage path");
        var partial = accountRoot.resolve(slideId + ".ome.tif.partial");
        var target = accountRoot.resolve(slideId + ".ome.tif");
        try {
            checkDownloadAccount(scope, slideId);
            var record = store.find(slideId).orElseThrow(() -> new IOException("Unknown remote slide"));
            if ("remote_removed".equals(record.remote().status())) throw new IOException("Slide is no longer available in Viewer");
            var endpoint = "/api/v2/desktop/slides/" + slideId + "/content";
            long bytes;
            String sha;
            try (var head = client.requestBound(scope, "HEAD", endpoint, Map.of(), new byte[0])) {
                requireStatus(head, 200);
                bytes = positiveLong(head.header("Content-Length"), "Viewer omitted content length");
                sha = head.header("X-PathLab-SHA256");
            }
            if (bytes == 0 || !sha.matches("[0-9a-fA-F]{64}")) throw new IOException("Viewer returned invalid content identity");
            if (!record.remote().contentSha256().isBlank()
                    && (!record.remote().contentSha256().equalsIgnoreCase(sha) || record.remote().contentBytes() != bytes))
                throw new IOException("Viewer content changed; sync its new revision first");
            ensureSpace(bytes);
            long offset = 0;
            if (record.downloadBytes() == bytes && record.downloadSha256().equalsIgnoreCase(sha)
                    && physicalFile(partial) && Files.size(partial) <= bytes) offset = Files.size(partial);
            else Files.deleteIfExists(partial);
            store.beginDownload(slideId, partial, bytes, sha);
            store.advanceDownload(slideId, offset);
            if (offset < bytes) {
                var headers = offset == 0 ? Map.<String, String>of() : Map.of("Range", "bytes=" + offset + "-", "If-Match", "\"" + sha + "\"");
                try (var response = client.requestBound(scope, "GET", endpoint, headers, new byte[0])) {
                    activeStream = response.body();
                    requireStatus(response, offset == 0 ? 200 : 206);
                    if (offset > 0 && !response.header("Content-Range").equals("bytes " + offset + "-" + (bytes - 1) + "/" + bytes))
                        throw new IOException("Viewer returned a mismatched resume range");
                    try (var output = Files.newOutputStream(partial, StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE, StandardOpenOption.APPEND, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        var buffer = new byte[BUFFER_BYTES];
                        for (int count; (count = response.body().read(buffer)) != -1;) {
                            checkDownloadAccount(scope, slideId);
                            if (count == 0) continue;
                            if (count > bytes - offset) throw new IOException("Viewer sent more content than declared");
                            output.write(buffer, 0, count); offset += count;
                            store.advanceDownload(slideId, offset);
                        }
                    }
                }
            }
            checkDownloadAccount(scope, slideId);
            store.downloadState(slideId, "VERIFYING", target, "Verifying length and SHA-256");
            if (offset != bytes || !sha.equalsIgnoreCase(sha256(partial)))
                throw new IOException("Offline slide failed length or SHA-256 verification");
            checkDownloadAccount(scope, slideId);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            store.downloadState(slideId, "READY", target, "Verified local OME available offline");
            cacheVerified(target, sha);
            return target;
        } catch (IOException | RuntimeException error) {
            if (store.find(slideId).isPresent()) store.downloadState(slideId,
                    cancelledDownloads.contains(slideId) ? "CANCELLED" : "FAILED", Path.of(""),
                    error.getMessage() == null ? "Download failed" : error.getMessage());
            throw error;
        } finally {
            activeStream = null; activeDownload = ""; downloadThread = null;
            if (cancelledDownloads.remove(slideId)) Thread.interrupted();
        }
    }

    public synchronized java.util.Optional<Path> offlineFile(String slideId) throws IOException {
        if (!bindAccount()) return java.util.Optional.empty();
        var record = store.find(safeName(slideId));
        if (record.isEmpty() || !"READY".equals(record.get().downloadState())) return java.util.Optional.empty();
        var path = record.get().offlinePath().toAbsolutePath().normalize();
        if (!path.startsWith(offlineRoot.resolve(scopeKey)) || !physicalFile(path)) return invalidateOffline(slideId, path);
        var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
        var identity = new VerifiedOffline(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey(), record.get().downloadSha256());
        if (!identity.equals(verifiedOffline.get(path))) {
            if (attributes.size() != record.get().downloadBytes() || !sha256(path).equalsIgnoreCase(record.get().downloadSha256()))
                return invalidateOffline(slideId, path);
            var after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (after.size() != attributes.size() || !after.lastModifiedTime().equals(attributes.lastModifiedTime())
                    || !java.util.Objects.equals(after.fileKey(), attributes.fileKey())) return invalidateOffline(slideId, path);
            verifiedOffline.put(path, identity);
            while (verifiedOffline.size() > 64) verifiedOffline.remove(verifiedOffline.keySet().iterator().next());
        }
        return java.util.Optional.of(path);
    }

    private void cacheVerified(Path path, String sha) throws IOException {
        var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        verifiedOffline.put(path, new VerifiedOffline(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey(), sha));
        while (verifiedOffline.size() > 64) verifiedOffline.remove(verifiedOffline.keySet().iterator().next());
    }

    private java.util.Optional<Path> invalidateOffline(String slideId, Path path) throws IOException {
        verifiedOffline.remove(path);
        store.downloadState(slideId, "FAILED", Path.of(""), "Retained offline content failed integrity verification");
        return java.util.Optional.empty();
    }

    private void checkDownloadAccount(String key, String slideId) throws IOException {
        if (cancelledDownloads.contains(slideId) || Thread.currentThread().isInterrupted()) throw new IOException("Offline download cancelled");
        if (!key.equals(client.connectionKey())) throw new IOException("Viewer account changed during download");
    }

    private boolean bindAccount() throws IOException {
        var key = client.connectionKey();
        if (key.isEmpty()) return false;
        if (!key.matches("[0-9a-f]{64}")) throw new IOException("Viewer connection identity is invalid");
        if (!key.equals(scopeKey)) {
            if (!activeDownload.isEmpty()) throw new IOException("Viewer account changed; waiting for previous transfer to stop");
            synchronized (this) {
            if (key.equals(scopeKey)) return true;
            store.bindConnection(key);
            scopeKey = key;
            verifiedOffline.clear();
            for (var record : store.all()) {
                if (java.util.Set.of("DOWNLOADING", "VERIFYING").contains(record.downloadState())) {
                    var target = offlineRoot.resolve(key).resolve(safeName(record.remote().id()) + ".ome.tif");
                    if (physicalFile(target) && Files.size(target) == record.downloadBytes()
                            && sha256(target).equalsIgnoreCase(record.downloadSha256()))
                        store.downloadState(record.remote().id(), "READY", target, "Verified atomic activation recovered after restart");
                    else store.downloadState(record.remote().id(), "FAILED", Path.of(""), "Download interrupted; retry can resume verified identity");
                }
            }
            }
        }
        return true;
    }

    private void requireAccount() throws IOException {
        if (!bindAccount()) throw new IOException("Connect to Viewer before syncing");
    }

    private static boolean physicalFile(Path path) {
        return Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) && safeParents(path.getParent());
    }

    private static boolean safeParents(Path path) {
        for (var parent = path; parent != null; parent = parent.getParent())
            if (Files.isSymbolicLink(parent)) return false;
        return true;
    }

    public void cancelOffline(String slideId) {
        cancelledDownloads.add(slideId);
        if (slideId.equals(activeDownload)) {
            var input = activeStream;
            if (input != null) try { input.close(); } catch (IOException ignored) { }
            var thread = downloadThread;
            if (thread != null) thread.interrupt();
        }
    }

    public void removeOffline(String slideId) throws IOException {
        cancelOffline(slideId);
        synchronized (this) {
        requireAccount();
        var accountRoot = offlineRoot.resolve(scopeKey);
        Files.deleteIfExists(accountRoot.resolve(safeName(slideId) + ".ome.tif"));
        Files.deleteIfExists(accountRoot.resolve(safeName(slideId) + ".ome.tif.partial"));
        store.clearDownload(slideId);
        }
    }

    public synchronized List<ViewerSyncRecord> library() throws IOException { return bindAccount() ? store.all() : List.of(); }
    public synchronized List<ViewerRemoteFolder> folders() throws IOException { return bindAccount() ? store.folders() : List.of(); }
    public synchronized List<ViewerSyncConflict> conflicts() throws IOException { return bindAccount() ? store.conflicts() : List.of(); }

    public synchronized ViewerRemoteSlide updateMetadata(String slideId, String displayName,
            String folderId) throws IOException {
        requireAccount();
        var current = store.find(slideId).orElseThrow(() -> new IOException("Unknown remote slide"));
        var changed = new java.util.LinkedHashSet<String>();
        var payload = JSON.createObjectNode();
        payload.put("expectedMetadataRevision", current.remote().metadataRevision());
        payload.put("expectedFolderRevision", current.remote().folderRevision());
        if (displayName != null) { payload.put("displayName", displayName); changed.add("displayName"); }
        if (folderId != null) {
            if (folderId.isBlank()) payload.putNull("folderId"); else payload.put("folderId", folderId);
            changed.add("folderId");
        }
        if (changed.isEmpty()) throw new IllegalArgumentException("No remote fields changed");
        var intended = new LinkedHashMap<String, String>();
        if (displayName != null) intended.put("displayName", displayName);
        if (folderId != null) intended.put("folderId", folderId);
        store.saveIntendedMetadata(slideId, intended);
        try (var response = client.requestBound(scopeKey, "PATCH", "/api/v2/desktop/slides/" + safeName(slideId),
                Map.of("Content-Type", "application/json"), JSON.writeValueAsBytes(payload))) {
            var bytes = response.body().readNBytes(MAX_JSON_BYTES + 1);
            if (response.status() == 409) {
                var snapshot = refreshLibrary();
                store.replaceSnapshot(snapshot.slides(), snapshot.folders(), store.cursor());
                var remote = store.find(slideId).orElseThrow().remote();
                for (var field : changed) {
                    var local = "displayName".equals(field) ? displayName : folderId;
                    var remoteValue = "displayName".equals(field) ? remote.displayName() : remote.folderId();
                    store.recordConflict(new ViewerSyncConflict(slideId, field,
                            local == null ? "" : local, remoteValue,
                            "folderId".equals(field) ? current.remote().folderRevision() : current.remote().metadataRevision(),
                            "folderId".equals(field) ? remote.folderRevision() : remote.metadataRevision(), Instant.now(), true));
                }
                throw new IOException("Viewer edit conflicts with a newer remote revision");
            }
            if (response.status() != 200) throw new IOException("Viewer update failed (" + response.status() + ")");
            var remote = parseSlide(JSON.readTree(bytes));
            store.upsertRemote(remote);
            store.clearDirty(slideId, changed);
            return remote;
        }
    }

    public synchronized void resolveConflict(String slideId, String field, String resolution)
            throws IOException {
        requireAccount();
        var conflict = store.conflicts().stream()
                .filter(item -> item.slideId().equals(slideId) && item.field().equals(field))
                .findFirst().orElseThrow(() -> new IOException("Sync conflict was not found"));
        if ("viewer".equals(resolution)) {
            store.clearDirty(slideId, java.util.Set.of(field));
        } else if ("local".equals(resolution)) {
            if ("displayName".equals(field)) updateMetadata(slideId, conflict.localValue(), null);
            else if ("folderId".equals(field)) updateMetadata(slideId, null, conflict.localValue());
            else throw new IOException("This conflict requires annotation resolution");
        } else {
            throw new IllegalArgumentException("Resolution must be local or viewer");
        }
        store.resolveConflict(slideId, field);
    }

    private JsonNode json(String method, String path, Map<String, String> headers, byte[] body)
            throws IOException {
        try (var response = client.requestBound(scopeKey, method, path, headers, body)) {
            requireStatus(response, 200);
            var bytes = response.body().readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) throw new IOException("Viewer JSON response is too large");
            return JSON.readTree(bytes);
        }
    }

    private static ViewerRemoteSlide parseSlide(JsonNode item) throws IOException {
        var metadata = new LinkedHashMap<String, Object>();
        for (var key : List.of("description", "caseId", "organSite", "stain", "diagnosis",
                "course", "tags", "teachingNote", "adminNotes", "width", "height")) {
            var value = item.get(key);
            if (value != null && !value.isNull()) metadata.put(key, JSON.convertValue(value, Object.class));
        }
        return new ViewerRemoteSlide(text(item, "id"), text(item, "displayName"),
                nullableText(item, "folderId"), text(item, "state"), item.path("imageRevision").asLong(),
                item.path("annotationRevision").asLong(), item.path("metadataRevision").asLong(),
                item.path("folderRevision").asLong(), text(item, "thumbnailUrl"),
                text(item, "tileSourceUrl"), item.path("contentBytes").asLong(),
                nullableText(item, "contentSha256"), metadata,
                parseViewerInstant(text(item, "updatedAt")));
    }

    static Instant parseViewerInstant(String value) throws IOException {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException offsetError) {
            try {
                // Viewer persists SQLite timestamps without an offset. Its server clock is UTC.
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException localError) {
                throw new IOException("Viewer returned an invalid timestamp", localError);
            }
        }
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

    @Override public void close() {
        executor.shutdownNow();
        try { store.close(); } catch (IOException ignored) { }
    }
}

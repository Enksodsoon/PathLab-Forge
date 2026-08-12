package org.pathlab.forge.viewer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;

public final class ViewerTileCache {
    public static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private final ViewerAuthorizedClient client;
    private final Path root;
    private final long maxBytes;
    private long lastTouch;

    public ViewerTileCache(ViewerAuthorizedClient client, Path root) throws IOException {
        this(client, root, DEFAULT_MAX_BYTES);
    }

    ViewerTileCache(ViewerAuthorizedClient client, Path root, long maxBytes) throws IOException {
        if (maxBytes < 1) throw new IllegalArgumentException("Tile cache must have a positive limit");
        this.client = client;
        this.root = root.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        Files.createDirectories(this.root);
    }

    public synchronized CachedViewerResource get(String remotePath) throws IOException {
        if (!remotePath.startsWith("/api/") || remotePath.contains("..")) {
            throw new IllegalArgumentException("Remote preview path is invalid");
        }
        var key = digest(remotePath);
        var data = root.resolve(key + ".cache");
        var type = root.resolve(key + ".type");
        if (Files.isRegularFile(data) && Files.isRegularFile(type)) {
            touch(data);
            return new CachedViewerResource(data, Files.readString(type, StandardCharsets.UTF_8));
        }
        var partial = root.resolve(key + ".partial");
        try (var response = client.request("GET", remotePath, Map.of(), new byte[0])) {
            if (response.status() != 200) throw new IOException("Viewer preview failed (" + response.status() + ")");
            var bytes = response.body().readNBytes((int) Math.min(Integer.MAX_VALUE, maxBytes + 1));
            if (bytes.length > maxBytes) throw new IOException("Viewer preview exceeds the local cache limit");
            Files.write(partial, bytes);
            Files.move(partial, data, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(type, normalizedType(response.header("Content-Type")), StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(partial);
        }
        touch(data);
        trim();
        return new CachedViewerResource(data, Files.readString(type, StandardCharsets.UTF_8));
    }

    private void trim() throws IOException {
        try (var files = Files.list(root)) {
            var entries = files.filter(path -> path.getFileName().toString().endsWith(".cache"))
                    .sorted(Comparator.comparingLong(this::modified)).toList();
            long total = 0;
            for (var entry : entries) total += Files.size(entry);
            for (var entry : entries) {
                if (total <= maxBytes) break;
                total -= Files.size(entry);
                Files.deleteIfExists(entry);
                Files.deleteIfExists(root.resolve(entry.getFileName().toString().replace(".cache", ".type")));
            }
        }
    }

    private long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ignored) { return 0; }
    }

    private void touch(Path path) throws IOException {
        lastTouch = Math.max(System.currentTimeMillis(), lastTouch + 1);
        Files.setLastModifiedTime(path, FileTime.fromMillis(lastTouch));
    }

    private static String normalizedType(String value) {
        return value.isBlank() || value.length() > 128 ? "application/octet-stream" : value;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

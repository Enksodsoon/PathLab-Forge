package org.pathlab.forge.conversion;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

public final class ReaderSession {
    private final long maximumBytes;
    private final Map<TileKey, byte[]> cache = new LinkedHashMap<>(32, 0.75f, true);
    private final ConcurrentHashMap<TileKey, CompletableFuture<byte[]>> inFlight =
            new ConcurrentHashMap<>();
    private final AtomicLong cacheHits = new AtomicLong();
    private long cachedBytes;
    private volatile long lastAccessNanos = System.nanoTime();

    public ReaderSession(long maximumBytes) {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("Reader cache bound must be positive");
        }
        this.maximumBytes = maximumBytes;
    }

    public byte[] tile(TileKey key, TileLoader loader) throws Exception {
        lastAccessNanos = System.nanoTime();
        synchronized (cache) {
            var existing = cache.get(key);
            if (existing != null) {
                cacheHits.incrementAndGet();
                return existing;
            }
        }
        var pending = new CompletableFuture<byte[]>();
        var existingFuture = inFlight.putIfAbsent(key, pending);
        if (existingFuture != null) {
            cacheHits.incrementAndGet();
            return await(existingFuture);
        }
        try {
            var loaded = loader.load();
            if (loaded == null || loaded.length == 0) {
                throw new IllegalStateException("Reader returned an empty tile");
            }
            put(key, loaded);
            pending.complete(loaded);
            return loaded;
        } catch (Exception error) {
            pending.completeExceptionally(error);
            throw error;
        } finally {
            inFlight.remove(key, pending);
        }
    }

    private static byte[] await(CompletableFuture<byte[]> future) throws Exception {
        try {
            return future.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException("Tile read failed", error.getCause());
        }
    }

    private void put(TileKey key, byte[] value) {
        synchronized (cache) {
            var replaced = cache.put(key, value);
            if (replaced != null) {
                cachedBytes -= replaced.length;
            }
            cachedBytes += value.length;
            var iterator = cache.entrySet().iterator();
            while (cachedBytes > maximumBytes && cache.size() > 1 && iterator.hasNext()) {
                var eldest = iterator.next();
                cachedBytes -= eldest.getValue().length;
                iterator.remove();
            }
        }
    }

    public long cacheHits() {
        return cacheHits.get();
    }

    public long cachedBytes() {
        synchronized (cache) {
            return cachedBytes;
        }
    }

    public boolean idleFor(long nanos) {
        return System.nanoTime() - lastAccessNanos >= nanos;
    }

    public record TileKey(int series, int level, int tileX, int tileY) {
        public TileKey {
            if (series < 0 || level < 0 || tileX < 0 || tileY < 0) {
                throw new IllegalArgumentException("Tile coordinates must not be negative");
            }
        }
    }

    @FunctionalInterface
    public interface TileLoader {
        byte[] load() throws Exception;
    }
}

package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ReaderSessionTest {
    @Test
    void coalescesConcurrentDuplicateTileReadsAndCachesResult() throws Exception {
        var reads = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var session = new ReaderSession(256 * 1024 * 1024L);
        var key = new ReaderSession.TileKey(3, 15, 2, 7);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> session.tile(key, () -> {
                reads.incrementAndGet();
                entered.countDown();
                release.await();
                return new byte[] {1, 2, 3};
            }));
            entered.await();
            var second = pool.submit(() -> session.tile(key, () -> {
                reads.incrementAndGet();
                return new byte[] {9};
            }));
            release.countDown();

            assertArrayEquals(new byte[] {1, 2, 3}, first.get());
            assertArrayEquals(new byte[] {1, 2, 3}, second.get());
            assertArrayEquals(new byte[] {1, 2, 3}, session.tile(key, () -> new byte[] {8}));
            assertEquals(1, reads.get());
            assertEquals(2, session.cacheHits());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void evictsLeastRecentlyUsedTilesWithinByteBound() throws Exception {
        var session = new ReaderSession(5);
        var reads = new AtomicInteger();
        var first = new ReaderSession.TileKey(0, 1, 0, 0);
        var second = new ReaderSession.TileKey(0, 1, 1, 0);

        session.tile(first, () -> new byte[] {1, 2, 3});
        session.tile(second, () -> new byte[] {4, 5, 6});
        session.tile(first, () -> {
            reads.incrementAndGet();
            return new byte[] {7};
        });

        assertEquals(1, reads.get());
        assertEquals(4, session.cachedBytes());
    }
}

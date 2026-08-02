package org.pathlab.forge.adapt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StudyPackRepositoryTest {
    @TempDir
    Path temp;

    @Test
    void savesImmutableVersionedPackAndReturnsIdenticalContent() throws Exception {
        var repository = new StudyPackRepository(temp);
        var body = spatialPack("pack-a", 1, "0.50");

        var saved = repository.save(body);
        var repeated = repository.save(body);

        assertEquals(saved, repeated);
        assertEquals(body, repository.read(saved.checksum()));
        assertEquals(1, repository.list().size());
        assertEquals("pack-a", saved.packKey());
        assertFalse(saved.masteryEligible());
        assertTrue(saved.path().startsWith(temp.toAbsolutePath()));
    }

    @Test
    void refusesVersionReplacementAndUnprovenMedicalKeys() throws Exception {
        var repository = new StudyPackRepository(temp);
        repository.save(spatialPack("pack-a", 1, "0.50"));

        assertThrows(
                IllegalArgumentException.class,
                () -> repository.save(spatialPack("pack-a", 1, "0.60")));
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.save("""
                        {"schema":"pathlab.study-pack/1","packKey":"pack-b","version":1,
                        "title":"Unsafe","courseId":"path-101","objectives":["Recall"],
                        "slides":[{"viewerSlideId":"slide-1","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","displayName":"Teaching","license":"teaching"}],
                        "tasks":[{"type":"keyed","id":"q1","slideId":"slide-1","prompt":"Diagnosis?","answerKey":"X","source":"","author":"","license":"","revision":""}]}
                        """));
    }

    @Test
    void rejectsDuplicateUnknownPixelPathAndInvalidTaskSmuggling() {
        var repository = new StudyPackRepository(temp);
        for (var body : java.util.List.of(
                spatialPack("pack-a", 1, "0.50").replace("\"title\":", "\"title\":\"duplicate\",\"title\":"),
                spatialPack("pack-a", 1, "0.50").replace("\"courseId\":", "\"pixelPath\":\"C:/slide.svs\",\"courseId\":"),
                spatialPack("pack-a", 1, "0.50").replace("\"tolerance\":0.08", "\"tolerance\":0.08,\"answerKey\":\"smuggled\""),
                spatialPack("pack-a", 1, "0.50").replace("\"type\":\"spatial\"", "\"type\":\"keyed\"")
                        .replace(",\"targetX\":0.50,\"targetY\":0.40,\"tolerance\":0.08", ""))) {
            assertThrows(IllegalArgumentException.class, () -> repository.save(body));
        }
    }

    @Test
    void atomicallyClaimsOneImmutableVersionAcrossConcurrentRepositoryInstances() throws Exception {
        var first = new StudyPackRepository(temp);
        var second = new StudyPackRepository(temp);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> { start.await(); return first.save(spatialPack("race", 1, "0.20")); });
            var two = pool.submit(() -> { start.await(); return second.save(spatialPack("race", 1, "0.80")); });
            start.countDown();
            var successes = 0;
            for (var future : java.util.List.of(one, two)) {
                try { future.get(10, TimeUnit.SECONDS); successes++; }
                catch (java.util.concurrent.ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof IllegalArgumentException);
                }
            }
            assertEquals(1, successes);
            assertEquals(1, first.list().size());
        } finally {
            pool.shutdownNow();
        }
    }

    private static String spatialPack(String key, int version, String targetX) {
        return """
                {"schema":"pathlab.study-pack/1","packKey":"%s","version":%d,
                "title":"Spatial navigation","courseId":"path-101","objectives":["Navigate"],
                "slides":[{"viewerSlideId":"slide-1","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","displayName":"Teaching","license":"teaching"}],
                "tasks":[{"type":"spatial","id":"s1","slideId":"slide-1","prompt":"Find region","targetX":%s,"targetY":0.40,"targetWidth":0.10,"targetHeight":0.10,"tolerance":0.08,"source":"PIVOT","author":"PathLab Forge","license":"teaching","revision":"pivot-v1"}]}
                """.formatted(key, version, targetX);
    }
}

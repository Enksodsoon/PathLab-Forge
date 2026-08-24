package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvidenceQuotaManagerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void exposesFixedBucketsAndUntouchableReserve() throws Exception {
        var snapshot = new EvidenceQuotaManager(temporaryDirectory).snapshot();
        assertEquals(5, snapshot.buckets().size());
        assertEquals(45L * 1024 * 1024 * 1024, snapshot.buckets().get("source").limitBytes());
        assertTrue(snapshot.buckets().get("reserve").untouchable());
        try (var reservation = new EvidenceQuotaManager(temporaryDirectory)
                .reserve(EvidenceQuotaManager.Bucket.MODELS, "dinov2-download", 1024)) {
            assertEquals(1024, reservation.bytes());
            assertEquals(1024, new EvidenceQuotaManager(temporaryDirectory).snapshot()
                    .buckets().get("models").reservedBytes());
        }
        assertEquals(0, new EvidenceQuotaManager(temporaryDirectory).snapshot()
                .buckets().get("models").reservedBytes());
    }
}

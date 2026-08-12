package org.pathlab.forge.viewer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ViewerSyncServiceTest {
    @TempDir Path temp;

    @Test
    void importsBoundedRemoteLibraryAndCursor() throws Exception {
        var library = new String(ViewerSyncServiceTest.class.getResourceAsStream(
                "/viewer-sync-v1/library-page.json").readAllBytes(), StandardCharsets.UTF_8);
        var transport = new FakeTransport(Map.of(
                "GET /api/v2/desktop/library/items?limit=100", json(library),
                "GET /api/v2/desktop/library/changes?after=0&limit=500",
                json("{\"schema\":\"desktop-sync/v1\",\"changes\":[],\"nextCursor\":\"9\"}")));
        try (var store = new SqliteViewerSyncStore(temp.resolve("sync.db"));
                var service = new ViewerSyncService(transport, store, temp.resolve("offline"))) {
            service.syncNow();
            assertEquals("Slide 1", store.find("slide-1").orElseThrow().remote().displayName());
            assertEquals(2048, store.find("slide-1").orElseThrow().remote().metadata().get("width"));
            assertEquals(0, store.folders().size());
            assertEquals(9, store.cursor());
        }
    }

    @Test
    void resumesAndVerifiesOfflineContentBeforeAtomicActivation() throws Exception {
        var payload = "verified OME bytes".getBytes(StandardCharsets.UTF_8);
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        var remote = new ViewerRemoteSlide("s1", "Remote", "", "ready_private",
                1, 0, 0, 0, "/thumb", "/tiles", payload.length, sha, Map.of(),
                java.time.Instant.parse("2026-08-12T00:00:00Z"));
        var transport = new FakeTransport(Map.of(
                "HEAD /api/v2/desktop/slides/s1/content",
                new ViewerHttpResponse(200, Map.of("content-length", List.of(String.valueOf(payload.length)),
                        "x-pathlab-sha256", List.of(sha)), new ByteArrayInputStream(new byte[0])),
                "GET /api/v2/desktop/slides/s1/content",
                new ViewerHttpResponse(200, Map.of(), new ByteArrayInputStream(payload))));
        try (var store = new SqliteViewerSyncStore(temp.resolve("offline.db"));
                var service = new ViewerSyncService(transport, store, temp.resolve("offline"))) {
            store.upsertRemote(remote);
            var result = service.keepOffline("s1");
            assertArrayEquals(payload, Files.readAllBytes(result));
            assertEquals(payload.length, store.find("s1").orElseThrow().downloadOffset());
        }
    }

    private static ViewerHttpResponse json(String value) {
        return new ViewerHttpResponse(200, Map.of(),
                new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record FakeTransport(Map<String, ViewerHttpResponse> responses)
            implements ViewerAuthorizedClient {
        @Override public ViewerHttpResponse request(String method, String path,
                Map<String, String> headers, byte[] body) {
            var response = responses.get(method + " " + path);
            if (response == null) throw new AssertionError("Unexpected request: " + method + " " + path);
            return response;
        }
    }
}

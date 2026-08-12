package org.pathlab.forge.viewer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ViewerTileCacheTest {
    @TempDir Path temp;

    @Test
    void cachesAuthenticatedResourceAndEvictsLeastRecentlyUsedBytes() throws Exception {
        var responses = new ArrayDeque<>(List.of(response("123456"), response("abcdef")));
        ViewerAuthorizedClient client = (method, path, headers, body) -> responses.removeFirst();
        var cache = new ViewerTileCache(client, temp, 10);
        var first = cache.get("/api/one").path();
        var second = cache.get("/api/two").path();

        assertFalse(Files.exists(first));
        assertTrue(Files.exists(second));
    }

    private static ViewerHttpResponse response(String value) {
        return new ViewerHttpResponse(200, Map.of("content-type", List.of("image/jpeg")),
                new ByteArrayInputStream(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}

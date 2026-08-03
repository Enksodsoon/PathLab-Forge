package org.pathlab.forge.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.viewer.EphemeralCredentialStore;
import org.pathlab.forge.viewer.ViewerPairingService;

final class PathLabAiBridgeTest {
    @TempDir Path temporary;

    @Test
    void pairsWithLeastPrivilegeScopesAndPollsTheControlPlane() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/pairing/exchange", exchange -> {
            var response = "{\"token\":\"device.token\",\"scopes\":[\"forge:jobs:poll\",\"forge:jobs:claim\",\"forge:progress:write\",\"forge:results:write\"]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/v1/forge/jobs/next", exchange -> {
            assertEquals("Bearer device.token", exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        var viewer = new ViewerPairingService(new EphemeralCredentialStore());
        try (var bridge = new PathLabAiBridge(
                HttpClient.newHttpClient(), new EphemeralCredentialStore(), viewer,
                temporary, null)) {
            var connection = bridge.pair(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "ABCDEFGHJK");
            assertTrue(connection.connected());
            assertEquals(4, connection.scopes().size());
            bridge.pollOnce();
            assertTrue(bridge.status().detail().contains("waiting"));
        } finally {
            viewer.close();
            server.stop(0);
        }
    }

    @Test
    void refusesUnencryptedRemoteOrPathBearingControlPlaneUrls() {
        assertThrows(IllegalArgumentException.class, () -> PathLabAiBridge.validateBase("http://example.com"));
        assertThrows(IllegalArgumentException.class, () -> PathLabAiBridge.validateBase("https://example.com/path"));
    }
}

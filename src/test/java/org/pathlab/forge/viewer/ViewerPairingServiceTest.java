package org.pathlab.forge.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ViewerPairingServiceTest {
    @Test
    void pairsExchangesChecksAndRevokesAgainstLoopbackViewer() throws Exception {
        var viewer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        viewer.createContext("/", ViewerPairingServiceTest::respond);
        viewer.start();
        var store = new MemoryCredentialStore();
        try (var service = new ViewerPairingService(store)) {
            var base = "http://127.0.0.1:" + viewer.getAddress().getPort();
            var pairing = service.start(base);
            assertEquals("ABCD-EFGH", pairing.userCode());
            assertEquals(base + "/admin/connect?code=ABCD-EFGH", pairing.verificationUrl());

            var connection = service.exchange();
            assertTrue(connection.connected());
            assertEquals(base, connection.viewerUrl());
            assertTrue(store.read().orElseThrow().endsWith("\ndesktop-token"));
            assertTrue(service.status().connected());

            service.revoke();
            assertFalse(service.status().connected());
        } finally {
            viewer.stop(0);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath();
        var port = exchange.getLocalAddress().getPort();
        int status;
        String body;
        if (path.equals("/api/v1/desktop/pairings")) {
            status = 201;
            body = "{\"deviceCode\":\"device-code\",\"deviceSecret\":\"device-secret\","
                    + "\"userCode\":\"ABCD-EFGH\",\"verificationUrl\":\"http://127.0.0.1:"
                    + port + "/admin/connect?code=ABCD-EFGH\","
                    + "\"expiresAt\":\"2026-07-29T08:30:00Z\"}";
        } else if (path.equals("/api/v1/desktop/pairings/exchange")) {
            status = 200;
            body = "{\"accessToken\":\"desktop-token\",\"scopes\":[\"desktop:ingest\","
                    + "\"slides:private:read\",\"annotations:sync\"]}";
        } else if (path.equals("/api/v1/desktop/credential/revoke")) {
            status = 204;
            body = "";
        } else if (path.equals("/api/v1/desktop/credential")) {
            status = 200;
            body = "{\"deviceName\":\"PathLab Forge on Windows\",\"scopes\":["
                    + "\"desktop:ingest\",\"slides:private:read\",\"annotations:sync\"]}";
        } else {
            status = 404;
            body = "{\"detail\":\"not found\"}";
        }
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static final class MemoryCredentialStore implements CredentialStore {
        private String value;

        @Override
        public void write(String nextValue) {
            value = nextValue;
        }

        @Override
        public Optional<String> read() {
            return Optional.ofNullable(value);
        }

        @Override
        public void delete() {
            value = null;
        }
    }
}

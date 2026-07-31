package org.pathlab.forge.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.conversion.ArtifactRevision;
import org.pathlab.forge.conversion.ArtifactRevisionFormat;
import org.pathlab.forge.conversion.ArtifactRevisionStatus;

final class ViewerPairingServiceTest {
    @TempDir
    Path temporaryDirectory;

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

    @Test
    void uploadsOnlyTheApprovedOmeWhenViewerAdvertisesDynamicIngest() throws Exception {
        var receivedCreateBody = new AtomicReference<String>();
        var receivedPayload = new AtomicReference<byte[]>();
        var viewer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        viewer.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            if (path.equals("/api/v1/desktop/capabilities")) {
                respond(exchange, 200, "{\"ingestModes\":[\"prepared-v2\",\"ome-dynamic-v1\"],"
                        + "\"maxChunkBytes\":67108864,\"recommendedChunkBytes\":67108864}");
            } else if (path.equals("/api/v1/desktop/ome-ingests")) {
                receivedCreateBody.set(new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 201, "{\"uploadUrl\":\"/api/v1/desktop/ingests/one/content\"}");
            } else if (path.equals("/api/v1/desktop/ingests/one/content")
                    && exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Upload-Offset", "0");
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            } else if (path.equals("/api/v1/desktop/ingests/one/content")) {
                receivedPayload.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, 202, "{\"slideId\":null}");
            } else if (path.equals("/api/v1/desktop/ingests/one")) {
                respond(exchange, 200, "{\"status\":\"ready_private\",\"slideId\":\"slide-one\"}");
            } else {
                respond(exchange, 404, "{\"detail\":\"not found\"}");
            }
        });
        viewer.start();
        try {
            var ome = Files.write(
                    temporaryDirectory.resolve("export.ome.tif"),
                    new byte[] {'I', 'I', 42, 0, 1, 2, 3});
            var sha = sha256(ome);
            var revision = revision(ome, sha);
            writeOmeStamp(revision, ome);
            var store = new MemoryCredentialStore();
            var base = "http://127.0.0.1:" + viewer.getAddress().getPort();
            store.write(base + "\ndesktop-token");
            try (var service = new ViewerPairingService(store)) {
                var started = service.startUpload(
                        "case-1.5x", revision, List.of(), 0, 0, 150, 75, 1.5);
                assertEquals("OME_DYNAMIC", started.uploadMode());
                for (var attempt = 0;
                        attempt < 100 && !"READY_PRIVATE".equals(service.uploadStatus().state());
                        attempt++) {
                    Thread.sleep(25);
                }
                assertEquals("READY_PRIVATE", service.uploadStatus().state());
                assertEquals("OME_DYNAMIC", service.uploadStatus().uploadMode());
                assertTrue(receivedCreateBody.get().contains("\"profile\":\"ome-dynamic-v1\""));
                assertTrue(receivedCreateBody.get().contains("\"jpegQuality\":75"));
                assertTrue(receivedCreateBody.get().contains("\"omeSha256\":\"" + sha + "\""));
                assertEquals(
                        HexFormat.of().formatHex(Files.readAllBytes(ome)),
                        HexFormat.of().formatHex(receivedPayload.get()));
            }
        } finally {
            viewer.stop(0);
        }
    }

    @Test
    void retriesTheSameDynamicIngestFromTheViewerOffsetWithoutCreatingAnotherUpload()
            throws Exception {
        var createCount = new AtomicInteger();
        var patchCount = new AtomicInteger();
        var resumeOffset = new AtomicInteger();
        var resumedPayload = new AtomicReference<byte[]>();
        var viewer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        viewer.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            if (path.equals("/api/v1/desktop/capabilities")) {
                respond(exchange, 200, "{\"ingestModes\":[\"ome-dynamic-v1\"],"
                        + "\"maxChunkBytes\":67108864,\"recommendedChunkBytes\":67108864}");
            } else if (path.equals("/api/v1/desktop/ome-ingests")) {
                createCount.incrementAndGet();
                respond(exchange, 201, "{\"uploadUrl\":\"/api/v1/desktop/ingests/resume/content\"}");
            } else if (path.equals("/api/v1/desktop/ingests/resume/content")
                    && exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Upload-Offset", Integer.toString(resumeOffset.get()));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            } else if (path.equals("/api/v1/desktop/ingests/resume/content")) {
                var body = exchange.getRequestBody().readAllBytes();
                if (patchCount.getAndIncrement() == 0) {
                    resumeOffset.set(3);
                    respond(exchange, 503, "{\"detail\":\"connection lost\"}");
                } else {
                    assertEquals("3", exchange.getRequestHeaders().getFirst("Upload-Offset"));
                    resumedPayload.set(body);
                    resumeOffset.addAndGet(body.length);
                    respond(exchange, 202, "{\"slideId\":null}");
                }
            } else if (path.equals("/api/v1/desktop/ingests/resume")) {
                respond(exchange, 200, "{\"status\":\"ready_private\",\"slideId\":\"slide-resumed\"}");
            } else {
                respond(exchange, 404, "{\"detail\":\"not found\"}");
            }
        });
        viewer.start();
        try {
            var bytes = new byte[] {'I', 'I', 42, 0, 1, 2, 3};
            var ome = Files.write(temporaryDirectory.resolve("resume.ome.tif"), bytes);
            var revision = revision(ome, sha256(ome));
            writeOmeStamp(revision, ome);
            var store = new MemoryCredentialStore();
            store.write("http://127.0.0.1:" + viewer.getAddress().getPort() + "\ndesktop-token");
            try (var service = new ViewerPairingService(store)) {
                service.startUpload("resume", revision, List.of(), 0, 0, 100, 50, 1);
                awaitState(service, "FAILED");
                service.startUpload("resume", revision, List.of(), 0, 0, 100, 50, 1);
                awaitState(service, "READY_PRIVATE");

                assertEquals(1, createCount.get());
                assertEquals(2, patchCount.get());
                assertEquals(HexFormat.of().formatHex(java.util.Arrays.copyOfRange(bytes, 3, bytes.length)),
                        HexFormat.of().formatHex(resumedPayload.get()));
                assertEquals("OME_DYNAMIC", service.uploadStatus().uploadMode());
            }
        } finally {
            viewer.stop(0);
        }
    }

    @Test
    void retriesFailedFinalizationWithoutCreatingOrUploadingTheArtifactAgain() throws Exception {
        var createCount = new AtomicInteger();
        var patchCount = new AtomicInteger();
        var finalizationChecks = new AtomicInteger();
        var length = 7;
        var viewer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        viewer.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            if (path.equals("/api/v1/desktop/capabilities")) {
                respond(exchange, 200, "{\"ingestModes\":[\"ome-dynamic-v1\"],"
                        + "\"maxChunkBytes\":67108864,\"recommendedChunkBytes\":67108864}");
            } else if (path.equals("/api/v1/desktop/ome-ingests")) {
                createCount.incrementAndGet();
                respond(exchange, 201, "{\"uploadUrl\":\"/api/v1/desktop/ingests/finalize/content\"}");
            } else if (path.equals("/api/v1/desktop/ingests/finalize/content")
                    && exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set(
                        "Upload-Offset", patchCount.get() == 0 ? "0" : Integer.toString(length));
                if (finalizationChecks.get() > 0) {
                    exchange.getResponseHeaders().set("Upload-Status", "failed");
                }
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            } else if (path.equals("/api/v1/desktop/ingests/finalize/content")) {
                var body = exchange.getRequestBody().readAllBytes();
                if (patchCount.get() > 0) {
                    assertEquals(0, body.length);
                }
                patchCount.incrementAndGet();
                respond(exchange, 202, "{\"slideId\":null}");
            } else if (path.equals("/api/v1/desktop/ingests/finalize")) {
                if (finalizationChecks.getAndIncrement() == 0) {
                    respond(exchange, 200, "{\"status\":\"failed\",\"errorCode\":\"FINALIZER_FAILED\"}");
                } else {
                    respond(exchange, 200, "{\"status\":\"ready_private\",\"slideId\":\"slide-finalized\"}");
                }
            } else {
                respond(exchange, 404, "{\"detail\":\"not found\"}");
            }
        });
        viewer.start();
        try {
            var ome = Files.write(
                    temporaryDirectory.resolve("finalize.ome.tif"),
                    new byte[] {'I', 'I', 42, 0, 1, 2, 3});
            var revision = revision(ome, sha256(ome));
            writeOmeStamp(revision, ome);
            var store = new MemoryCredentialStore();
            store.write("http://127.0.0.1:" + viewer.getAddress().getPort() + "\ndesktop-token");
            try (var service = new ViewerPairingService(store)) {
                service.startUpload("finalize", revision, List.of(), 0, 0, 100, 50, 1);
                awaitState(service, "FAILED");
                service.startUpload("finalize", revision, List.of(), 0, 0, 100, 50, 1);
                awaitState(service, "READY_PRIVATE");

                assertEquals(1, createCount.get());
                assertEquals(2, patchCount.get());
                assertEquals(2, finalizationChecks.get());
            }
        } finally {
            viewer.stop(0);
        }
    }

    private ArtifactRevision revision(Path ome, String sha) {
        return new ArtifactRevision(
                "11111111-1111-1111-1111-111111111111",
                "dataset-one",
                "configuration-one",
                "a".repeat(64),
                System.currentTimeMillis(),
                ArtifactRevisionStatus.APPROVED,
                ArtifactRevisionFormat.PREPARED_DZI_V2,
                ome.toString(),
                temporaryDirectory.resolve("derivative").toString(),
                temporaryDirectory.resolve("absent.plslide").toString(),
                sha,
                "",
                100,
                50,
                "ome-dynamic-v1",
                75,
                System.currentTimeMillis(),
                "Test conversion",
                "");
    }

    private static void respond(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath();
        int status;
        String body;
        if (path.equals("/api/v1/desktop/pairings")) {
            status = 201;
            body = "{\"deviceCode\":\"device-code\",\"deviceSecret\":\"device-secret\","
                    + "\"userCode\":\"ABCD-EFGH\",\"verificationUrl\":\"http://127.0.0.1:8000"
                    + "/admin/connect?code=ABCD-EFGH\","
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

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static void writeOmeStamp(ArtifactRevision revision, Path ome) throws Exception {
        var values = new Properties();
        values.setProperty("omeSha256", revision.omeSha256());
        values.setProperty("omeProfile", revision.omeProfile());
        values.setProperty("omeJpegQuality", Integer.toString(revision.omeJpegQuality()));
        values.setProperty("omeSize", Long.toString(Files.size(ome)));
        values.setProperty("omeModified", Long.toString(Files.getLastModifiedTime(ome).toMillis()));
        try (var output = Files.newOutputStream(
                ome.getParent().resolve("artifact.integrity.properties"))) {
            values.store(output, "test");
        }
    }

    private static void awaitState(ViewerPairingService service, String expected)
            throws InterruptedException {
        for (var attempt = 0;
                attempt < 100 && !expected.equals(service.uploadStatus().state());
                attempt++) {
            Thread.sleep(25);
        }
        assertEquals(expected, service.uploadStatus().state());
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

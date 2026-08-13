package org.pathlab.forge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.library.PropertiesDatasetRepository;
import org.junit.jupiter.api.Test;

final class ForgeServerTest {
    @TempDir
    Path temp;

    @Test
    void boundsHttpWorkersToDetectedProcessors() {
        assertEquals(6, ForgeServer.recommendedHttpWorkers(6));
        assertEquals(8, ForgeServer.recommendedHttpWorkers(24));
        assertEquals(2, ForgeServer.recommendedHttpWorkers(1));
    }

    @Test
    void bootstrapsOneTimeSessionAndServesPathLabShell() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        try (var server = startEphemeral()) {
            var bootstrap = client.send(
                    HttpRequest.newBuilder(server.launchUri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(303, bootstrap.statusCode());
            assertEquals("/app", bootstrap.headers().firstValue("location").orElseThrow());
            assertTrue(bootstrap.headers().firstValue("set-cookie").orElseThrow()
                    .contains("Max-Age=315360000; HttpOnly; SameSite=Strict"));
            var reused = client.send(
                    HttpRequest.newBuilder(server.launchUri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(303, reused.statusCode());

            var unauthenticatedClient = HttpClient.newHttpClient();
            var stolenReuse = unauthenticatedClient.send(
                    HttpRequest.newBuilder(server.launchUri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, stolenReuse.statusCode());

            var app = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve("/app")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, app.statusCode());
            assertTrue(app.body().contains("PathLab"));
            assertTrue(app.body().contains("Forge"));
            assertTrue(app.body().contains("<div id=\"root\"></div>"));
            assertTrue(app.body().contains("src=\"/assets/app.js\""));
            var contentSecurityPolicy = app.headers()
                    .firstValue("content-security-policy")
                    .orElseThrow();
            assertTrue(contentSecurityPolicy.contains("script-src 'self'"));
            assertTrue(contentSecurityPolicy.contains("style-src 'self' 'unsafe-inline'"));

            var script = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve("/assets/app.js")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, script.statusCode());
            assertTrue(script.body().contains("/api/local-files"));
            assertTrue(script.body().contains("/api/datasets"));
            assertTrue(script.body().contains("Queue ready"));
        }
    }

    @Test
    void requiresSameOriginAndCsrfForWrites() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();

        try (var server = startEphemeral()) {
            client.send(
                    HttpRequest.newBuilder(server.launchUri()).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            var session = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve("/api/session")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var csrf = session.headers().firstValue("x-forge-csrf").orElseThrow();

            var missingOrigin = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve("/api/batches"))
                            .header("X-Forge-CSRF", csrf)
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, missingOrigin.statusCode());

            URI origin = server.baseUri();
            var accepted = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve("/api/batches"))
                            .header("Origin", origin.toString())
                            .header("X-Forge-CSRF", csrf)
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, accepted.statusCode());
            assertTrue(accepted.body().contains("\"state\":\"staged\""));
        }
    }

    @Test
    void returnsNotModifiedForUnchangedDatasetWorkspace() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();
        try (var server = startEphemeral()) {
            client.send(
                    HttpRequest.newBuilder(server.launchUri()).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            var first = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve("/api/datasets")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var etag = first.headers().firstValue("etag").orElseThrow();
            var unchanged = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve("/api/datasets"))
                            .header("If-None-Match", etag)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, first.statusCode());
            assertEquals(304, unchanged.statusCode());
            assertEquals("", unchanged.body());
        }
    }

    @Test
    void fixedAddressAndAuthorizedBrowserSurviveServerRestart() throws Exception {
        var sessionFile = temp.resolve("browser-session.token");
        var sessionToken = LocalBrowserSession.loadOrCreate(sessionFile);
        assertEquals(sessionToken, LocalBrowserSession.loadOrCreate(sessionFile));

        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var port = availableLoopbackPort();
        URI permanentUri;

        try (var first = startOnPort(port, sessionToken)) {
            permanentUri = first.appUri();
            var bootstrap = client.send(
                    HttpRequest.newBuilder(first.launchUri()).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertEquals(303, bootstrap.statusCode());
            assertEquals("http://127.0.0.1:" + port + "/app", permanentUri.toString());
        }

        try (var restarted = startOnPort(port, LocalBrowserSession.loadOrCreate(sessionFile))) {
            assertEquals(permanentUri, restarted.appUri());
            var app = client.send(
                    HttpRequest.newBuilder(permanentUri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, app.statusCode());
            assertTrue(app.body().contains("<div id=\"root\"></div>"));
        }
    }

    @Test
    void permanentAppLinkAuthorizesACleanLocalBrowserButRejectsCrossSiteNavigation()
            throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        try (var server = startEphemeral()) {
            var firstVisit = client.send(
                    HttpRequest.newBuilder(server.appUri())
                            .header("Sec-Fetch-Site", "same-origin")
                            .header("Sec-Fetch-Mode", "navigate")
                            .header("Sec-Fetch-Dest", "document")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(303, firstVisit.statusCode());
            assertEquals("/app", firstVisit.headers().firstValue("location").orElseThrow());
            assertTrue(firstVisit.headers().firstValue("set-cookie").orElseThrow()
                    .contains("HttpOnly; SameSite=Strict"));

            var authorizedVisit = client.send(
                    HttpRequest.newBuilder(server.appUri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, authorizedVisit.statusCode());
            assertTrue(authorizedVisit.body().contains("<div id=\"root\"></div>"));

            var crossSiteClient = HttpClient.newHttpClient();
            var crossSiteVisit = crossSiteClient.send(
                    HttpRequest.newBuilder(server.appUri())
                            .header("Sec-Fetch-Site", "cross-site")
                            .header("Sec-Fetch-Mode", "navigate")
                            .header("Sec-Fetch-Dest", "document")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, crossSiteVisit.statusCode());
        }
    }

    private ForgeServer startEphemeral() throws Exception {
        return startOnPort(0, LocalBrowserSession.randomToken());
    }

    private ForgeServer startOnPort(int port, String sessionToken) throws Exception {
        var repository =
                new PropertiesDatasetRepository(temp.resolve("library-" + port + ".properties"));
        return ForgeServer.startOnPort(
                repository, java.util.List::of, temp.resolve("managed-" + port), port, sessionToken);
    }

    private static int availableLoopbackPort() throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}

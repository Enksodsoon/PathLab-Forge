package org.pathlab.forge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

final class ForgeServerTest {
    @Test
    void bootstrapsOneTimeSessionAndServesPathLabShell() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        try (var server = ForgeServer.start()) {
            var bootstrap = client.send(
                    HttpRequest.newBuilder(server.launchUri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(303, bootstrap.statusCode());
            assertEquals("/app", bootstrap.headers().firstValue("location").orElseThrow());
            assertTrue(bootstrap.headers().firstValue("set-cookie").orElseThrow()
                    .contains("HttpOnly; SameSite=Strict"));

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
            assertTrue(app.headers().firstValue("content-security-policy").isPresent());

            var script = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve("/assets/app.js")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, script.statusCode());
            assertTrue(script.body().contains("/api/datasets/select"));
            assertTrue(script.body().contains("/api/datasets"));
            assertTrue(script.body().contains("Queue ready"));
        }
    }

    @Test
    void requiresSameOriginAndCsrfForWrites() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();

        try (var server = ForgeServer.start()) {
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
}

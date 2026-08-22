package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvidenceMentorRunnerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporaryDirectory;

    @Test
    void acceptsAuthenticatedLoopbackSubmissionIntoDurableQueue() throws Exception {
        var requestFile = temporaryDirectory.resolve("request.json");
        Files.writeString(requestFile, "{}");
        try (var runner = EvidenceMentorRunner.start(
                temporaryDirectory.resolve("state"), 0, "test-loopback-token-0123456789abcdef", false)) {
            var client = HttpClient.newHttpClient();
            var body = "{\"id\":\"job-http-1\",\"requestPath\":"
                    + quote(requestFile.toString()) + "}";
            var unauthorized = client.send(HttpRequest.newBuilder(runner.uri("/v1/jobs"))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauthorized.statusCode());

            var invalid = client.send(HttpRequest.newBuilder(runner.uri("/v1/jobs"))
                    .header("Authorization", "Bearer test-loopback-token-0123456789abcdef")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")) .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(422, invalid.statusCode());

            var accepted = client.send(HttpRequest.newBuilder(runner.uri("/v1/jobs"))
                    .header("Authorization", "Bearer test-loopback-token-0123456789abcdef")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, accepted.statusCode());
            assertTrue(accepted.body().contains("job-http-1"));
            assertTrue(runner.find("job-http-1").isPresent());
            assertEquals("127.0.0.1", runner.uri("/health").getHost());

            var status = client.send(HttpRequest.newBuilder(runner.uri("/v1/status"))
                    .header("Authorization", "Bearer test-loopback-token-0123456789abcdef")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, status.statusCode());
            assertTrue(status.body().contains("networkDisabledForAnalysis"));
            assertTrue(status.body().contains("evidence-test"));
            assertTrue(status.body().contains("pathlab.evidence-runner-status/2"));

            var jobs = client.send(HttpRequest.newBuilder(runner.uri("/v1/jobs?limit=50"))
                    .header("Authorization", "Bearer test-loopback-token-0123456789abcdef")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, jobs.statusCode());
            var notModified = client.send(HttpRequest.newBuilder(runner.uri("/v1/jobs?limit=50"))
                    .header("Authorization", "Bearer test-loopback-token-0123456789abcdef")
                    .header("If-None-Match", jobs.headers().firstValue("ETag").orElseThrow())
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(304, notModified.statusCode());

            var endpoint = JSON.readTree(temporaryDirectory.resolve("state/endpoint.json").toFile());
            assertEquals("pathlab.runner-endpoint/1", endpoint.path("schema").asText());
            assertEquals("2.0.2", endpoint.path("serviceVersion").asText());
            assertEquals(runner.uri("/").getPort(), endpoint.path("port").asInt());
            assertTrue(!endpoint.has("token"));
        }
    }

    @Test
    void exchangesSingleUseDashboardCodeAndProtectsMutations() throws Exception {
        try (var runner = EvidenceMentorRunner.start(
                temporaryDirectory.resolve("state"), 0, "test-loopback-token-0123456789abcdef", false)) {
            var client = HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build();
            var create = client.send(HttpRequest.newBuilder(runner.uri("/v1/dashboard-sessions"))
                    .header("Authorization", "Bearer test-loopback-token-0123456789abcdef")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, create.statusCode());
            var code = JSON.readTree(create.body()).path("code").asText();
            var exchangeBody = "{\"code\":" + quote(code) + "}";
            var exchange = client.send(HttpRequest.newBuilder(runner.uri("/v1/dashboard-session/exchange"))
                    .header("Origin", runner.uri("").toString())
                    .POST(HttpRequest.BodyPublishers.ofString(exchangeBody)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, exchange.statusCode());
            var csrf = JSON.readTree(exchange.body()).path("csrfToken").asText();
            assertTrue(exchange.headers().firstValue("set-cookie").orElse("").contains("HttpOnly"));

            var reused = client.send(HttpRequest.newBuilder(runner.uri("/v1/dashboard-session/exchange"))
                    .header("Origin", runner.uri("").toString())
                    .POST(HttpRequest.BodyPublishers.ofString(exchangeBody)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, reused.statusCode());

            var withoutCsrf = client.send(HttpRequest.newBuilder(runner.uri("/v1/control/pause"))
                    .header("Origin", runner.uri("").toString())
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, withoutCsrf.statusCode());
            var pause = client.send(HttpRequest.newBuilder(runner.uri("/v1/control/pause"))
                    .header("Origin", runner.uri("").toString()).header("X-PathLab-CSRF", csrf)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, pause.statusCode());
            assertTrue(pause.body().contains("\"acceptingJobs\":false"));

            var dashboard = client.send(HttpRequest.newBuilder(runner.uri("/dashboard/"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, dashboard.statusCode());
            assertTrue(dashboard.body().contains("PathLab Evidence Mentor"));
            assertTrue(!dashboard.body().contains("https://"));
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

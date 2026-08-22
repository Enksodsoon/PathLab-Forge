package org.pathlab.forge.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EvidenceMentorRunnerTest {
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
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

package org.pathlab.forge.server;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.library.PropertiesDatasetRepository;

final class FeatureImportHttpTest {
    @TempDir Path temp;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Test void requiresCsrfAndFeaturePurposeForBothPathsBeforeSignedCatalogVerification() throws Exception {
        var previousDesktop = System.getProperty("pathlab.forge.desktop");
        var previousKey = System.getProperty("pathlab.forge.featureCatalogPublicKey");
        var key = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        System.setProperty("pathlab.forge.desktop", "true");
        System.setProperty("pathlab.forge.featureCatalogPublicKey", Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));
        try {
            var catalog = temp.resolve("catalog.json");
            // Correct envelope encoding, deliberately invalid Ed25519 signature; no pack is trusted.
            Files.writeString(catalog, mapper.writeValueAsString(Map.of(
                    "payload", Base64.getEncoder().encodeToString("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "signature", Base64.getEncoder().encodeToString(new byte[64]))));
            var archive = temp.resolve("feature.zip");
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) { }
            var paths = List.of(catalog.toString(), archive.toString());
            var requestBody = mapper.writeValueAsString(Map.of("catalogPath", catalog.toString(), "archivePath", archive.toString()));
            var repository = new PropertiesDatasetRepository(temp.resolve("library.properties"));
            try (var server = ForgeServer.startOnPort(repository, List::of, temp.resolve("managed"), 0, LocalBrowserSession.randomToken())) {
                var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
                client.send(HttpRequest.newBuilder(server.launchUri()).GET().build(), HttpResponse.BodyHandlers.discarding());
                var csrf = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/session")).GET().build(), HttpResponse.BodyHandlers.discarding())
                        .headers().firstValue("X-Forge-CSRF").orElseThrow();
                var before = featureList(server, client);
                assertEquals(403, post(server, client, "/api/features/import", "", requestBody).statusCode());
                assertEquals(403, post(server, client, "/api/features/import", "wrong-csrf", requestBody).statusCode());
                nativeDialogDenied(post(server, client, "/api/features/import", csrf, requestBody));
                // Main-process approval to import images must not authorize installing a feature pack.
                assertEquals(204, grant(server, client, "import", paths).statusCode());
                nativeDialogDenied(post(server, client, "/api/features/import", csrf, requestBody));
                assertEquals(204, grant(server, client, "feature", List.of(catalog.toString())).statusCode());
                nativeDialogDenied(post(server, client, "/api/features/import", csrf, requestBody));
                assertEquals(204, grant(server, client, "feature", List.of(archive.toString())).statusCode());
                var verified = post(server, client, "/api/features/import", csrf, requestBody);
                assertEquals(409, verified.statusCode(), verified.body());
                assertEquals("feature_import_failed", mapper.readTree(verified.body()).path("error").asText());
                assertTrue(mapper.readTree(verified.body()).path("detail").asText().toLowerCase(java.util.Locale.ROOT).contains("signature"), verified.body());
                assertFalse(verified.body().contains("native dialog"), verified.body());
                assertEquals(before, featureList(server, client), "An invalid signed catalog must not install or activate a pack");
                assertFalse(Files.exists(temp.resolve("feature-packs/catalog-envelope.json")), "Unverified catalog must not become the offline catalog");
                var extraField = mapper.readTree(requestBody).deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) extraField).put("id", "pathology-tools");
                assertEquals(409, post(server, client, "/api/features/import", csrf, mapper.writeValueAsString(extraField)).statusCode());
                assertEquals(before, featureList(server, client));
            }
        } finally {
            restore("pathlab.forge.desktop", previousDesktop);
            restore("pathlab.forge.featureCatalogPublicKey", previousKey);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode featureList(ForgeServer server, HttpClient client) throws Exception {
        var response = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/features")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body()).path("features");
    }
    private void nativeDialogDenied(HttpResponse<String> response) throws Exception {
        assertEquals(409, response.statusCode(), response.body());
        assertEquals("feature_import_failed", mapper.readTree(response.body()).path("error").asText());
        assertTrue(mapper.readTree(response.body()).path("detail").asText().contains("native dialog"), response.body());
    }
    private HttpResponse<String> grant(ForgeServer server, HttpClient client, String purpose, List<String> paths) throws Exception {
        return client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/desktop/selections"))
                .header("X-Forge-Desktop-Secret", server.desktopSecret())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("purpose", purpose, "paths", paths))))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(ForgeServer server, HttpClient client, String path, String csrf, String body) throws Exception {
        var request = HttpRequest.newBuilder(server.baseUri().resolve(path)).header("Origin", server.baseUri().toString())
                .header("Content-Type", "application/json");
        if (!csrf.isEmpty()) request.header("X-Forge-CSRF", csrf);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void restore(String property, String value) {
        if (value == null) System.clearProperty(property); else System.setProperty(property, value);
    }
}

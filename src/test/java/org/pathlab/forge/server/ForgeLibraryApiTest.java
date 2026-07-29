package org.pathlab.forge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.library.PropertiesDatasetRepository;

final class ForgeLibraryApiTest {
    @TempDir
    Path tempDirectory;

    @Test
    void selectsPersistsPreparesAndRemovesDataset() throws Exception {
        var source = tempDirectory.resolve("case.ome.tif");
        Files.write(source, new byte[] {'I', 'I', 42, 0, 1, 2, 3});
        var repositoryFile = tempDirectory.resolve("library.properties");
        var repository = new PropertiesDatasetRepository(repositoryFile);
        var managed = tempDirectory.resolve("managed");
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();

        try (var server = ForgeServer.start(repository, () -> List.of(source), managed)) {
            client.send(
                    HttpRequest.newBuilder(server.launchUri()).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            var session = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve("/api/session")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var csrf = session.headers().firstValue("x-forge-csrf").orElseThrow();

            var selected = write(client, server, csrf, "/api/datasets/select", "POST");
            assertEquals(200, selected.statusCode());
            assertTrue(selected.body().contains("case.ome.tif"));
            assertTrue(selected.body().contains("\"status\":\"READY\""));

            var reloadedRepository = new PropertiesDatasetRepository(repositoryFile);
            var dataset = reloadedRepository.list().get(0);
            assertEquals(source.toAbsolutePath().normalize().toString(), dataset.sourcePath());

            var prepared =
                    write(client, server, csrf, "/api/datasets/" + dataset.id() + "/prepare", "POST");
            assertEquals(200, prepared.statusCode());
            assertTrue(prepared.body().contains("\"status\":\"LOCAL_COPY_READY\""));
            assertTrue(Files.exists(Path.of(repository.find(dataset.id()).orElseThrow().outputPath())));

            var removed =
                    write(client, server, csrf, "/api/datasets/" + dataset.id(), "DELETE");
            assertEquals(204, removed.statusCode());
            assertTrue(repository.list().isEmpty());
            assertFalse(Files.notExists(source));
        }
    }

    private static HttpResponse<String> write(
            HttpClient client, ForgeServer server, String csrf, String path, String method)
            throws Exception {
        var builder = HttpRequest.newBuilder(server.baseUri().resolve(path))
                .header("Origin", server.baseUri().toString())
                .header("X-Forge-CSRF", csrf);
        if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}

package org.pathlab.forge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.conversion.ConversionEngine;
import org.pathlab.forge.conversion.SeriesInfo;
import org.pathlab.forge.derivative.DerivativeEngine;
import org.pathlab.forge.derivative.DerivativeInfo;
import org.pathlab.forge.library.PropertiesDatasetRepository;

final class PivotApiTest {
    @TempDir
    Path temp;

    @Test
    void compilesStartsServesAndScoresAnAnnotationFreeSession() throws Exception {
        var source = temp.resolve("teaching.ome.tif");
        Files.write(source, new byte[] {'I', 'I', 42, 0, 1, 2, 3});
        var repository = new PropertiesDatasetRepository(temp.resolve("library.properties"));
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();
        ConversionEngine engine = new ConversionEngine() {
            @Override public boolean available() { return true; }
            @Override public String runtimeDescription() { return "PIVOT API test"; }
            @Override public List<SeriesInfo> inspect(Path ignored) {
                return List.of(new SeriesInfo(
                        0, "Tissue", 192, 128, 3, 1, 1, "uint8", 0.5, 0.5, "µm"));
            }
            @Override public void convert(Path ignored, int series, Path output) {}
        };
        DerivativeEngine derivatives = new DerivativeEngine() {
            @Override public boolean available() { return true; }
            @Override public String description() { return "PIVOT preview test"; }
            @Override public void optimizeOme(Path input, Path output, int width, int height) {}
            @Override public DerivativeInfo generateDzi(Path input, Path root, int width, int height) {
                throw new AssertionError("PIVOT must use viewer DZI");
            }
            @Override public DerivativeInfo generateViewerDzi(
                    Path input, Path root, int width, int height) throws java.io.IOException {
                Files.createDirectories(root.resolve("slide_files/8"));
                Files.writeString(
                        root.resolve("slide.dzi"),
                        "<Image TileSize=\"64\" Overlap=\"1\" Format=\"jpg\" "
                                + "xmlns=\"http://schemas.microsoft.com/deepzoom/2008\">"
                                + "<Size Width=\"192\" Height=\"128\"/></Image>");
                for (var y = 0; y < 2; y++) {
                    for (var x = 0; x < 3; x++) {
                        writeTissue(root.resolve("slide_files/8/" + x + "_" + y + ".jpg"), x, y);
                    }
                }
                return new DerivativeInfo(root, 6, 6, 0, "pivot-test");
            }
        };

        try (var server = ForgeServer.start(
                repository, () -> List.of(source), temp.resolve("managed"), engine, derivatives)) {
            client.send(HttpRequest.newBuilder(server.launchUri()).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            var sessionBootstrap = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve("/api/session")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var csrf = sessionBootstrap.headers().firstValue("x-forge-csrf").orElseThrow();
            write(client, server, csrf, "/api/datasets/select");
            var dataset = repository.list().get(0);
            write(client, server, csrf, "/api/datasets/" + dataset.id() + "/inspect");
            for (var attempt = 0; attempt < 100; attempt++) {
                if (repository.find(dataset.id()).orElseThrow().selectedSeries() == 0) break;
                Thread.sleep(10);
            }
            for (var attempt = 0; attempt < 100; attempt++) {
                var preview = client.send(
                        HttpRequest.newBuilder(server.baseUri().resolve(
                                        "/api/datasets/" + dataset.id() + "/preview/slide.dzi"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (preview.statusCode() == 200) break;
                Thread.sleep(10);
            }

            var forbidden = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve(
                                    "/api/v2/desktop/datasets/" + dataset.id() + "/pivot/compile"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, forbidden.statusCode());

            var compiled = write(
                    client,
                    server,
                    csrf,
                    "/api/v2/desktop/datasets/" + dataset.id() + "/pivot/compile");
            assertEquals(201, compiled.statusCode());
            assertTrue(compiled.body().contains("\"status\":\"READY\""));
            assertTrue(compiled.body().contains("\"nonDiagnostic\":true"));

            var started = write(
                    client,
                    server,
                    csrf,
                    "/api/v2/desktop/datasets/" + dataset.id() + "/pivot/session");
            assertEquals(201, started.statusCode());
            assertTrue(started.body().contains("\"currentTask\""));
            var taskId = started.body().replaceFirst(".*\\\"currentTask\\\":\\{\\\"id\\\":\\\"", "")
                    .replaceFirst("\\\".*", "");

            var query = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve(
                                    "/api/v2/desktop/datasets/" + dataset.id()
                                            + "/pivot/query/" + taskId + ".jpg"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, query.statusCode());
            assertEquals("image/jpeg", query.headers().firstValue("content-type").orElseThrow());
            assertTrue(query.body().length > 100);

            var score = write(
                    client,
                    server,
                    csrf,
                    "/api/v2/desktop/datasets/" + dataset.id()
                            + "/pivot/session/submit?x=96&y=64&elapsedMs=1200"
                            + "&panDistance=500&zoomReversals=1&confidence=3");
            assertEquals(200, score.statusCode());
            assertTrue(score.body().contains("\"normalizedError\":"));
            assertTrue(score.body().contains("\"target\":"));
        }
    }

    private static HttpResponse<String> write(
            HttpClient client, ForgeServer server, String csrf, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(server.baseUri().resolve(path))
                        .header("Origin", server.baseUri().toString())
                        .header("X-Forge-CSRF", csrf)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void writeTissue(Path target, int tileX, int tileY) throws java.io.IOException {
        var image = new BufferedImage(65, 65, BufferedImage.TYPE_INT_RGB);
        for (var y = 0; y < 65; y++) {
            for (var x = 0; x < 65; x++) {
                var dark = ((x + tileX * 9) % (17 + tileY) < 8)
                        ^ ((y + tileY * 7) % (13 + tileX) < 6);
                image.setRGB(
                        x,
                        y,
                        (dark
                                ? new Color(86 + tileX * 13, 48 + tileY * 11, 119)
                                : new Color(228, 174 - tileX * 5, 200 - tileY * 4))
                                .getRGB());
            }
        }
        ImageIO.write(image, "jpg", target.toFile());
    }
}

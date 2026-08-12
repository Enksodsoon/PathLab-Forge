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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import org.pathlab.forge.conversion.ArtifactRevision;
import org.pathlab.forge.conversion.ArtifactRevisionFormat;
import org.pathlab.forge.conversion.ArtifactRevisionRepository;
import org.pathlab.forge.conversion.ConversionEngine;
import org.pathlab.forge.conversion.DirectTileSource;
import org.pathlab.forge.conversion.SeriesInfo;
import org.pathlab.forge.derivative.DerivativeEngine;
import org.pathlab.forge.derivative.DerivativeInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.library.PropertiesDatasetRepository;

final class ForgeLibraryApiTest {
    @TempDir
    Path tempDirectory;

    @Test
    void servesVerifiedDirectOmeArtifactsWithoutBuildingPersistentDzi() throws Exception {
        var source = Files.write(
                tempDirectory.resolve("source.ome.tif"), new byte[] {'I', 'I', 42, 0, 1});
        var managed = tempDirectory.resolve("managed-direct-ome");
        var repository = new PropertiesDatasetRepository(tempDirectory.resolve("direct-ome.properties"));
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();
        var tileReads = new java.util.concurrent.atomic.AtomicInteger();
        var artifactSource = new java.util.concurrent.atomic.AtomicReference<Path>();
        ConversionEngine engine = new ConversionEngine() {
            @Override public boolean available() { return true; }
            @Override public String runtimeDescription() { return "direct OME viewer test"; }
            @Override public List<SeriesInfo> inspect(Path ignored) {
                return List.of(new SeriesInfo(0, "Tissue", 1000, 500, 3, 1, 1, "uint8", 0.25, 0.25, "µm"));
            }
            @Override public void convert(Path ignored, int series, Path output) {}
            @Override public boolean supportsDirectTiles() { return true; }
            @Override public DirectTileSource directTileSource(Path selected, int series) {
                artifactSource.set(selected);
                assertEquals(0, series);
                return new DirectTileSource(800, 400, 512);
            }
            @Override public byte[] readDirectTile(
                    Path selected, int series, int level, int x, int y) {
                artifactSource.set(selected);
                tileReads.incrementAndGet();
                return new byte[] {1, 2, 3};
            }
        };
        DerivativeEngine derivatives = new DerivativeEngine() {
            @Override public boolean available() { return true; }
            @Override public String description() { return "unused derivative runtime"; }
            @Override public void optimizeOme(Path input, Path output, int width, int height) {}
            @Override public DerivativeInfo generateDzi(
                    Path input, Path output, int width, int height) {
                throw new AssertionError("Direct OME viewing must not generate persistent DZI");
            }
        };

        try (var server = ForgeServer.start(
                repository, () -> List.of(source), managed, engine, derivatives)) {
            client.send(HttpRequest.newBuilder(server.launchUri()).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            var session = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve("/api/session")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var csrf = session.headers().firstValue("x-forge-csrf").orElseThrow();
            write(client, server, csrf, "/api/datasets/select", "POST");
            var dataset = repository.list().get(0);
            write(client, server, csrf, "/api/datasets/" + dataset.id() + "/inspect", "POST");
            for (var attempt = 0; attempt < 100; attempt++) {
                dataset = repository.find(dataset.id()).orElseThrow();
                if (!dataset.configurationRevision().isBlank()) {
                    break;
                }
                Thread.sleep(20);
            }

            var artifacts = new ArtifactRevisionRepository(managed);
            var revision = artifacts.create(dataset, 800, 400, ArtifactRevisionFormat.OME_DYNAMIC_V1);
            var ome = Files.writeString(Path.of(revision.omePath()), "verified direct OME");
            revision = revision.ready(sha256(ome), "");
            artifacts.save(revision);
            writeOmeStamp(revision);
            var route = "/api/datasets/" + dataset.id() + "/artifacts/" + revision.id()
                    + "/ome-preview/";

            var descriptor = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve(route + "slide.dzi")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, descriptor.statusCode());
            assertTrue(descriptor.body().contains("Width=\"800\" Height=\"400\""));
            assertEquals("direct-ome", descriptor.headers()
                    .firstValue("x-pathlab-preview-mode").orElseThrow());
            var tileUri = server.baseUri().resolve(route + "slide_files/10/0_0.jpg");
            var tile = client.send(
                    HttpRequest.newBuilder(tileUri).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            var cachedTile = client.send(
                    HttpRequest.newBuilder(tileUri).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, tile.statusCode());
            assertEquals(List.of((byte) 1, (byte) 2, (byte) 3),
                    java.util.stream.IntStream.range(0, tile.body().length)
                            .mapToObj(index -> tile.body()[index]).toList());
            assertEquals(200, cachedTile.statusCode());
            assertEquals(1, tileReads.get());
            assertEquals(ome.toAbsolutePath().normalize(),
                    artifactSource.get().toAbsolutePath().normalize());
        }
    }

    @Test
    void preparesOmeViewerPyramidBeforeServingTilesInsteadOfDirectDecoding() throws Exception {
        var source = tempDirectory.resolve("single-resolution.ome.tif");
        Files.write(source, new byte[] {'I', 'I', 42, 0, 1});
        var repository = new PropertiesDatasetRepository(tempDirectory.resolve("ome-viewer.properties"));
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();
        var directReads = new java.util.concurrent.atomic.AtomicInteger();
        var buildStarted = new java.util.concurrent.CountDownLatch(1);
        var finishBuild = new java.util.concurrent.CountDownLatch(1);
        ConversionEngine engine = new ConversionEngine() {
            @Override public boolean available() { return true; }
            @Override public String runtimeDescription() { return "OME viewer test"; }
            @Override public List<SeriesInfo> inspect(Path ignored) {
                return List.of(new SeriesInfo(0, "Tissue", 1000, 500, 3, 1, 1, "uint8", 0.25, 0.25, "µm"));
            }
            @Override public void convert(Path ignored, int series, Path output) {}
            @Override public boolean supportsDirectTiles() { return true; }
            @Override public DirectTileSource directTileSource(Path ignored, int series) {
                return new DirectTileSource(1000, 500, 512);
            }
            @Override public byte[] readDirectTile(Path ignored, int series, int level, int x, int y) {
                directReads.incrementAndGet();
                return new byte[] {1};
            }
        };
        DerivativeEngine derivatives = new DerivativeEngine() {
            @Override public boolean available() { return true; }
            @Override public String description() { return "OME viewer derivative"; }
            @Override public void optimizeOme(Path input, Path output, int width, int height) {}
            @Override public DerivativeInfo generateDzi(Path input, Path root, int width, int height) {
                throw new AssertionError("Viewer cache must not run production DZI selection");
            }
            @Override public DerivativeInfo generateViewerDzi(Path input, Path root, int width, int height)
                    throws java.io.IOException {
                buildStarted.countDown();
                try {
                    if (!finishBuild.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new java.io.IOException("Timed out waiting for viewer test");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(error);
                }
                Files.createDirectories(root.resolve("slide_files/10"));
                Files.writeString(root.resolve("slide.dzi"), "<Image />");
                Files.write(root.resolve("slide_files/10/0_0.jpg"), new byte[] {9, 8, 7});
                return new DerivativeInfo(root, 3, 2, 1, "viewer-cache");
            }
        };

        try (var server = ForgeServer.start(
                repository, () -> List.of(source), tempDirectory.resolve("managed-ome-viewer"), engine, derivatives)) {
            client.send(HttpRequest.newBuilder(server.launchUri()).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            var session = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve("/api/session")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var csrf = session.headers().firstValue("x-forge-csrf").orElseThrow();
            write(client, server, csrf, "/api/datasets/select", "POST");
            var dataset = repository.list().get(0);
            write(client, server, csrf, "/api/datasets/" + dataset.id() + "/inspect", "POST");

            var direct = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve(
                                    "/api/datasets/" + dataset.id() + "/preview/slide.dzi"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, direct.statusCode());
            assertEquals("direct", direct.headers()
                    .firstValue("x-pathlab-preview-mode").orElseThrow());
            assertFalse(buildStarted.await(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            assertEquals(0, directReads.get());
            finishBuild.countDown();
        }
    }

    @Test
    void importsAProjectFolderRecursivelyAndIsolatesIncompleteVsiFiles() throws Exception {
        var project = Files.createDirectories(tempDirectory.resolve("project/nested"));
        Files.write(project.resolve("one.ome.tif"), new byte[] {'I', 'I', 42, 0, 1});
        Files.write(tempDirectory.resolve("project/incomplete.vsi"), new byte[] {1, 2, 3});
        Files.write(project.resolve("ignore.ets"), new byte[] {4, 5});
        var repository = new PropertiesDatasetRepository(tempDirectory.resolve("project.properties"));
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();

        try (var server = ForgeServer.start(repository, List::of, tempDirectory.resolve("managed-project"))) {
            client.send(HttpRequest.newBuilder(server.launchUri()).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            var session = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve("/api/session")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var csrf = session.headers().firstValue("x-forge-csrf").orElseThrow();
            var result = write(
                    client,
                    server,
                    csrf,
                    "/api/v2/desktop/projects/import-folder?path="
                            + java.net.URLEncoder.encode(
                                    tempDirectory.resolve("project").toString(),
                                    java.nio.charset.StandardCharsets.UTF_8),
                    "POST");

            assertEquals(200, result.statusCode());
            assertEquals(2, repository.list().size());
            assertTrue(result.body().contains("\"imported\":2"));
            assertTrue(result.body().contains("NEEDS_COMPANIONS"));
        }
    }

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
            assertTrue(selected.body().contains("\"status\":\"VERIFYING_SOURCE\""));
            assertTrue(selected.body().contains("\"workspaceRevision\":"));
            assertTrue(selected.body().contains("\"verificationState\":\"PENDING\""));
            assertTrue(selected.body().contains("\"completedUnits\":"));
            assertTrue(selected.body().contains("\"peakWorkingSetBytes\":"));
            assertTrue(selected.body().contains("\"resourceProfile\":\"8gb-6core\""));

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

    @Test
    void inspectsSelectsAndConvertsVsiInBackground() throws Exception {
        var source = tempDirectory.resolve("case.vsi");
        Files.write(source, new byte[] {1, 2, 3});
        var companion = Files.createDirectories(tempDirectory.resolve("case")).resolve("frame.ets");
        Files.write(companion, new byte[] {4, 5, 6});
        var repository = new PropertiesDatasetRepository(
                tempDirectory.resolve("conversion-library.properties"));
        var managed = tempDirectory.resolve("managed-conversion");
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();
        var directReads = new java.util.concurrent.atomic.AtomicInteger();
        ConversionEngine fakeEngine = new ConversionEngine() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String runtimeDescription() {
                return "test engine";
            }

            @Override
            public List<SeriesInfo> inspect(Path ignored) {
                return List.of(new SeriesInfo(
                        0, "Tissue", 1000, 500, 3, 1, 1, "uint8", 0.25, 0.25, "µm"));
            }

            @Override
            public boolean supportsDirectTiles() {
                return true;
            }

            @Override
            public DirectTileSource directTileSource(Path ignored, int seriesIndex) {
                return new DirectTileSource(1000, 500, 512);
            }

            @Override
            public byte[] readDirectTile(
                    Path ignored, int seriesIndex, int level, int tileX, int tileY) {
                directReads.incrementAndGet();
                return new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9};
            }

            @Override
            public void convert(Path ignored, int seriesIndex, Path output) throws java.io.IOException {
                Files.write(output, new byte[] {'I', 'I', 43, 0, 8, 0, 0, 0});
            }
        };
        DerivativeEngine fakeDerivative = new DerivativeEngine() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String description() {
                return "test derivative";
            }

            @Override
            public void optimizeOme(
                    Path renderedOme, Path pyramidalOme, int width, int height)
                    throws java.io.IOException {
                Files.copy(renderedOme, pyramidalOme);
            }

            @Override
            public DerivativeInfo generateDzi(
                    Path omeTiff, Path outputRoot, int width, int height) {
                throw new UnsupportedOperationException();
            }
        };

        try (var server = ForgeServer.start(
                repository, () -> List.of(source), managed, fakeEngine, fakeDerivative)) {
            client.send(
                    HttpRequest.newBuilder(server.launchUri()).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            var session = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve("/api/session")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var csrf = session.headers().firstValue("x-forge-csrf").orElseThrow();
            write(client, server, csrf, "/api/datasets/select", "POST");
            var dataset = repository.list().get(0);

            var inspected =
                    write(client, server, csrf, "/api/datasets/" + dataset.id() + "/inspect", "POST");
            assertEquals(200, inspected.statusCode());
            assertTrue(inspected.body().contains("\"name\":\"Tissue\""));
            for (var attempt = 0; attempt < 100; attempt++) {
                if (repository.find(dataset.id()).orElseThrow().status()
                        == org.pathlab.forge.library.DatasetStatus.READY_TO_CONVERT) {
                    break;
                }
                Thread.sleep(20);
            }
            assertEquals(
                    org.pathlab.forge.library.DatasetStatus.READY_TO_CONVERT,
                    repository.find(dataset.id()).orElseThrow().status());

            var configured = write(
                    client,
                    server,
                    csrf,
                    "/api/datasets/" + dataset.id()
                            + "/series?series=0&downsample=1.5&x=10&y=20&width=600&height=300",
                    "POST");
            assertEquals(200, configured.statusCode());
            var estimate = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve(
                                    "/api/datasets/" + dataset.id()
                                            + "/estimate?downsample=2&width=600&height=300"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, estimate.statusCode());
            assertTrue(estimate.body().contains("\"outputWidth\":300"));
            assertTrue(estimate.body().contains("\"outputHeight\":150"));
            assertTrue(estimate.body().contains("\"fileBytes\":"));
            assertTrue(estimate.body().contains("\"workspaceBytes\":"));
            var reinspected =
                    write(client, server, csrf, "/api/datasets/" + dataset.id() + "/inspect", "POST");
            assertEquals(200, reinspected.statusCode());
            var persistedConfiguration = repository.find(dataset.id()).orElseThrow();
            assertEquals(1.5, persistedConfiguration.downsample());
            assertEquals(10, persistedConfiguration.cropX());
            assertEquals(20, persistedConfiguration.cropY());
            assertEquals(600, persistedConfiguration.cropWidth());
            assertEquals(300, persistedConfiguration.cropHeight());
            var descriptor = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve(
                                    "/api/datasets/" + dataset.id() + "/preview/slide.dzi"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, descriptor.statusCode());
            assertTrue(descriptor.body().contains("Width=\"1000\" Height=\"500\""));
            assertEquals(
                    "1000x500",
                    descriptor.headers().firstValue("x-pathlab-preview-geometry").orElseThrow());
            var tile = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve(
                                    "/api/datasets/" + dataset.id()
                                            + "/preview/slide_files/10/0_0.jpg"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, tile.statusCode());
            assertEquals("image/jpeg", tile.headers().firstValue("content-type").orElseThrow());
            assertEquals(6, tile.body().length);
            var tileEtag = tile.headers().firstValue("etag").orElseThrow();
            var unchangedTile = client.send(
                    HttpRequest.newBuilder(server.baseUri().resolve(
                                    "/api/datasets/" + dataset.id()
                                            + "/preview/slide_files/10/0_0.jpg"))
                            .header("If-None-Match", tileEtag)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(304, unchangedTile.statusCode());
            assertEquals(1, directReads.get());
            assertEquals(
                    200,
                    write(
                                    client,
                                    server,
                                    csrf,
                                    "/api/datasets/" + dataset.id()
                                            + "/series?series=0&downsample=1&x=0&y=0&width=1000&height=500",
                                    "POST")
                            .statusCode());

            var started =
                    write(client, server, csrf, "/api/datasets/" + dataset.id() + "/convert", "POST");
            assertEquals(202, started.statusCode());
            for (var attempt = 0; attempt < 50; attempt++) {
                if (repository.find(dataset.id()).orElseThrow().status()
                        == org.pathlab.forge.library.DatasetStatus.CONVERSION_READY) {
                    break;
                }
                Thread.sleep(20);
            }
            var converted = repository.find(dataset.id()).orElseThrow();
            assertEquals(
                    org.pathlab.forge.library.DatasetStatus.CONVERSION_READY, converted.status());
            assertTrue(Files.isRegularFile(Path.of(converted.outputPath())));
            assertEquals(64, converted.sha256().length());
        }
    }

    private static void writeOmeStamp(ArtifactRevision revision) throws Exception {
        var ome = Path.of(revision.omePath());
        var values = new Properties();
        values.setProperty("omeSha256", revision.omeSha256());
        values.setProperty("packageSha256", "");
        values.setProperty("omeProfile", revision.omeProfile());
        values.setProperty("omeJpegQuality", Integer.toString(revision.omeJpegQuality()));
        values.setProperty("omeSize", Long.toString(Files.size(ome)));
        values.setProperty("omeModified", Long.toString(Files.getLastModifiedTime(ome).toMillis()));
        try (var output = Files.newOutputStream(
                ome.getParent().resolve("artifact.integrity.properties"))) {
            values.store(output, "Test direct OME identity");
        }
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
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

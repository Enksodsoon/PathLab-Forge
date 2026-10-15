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

    @Test void offlineTeachingAssociationLoadsFrozenPixelsAndApprovesOnlyReviewedTasks() throws Exception {
        var mapper = org.pathlab.forge.study.StudyPackCanonicalJson.mapper();
        var datasetId = java.util.UUID.randomUUID().toString();
        var artifactId = java.util.UUID.randomUUID().toString();
        var managed = temp.resolve("managed-0");
        var directory = java.nio.file.Files.createDirectories(managed.resolve(datasetId).resolve("artifacts").resolve(artifactId));
        var derivative = java.nio.file.Files.createDirectories(directory.resolve("derivative"));
        var dzi = "<Image xmlns=\"http://schemas.microsoft.com/deepzoom/2008\" Format=\"jpg\" Overlap=\"1\" TileSize=\"512\"><Size Width=\"1\" Height=\"1\"/></Image>";
        java.nio.file.Files.writeString(derivative.resolve("slide.dzi"), dzi);
        var tile = java.nio.file.Files.createDirectories(derivative.resolve("slide_files/0")).resolve("0_0.jpg");
        var image = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(image, "jpg", tile.toFile());
        javax.imageio.ImageIO.write(image, "jpg", derivative.resolve("thumbnail.jpg").toFile());
        var packaged = org.pathlab.forge.packageformat.PreparedPackageBuilder.build(derivative, 1, 1,
                new org.pathlab.forge.packageformat.PackageMetadata(artifactId, "config", "source", 0,
                        0, 0, 1, 1, 1, 0, 0, "", "actual-staging-ome", "test"), directory.resolve("slide.plslide"));
        var artifact = new org.pathlab.forge.conversion.ArtifactRevision(artifactId, datasetId, "config", "source", 1,
                org.pathlab.forge.conversion.ArtifactRevisionStatus.READY, org.pathlab.forge.conversion.ArtifactRevisionFormat.PREPARED_DZI_V2,
                directory.resolve("missing.ome.tif").toString(), derivative.toString(), packaged.path().toString(), "", packaged.sha256(), 1, 1, "", 0, 0, "Synthetic", "");
        new org.pathlab.forge.conversion.ArtifactRevisionRepository(managed).save(artifact);
        var stamp = org.pathlab.forge.conversion.ArtifactIntegrityStamp.class.getDeclaredMethod("write", org.pathlab.forge.conversion.ArtifactRevision.class);
        stamp.setAccessible(true); stamp.invoke(null, artifact);
        var definition = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(getClass().getResourceAsStream("/study/viewer-v1.json"));
        var localId = "local:" + datasetId;
        for (var slide : definition.path("slides")) { ((com.fasterxml.jackson.databind.node.ObjectNode) slide).put("viewerSlideId", localId).put("sha256", ""); }
        for (var task : definition.path("tasks")) ((com.fasterxml.jackson.databind.node.ObjectNode) task).put("slideId", localId);
        try (var server = startEphemeral()) {
            var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            client.send(HttpRequest.newBuilder(server.launchUri()).GET().build(), HttpResponse.BodyHandlers.ofString());
            var csrf = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/session")).GET().build(), HttpResponse.BodyHandlers.ofString()).headers().firstValue("X-Forge-CSRF").orElseThrow();
            var draft = mapper.readTree(studyPost(server, client, csrf, "/api/study/drafts", mapper.createObjectNode().put("name", "Offline faculty").set("definition", definition)).body());
            var path = "/api/study/drafts/" + draft.path("id").asText();
            var associated = studyPost(server, client, csrf, path + "/associate", mapper.createObjectNode().put("revision", draft.path("revision").asLong()).put("referenceId", localId).put("datasetId", datasetId).put("artifactRevision", artifactId));
            assertEquals(200, associated.statusCode(), associated.body()); draft = mapper.readTree(associated.body());
            var preview = studyPost(server, client, csrf, path + "/preview", mapper.createObjectNode().put("revision", draft.path("revision").asLong()));
            assertEquals(200, preview.statusCode(), preview.body()); draft = mapper.readTree(preview.body());
            var previewPath = "/api/datasets/" + datasetId + "/artifacts/" + artifactId + "/teaching-preview/slide.dzi";
            java.nio.file.Files.writeString(derivative.resolve("slide.dzi"), "loose content changed");
            var pixels = client.send(HttpRequest.newBuilder(server.baseUri().resolve(previewPath)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, pixels.statusCode(), pixels.body()); assertEquals(dzi, pixels.body());
            for (var task : draft.path("definition").path("tasks")) {
                var review = mapper.createObjectNode().put("revision", draft.path("revision").asLong()).put("checksum", draft.path("previewChecksum").asText()).put("taskId", task.path("id").asText());
                var proof = mapper.createObjectNode().put("previewChecksum", draft.path("previewChecksum").asText()).put("slideId", localId).put("datasetId", datasetId).put("artifactRevision", artifactId).put("packageSha256", "a".repeat(64));
                review.set("pixels", proof);
                assertEquals(409, studyPost(server, client, csrf, path + "/review", review).statusCode());
                proof.put("packageSha256", packaged.sha256());
                var reviewed = studyPost(server, client, csrf, path + "/review", review);
                assertEquals(200, reviewed.statusCode(), reviewed.body()); draft = mapper.readTree(reviewed.body());
            }
            var approved = studyPost(server, client, csrf, path + "/approve", mapper.createObjectNode().put("revision", draft.path("revision").asLong()).put("checksum", draft.path("previewChecksum").asText()));
            assertEquals(200, approved.statusCode(), approved.body());
            var approvedChecksum = mapper.readTree(approved.body()).path("approvedChecksum").asText();
            var export = client.send(HttpRequest.newBuilder(server.baseUri().resolve(path + "/export?format=approved&checksum=" + approvedChecksum)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("pathlab.study-local-approved/1", mapper.readTree(export.body()).path("schema").asText());
            java.nio.file.Files.writeString(packaged.path().resolveSibling("slide.plslide.index"), "altered index");
            var changed = client.send(HttpRequest.newBuilder(server.baseUri().resolve(previewPath)).header("If-None-Match", pixels.headers().firstValue("ETag").orElseThrow()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, changed.statusCode(), "Integrity must be checked before returning 304");
        }
    }

    private static HttpResponse<String> studyPost(ForgeServer server, HttpClient client, String csrf, String path, com.fasterxml.jackson.databind.JsonNode body) throws Exception {
        return client.send(HttpRequest.newBuilder(server.baseUri().resolve(path)).header("Origin", server.baseUri().toString()).header("X-Forge-CSRF", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void studyRoutesPreserveOfflineDraftsAndRejectStaleWritesAfterRestart() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String id;
        try (var server = startEphemeral()) {
            var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            assertEquals(401, HttpClient.newHttpClient().send(HttpRequest.newBuilder(server.baseUri().resolve("/api/study/drafts")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            client.send(HttpRequest.newBuilder(server.launchUri()).GET().build(), HttpResponse.BodyHandlers.ofString());
            var csrf = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/session")).GET().build(), HttpResponse.BodyHandlers.ofString()).headers().firstValue("X-Forge-CSRF").orElseThrow();
            var created = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/study/drafts"))
                    .header("Origin", server.baseUri().toString()).header("X-Forge-CSRF", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Offline draft\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, created.statusCode(), created.body());
            var draft = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(created.body());
            id = draft.path("id").asText();
            draft.put("name", "Saved offline");
            var save = HttpRequest.newBuilder(server.baseUri().resolve("/api/study/drafts/" + id))
                    .header("Origin", server.baseUri().toString()).header("X-Forge-CSRF", csrf)
                    .PUT(HttpRequest.BodyPublishers.ofString(draft.toString())).build();
            assertEquals(200, client.send(save, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(409, client.send(save, HttpResponse.BodyHandlers.ofString()).statusCode());
            var export = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/study/drafts/" + id + "/export?format=json")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, export.statusCode());
            assertTrue(export.body().contains("Saved offline"));
            var destination = temp.resolve("native-study.json");
            var nativePayload = mapper.writeValueAsString(java.util.Map.of("kind", "study", "draftId", id,
                    "format", "json", "checksum", "", "destination", destination.toString()));
            var nativeExport = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/exports"))
                    .header("Origin", server.baseUri().toString()).header("X-Forge-CSRF", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString(nativePayload)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, nativeExport.statusCode(), nativeExport.body());
            var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!java.nio.file.Files.exists(destination) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(java.nio.file.Files.readString(destination).contains("Saved offline"));
            var staleCancel = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/exports/cancel?id=another-job"))
                    .header("Origin", server.baseUri().toString()).header("X-Forge-CSRF", csrf)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, staleCancel.statusCode());
            assertEquals(409, client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/study/drafts/" + id + "/export?format=approved&checksum=unapproved")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(405, client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/study/import")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var noTeaching = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/study/viewer/slides")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, noTeaching.statusCode()); assertTrue(noTeaching.body().contains("teaching authority"));
            var unapproved = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/study/drafts/" + id + "/publish"))
                    .header("Origin", server.baseUri().toString()).header("X-Forge-CSRF", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"revision\":2,\"checksum\":\"unapproved\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, unapproved.statusCode()); assertTrue(unapproved.body().contains("Draft changed since approval"));
        }
        try (var server = startEphemeral()) {
            var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            client.send(HttpRequest.newBuilder(server.launchUri()).GET().build(), HttpResponse.BodyHandlers.ofString());
            var loaded = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/study/drafts/" + id)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, loaded.statusCode());
            assertEquals("Saved offline", mapper.readTree(loaded.body()).path("name").asText());
            assertEquals(2, mapper.readTree(loaded.body()).path("revision").asLong());
        }
    }

    @Test
    void desktopPathsRequireMainProcessGrantAndAppCannotMintItsOwnSession() throws Exception {
        var previous = System.getProperty("pathlab.forge.desktop");
        System.setProperty("pathlab.forge.desktop", "true");
        try (var server = startEphemeral()) {
            var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            var client = HttpClient.newBuilder().cookieHandler(cookies).build();
            var app = client.send(HttpRequest.newBuilder(server.appUri())
                    .header("Sec-Fetch-Site", "same-origin").header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Dest", "document").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, app.statusCode());
            client.send(HttpRequest.newBuilder(server.launchUri()).GET().build(), HttpResponse.BodyHandlers.ofString());
            var session = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/session")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var source = temp.resolve("selected.tif");
            java.nio.file.Files.write(source, new byte[] {'I', 'I', 42, 0});
            var payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    java.util.Map.of("paths", java.util.List.of(source.toString()), "purpose", "import"));
            var denied = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/desktop/selections"))
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, denied.statusCode());
            var guessedImport = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/v2/desktop/imports"))
                    .header("Origin", server.baseUri().toString())
                    .header("X-Forge-CSRF", session.headers().firstValue("X-Forge-CSRF").orElseThrow())
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(422, guessedImport.statusCode());
            assertTrue(guessedImport.body().contains("native dialog"));
            var granted = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/desktop/selections"))
                    .header("X-Forge-Desktop-Secret", server.desktopSecret())
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, granted.statusCode());
            var deniedQuit = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/desktop/lifecycle"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, deniedQuit.statusCode());
            var approvedQuit = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/desktop/lifecycle"))
                    .header("X-Forge-Desktop-Secret", server.desktopSecret())
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, approvedQuit.statusCode());
            assertTrue(approvedQuit.body().contains("\"paused\":true"));
        } finally {
            if (previous == null) System.clearProperty("pathlab.forge.desktop");
            else System.setProperty("pathlab.forge.desktop", previous);
        }
    }

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
            assertEquals(409, accepted.statusCode());
            assertTrue(accepted.body().contains("Select datasetIds"), "Authorized empty payload reaches real batch validation");
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

    @Test void legacyMeasurementsPreserveUnknownScopeAndEscapeWhitespaceFormulas() throws Exception {
        var id = java.util.UUID.randomUUID().toString();
        var source = java.nio.file.Files.write(temp.resolve("uninspected.tif"),new byte[] {1,2,3});
        var repository = new PropertiesDatasetRepository(temp.resolve("library-0.properties"));
        repository.save(new org.pathlab.forge.library.LocalDataset(id,"Legacy",source.toString(),3,
            org.pathlab.forge.library.DatasetFormat.OME_TIFF,org.pathlab.forge.library.DatasetStatus.READY,"","",""));
        var annotations = new org.pathlab.forge.annotation.AnnotationRepository(temp.resolve("managed-0"));
        var mark = annotations.create(id,"rectangle","1,2;11,22","Legacy","#ff0000");
        annotations.updateMetadata(id,mark.id(),"","  =SUM(1)",mark.revision());
        try (var server = startEphemeral()) {
            var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
            client.send(HttpRequest.newBuilder(server.launchUri()).GET().build(),HttpResponse.BodyHandlers.ofString());
            var response = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/datasets/"+id+"/measurements.csv")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode(),response.body());
            assertTrue(response.body().contains("geometry")); assertTrue(response.body().contains("1,2;11,22"));
            assertTrue(response.body().contains("'=SUM(1)"));
            var csv = ForgeServer.class.getDeclaredMethod("csv",String.class); csv.setAccessible(true);
            assertEquals("\"'  =SUM(1)\"",csv.invoke(null,"  =SUM(1)")); org.junit.jupiter.api.Assertions.assertFalse(response.body().contains("Um"));
            assertTrue(response.body().contains("-1,-1,-1"));
            var library = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/datasets")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertTrue(library.body().contains("\"unscopedAnnotationCount\":1"));
        }
    }

    @Test void batchHttpJourneyRetainsFailureSnapshotExportsAndRestarts() throws Exception {
        var repository = new PropertiesDatasetRepository(temp.resolve("library-0.properties"));
        var datasetId = java.util.UUID.randomUUID().toString();
        var source = temp.resolve("incomplete.tif"); java.nio.file.Files.write(source, new byte[] {1,2,3});
        repository.save(new org.pathlab.forge.library.LocalDataset(datasetId, "Uninspected source", source.toString(), 3,
                org.pathlab.forge.library.DatasetFormat.OME_TIFF, org.pathlab.forge.library.DatasetStatus.READY, "", "", ""));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(); String id;
        try (var server = startEphemeral()) {
            var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            assertEquals(401, client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/batches")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            client.send(HttpRequest.newBuilder(server.launchUri()).GET().build(), HttpResponse.BodyHandlers.discarding());
            var csrf = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/session")).GET().build(), HttpResponse.BodyHandlers.discarding()).headers().firstValue("X-Forge-CSRF").orElseThrow();
            var created = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/batches"))
                    .header("Origin", server.baseUri().toString()).header("X-Forge-CSRF", csrf)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"datasetIds\":[\"" + datasetId + "\"]}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, created.statusCode(), created.body()); id = mapper.readTree(created.body()).path("id").asText();
            var report = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/batches/" + id + "/report")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, report.statusCode(), report.body());
            for (int attempt = 0; attempt < 250 && !mapper.readTree(report.body()).path("slides").get(0).path("item").path("state").asText().equals("FAILED"); attempt++) {
                Thread.sleep(20);
                report = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/batches/" + id + "/report")).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, report.statusCode(), report.body());
            }
            assertEquals("FAILED", mapper.readTree(report.body()).path("slides").get(0).path("item").path("state").asText());
            assertTrue(report.body().contains("Uninspected source"));
            var csv = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/batches/" + id + "/export?format=csv")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, csv.statusCode()); assertTrue(csv.body().contains("Uninspected source"));
            var destination = temp.resolve("native-batch.csv");
            var payload = mapper.writeValueAsString(java.util.Map.of("kind", "batch", "batchId", id, "format", "csv", "destination", destination.toString()));
            var saved = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/exports"))
                    .header("Origin", server.baseUri().toString()).header("X-Forge-CSRF", csrf).POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, saved.statusCode(), saved.body());
            for (var attempt = 0; attempt < 100 && !java.nio.file.Files.exists(destination); attempt++) Thread.sleep(10);
            assertEquals(csv.body(), java.nio.file.Files.readString(destination));
        }
        try (var server = startEphemeral()) {
            var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            client.send(HttpRequest.newBuilder(server.launchUri()).GET().build(), HttpResponse.BodyHandlers.discarding());
            var report = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/batches/" + id + "/report")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, report.statusCode()); assertTrue(report.body().contains("Uninspected source"));
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

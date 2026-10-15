package org.pathlab.forge.server;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.analysis.GeometryMeasurements;
import org.pathlab.forge.analysis.RoiMask;
import org.pathlab.forge.annotation.AnnotationRepository;
import org.pathlab.forge.conversion.ConversionEngine;
import org.pathlab.forge.conversion.SeriesInfo;
import org.pathlab.forge.library.*;
import org.pathlab.forge.reader.*;
import org.pathlab.forge.study.StudyPackCanonicalJson;

final class BrushHttpJourneyTest {
    @TempDir Path temp;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = StudyPackCanonicalJson.mapper();

    @Test void composesActualHoleAndRejectsStaleViewBoundsAndCsrfWithoutChangingParent() throws Exception {
        var repository = new PropertiesDatasetRepository(temp.resolve("library.properties"));
        var source = temp.resolve("synthetic.tif"); Files.write(source, new byte[]{1, 2, 3});
        var identity = DatasetSourceInventory.singleFile(source);
        var view = view(1);
        // Persisted overview dimensions deliberately differ from the selected native series.
        var dataset = new LocalDataset(java.util.UUID.randomUUID().toString(), "Synthetic", source.toString(), 3,
                DatasetFormat.OME_TIFF, DatasetStatus.READY, "", "", "")
                .withSourceIdentity(DatasetStatus.READY, "", identity.fingerprint(), identity.serialized())
                .withExportConfiguration(DatasetStatus.READY, "", 1, 100, 100, 1, 0, 0, 0, 100, 100)
                .withViewDefinition(mapper.writeValueAsString(view), view.revision());
        repository.save(dataset);
        var managed = temp.resolve("managed");
        try (var server = ForgeServer.start(repository, List::of, managed, new SyntheticEngine())) {
            var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
            client.send(HttpRequest.newBuilder(server.launchUri()).GET().build(), HttpResponse.BodyHandlers.discarding());
            var csrf = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/api/session")).GET().build(), HttpResponse.BodyHandlers.discarding())
                    .headers().firstValue("X-Forge-CSRF").orElseThrow();
            var annotationPath = "/api/datasets/" + dataset.id() + "/annotations";
            var created = post(server, client, csrf, annotationPath + "?type=rectangle&geometry=" + encode("0,0;10,10")
                    + "&configurationRevision=" + dataset.configurationRevision(), "{}");
            assertEquals(201, created.statusCode(), created.body());
            var parent = mapper.readTree(created.body());
            assertEquals(1, parent.path("series").asInt()); assertEquals(1, parent.path("z").asInt()); assertEquals(1, parent.path("t").asInt());
            var brushPath = annotationPath + "/" + parent.path("id").asText() + "/brush";
            var body = brush(dataset, 1, "2,2;8,2;8,8;2,8");
            var denied = post(server, client, "", brushPath, body);
            assertEquals(403, denied.statusCode(), denied.body());
            assertEquals(1, saved(managed, dataset).revision());
            var applied = post(server, client, csrf, brushPath, body);
            assertEquals(200, applied.statusCode(), applied.body());
            var mask = mapper.readTree(applied.body());
            assertEquals(parent.path("id"), mask.path("id")); assertEquals("roi_mask", mask.path("type").asText()); assertEquals(2, mask.path("revision").asLong());
            var exact = new RoiMask("roi_mask", mask.path("geometry").asText());
            assertTrue(exact.contains(1, 1)); assertFalse(exact.contains(5, 5));
            assertEquals(64, GeometryMeasurements.measure("roi_mask", mask.path("geometry").asText()).get("areaPx2"), 1e-9);
            var frozen = saved(managed, dataset);
            assertEquals(mask.path("geometry").asText(), frozen.geometry());
            assertEquals(409, post(server, client, csrf, brushPath, body).statusCode());
            assertEquals(422, post(server, client, csrf, brushPath, brush(dataset, 2, "19,0;21,0;21,2;19,2")).statusCode());
            var wrongConfiguration = mapper.readTree(brush(dataset, 2, "0,0;1,0;1,1")).deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) wrongConfiguration).put("configurationRevision", "old-view");
            assertEquals(409, post(server, client, csrf, brushPath, mapper.writeValueAsString(wrongConfiguration)).statusCode());
            var changedView = view(0);
            var changed = dataset.withViewDefinition(mapper.writeValueAsString(changedView), changedView.revision()); repository.save(changed);
            assertEquals(409, post(server, client, csrf, brushPath, brush(changed, 2, "0,0;1,0;1,1")).statusCode());
            assertEquals(frozen, saved(managed, dataset));
            repository.save(dataset);
            var loaded = client.send(HttpRequest.newBuilder(server.baseUri().resolve(annotationPath)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, loaded.statusCode(), loaded.body());
            assertEquals(mask.path("geometry"), mapper.readTree(loaded.body()).path("annotations").get(0).path("geometry"));
            assertEquals(1, mapper.readTree(loaded.body()).path("annotations").size());
            // The earlier successful request has populated the series cache.
            Files.write(source, new byte[]{9, 8, 7, 6});
            var changedSource = post(server, client, csrf, brushPath, brush(dataset, 2, "0,0;1,0;1,1"));
            assertEquals(409, changedSource.statusCode(), changedSource.body());
            assertEquals(frozen, saved(managed, dataset));
        }
    }

    private org.pathlab.forge.annotation.AnnotationRecord saved(Path managed, LocalDataset dataset) throws Exception {
        return new AnnotationRepository(managed).list(dataset.id()).get(0);
    }
    private ViewDefinition view(int plane) {
        return new ViewDefinition(1, AxisSelection.slice(plane), AxisSelection.slice(plane),
                List.of(new ChannelRender(0, true, "#ffffff", 0, 255)), RenderProfile.PATHOLOGY_STANDARD);
    }
    private String brush(LocalDataset dataset, long revision, String geometry) throws Exception {
        return mapper.writeValueAsString(java.util.Map.of("operation", "brush_subtract", "geometry", geometry,
                "revision", revision, "configurationRevision", dataset.configurationRevision()));
    }
    private HttpResponse<String> post(ForgeServer server, HttpClient client, String csrf, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(server.baseUri().resolve(path)).header("Origin", server.baseUri().toString())
                .header("Content-Type", "application/json");
        if (!csrf.isEmpty()) request.header("X-Forge-CSRF", csrf);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static final class SyntheticEngine implements ConversionEngine {
        public boolean available() { return true; }
        public String runtimeDescription() { return "deterministic HTTP test reader"; }
        public List<SeriesInfo> inspect(Path source) {
            return List.of(new SeriesInfo(0, "Overview", 100, 100, 3, 1, 1, "uint8", 1, 1, "um"),
                    new SeriesInfo(1, "Native", 20, 20, 3, 2, 2, "uint8", 2, 3, "um"));
        }
        public void convert(Path source, int series, Path destination) { throw new UnsupportedOperationException("No conversion in HTTP geometry test"); }
    }
}

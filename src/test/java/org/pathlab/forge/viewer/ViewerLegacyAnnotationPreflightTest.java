package org.pathlab.forge.viewer;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.pathlab.forge.annotation.AnnotationRecord;
import org.pathlab.forge.conversion.ArtifactRevision;
import org.pathlab.forge.conversion.ArtifactRevisionFormat;
import org.pathlab.forge.conversion.ArtifactRevisionStatus;

final class ViewerLegacyAnnotationPreflightTest {
    @Test void unsupportedMaskInSecondBatchDoesNotLeaveFirstBatchOnViewer() throws Exception {
        var posts = new AtomicInteger();
        var remoteCount = new AtomicInteger();
        var viewer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        viewer.createContext("/", exchange -> {
            String response;
            if (exchange.getRequestMethod().equals("POST")) {
                exchange.getRequestBody().readAllBytes();
                posts.incrementAndGet(); remoteCount.addAndGet(50);
                response = "{\"version\":1}";
            } else response = "{\"total\":" + remoteCount.get() + ",\"visible\":true}";
            var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        viewer.start();
        var origin = "http://127.0.0.1:" + viewer.getAddress().getPort();
        var credentials = new CredentialStore() {
            public Optional<String> read() { return Optional.of(origin + "\ntest-token"); }
            public void write(String value) {} public void delete() {}
        };
        try (var service = new ViewerPairingService(credentials)) {
            var revision = new ArtifactRevision(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    "configuration", "source", 1, ArtifactRevisionStatus.APPROVED,
                    ArtifactRevisionFormat.PREPARED_DZI_V2, "unused.ome.tif", "unused-derivative", "unused.plslide",
                    "a".repeat(64), "b".repeat(64), 100, 100, "", 0, 1, "Fixture", "");
            // Seed an already uploaded artifact; this test exercises the retained public synchronization API.
            var field = ViewerPairingService.class.getDeclaredField("uploadStatus");
            field.setAccessible(true);
            field.set(service, new ViewerUploadStatus("IMAGE_READY", revision.id(), 1, 1,
                    "slide", revision.packageSha256(), "PREPARED_V2", "Uploaded"));
            var annotations = new ArrayList<AnnotationRecord>();
            for (var i = 0; i < 50; i++) annotations.add(new AnnotationRecord(UUID.randomUUID().toString(),
                    "rectangle", "0,0;10,10", "ROI", "#ffaa22", 1));
            annotations.add(new AnnotationRecord(UUID.randomUUID().toString(), "roi_mask",
                    "mask/1|0,0;10,0;10,10;0,10|2,2;8,2;8,8;2,8", "Hole", "#ffaa22", 1));
            for (var attempt = 0; attempt < 2; attempt++) {
                var rejected = assertThrows(IllegalArgumentException.class, () ->
                        service.synchronizeAnnotations(revision, annotations, 0, 0, 100, 100, 1));
                assertTrue(rejected.getMessage().contains("private result delivery"));
                assertEquals(0, posts.get(), "Unsupported later geometry must be rejected before any remote mutation");
                assertEquals(0, remoteCount.get());
            }
        } finally { viewer.stop(0); }
    }
}

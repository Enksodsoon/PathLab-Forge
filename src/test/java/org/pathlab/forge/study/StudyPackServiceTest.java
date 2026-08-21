package org.pathlab.forge.study;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pathlab.forge.viewer.ViewerAuthorizedClient;
import org.pathlab.forge.viewer.ViewerHttpResponse;

final class StudyPackServiceTest {
    @TempDir Path temporary;

    @Test
    void requiresExactPreviewAndKeepsPackVersionsImmutable() throws Exception {
        var service = new StudyPackService(temporary.resolve("packs"), new FakeViewer());
        var preview = service.preview(core(1, "Original"));
        var approved = attest(preview.canonicalCore(), preview.checksum());
        var stored = service.save(approved);
        assertEquals(preview.checksum(), stored.checksum());
        assertEquals(1, service.list().size());

        var changedPreview = service.preview(core(1, "Changed"));
        var changed = attest(changedPreview.canonicalCore(), changedPreview.checksum());
        assertThrows(IllegalArgumentException.class, () -> service.save(changed));

        var tampered = StudyPackCanonicalJson.mapper().readTree(approved);
        ((ObjectNode) tampered).put("title", "Changed after review");
        assertThrows(IllegalArgumentException.class, () -> service.save(tampered.toString()));
    }

    @Test
    void rechecksViewerCapabilitiesImmediatelyBeforePublishing() throws Exception {
        var viewer = new FakeViewer();
        var service = new StudyPackService(temporary.resolve("packs"), viewer);
        var preview = service.preview(core(1, "Original"));
        var stored = service.save(attest(preview.canonicalCore(), preview.checksum()));

        assertEquals("{\"id\":\"pack-1\"}", service.publish(stored.checksum()));
        assertEquals(List.of(
                "GET /api/v1/desktop/capabilities",
                "POST /api/v1/desktop/study-packs"), viewer.calls);
    }

    private static String attest(String canonicalCore, String checksum) throws Exception {
        var root = (ObjectNode) StudyPackCanonicalJson.mapper().readTree(canonicalCore);
        root.put("checksum", checksum);
        var preview = root.putObject("facultyPreview");
        preview.put("packChecksum", checksum);
        preview.put("previewVersion", StudyPackService.PREVIEW_VERSION);
        preview.put("reviewedAt", "2026-08-21T00:00:00Z");
        return StudyPackCanonicalJson.canonicalize(root);
    }

    private static String core(int version, String title) {
        return """
                {
                  "schema":"pathlab.study-pack/1",
                  "packKey":"histology-basics",
                  "version":%d,
                  "title":"%s",
                  "author":"Faculty",
                  "license":"CC-BY-4.0",
                  "provenance":"Faculty-authored deidentified teaching material",
                  "revision":"2026-08-21",
                  "languages":["en","th"],
                  "slides":[{"viewerSlideId":"slide-1","sha256":"%s","displayName":"Slide"}],
                  "tasks":[{
                    "id":"task-1","type":"multiple-choice","slideId":"slide-1",
                    "prompt":"Select the approved answer","options":["A","B"],"answerKey":"A",
                    "hints":["Use the source"],"explanation":"A is approved",
                    "sources":[{"title":"Faculty source","url":"https://example.edu/source"}]
                  }]
                }
                """.formatted(version, title, "a".repeat(64));
    }

    private static final class FakeViewer implements ViewerAuthorizedClient {
        private final List<String> calls = new ArrayList<>();

        @Override
        public ViewerHttpResponse request(
                String method, String path, Map<String, String> headers, byte[] body) {
            calls.add(method + " " + path);
            var response = path.endsWith("capabilities")
                    ? "{\"studyPackSchemas\":[\"pathlab.study-pack/1\"],"
                            + "\"studyPackMaxBytes\":2097152,\"studyPackMaxTasks\":500}"
                    : "{\"id\":\"pack-1\"}";
            return new ViewerHttpResponse(
                    path.endsWith("capabilities") ? 200 : 201,
                    Map.of(), new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));
        }
    }
}

package org.pathlab.forge.adapt;

import java.util.Objects;
import org.pathlab.forge.pivot.PivotManifest;
import org.pathlab.forge.pivot.PivotTask;

/** Builds auditable Study Pack JSON without ever embedding slide or query-image pixels. */
public final class StudyPackAuthoringService {
    public String fromApprovedPivot(
            PivotManifest manifest,
            String packKey,
            int version,
            String title,
            String courseId,
            String viewerSlideId,
            String author,
            String license,
            String revision,
            boolean facultyApproved) {
        var approvedManifest = Objects.requireNonNull(manifest, "manifest");
        if (!"pathlab-pivot/v1".equals(approvedManifest.schema())) {
            throw new IllegalArgumentException("PIVOT manifest schema is not approved");
        }
        if (!facultyApproved) {
            throw new IllegalArgumentException("Faculty approval is required for PIVOT export");
        }
        requireText(packKey, "packKey");
        requireText(title, "title");
        requireText(courseId, "courseId");
        requireText(viewerSlideId, "viewerSlideId");
        requireText(author, "author");
        requireText(license, "license");
        requireText(revision, "revision");
        if (version < 1) {
            throw new IllegalArgumentException("Study Pack version is invalid");
        }

        var tasks = approvedManifest.tasks().stream()
                .map(task -> spatialTask(task, viewerSlideId, approvedManifest, author, license, revision))
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"schema\":\"pathlab.study-pack/1\",\"packKey\":" + json(packKey)
                + ",\"version\":" + version + ",\"title\":" + json(title)
                + ",\"courseId\":" + json(courseId)
                + ",\"objectives\":[\"Coordinate retrieval\"],\"slides\":[{\"viewerSlideId\":"
                + json(viewerSlideId) + "}],\"tasks\":[" + tasks + "]}";
    }

    private static String spatialTask(
            PivotTask task,
            String viewerSlideId,
            PivotManifest manifest,
            String author,
            String license,
            String revision) {
        return "{\"type\":\"spatial\",\"id\":" + json(task.id())
                + ",\"slideId\":" + json(viewerSlideId)
                + ",\"prompt\":\"Locate the approved source region\""
                + ",\"targetX\":" + task.targetX()
                + ",\"targetY\":" + task.targetY()
                + ",\"targetWidth\":" + task.targetWidth()
                + ",\"targetHeight\":" + task.targetHeight()
                + ",\"tolerance\":0.08,\"source\":"
                + json("PIVOT manifest " + manifest.id())
                + ",\"author\":" + json(author)
                + ",\"license\":" + json(license)
                + ",\"revision\":" + json(revision) + "}";
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}

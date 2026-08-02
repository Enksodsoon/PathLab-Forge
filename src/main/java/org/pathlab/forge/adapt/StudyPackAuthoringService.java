package org.pathlab.forge.adapt;

import java.util.Objects;
import org.pathlab.forge.pivot.PivotManifest;
import org.pathlab.forge.pivot.PivotTask;

/** Builds auditable Study Pack JSON without ever embedding slide or query-image pixels. */
public final class StudyPackAuthoringService {
    public String fromApprovedPivot(
            PivotManifest manifest,
            PivotApproval approval,
            ViewerSlideAssociation association,
            String packKey,
            int version,
            String title,
            String courseId,
            String author,
            String license,
            String revision) {
        var approvedManifest = Objects.requireNonNull(manifest, "manifest");
        if (!"pathlab-pivot/v1".equals(approvedManifest.schema())) {
            throw new IllegalArgumentException("PIVOT manifest schema is not approved");
        }
        if (approval == null || !approval.datasetId().equals(approvedManifest.datasetId())
                || !approval.manifestId().equals(approvedManifest.id())
                || !approval.inputRevision().equals(approvedManifest.inputRevision())) {
            throw new IllegalArgumentException("Durable PIVOT approval does not match the manifest");
        }
        if (association == null || !association.datasetId().equals(approvedManifest.datasetId())) {
            throw new IllegalArgumentException("Viewer slide association does not match the PIVOT dataset");
        }
        requireText(packKey, "packKey");
        requireText(title, "title");
        requireText(courseId, "courseId");
        requireText(author, "author");
        requireText(license, "license");
        requireText(revision, "revision");
        if (version < 1) {
            throw new IllegalArgumentException("Study Pack version is invalid");
        }

        var tasks = approvedManifest.tasks().stream()
                .map(task -> spatialTask(task, association.viewerSlideId(), approvedManifest, author, license, revision))
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"schema\":\"pathlab.study-pack/1\",\"packKey\":" + json(packKey)
                + ",\"version\":" + version + ",\"title\":" + json(title)
                + ",\"courseId\":" + json(courseId)
                + ",\"objectives\":[\"Coordinate retrieval\"],\"slides\":[{\"viewerSlideId\":"
                + json(association.viewerSlideId())
                + ",\"sha256\":" + json(association.sha256())
                + ",\"displayName\":" + json(association.displayName())
                + ",\"license\":" + json(association.license())
                + "}],\"tasks\":[" + tasks + "]}";
    }

    private static String spatialTask(
            PivotTask task,
            String viewerSlideId,
            PivotManifest manifest,
            String author,
            String license,
            String revision) {
        var x = normalized(task.targetX(), manifest.sourceWidth());
        var y = normalized(task.targetY(), manifest.sourceHeight());
        var width = normalized(task.targetWidth(), manifest.sourceWidth());
        var height = normalized(task.targetHeight(), manifest.sourceHeight());
        if (width <= 0 || height <= 0 || x + width > 1.000000001 || y + height > 1.000000001) {
            throw new IllegalArgumentException("PIVOT target escapes source bounds");
        }
        return "{\"type\":\"spatial\",\"id\":" + json(task.id())
                + ",\"slideId\":" + json(viewerSlideId)
                + ",\"prompt\":\"Locate the approved source region\""
                + ",\"targetX\":" + x
                + ",\"targetY\":" + y
                + ",\"targetWidth\":" + width
                + ",\"targetHeight\":" + height
                + ",\"tolerance\":0.08,\"source\":"
                + json("PIVOT manifest " + manifest.id())
                + ",\"author\":" + json(author)
                + ",\"license\":" + json(license)
                + ",\"revision\":" + json(revision) + "}";
    }

    private static double normalized(double value, int extent) {
        var normalized = value / extent;
        if (!Double.isFinite(normalized) || normalized < 0 || normalized > 1) {
            throw new IllegalArgumentException("PIVOT coordinate escapes source bounds");
        }
        return normalized;
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

package org.pathlab.forge.study;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.pathlab.forge.conversion.ArtifactIntegrityStamp;
import org.pathlab.forge.conversion.ArtifactRevision;
import org.pathlab.forge.conversion.ArtifactRevisionFormat;
import org.pathlab.forge.conversion.ArtifactRevisionStatus;
import org.pathlab.forge.packageformat.PackageEntryIndex;

/** Local-only binding; provenance comes from the immutable prepared package, never the current view. */
public record TeachingSlideAssociation(String referenceId, String viewerSlideId, String datasetId,
        String artifactRevision, String packageSha256, String configurationRevision, String sourceFingerprint,
        int outputWidth, int outputHeight, ObjectNode provenance) {
    public TeachingSlideAssociation {
        if (referenceId == null || referenceId.isBlank() || referenceId.length() > 100
                || viewerSlideId == null || viewerSlideId.length() > 100
                || (!viewerSlideId.isBlank() && viewerSlideId.startsWith("local:"))
                || !datasetId.matches("[a-fA-F0-9-]{36}") || !artifactRevision.matches("[a-fA-F0-9-]{36}")
                || !packageSha256.matches("[a-f0-9]{64}") || configurationRevision.isBlank() || sourceFingerprint.isBlank()
                || outputWidth < 1 || outputHeight < 1 || provenance == null) {
            throw new IllegalArgumentException("Teaching slide association identity is invalid");
        }
        provenance = provenance.deepCopy();
    }

    public static TeachingSlideAssociation fromArtifact(String referenceId, String viewerSlideId, ArtifactRevision revision)
            throws IOException {
        if (revision.format() != ArtifactRevisionFormat.PREPARED_DZI_V2
                || !java.util.Set.of(ArtifactRevisionStatus.READY, ArtifactRevisionStatus.APPROVED).contains(revision.status())
                || !revision.packageSha256().matches("[a-f0-9]{64}") || !ArtifactIntegrityStamp.matches(revision)) {
            throw new IllegalStateException("Teaching association requires an intact prepared DZI v2 package");
        }
        var packagePath = Path.of(revision.packagePath());
        var index = PackageEntryIndex.read(packagePath.resolveSibling(packagePath.getFileName() + ".index"));
        var entry = index.require("manifest.json");
        if (entry.size() < 1 || entry.size() > 2 * 1024 * 1024) throw new IOException("Teaching manifest is outside the bound");
        var bytes = ByteBuffer.allocate((int) entry.size());
        try (var file = FileChannel.open(packagePath)) {
            if (entry.offset() > file.size() - entry.size()) throw new IOException("Teaching manifest range is invalid");
            file.position(entry.offset());
            while (bytes.hasRemaining()) if (file.read(bytes) < 0) throw new IOException("Teaching manifest is truncated");
        }
        var manifest = StudyPackCanonicalJson.parse(new String(bytes.array(), StandardCharsets.UTF_8));
        if (!"pathlab-prepared-slide/v2".equals(manifest.path("schema").asText())
                || !(manifest.path("provenance") instanceof ObjectNode provenance)
                || !revision.id().equals(provenance.path("artifactRevisionId").asText())
                || !revision.configurationRevision().equals(provenance.path("configurationRevision").asText())
                || !revision.sourceFingerprint().equals(provenance.path("sourceFingerprint").asText())
                || revision.outputWidth() != manifest.path("slide").path("width").asInt()
                || revision.outputHeight() != manifest.path("slide").path("height").asInt()) {
            throw new IOException("Teaching package manifest does not match its immutable artifact revision");
        }
        var crop = provenance.path("crop");
        for (var field : java.util.List.of("x", "y", "width", "height")) {
            var number = crop.path(field);
            if (!number.isNumber() || !Double.isFinite(number.asDouble())
                    || (java.util.Set.of("width", "height").contains(field) ? number.asDouble() <= 0 : number.asDouble() < 0)) {
                throw new IOException("Teaching crop metadata is invalid");
            }
        }
        if (!provenance.path("series").isIntegralNumber() || provenance.path("series").asInt() < 0
                || !provenance.path("downsample").isNumber() || !Double.isFinite(provenance.path("downsample").asDouble())
                || provenance.path("downsample").asDouble() <= 0) throw new IOException("Teaching source transform is invalid");
        var view = provenance.path("viewDefinition");
        provenance.put("viewRevision", view.isObject()
                ? StudyPackCanonicalJson.mapper().treeToValue(view, org.pathlab.forge.reader.ViewDefinition.class).revision()
                : revision.configurationRevision());
        return new TeachingSlideAssociation(referenceId, viewerSlideId, revision.datasetId(), revision.id(),
                revision.packageSha256(), revision.configurationRevision(), revision.sourceFingerprint(),
                revision.outputWidth(), revision.outputHeight(), provenance);
    }

    public void validate(ArtifactRevision revision) throws IOException {
        if (!equals(fromArtifact(referenceId, viewerSlideId, revision))) {
            throw new IllegalStateException("Local teaching association changed; select the exact prepared artifact again");
        }
    }
}

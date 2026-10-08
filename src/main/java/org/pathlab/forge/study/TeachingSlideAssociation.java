package org.pathlab.forge.study;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
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
        var manifest = StudyPackCanonicalJson.parse(new String(readPackageEntry(revision, "manifest.json", 2 * 1024 * 1024), StandardCharsets.UTF_8));
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

    /** Call before evaluating conditional HTTP responses; never serve loose derivative files. */
    public static byte[] readPreview(ArtifactRevision revision, String relative) throws IOException {
        if (relative == null || relative.length() > 160
                || !relative.matches("slide\\.dzi|slide_files/\\d{1,10}/\\d{1,10}_\\d{1,10}\\.jpg")) {
            throw new IllegalArgumentException("Invalid teaching preview entry");
        }
        return readPackageEntry(revision, relative, 32 * 1024 * 1024);
    }

    private static byte[] readPackageEntry(ArtifactRevision revision, String relative, int maximumBytes) throws IOException {
        var packagePath = Path.of(revision.packagePath()).toAbsolutePath().normalize();
        var root = packagePath.getParent();
        var indexPath = packagePath.resolveSibling(packagePath.getFileName() + ".index");
        if (root == null || root.getParent() == null || root.getParent().getParent() == null) throw new IOException("Teaching payload origin is invalid");
        var stampPath = root.resolve("artifact.integrity.properties");
        if (!root.equals(Path.of(revision.omePath()).toAbsolutePath().normalize().getParent())
                || !root.getFileName().toString().equals(revision.id())
                || !root.getParent().getFileName().toString().equals("artifacts")
                || !root.getParent().getParent().getFileName().toString().equals(revision.datasetId())) {
            throw new IOException("Teaching payload paths do not belong to this saved artifact");
        }
        for (var directory : java.util.List.of(root, root.getParent(), root.getParent().getParent())) {
            if (Files.isSymbolicLink(directory)) throw new IOException("Teaching payload directory cannot be a symbolic link");
        }
        for (var file : java.util.List.of(packagePath, indexPath, stampPath)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Teaching payload component is missing or linked");
        }
        if (Files.size(stampPath) > 64 * 1024 || Files.size(indexPath) > 128L * 1024 * 1024) throw new IOException("Teaching integrity metadata exceeds its bound");
        if (revision.format() != ArtifactRevisionFormat.PREPARED_DZI_V2
                || !java.util.Set.of(ArtifactRevisionStatus.READY, ArtifactRevisionStatus.APPROVED).contains(revision.status())
                || !revision.packageSha256().matches("[a-f0-9]{64}") || !ArtifactIntegrityStamp.matches(revision)) {
            throw new IllegalStateException("Teaching association requires an intact prepared DZI v2 package");
        }
        PackageEntryIndex.Entry entry = null;
        // ponytail: linear streaming index lookup; cache a bounded verified seek map if measured tile latency requires it.
        try (var reader = new java.io.BufferedReader(java.nio.channels.Channels.newReader(
                FileChannel.open(indexPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8))) {
            var line = new StringBuilder(); int character;
            while ((character = reader.read()) != -1) {
                if (character != '\n') {
                    if (line.length() >= 512) throw new IOException("Teaching index line exceeds its bound");
                    line.append((char) character); continue;
                }
                entry = previewIndexEntry(line.toString(), relative);
                line.setLength(0);
                if (entry != null) break;
            }
            if (entry == null && !line.isEmpty()) entry = previewIndexEntry(line.toString(), relative);
        }
        if (entry == null) throw new IOException("Teaching preview entry was not indexed");
        if (entry.size() < 1 || entry.size() > maximumBytes) throw new IOException("Teaching preview entry exceeds its bound");
        var bytes = ByteBuffer.allocate((int) entry.size());
        try (var file = FileChannel.open(packagePath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (entry.offset() > file.size() - entry.size()) throw new IOException("Teaching preview entry range is invalid");
            file.position(entry.offset());
            while (bytes.hasRemaining()) if (file.read(bytes) < 0) throw new IOException("Teaching preview entry is truncated");
        }
        if (!ArtifactIntegrityStamp.matches(revision)) throw new IOException("Teaching package changed during preview read");
        return bytes.array();
    }

    private static PackageEntryIndex.Entry previewIndexEntry(String line, String relative) throws IOException {
        if (line.isBlank()) return null;
        var fields = line.stripTrailing().split("\\|", -1);
        if (fields.length != 3 || fields[0].startsWith("/") || fields[0].contains("\\") || fields[0].contains("../")) throw new IOException("Teaching index contains an invalid entry");
        try {
            var entry = new PackageEntryIndex.Entry(Long.parseLong(fields[1]), Long.parseLong(fields[2]));
            return fields[0].equals(relative) ? entry : null;
        } catch (IllegalArgumentException error) { throw new IOException("Teaching index contains an invalid range", error); }
    }

    public void validate(ArtifactRevision revision) throws IOException {
        if (!equals(fromArtifact(referenceId, viewerSlideId, revision))) {
            throw new IllegalStateException("Local teaching association changed; select the exact prepared artifact again");
        }
    }
}

package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Immutable, checksum-bound description of one autonomous qualification campaign. */
public record QualificationCampaignManifest(
        String campaignId,
        Path path,
        String sha256,
        int maxRemediationAttempts,
        List<Track> tracks) {
    public static final String SCHEMA = "pathlab.qualification-campaign/1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CAPABILITIES = Set.of(
            "he-evidence", "cell-morphology", "ihc-descriptive",
            "special-stain-descriptive", "cytology-descriptive",
            "grounded-tutor", "atlas-distillation");
    private static final Set<String> SCOPES = Set.of("deployment", "research-restricted", "local-benchmark");

    public static QualificationCampaignManifest load(Path input) throws IOException {
        var path = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || Files.size(path) > 2L * 1024 * 1024) {
            throw new IllegalArgumentException("Qualification campaign manifest is unavailable or too large");
        }
        var root = JSON.readTree(path.toFile());
        exact(root, Set.of("schema", "campaignId", "createdAt", "researchOnly", "notDiagnostic",
                "maxRemediationAttempts", "quota", "tracks"));
        require(SCHEMA.equals(text(root, "schema")), "Qualification campaign schema is unsupported");
        var campaignId = identifier(text(root, "campaignId"));
        Instant.parse(text(root, "createdAt"));
        require(root.path("researchOnly").asBoolean(false), "Qualification campaign must be research-only");
        require(root.path("notDiagnostic").asBoolean(false), "Qualification campaign must be non-diagnostic");
        var remediation = root.path("maxRemediationAttempts");
        require(remediation.isInt() && remediation.intValue() == 1,
                "Qualification campaign requires exactly one bounded remediation attempt");
        validateQuota(root.path("quota"));
        var items = root.path("tracks");
        require(items.isArray() && !items.isEmpty() && items.size() <= 64,
                "Qualification campaign tracks are invalid");
        var tracks = new ArrayList<Track>();
        var ids = new HashSet<String>();
        for (var item : items) {
            exact(item, Set.of("id", "candidateId", "capability", "scope", "requestPath",
                    "remediationRequestPath", "expectedAttestationPath", "protocolSha256",
                    "dependsOn", "required"));
            var id = identifier(text(item, "id"));
            require(ids.add(id), "Qualification track id is duplicated");
            var candidateId = identifier(text(item, "candidateId"));
            var capability = text(item, "capability");
            require(CAPABILITIES.contains(capability), "Qualification capability is unsupported");
            var scope = text(item, "scope");
            require(SCOPES.contains(scope), "Qualification scope is unsupported");
            var requestPath = immutablePath(path, text(item, "requestPath"));
            Path remediationPath = null;
            if (!item.path("remediationRequestPath").isNull()) {
                remediationPath = immutablePath(path, text(item, "remediationRequestPath"));
            }
            Path attestationPath = null;
            if (!item.path("expectedAttestationPath").isNull()) {
                attestationPath = boundedPath(path, text(item, "expectedAttestationPath"));
            }
            var protocolSha = text(item, "protocolSha256");
            require(protocolSha.matches("[a-f0-9]{64}"), "Qualification protocol checksum is invalid");
            var dependencies = new ArrayList<String>();
            var dependencyNode = item.path("dependsOn");
            require(dependencyNode.isArray() && dependencyNode.size() <= 16,
                    "Qualification dependencies are invalid");
            dependencyNode.forEach(value -> dependencies.add(identifier(value.asText())));
            require(item.path("required").isBoolean(), "Qualification required flag is invalid");
            tracks.add(new Track(id, candidateId, capability, scope, requestPath, remediationPath,
                    attestationPath, protocolSha, List.copyOf(dependencies), item.path("required").booleanValue()));
        }
        for (var track : tracks) {
            require(!track.dependsOn().contains(track.id()) && ids.containsAll(track.dependsOn()),
                    "Qualification dependency graph is invalid");
        }
        rejectCycles(tracks);
        return new QualificationCampaignManifest(campaignId, path, sha256(path), 1, List.copyOf(tracks));
    }

    private static void validateQuota(JsonNode quota) {
        exact(quota, Set.of("sourceBytes", "derivedBytes", "modelBytes", "evidenceBytes", "reserveBytes"));
        require(quota.path("sourceBytes").asLong(-1) == 45L * 1024 * 1024 * 1024,
                "Qualification source quota is not frozen");
        require(quota.path("derivedBytes").asLong(-1) == 25L * 1024 * 1024 * 1024,
                "Qualification derived quota is not frozen");
        require(quota.path("modelBytes").asLong(-1) == 10L * 1024 * 1024 * 1024,
                "Qualification model quota is not frozen");
        require(quota.path("evidenceBytes").asLong(-1) == 10L * 1024 * 1024 * 1024,
                "Qualification evidence quota is not frozen");
        require(quota.path("reserveBytes").asLong(-1) == 10L * 1024 * 1024 * 1024,
                "Qualification reserve quota is not frozen");
    }

    private static void rejectCycles(List<Track> tracks) {
        var byId = new java.util.HashMap<String, Track>();
        tracks.forEach(track -> byId.put(track.id(), track));
        for (var track : tracks) visit(track.id(), byId, new HashSet<>(), new HashSet<>());
    }

    private static void visit(String id, java.util.Map<String, Track> tracks, Set<String> active, Set<String> done) {
        if (done.contains(id)) return;
        require(active.add(id), "Qualification dependency graph contains a cycle");
        for (var dependency : tracks.get(id).dependsOn()) visit(dependency, tracks, active, done);
        active.remove(id);
        done.add(id);
    }

    private static Path immutablePath(Path manifest, String value) {
        var path = boundedPath(manifest, value);
        require(Files.isRegularFile(path), "Qualification input file is unavailable");
        return path;
    }

    private static Path boundedPath(Path manifest, String value) {
        var base = manifest.getParent().toAbsolutePath().normalize();
        var candidate = Path.of(value);
        var path = (candidate.isAbsolute() ? candidate : base.resolve(candidate)).toAbsolutePath().normalize();
        require(path.startsWith(base), "Qualification input escapes the campaign directory");
        return path;
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static void exact(JsonNode value, Set<String> expected) {
        require(value != null && value.isObject(), "Qualification campaign object is invalid");
        var fields = new HashSet<String>();
        value.fieldNames().forEachRemaining(fields::add);
        require(fields.equals(expected), "Qualification campaign fields are invalid");
    }

    private static String text(JsonNode value, String field) {
        var node = value.path(field);
        require(node.isTextual() && !node.textValue().isBlank(), "Qualification field is invalid: " + field);
        return node.textValue();
    }

    private static String identifier(String value) {
        require(value != null && value.matches("[A-Za-z0-9._-]{1,120}"), "Qualification identifier is invalid");
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record Track(
            String id, String candidateId, String capability, String scope,
            Path requestPath, Path remediationRequestPath, Path expectedAttestationPath,
            String protocolSha256, List<String> dependsOn, boolean required) { }
}

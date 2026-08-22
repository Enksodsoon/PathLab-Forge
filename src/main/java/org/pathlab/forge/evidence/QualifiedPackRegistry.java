package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Set;

/** Local fail-closed activation registry, separate from mutable candidate manifests. */
public final class QualifiedPackRegistry {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final Path path;

    public QualifiedPackRegistry(Path stateRoot) {
        path = stateRoot.toAbsolutePath().normalize().resolve("trust/qualified-packs.json");
    }

    public synchronized void register(EvidencePackManifest pack, String protocolSha256,
            String attestationSha256, Instant acceptedAt) throws IOException {
        require(EvidencePackManifest.SCHEMA_V2.equals(pack.schema()), "Only v2 packs use qualification registry");
        require(pack.activationEligible() && !"local-benchmark".equals(pack.scope()),
                "Pack scope is not activation eligible");
        require(protocolSha256.matches("[a-f0-9]{64}") && attestationSha256.matches("[a-f0-9]{64}"),
                "Qualification registry hashes are invalid");
        var root = load();
        var entries = (ArrayNode) root.path("entries");
        for (var item : entries) {
            if (pack.sha256().equals(item.path("packManifestSha256").asText())) {
                require(attestationSha256.equals(item.path("attestationSha256").asText())
                                && protocolSha256.equals(item.path("protocolSha256").asText()),
                        "Pack checksum is already registered with different qualification evidence");
                return;
            }
        }
        var entry = entries.addObject();
        entry.put("packId", pack.packId());
        entry.put("packVersion", pack.version());
        entry.put("packManifestSha256", pack.sha256());
        entry.put("protocolSha256", protocolSha256);
        entry.put("attestationSha256", attestationSha256);
        entry.put("scope", pack.scope());
        entry.put("acceptedAt", acceptedAt.toString());
        entry.set("artifactSha256s", JSON.valueToTree(pack.artifacts().stream()
                .map(EvidencePackManifest.Artifact::sha256).toList()));
        write(root);
    }

    public synchronized void requireQualified(EvidencePackManifest pack, String attestationSha256)
            throws IOException {
        require(attestationSha256 != null && attestationSha256.matches("[a-f0-9]{64}"),
                "Qualified pack attestation checksum is required");
        for (var item : load().path("entries")) {
            if (pack.packId().equals(item.path("packId").asText())
                    && pack.version().equals(item.path("packVersion").asText())
                    && pack.sha256().equals(item.path("packManifestSha256").asText())
                    && attestationSha256.equals(item.path("attestationSha256").asText())
                    && pack.scope().equals(item.path("scope").asText())
                    && item.path("artifactSha256s").equals(JSON.valueToTree(pack.artifacts().stream()
                            .map(EvidencePackManifest.Artifact::sha256).toList()))) return;
        }
        throw new IllegalArgumentException("AI pack has no matching qualified-registry entry");
    }

    private ObjectNode load() throws IOException {
        if (!Files.isRegularFile(path)) {
            var root = JSON.createObjectNode();
            root.put("schema", "pathlab.qualified-pack-registry/1");
            root.set("entries", JSON.createArrayNode());
            return root;
        }
        var root = JSON.readTree(path.toFile());
        require(root.isObject() && root.size() == 2
                        && "pathlab.qualified-pack-registry/1".equals(root.path("schema").asText())
                        && root.path("entries").isArray(), "Qualified pack registry is invalid");
        return (ObjectNode) root;
    }

    private void write(ObjectNode root) throws IOException {
        Files.createDirectories(path.getParent());
        var partial = path.resolveSibling(path.getFileName() + ".partial");
        JSON.writerWithDefaultPrettyPrinter().writeValue(partial.toFile(), root);
        try {
            Files.move(partial, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

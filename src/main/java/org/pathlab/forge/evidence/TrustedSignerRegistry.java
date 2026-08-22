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
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/** ACL-protected signer registry; payload-embedded public keys are never trust anchors. */
public final class TrustedSignerRegistry {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final Path path;

    public TrustedSignerRegistry(Path stateRoot) {
        path = stateRoot.toAbsolutePath().normalize().resolve("trust/approved-signers.json");
    }

    public synchronized void pin(String keyId, String publicKeyDer, String allowedUse) throws IOException {
        require(keyId != null && keyId.matches("[a-f0-9]{64}"), "Trusted signer id is invalid");
        require(SetHolder.USES.contains(allowedUse), "Trusted signer use is invalid");
        byte[] publicBytes;
        try {
            publicBytes = Base64.getUrlDecoder().decode(publicKeyDer);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Trusted signer key is invalid", error);
        }
        require(EvidenceBundleWriter.sha256(publicBytes).equals(keyId),
                "Trusted signer key does not match its id");
        var root = load();
        var keys = (ArrayNode) root.path("keys");
        for (var item : keys) {
            if (keyId.equals(item.path("keyId").asText()) && allowedUse.equals(item.path("allowedUse").asText())) {
                require(publicKeyDer.equals(item.path("publicKeyDer").asText()),
                        "Trusted signer id is already pinned to a different key");
                return;
            }
        }
        var item = keys.addObject();
        item.put("keyId", keyId);
        item.put("publicKeyDer", publicKeyDer);
        item.put("allowedUse", allowedUse);
        write(root);
    }

    public synchronized Optional<byte[]> find(String keyId, String allowedUse) throws IOException {
        var root = load();
        for (var item : root.path("keys")) {
            if (keyId.equals(item.path("keyId").asText()) && allowedUse.equals(item.path("allowedUse").asText())) {
                try {
                    var value = Base64.getUrlDecoder().decode(item.path("publicKeyDer").asText());
                    if (MessageDigest.isEqual(
                            EvidenceBundleWriter.sha256(value).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                            keyId.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) return Optional.of(value);
                } catch (IllegalArgumentException ignored) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private ObjectNode load() throws IOException {
        if (!Files.isRegularFile(path)) {
            var root = JSON.createObjectNode();
            root.put("schema", "pathlab.trusted-signers/1");
            root.set("keys", JSON.createArrayNode());
            return root;
        }
        var root = JSON.readTree(path.toFile());
        require(root.isObject() && "pathlab.trusted-signers/1".equals(root.path("schema").asText())
                        && root.path("keys").isArray(), "Trusted signer registry is invalid");
        var copy = (ObjectNode) root;
        require(copy.size() == 2, "Trusted signer registry fields are invalid");
        return copy;
    }

    private void write(ObjectNode value) throws IOException {
        Files.createDirectories(path.getParent());
        var partial = path.resolveSibling(path.getFileName() + ".partial");
        JSON.writerWithDefaultPrettyPrinter().writeValue(partial.toFile(), value);
        try {
            Files.move(partial, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static final class SetHolder {
        private static final java.util.Set<String> USES = java.util.Set.of("qualification", "evidence-set", "evidence");
    }
}

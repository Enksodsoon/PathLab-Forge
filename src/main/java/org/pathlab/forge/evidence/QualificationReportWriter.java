package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/** Signs qualification reports while pinning trust outside the signed payload. */
public final class QualificationReportWriter {
    public static final String SCHEMA = "pathlab.model-qualification-report/2";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final EvidenceSigner signer;
    private final TrustedSignerRegistry registry;

    public QualificationReportWriter(Path stateRoot) {
        signer = new EvidenceSigner(stateRoot.resolve("signing"));
        registry = new TrustedSignerRegistry(stateRoot);
    }

    public EvidenceBundleWriter.Result write(Path output, ObjectNode unsigned) throws IOException {
        require(SCHEMA.equals(unsigned.path("schema").asText()), "Qualification report schema is unsupported");
        require(unsigned.path("researchOnly").asBoolean(false), "Qualification report must be research-only");
        require(unsigned.path("notDiagnostic").asBoolean(false), "Qualification report must be non-diagnostic");
        require(!unsigned.has("manifestSha256") && !unsigned.has("signature"),
                "Unsigned qualification report contains signature fields");
        var sha = EvidenceBundleWriter.sha256(EvidenceBundleWriter.canonicalBytes(unsigned));
        var signed = signer.sign(SCHEMA + "\n" + sha);
        registry.pin(signed.keyId(), signed.publicKeyDer(), "qualification");
        var complete = unsigned.deepCopy();
        complete.put("manifestSha256", sha);
        complete.set("signature", JSON.valueToTree(Map.of(
                "algorithm", "Ed25519", "keyId", signed.keyId(), "value", signed.signature())));
        var target = output.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        var partial = target.resolveSibling(target.getFileName() + ".partial");
        JSON.writerWithDefaultPrettyPrinter().writeValue(partial.toFile(), complete);
        try {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new EvidenceBundleWriter.Result(target, sha, signed.keyId());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

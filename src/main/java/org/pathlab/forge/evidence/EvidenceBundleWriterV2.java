package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/** Canonical signed evidence v2 writer; signer trust remains external to the payload. */
public final class EvidenceBundleWriterV2 {
    public static final String SCHEMA = "pathlab.ai-evidence/2";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final EvidenceSigner signer;
    private final TrustedSignerRegistry registry;

    public EvidenceBundleWriterV2(Path stateRoot) {
        var normalized = stateRoot.toAbsolutePath().normalize();
        signer = new EvidenceSigner(normalized.resolve("signing"));
        registry = new TrustedSignerRegistry(normalized);
    }

    public EvidenceBundleWriter.Result write(Path output, ObjectNode unsigned) throws IOException {
        require(SCHEMA.equals(unsigned.path("schema").asText()), "Evidence v2 schema is unsupported");
        require(unsigned.path("researchOnly").asBoolean(false)
                        && unsigned.path("notDiagnostic").asBoolean(false)
                        && unsigned.path("reviewRequired").asBoolean(false),
                "Evidence v2 research boundary is invalid");
        require(unsigned.path("qualificationAttestationSha256").asText().matches("[a-f0-9]{64}"),
                "Evidence v2 qualification attestation is required");
        var manifestSha = EvidenceBundleWriter.sha256(EvidenceBundleWriter.canonicalBytes(unsigned));
        var signed = signer.sign(SCHEMA + "\n" + manifestSha);
        registry.pin(signed.keyId(), signed.publicKeyDer(), "evidence");
        var complete = unsigned.deepCopy();
        complete.put("manifestSha256", manifestSha);
        complete.set("signature", JSON.valueToTree(Map.of(
                "algorithm", "Ed25519", "keyId", signed.keyId(), "value", signed.signature())));
        var target = output.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        var partial = target.resolveSibling(target.getFileName() + ".partial");
        JSON.writerWithDefaultPrettyPrinter().writeValue(partial.toFile(), complete);
        try { Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING); }
        return new EvidenceBundleWriter.Result(target, manifestSha, signed.keyId());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

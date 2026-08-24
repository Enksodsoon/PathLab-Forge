package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;

/** Fuses reviewed pack outputs only at evidence/provenance/QC/coordinate level. */
public final class EvidenceSetWriter {
    public static final String SCHEMA = "pathlab.evidence-set/1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path stateRoot;
    private final EvidenceSigner signer;
    private final TrustedSignerRegistry registry;

    public EvidenceSetWriter(Path stateRoot) {
        this.stateRoot = stateRoot.toAbsolutePath().normalize();
        signer = new EvidenceSigner(this.stateRoot.resolve("signing"));
        registry = new TrustedSignerRegistry(this.stateRoot);
    }

    public EvidenceBundleWriter.Result write(Path output, String setId,
            java.util.List<Path> evidenceBundles, Instant now) throws IOException {
        require(setId.matches("[A-Za-z0-9._-]{1,160}"), "Evidence set id is invalid");
        require(evidenceBundles != null && !evidenceBundles.isEmpty() && evidenceBundles.size() <= 16,
                "Evidence set bundle count is invalid");
        String slideSha = null;
        String revision = null;
        var hashes = new HashSet<String>();
        var bundles = JSON.createArrayNode();
        var status = "completed";
        for (var bundlePath : evidenceBundles) {
            var path = bundlePath.toAbsolutePath().normalize();
            require(path.startsWith(stateRoot.resolve("artifacts")) && Files.isRegularFile(path),
                    "Evidence set bundle is outside the artifact root");
            var root = JSON.readTree(path.toFile());
            require(!root.path("qualificationOnly").asBoolean(false),
                    "Qualification-only artifacts cannot enter an evidence set");
            var currentSha = root.path("source").path("slideSha256").asText();
            var currentRevision = root.path("source").path("revision").asText();
            if (slideSha == null) { slideSha = currentSha; revision = currentRevision; }
            require(slideSha.equals(currentSha) && revision.equals(currentRevision),
                    "Evidence set bundles must bind the same slide revision");
            var manifestSha = root.path("manifestSha256").asText();
            require(manifestSha.matches("[a-f0-9]{64}") && hashes.add(manifestSha),
                    "Evidence set bundle checksum is invalid or duplicated");
            require(EvidenceArtifactStore.isTrusted(stateRoot, path, slideSha, revision),
                    "Evidence set bundle signature is not trusted");
            var item = bundles.addObject();
            item.put("manifestSha256", manifestSha);
            item.put("packId", root.path("pack").path("id").asText());
            item.put("capability", root.path("pack").path("capability").asText("descriptive-evidence"));
            if (!"completed".equals(root.path("status").asText())) status = "partial";
        }
        var unsigned = JSON.createObjectNode();
        unsigned.put("schema", SCHEMA);
        unsigned.put("setId", setId);
        unsigned.set("source", JSON.valueToTree(Map.of("slideSha256", slideSha, "revision", revision)));
        unsigned.set("bundles", bundles);
        unsigned.set("fusion", JSON.valueToTree(Map.of(
                "method", "evidence-coordinate-v1", "coordinateBound", true,
                "qcBound", true, "provenanceBound", true, "uncertaintyBound", true,
                "serialSectionCellMatching", false)));
        unsigned.put("status", status);
        unsigned.put("researchOnly", true);
        unsigned.put("notDiagnostic", true);
        unsigned.put("reviewRequired", true);
        unsigned.put("createdAt", now.toString());
        var manifestSha = EvidenceBundleWriter.sha256(EvidenceBundleWriter.canonicalBytes(unsigned));
        var signed = signer.sign(SCHEMA + "\n" + manifestSha);
        registry.pin(signed.keyId(), signed.publicKeyDer(), "evidence-set");
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

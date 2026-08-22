package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;

/** Writes the signed terminal distinction between campaign completion and target success. */
public final class CapabilityMatrixWriter {
    public static final String SCHEMA = "pathlab.capability-matrix/1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path stateRoot;

    public CapabilityMatrixWriter(Path stateRoot) {
        this.stateRoot = stateRoot.toAbsolutePath().normalize();
    }

    public EvidenceBundleWriter.Result write(QualificationRunSnapshot run, Instant generatedAt)
            throws IOException {
        if (!run.campaignCompleted()) throw new IllegalArgumentException("Capability matrix requires a terminal campaign");
        var root = JSON.createObjectNode();
        root.put("schema", SCHEMA); root.put("campaignId", run.id());
        root.put("campaignManifestSha256", run.manifestSha256());
        root.put("campaignCompleted", true); root.put("campaignTargetMet", run.campaignTargetMet());
        root.put("generatedAt", generatedAt.toString());
        var tracks = root.putArray("tracks");
        for (var track : run.tracks()) {
            var item = tracks.addObject(); item.put("trackId", track.id());
            item.put("candidateId", track.candidateId()); item.put("capability", track.capability());
            item.put("scope", track.scope()); item.put("status", track.verdict());
            item.put("activationEligible", "qualified".equals(track.verdict())
                    && !"local-benchmark".equals(track.scope()));
            if (track.artifactSha256().isBlank()) item.putNull("qualificationReportSha256");
            else item.put("qualificationReportSha256", track.artifactSha256());
            if (track.failureCode().isBlank()) item.putNull("failureCode");
            else item.put("failureCode", track.failureCode());
        }
        var manifestSha = EvidenceBundleWriter.sha256(EvidenceBundleWriter.canonicalBytes(root));
        var signer = new EvidenceSigner(stateRoot.resolve("signing"));
        var signed = signer.sign(SCHEMA + "\n" + manifestSha);
        new TrustedSignerRegistry(stateRoot).pin(signed.keyId(), signed.publicKeyDer(), "qualification");
        var complete = root.deepCopy(); complete.put("manifestSha256", manifestSha);
        complete.set("signature", JSON.valueToTree(Map.of(
                "algorithm", "Ed25519", "keyId", signed.keyId(), "value", signed.signature())));
        var output = stateRoot.resolve("qualification").resolve(run.id()).resolve("capability-matrix.json");
        Files.createDirectories(output.getParent());
        var partial = output.resolveSibling(output.getFileName() + ".partial");
        JSON.writerWithDefaultPrettyPrinter().writeValue(partial.toFile(), complete);
        try { Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING); }
        return new EvidenceBundleWriter.Result(output, manifestSha, signed.keyId());
    }
}

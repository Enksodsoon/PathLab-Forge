package org.pathlab.forge.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/** Creates the signed pathlab-ai-result/v2 envelope for morphology retrieval. */
public final class MorphologyResultWriter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AiResultSigner signer;

    public MorphologyResultWriter(Path managedRoot) {
        signer = new AiResultSigner(managedRoot.resolve("morphology-results"));
    }

    public String writeQuery(
            Path target,
            String jobId,
            String sourceFingerprint,
            String datasetId,
            GeneralMorphologyEngine.FrozenEncoder encoder,
            GeneralMorphologyEngine.StainProfile stain,
            String confirmedBy,
            double micronsPerPixel,
            GeneralMorphologyEngine.SourceRectangle queryRectangle,
            GeneralMorphologyEngine.QueryResult result,
            Duration runtime)
            throws IOException {
        if (stain == GeneralMorphologyEngine.StainProfile.UNKNOWN
                || confirmedBy == null
                || confirmedBy.isBlank()) {
            throw new IllegalArgumentException("A query result requires a teacher-confirmed stain");
        }
        var payload = JSON.createObjectNode();
        payload.put("task_type", "query_similar");
        payload.put("index_partition", result.partition());
        payload.put("abstained", result.abstained());
        var matches = payload.putArray("matches");
        for (var match : result.matches()) {
            var item = matches.addObject();
            item.put("slide_id", match.slideId());
            item.put("source_fingerprint_sha256", match.sourceFingerprintSha256());
            rectangle(item.putObject("source_rectangle"), match.sourceRectangle());
            item.put("similarity", match.similarity()); item.put("rank", match.rank());
            item.put("cross_stain", match.crossStain());
            item.put("evidence_kind", match.evidenceKind().name().toLowerCase(java.util.Locale.ROOT));
        }
        var artifactSha = sha256(JSON.writeValueAsBytes(payload));
        var modelSha = sha256((encoder.id() + "\n" + encoder.version()).getBytes(StandardCharsets.UTF_8));
        var preprocessSha = sha256(encoder.preprocessingVersion().getBytes(StandardCharsets.UTF_8));
        var revision = System.getProperty("pathlab.forge.gitCommit", "unverified-local");
        var signature = signer.sign(String.join("\n", "pathlab-ai-result/v2", jobId, "morphology", sourceFingerprint, artifactSha, modelSha, revision));

        var root = JSON.createObjectNode();
        root.put("ai_lab_schema", "pathlab-ai-result/v2"); root.put("job_id", jobId);
        root.put("adapter", "morphology"); root.put("task_type", "query_similar");
        var stainNode = root.putObject("stain_profile");
        stainNode.put("id", stain.name().toLowerCase(java.util.Locale.ROOT));
        stainNode.put("confirmation_state", "teacher_confirmed"); stainNode.put("confirmed_by", confirmedBy); stainNode.put("suggestion_source", "manual");
        root.put("research_only", true); root.put("not_diagnostic", true); root.put("contains_diagnosis", false);
        root.put("review_required", true); root.put("official_score_impact", false); root.put("source_fingerprint_sha256", sourceFingerprint);
        root.putObject("dataset").put("id", datasetId);
        var model = root.putObject("model"); model.put("id", encoder.id()); model.put("version", encoder.version()); model.put("config_sha256", modelSha);
        root.putObject("code").put("revision", revision);
        var configuration = root.putObject("configuration"); configuration.put("max_threads", 3); configuration.put("timeout_seconds", 1200);
        root.putObject("artifact").put("sha256", artifactSha);
        var runtimeNode = root.putObject("runtime"); runtimeNode.put("seconds", runtime.toMillis() / 1000.0); runtimeNode.put("peak_memory_mib", 0); runtimeNode.put("memory_measured", false); runtimeNode.put("threads", 3);
        var coordinates = root.putObject("coordinates"); coordinates.put("space", "source-pixel"); coordinates.put("microns_per_pixel_x", micronsPerPixel); coordinates.put("microns_per_pixel_y", micronsPerPixel);
        var patches = root.putObject("patches"); patches.put("sampling_method", "bounded-tissue-stratified"); patches.put("count", 1); patches.put("maximum", 2048);
        var embedding = root.putObject("embedding"); embedding.put("dimension", encoder.dimension()); embedding.put("storage_dtype", "float16"); embedding.put("encoder_sha256", modelSha); embedding.put("preprocessing_sha256", preprocessSha); embedding.put("index_partition", result.partition());
        rectangle(root.putObject("query_source_rectangle"), queryRectangle);
        root.set("matches", matches.deepCopy());
        var ood = root.putObject("ood"); ood.put("score", result.abstained() ? 1 : 0); ood.put("abstained", result.abstained());
        if (result.abstentionReason() == null) ood.putNull("reason"); else ood.put("reason", result.abstentionReason());
        var signatureNode = root.putObject("signature"); signatureNode.put("algorithm", "Ed25519"); signatureNode.put("key_id", signature.keyId()); signatureNode.put("public_key_der", signature.publicKeyDer()); signatureNode.put("value", signature.signature());
        var rendered = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        var partial = target.resolveSibling(target.getFileName() + ".partial");
        Files.writeString(partial, rendered, StandardCharsets.UTF_8);
        try { Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING); }
        return rendered;
    }

    private static void rectangle(ObjectNode node, GeneralMorphologyEngine.SourceRectangle value) {
        node.put("x", value.x()); node.put("y", value.y()); node.put("width", value.width()); node.put("height", value.height());
    }

    private static String sha256(byte[] value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }
}

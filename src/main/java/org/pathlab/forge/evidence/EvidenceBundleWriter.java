package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Canonicalizes, signs, and atomically publishes a pathlab.ai-evidence/1 manifest. */
public final class EvidenceBundleWriter {
    public static final String SCHEMA = "pathlab.ai-evidence/1";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final EvidenceSigner signer;

    public EvidenceBundleWriter(Path signingRoot) {
        signer = new EvidenceSigner(signingRoot);
    }

    public Result write(Path output, ObjectNode unsigned) throws IOException {
        require(SCHEMA.equals(unsigned.path("schema").asText()), "Evidence schema is unsupported");
        require(unsigned.path("researchOnly").asBoolean(false), "Evidence must be research-only");
        require(unsigned.path("notDiagnostic").asBoolean(false), "Evidence must be non-diagnostic");
        require(unsigned.path("reviewRequired").asBoolean(false), "Evidence must require review");
        require(unsigned.path("source").path("slideSha256").asText().matches("[a-f0-9]{64}"),
                "Evidence source SHA-256 is invalid");
        require(!unsigned.has("manifestSha256") && !unsigned.has("signature"),
                "Unsigned evidence must not contain signature fields");
        var canonical = canonicalBytes(unsigned);
        var manifestSha = sha256(canonical);
        var signed = signer.sign(SCHEMA + "\n" + manifestSha);
        var complete = unsigned.deepCopy();
        complete.put("manifestSha256", manifestSha);
        complete.set("signature", JSON.valueToTree(java.util.Map.of(
                "algorithm", "Ed25519",
                "keyId", signed.keyId(),
                "publicKeyDer", signed.publicKeyDer(),
                "value", signed.signature())));
        var normalized = output.toAbsolutePath().normalize();
        Files.createDirectories(normalized.getParent());
        var partial = normalized.resolveSibling(normalized.getFileName() + ".partial");
        JSON.writerWithDefaultPrettyPrinter().writeValue(partial.toFile(), complete);
        try {
            Files.move(partial, normalized, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, normalized, StandardCopyOption.REPLACE_EXISTING);
        }
        return new Result(normalized, manifestSha, signed.keyId());
    }

    public static byte[] canonicalBytes(JsonNode value) throws IOException {
        var output = new StringBuilder();
        appendCanonical(value, output);
        return output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void appendCanonical(JsonNode value, StringBuilder output) throws IOException {
        if (value.isNull()) {
            output.append("null");
        } else if (value.isBoolean()) {
            output.append(value.booleanValue());
        } else if (value.isTextual()) {
            output.append(JSON.writeValueAsString(value.textValue()));
        } else if (value.isIntegralNumber()) {
            output.append(value.bigIntegerValue());
        } else if (value.isFloatingPointNumber()) {
            var decimal = value.decimalValue();
            if (!Double.isFinite(value.doubleValue())) throw new IOException("Evidence number is invalid");
            output.append(decimal.signum() == 0 ? "0" : decimal.stripTrailingZeros().toPlainString());
        } else if (value.isArray()) {
            output.append('[');
            for (var index = 0; index < value.size(); index++) {
                if (index > 0) output.append(',');
                appendCanonical(value.get(index), output);
            }
            output.append(']');
        } else if (value.isObject()) {
            output.append('{');
            var names = new java.util.ArrayList<String>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (var index = 0; index < names.size(); index++) {
                if (index > 0) output.append(',');
                output.append(JSON.writeValueAsString(names.get(index))).append(':');
                appendCanonical(value.get(names.get(index)), output);
            }
            output.append('}');
        } else {
            throw new IOException("Evidence value is invalid");
        }
    }

    public static String sha256(byte[] value) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record Result(Path path, String manifestSha256, String keyId) {}
}

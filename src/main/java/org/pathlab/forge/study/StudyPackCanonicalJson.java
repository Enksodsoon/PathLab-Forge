package org.pathlab.forge.study;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;

public final class StudyPackCanonicalJson {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static { JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100).maxStringLength(2 * 1024 * 1024).build()); }
    private StudyPackCanonicalJson() {}
    public static ObjectMapper mapper() { return JSON; }
    public static ObjectNode parse(String text) throws IOException {
        if (text == null || text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > StudyPackContract.MAX_PACK_BYTES) throw new IllegalArgumentException("Study content exceeds the 2 MiB bound or is empty");
        var parsed = JSON.readTree(text);
        if (!(parsed instanceof ObjectNode object)) throw new IllegalArgumentException("Study content must be a JSON object");
        return object;
    }
    public static ObjectNode core(ObjectNode definition) {
        var core = definition.deepCopy(); core.remove("checksum"); core.remove("facultyPreview"); return core;
    }
    public static String canonicalize(JsonNode value) {
        if (value == null || value.isNull()) return "null";
        if (value.isObject()) {
            var keys = new ArrayList<String>(); value.fieldNames().forEachRemaining(keys::add);
            // Python sorts Unicode code points; UTF-16 ordering differs for supplementary characters.
            keys.sort((first, second) -> java.util.Arrays.compare(first.codePoints().toArray(),second.codePoints().toArray()));
            return "{" + keys.stream().map(key -> quote(key) + ":" + canonicalize(value.get(key))).collect(java.util.stream.Collectors.joining(",")) + "}";
        }
        if (value.isArray()) {
            var items = new ArrayList<String>(); value.forEach(item -> items.add(canonicalize(item))); return "[" + String.join(",",items) + "]";
        }
        if (value.isTextual()) return quote(value.textValue());
        if (value.isBoolean() || value.isIntegralNumber()) return value.toString();
        if (value.isFloatingPointNumber()) {
            var number = value.doubleValue();
            if (!Double.isFinite(number)) throw new IllegalArgumentException("Nonfinite study number");
            if (number == 0) return "0";
            return new BigDecimal(com.fasterxml.jackson.core.io.schubfach.DoubleToDecimal.toString(number)).stripTrailingZeros().toPlainString();
        }
        throw new IllegalArgumentException("Unsupported JSON value");
    }
    private static String quote(String text) {
        try { return JSON.writeValueAsString(text); } catch(IOException error) { throw new IllegalArgumentException(error); }
    }
    public static String checksum(ObjectNode definition) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalize(core(definition)).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}

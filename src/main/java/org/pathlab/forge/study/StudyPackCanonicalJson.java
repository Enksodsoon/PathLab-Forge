package org.pathlab.forge.study;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class StudyPackCanonicalJson {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private StudyPackCanonicalJson() {}

    public static ObjectMapper mapper() {
        return JSON;
    }

    public static String canonicalize(Object value) {
        try {
            return JSON.writeValueAsString(normalize(value));
        } catch (IOException error) {
            throw new IllegalArgumentException("Study Pack JSON is invalid", error);
        }
    }

    public static String checksum(Object value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalize(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                sorted.put(key, normalize(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            var normalized = new ArrayList<>(list.size());
            for (var item : list) normalized.add(normalize(item));
            return List.copyOf(normalized);
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.signum() == 0 ? BigDecimal.ZERO : decimal.stripTrailingZeros();
        }
        return value;
    }
}

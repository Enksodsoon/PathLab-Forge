package org.pathlab.forge.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class StudyPackContract {
    public static final String SCHEMA = "pathlab.study-pack/1";
    public static final String PREVIEW_VERSION = "pathlab.study-preview/1";
    public static final int MAX_PACK_BYTES = 2 * 1024 * 1024;
    public static final int MAX_SLIDES = 50;
    public static final int MAX_TASKS = 500;
    private StudyPackContract() {}

    public static void validateCore(ObjectNode root) {
        if (StudyPackCanonicalJson.canonicalize(root).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PACK_BYTES) throw new IllegalArgumentException("Study Pack exceeds 2 MiB");
        if (!SCHEMA.equals(text(root, "schema", 80))) throw new IllegalArgumentException("Unsupported Study Pack schema");
        var key = text(root, "packKey", 120);
        if (!key.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Pack key is invalid");
        positiveInteger(root, "version");
        text(root, "title", 240); text(root, "author", 240); text(root, "license", 240);
        text(root, "provenance", 1000); text(root, "revision", 120);
        var languages = array(root, "languages", 1, 20);
        if (languages.stream().anyMatch(language -> !language.isTextual() || !Set.of("en", "th").contains(language.textValue()))) {
            throw new IllegalArgumentException("Languages must contain English or Thai");
        }
        var slides = array(root, "slides", 1, MAX_SLIDES);
        var slideIds = new HashSet<String>();
        for (var slide : slides) {
            if (!slide.isObject()) throw new IllegalArgumentException("Slide reference is invalid");
            var id = text(slide, "viewerSlideId", 100);
            if (!slideIds.add(id) || !text(slide, "sha256", 64).matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Slide reference is invalid");
            }
            text(slide, "displayName", 200);
        }
        var tasks = array(root, "tasks", 1, MAX_TASKS);
        var taskIds = new HashSet<String>();
        for (var task : tasks) {
            if (!task.isObject()) throw new IllegalArgumentException("Task is invalid");
            var id = text(task, "id", 120);
            if (!id.matches("[A-Za-z0-9._-]+") || !taskIds.add(id)
                    || !slideIds.contains(text(task, "slideId", 100))) {
                throw new IllegalArgumentException("Task identity is invalid");
            }
            text(task, "prompt", 2000); text(task, "explanation", 8000);
            if (task.has("hints")) array(task, "hints", 0, 3).forEach(item -> requireTextNode(item, 2000, "Hint"));
            var sources = array(task, "sources", 1, 10);
            for (var source : sources) {
                text(source, "title", 500);
                if (!text(source, "url", 1000).startsWith("https://")) {
                    throw new IllegalArgumentException("Source URL must use HTTPS");
                }
            }
            var type = text(task, "type", 40);
            if ("multiple-choice".equals(type)) {
                var options = array(task, "options", 2, 10);
                var values = new HashSet<String>();
                for (var option : options) values.add(requireTextNode(option, 1000, "Option"));
                if (values.size() != options.size() || !values.contains(text(task, "answerKey", 1000))) {
                    throw new IllegalArgumentException("Multiple-choice answer key must be explicit");
                }
            } else if ("spatial".equals(type)) {
                var x = unit(task, "targetX", false); var y = unit(task, "targetY", false);
                var width = unit(task, "targetWidth", true); var height = unit(task, "targetHeight", true);
                var tolerance = unit(task, "tolerance", true);
                if (x + width > 1 || y + height > 1 || tolerance > .5) {
                    throw new IllegalArgumentException("Spatial target escapes normalized bounds");
                }
            } else throw new IllegalArgumentException("Task type is unsupported");
        }
    }

    private static JsonNode object(JsonNode parent, String field) {
        var value = parent.get(field);
        if (value == null || !value.isObject()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
    private static List<JsonNode> array(JsonNode parent, String field, int minimum, int maximum) {
        var value = parent.get(field);
        if (value == null || !value.isArray() || value.size() < minimum || value.size() > maximum) {
            throw new IllegalArgumentException(field + " count is invalid");
        }
        var result = new ArrayList<JsonNode>(); value.forEach(result::add); return result;
    }
    private static Set<String> strings(JsonNode parent, String field) {
        var values = new HashSet<String>();
        for (var item : array(parent, field, 1, 20)) values.add(requireTextNode(item, 40, field));
        return values;
    }
    private static String text(JsonNode parent, String field, int maximum) {
        return requireTextNode(parent.get(field), maximum, field);
    }
    private static String requireTextNode(JsonNode value, int maximum, String field) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()
                || value.textValue().length() > maximum) throw new IllegalArgumentException(field + " is invalid");
        return value.textValue().strip();
    }
    private static void positiveInteger(JsonNode parent, String field) {
        var value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || value.bigIntegerValue().signum() < 1) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
    private static double unit(JsonNode parent, String field, boolean positive) {
        var value = parent.get(field); var number = value == null || !value.isNumber() ? Double.NaN : value.asDouble(Double.NaN);
        if (!Double.isFinite(number) || number > 1 || (positive ? number <= 0 : number < 0)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return number;
    }
}

package org.pathlab.forge.conversion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BioFormatsMetadataParser {
    private static final Pattern IMAGE = Pattern.compile(
            "<(?:\\w+:)?Image\\b([^>]*)>(.*?)</(?:\\w+:)?Image>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PIXELS = Pattern.compile(
            "<(?:\\w+:)?Pixels\\b([^>]*)[/ >]",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "\\b([A-Za-z][A-Za-z0-9:]*)\\s*=\\s*\"([^\"]*)\"");

    private BioFormatsMetadataParser() {}

    public static List<SeriesInfo> parse(String omeXml) {
        var result = new ArrayList<SeriesInfo>();
        Matcher images = IMAGE.matcher(omeXml);
        while (images.find()) {
            var pixels = PIXELS.matcher(images.group(2));
            if (!pixels.find()) {
                continue;
            }
            var imageAttributes = attributes(images.group(1));
            var pixelAttributes = attributes(pixels.group(1));
            result.add(new SeriesInfo(
                    result.size(),
                    decode(imageAttributes.value("Name", "Series " + result.size())),
                    pixelAttributes.integer("SizeX"),
                    pixelAttributes.integer("SizeY"),
                    pixelAttributes.integer("SizeC"),
                    pixelAttributes.integer("SizeZ"),
                    pixelAttributes.integer("SizeT"),
                    pixelAttributes.value("Type", ""),
                    pixelAttributes.decimal("PhysicalSizeX"),
                    pixelAttributes.decimal("PhysicalSizeY"),
                    normalizePhysicalUnit(
                            decode(pixelAttributes.value("PhysicalSizeXUnit", "")))));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Bio-Formats returned no readable image series");
        }
        return List.copyOf(result);
    }

    private static Attributes attributes(String input) {
        var values = new java.util.HashMap<String, String>();
        Matcher matcher = ATTRIBUTE.matcher(input);
        while (matcher.find()) {
            var name = matcher.group(1);
            var separator = name.indexOf(':');
            values.put(separator < 0 ? name : name.substring(separator + 1), matcher.group(2));
        }
        return new Attributes(values);
    }

    private static String decode(String value) {
        return value.replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static String normalizePhysicalUnit(String value) {
        var normalized = value.strip();
        if (normalized.equalsIgnoreCase("um")
                || normalized.equalsIgnoreCase("micrometer")
                || normalized.equalsIgnoreCase("micrometre")
                || normalized.equals("\uFFFDm")) {
            return "µm";
        }
        return normalized;
    }

    private record Attributes(java.util.Map<String, String> values) {
        String value(String name, String fallback) {
            return values.getOrDefault(name, fallback);
        }

        int integer(String name) {
            return Integer.parseInt(value(name, "1"));
        }

        double decimal(String name) {
            var value = values.get(name);
            return value == null || value.isBlank() ? 0 : Double.parseDouble(value);
        }
    }
}

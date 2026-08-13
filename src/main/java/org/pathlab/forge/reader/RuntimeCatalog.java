package org.pathlab.forge.reader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

public record RuntimeCatalog(String runtimeVersion, String fingerprint, List<ReaderDescriptor> formats) {
    public RuntimeCatalog {
        runtimeVersion = Objects.requireNonNull(runtimeVersion, "runtimeVersion").trim();
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        formats = List.copyOf(Objects.requireNonNull(formats, "formats"));
        if (runtimeVersion.isEmpty() || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Runtime catalog identity is invalid");
        }
    }

    public static RuntimeCatalog parseBioFormats(String xml, String version) throws Exception {
        xml = Objects.requireNonNull(xml, "xml").lines()
                .filter(line -> !line.startsWith("WARNING:"))
                .filter(line -> !line.startsWith("WARNING "))
                .collect(java.util.stream.Collectors.joining("\n"));
        var start = xml.indexOf("<response>");
        var end = xml.lastIndexOf("</response>");
        if (start >= 0 && end > start) xml = xml.substring(start, end + "</response>".length());
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8)));
        var result = new ArrayList<ReaderDescriptor>();
        var nodes = document.getElementsByTagName("format");
        for (var index = 0; index < nodes.getLength(); index++) {
            var element = (org.w3c.dom.Element) nodes.item(index);
            var readable = false;
            var extensions = new LinkedHashSet<String>();
            var tags = element.getElementsByTagName("tag");
            for (var tagIndex = 0; tagIndex < tags.getLength(); tagIndex++) {
                var tag = (org.w3c.dom.Element) tags.item(tagIndex);
                if ("support".equals(tag.getAttribute("name"))
                        && "reading".equals(tag.getAttribute("value"))) readable = true;
                if ("extensions".equals(tag.getAttribute("name"))) {
                    for (var extension : tag.getAttribute("value").split("\\|")) {
                        var normalized = extension.trim().toLowerCase(Locale.ROOT);
                        if (!normalized.isEmpty()) extensions.add(normalized);
                    }
                }
            }
            if (!readable) continue;
            var name = element.getAttribute("name").trim();
            result.add(new ReaderDescriptor(
                    "BIO_FORMATS",
                    stableReaderId(name),
                    name,
                    List.copyOf(extensions),
                    true,
                    false,
                    true,
                    true));
        }
        result.sort(Comparator.comparing(ReaderDescriptor::displayName));
        var canonical = version + "\n" + result.stream()
                .map(item -> item.readerId() + "=" + String.join(",", item.extensions()))
                .collect(java.util.stream.Collectors.joining("\n"));
        return new RuntimeCatalog(version, sha256(canonical), result);
    }

    private static String stableReaderId(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}

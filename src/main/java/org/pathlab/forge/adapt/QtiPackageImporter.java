package org.pathlab.forge.adapt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

public final class QtiPackageImporter {
    private static final long MAX_PACKAGE_BYTES = 8L * 1024 * 1024;
    private static final int MAX_XML_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ENTRIES = 128;

    public List<ImportedItem> read(Path packagePath) throws IOException {
        if (!Files.isRegularFile(packagePath) || Files.size(packagePath) > MAX_PACKAGE_BYTES) {
            throw new IllegalArgumentException("QTI package is missing or exceeds 8 MiB");
        }
        var items = new ArrayList<ImportedItem>();
        var total = 0;
        try (var archive = new ZipFile(packagePath.toFile())) {
            var entries = archive.entries();
            var count = 0;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (++count > MAX_ENTRIES) throw new IllegalArgumentException("QTI package has too many entries");
                var normalized = Path.of(entry.getName()).normalize();
                if (normalized.isAbsolute() || normalized.startsWith("..")) {
                    throw new IllegalArgumentException("QTI package entry escapes the archive");
                }
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".xml")) continue;
                try (var input = archive.getInputStream(entry); var output = new ByteArrayOutputStream()) {
                    var buffer = new byte[8192];
                    for (var read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                        total += read;
                        if (total > MAX_XML_BYTES) throw new IllegalArgumentException("QTI XML exceeds 4 MiB");
                        output.write(buffer, 0, read);
                    }
                    parse(output.toByteArray(), items);
                }
            }
        } catch (java.util.zip.ZipException error) {
            throw new IllegalArgumentException("QTI package is not a valid archive", error);
        }
        if (items.isEmpty()) throw new IllegalArgumentException("QTI package has no keyed assessment items");
        return List.copyOf(items);
    }

    private static void parse(byte[] xml, List<ImportedItem> items) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            for (var index = 0; index < document.getElementsByTagNameNS("*", "assessmentItem").getLength(); index++) {
                var item = (Element) document.getElementsByTagNameNS("*", "assessmentItem").item(index);
                var correctResponses = item.getElementsByTagNameNS("*", "correctResponse");
                if (correctResponses.getLength() != 1) {
                    throw new IllegalArgumentException("QTI item needs exactly one correct response");
                }
                items.add(new ImportedItem(requiredAttribute(item, "identifier"), required(item, "itemBody"),
                        required((Element) correctResponses.item(0), "value"),
                        optional(item, "source"), optional(item, "author"),
                        optional(item, "license"), optional(item, "revision"), "imported"));
            }
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("QTI XML is malformed or unsafe", error); }
    }

    private static String requiredAttribute(Element item, String name) {
        var value = item.getAttribute(name).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("QTI item omitted " + name);
        return value;
    }

    private static String required(Element item, String localName) {
        var value = optional(item, localName);
        if (value.isBlank()) throw new IllegalArgumentException("QTI item omitted " + localName);
        return value;
    }

    private static String optional(Element item, String localName) {
        var matches = item.getElementsByTagNameNS("*", localName);
        return matches.getLength() == 0 ? "" : matches.item(0).getTextContent().replaceAll("\\s+", " ").trim();
    }

    public record ImportedItem(String id, String prompt, String answerKey, String source,
            String author, String license, String revision, String keyOrigin) {}
}

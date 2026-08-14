package org.pathlab.forge.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Deterministic inventory for externally staged reader runtimes. */
public record ReaderRuntimeManifest(
        int schemaVersion,
        String channel,
        String distributionLabel,
        String platform,
        String fingerprint,
        List<Component> components,
        List<FileEntry> files) {
    public enum Channel { INTERNAL, PRODUCTION }

    public record Component(
            String id,
            String version,
            String license,
            String reviewStatus,
            boolean required,
            boolean included,
            List<String> noticeFiles) {
        public Component {
            id = safeToken(id, "component id");
            version = text(version, "component version");
            license = text(license, "component license");
            reviewStatus = text(reviewStatus, "review status");
            noticeFiles = List.copyOf(Objects.requireNonNull(noticeFiles, "noticeFiles"));
        }
    }

    public record FileEntry(String component, String path, long bytes, String sha256) {
        public FileEntry {
            component = safeToken(component, "file component");
            path = safeRelative(path);
            if (bytes < 0 || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Runtime file identity is invalid");
            }
        }
    }

    private static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024;
    private static final int MAX_FILES = 50_000;
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();
    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("bioformats", "bftools", "8.5.x", "GPL-2.0-or-later/commercial", true),
            new Definition("libvips", "vips", "8.18.x", "LGPL-2.1-or-later and transitive codecs", true),
            new Definition("sdpc", "sdpc", "owner-supplied", "Review exact decoder and FFmpeg DLLs", false),
            new Definition("python", "isyntax", "3.12.x", "PSF-2.0", false),
            new Definition("pyisyntax", "isyntax", "0.1.6", "MIT", false),
            new Definition("libisyntax", "isyntax", "0.1.6", "BSD-2-Clause", false),
            new Definition("numpy", "isyntax", "pinned by staged runtime", "BSD-3-Clause", false),
            new Definition("pillow", "isyntax", "pinned by staged runtime", "HPND", false),
            new Definition("cffi", "isyntax", "pinned by staged runtime", "MIT", false));

    public ReaderRuntimeManifest {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported runtime manifest schema");
        channel = text(channel, "channel");
        distributionLabel = text(distributionLabel, "distributionLabel");
        platform = text(platform, "platform");
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Runtime manifest fingerprint is invalid");
        }
    }

    public static ReaderRuntimeManifest create(Path source, Channel channel, String platform)
            throws IOException {
        var root = source.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Reader runtime source is not a directory");
        }
        var review = review(root);
        var production = channel == Channel.PRODUCTION;
        if (production && !"APPROVED".equals(review.get("redistribution.status"))) {
            throw new IOException("Reader runtime redistribution review is incomplete");
        }
        var components = new ArrayList<Component>();
        for (var definition : DEFINITIONS) {
            var included = Files.isDirectory(root.resolve(definition.root()), LinkOption.NOFOLLOW_LINKS);
            var status = review.getOrDefault("component." + definition.id() + ".status", "PENDING");
            var version = review.getOrDefault("component." + definition.id() + ".version", definition.version());
            var notices = noticeFiles(root, definition.id());
            if (production && included && (!"APPROVED".equals(status) || notices.isEmpty())) {
                throw new IOException("Reader runtime redistribution or notice review is incomplete for "
                        + definition.id());
            }
            components.add(new Component(definition.id(), version, definition.license(), status,
                    definition.required(), included, notices));
        }
        for (var component : components) {
            if (component.required() && !component.included()) {
                throw new IOException("Required reader runtime component is missing: " + component.id());
            }
        }
        var entries = scan(root);
        var label = production ? "PRODUCTION" : "NON_REDISTRIBUTABLE";
        var fingerprint = fingerprint(channel.name(), label, platform, components, entries);
        return new ReaderRuntimeManifest(1, channel.name(), label, platform, fingerprint,
                components, entries);
    }

    public static ReaderRuntimeManifest read(Path path) throws IOException {
        return MAPPER.readValue(path.toFile(), ReaderRuntimeManifest.class);
    }

    public String toJson() throws IOException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this) + "\n";
    }

    public void verify(Path root, boolean requireProduction) throws IOException {
        var normalized = root.toAbsolutePath().normalize();
        if (requireProduction && (!"PRODUCTION".equals(distributionLabel)
                || components.stream().filter(Component::included)
                        .anyMatch(component -> !"APPROVED".equals(component.reviewStatus())
                                || component.noticeFiles().isEmpty()))) {
            throw new IOException("Reader runtime is not approved for production redistribution");
        }
        long total = 0;
        for (var entry : files) {
            var file = normalized.resolve(entry.path().replace('/', java.io.File.separatorChar)).normalize();
            if (!file.startsWith(normalized) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file) || Files.size(file) != entry.bytes()
                    || !sha256(file).equals(entry.sha256())) {
                throw new IOException("Reader runtime file verification failed: " + entry.path());
            }
            total = Math.addExact(total, entry.bytes());
            if (total > MAX_TOTAL_BYTES) throw new IOException("Reader runtime exceeds its size bound");
        }
        var rescanned = scan(normalized);
        if (!rescanned.equals(files)) throw new IOException("Reader runtime contains unmanifested files");
        var expected = fingerprint(channel, distributionLabel, platform, components, files);
        if (!expected.equals(fingerprint)) throw new IOException("Reader runtime manifest fingerprint is invalid");
    }

    public static String currentPlatform() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        var normalizedArch = arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x86_64";
        if (os.contains("win")) return "windows-" + normalizedArch;
        if (os.contains("mac")) return "macos-" + normalizedArch;
        return "unsupported-" + normalizedArch;
    }

    private static List<FileEntry> scan(Path root) throws IOException {
        var entries = new ArrayList<FileEntry>();
        long total = 0;
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted().toList()) {
                if (path.equals(root)) continue;
                if (Files.isSymbolicLink(path)) throw new IOException("Reader runtime may not contain symbolic links");
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                var relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.equals("runtime-review.properties")
                        || relative.equals("reader-runtime-manifest.json")
                        || relative.equals("NON_REDISTRIBUTABLE")) continue;
                var first = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : relative;
                if (!List.of("bftools", "vips", "sdpc", "isyntax", "licenses").contains(first)) {
                    throw new IOException("Reader runtime contains an unexpected top-level file");
                }
                var bytes = Files.size(path);
                total = Math.addExact(total, bytes);
                if (total > MAX_TOTAL_BYTES || entries.size() >= MAX_FILES) {
                    throw new IOException("Reader runtime exceeds its bounded inventory");
                }
                entries.add(new FileEntry(classify(relative), relative, bytes, sha256(path)));
            }
        }
        entries.sort(Comparator.comparing(FileEntry::path));
        return List.copyOf(entries);
    }

    private static String classify(String path) {
        if (path.startsWith("bftools/")) return "bioformats";
        if (path.startsWith("vips/")) return "libvips";
        if (path.startsWith("sdpc/")) return "sdpc";
        var lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("numpy")) return "numpy";
        if (lower.contains("pillow") || lower.contains("pil/")) return "pillow";
        if (lower.contains("cffi")) return "cffi";
        if (lower.contains("pyisyntax")) return "pyisyntax";
        if (path.startsWith("isyntax/isyntax/")) return "libisyntax";
        if (path.startsWith("isyntax/")) return "python";
        if (path.startsWith("licenses/")) {
            var parts = path.split("/", 3);
            return parts.length > 1 ? safeToken(parts[1], "license component") : "licenses";
        }
        return "runtime";
    }

    private static List<String> noticeFiles(Path root, String component) throws IOException {
        var directory = root.resolve("licenses").resolve(component);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var paths = Files.walk(directory)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .sorted().toList();
        }
    }

    private static Map<String, String> review(Path root) throws IOException {
        var path = root.resolve("runtime-review.properties");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        var properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) { properties.load(input); }
        var result = new LinkedHashMap<String, String>();
        for (var name : properties.stringPropertyNames()) result.put(name, properties.getProperty(name).trim());
        return result;
    }

    private static String fingerprint(String channel, String label, String platform,
            List<Component> components, List<FileEntry> files) throws IOException {
        var canonical = MAPPER.writeValueAsString(List.of(1, channel, label, platform, components, files));
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            var digest = digest();
            var buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static String safeRelative(String value) {
        var normalized = text(value, "runtime path").replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":")
                || normalized.equals("..") || normalized.startsWith("../")
                || normalized.contains("/../") || normalized.endsWith("/..")) {
            throw new IllegalArgumentException("Runtime path is unsafe");
        }
        return normalized;
    }

    private static String safeToken(String value, String name) {
        var normalized = text(value, name).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9._-]{1,64}")) throw new IllegalArgumentException(name + " is invalid");
        return normalized;
    }

    private static String text(String value, String name) {
        var normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }

    private record Definition(String id, String root, String version, String license, boolean required) {}
}

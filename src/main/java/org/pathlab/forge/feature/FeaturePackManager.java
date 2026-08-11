package org.pathlab.forge.feature;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

public final class FeaturePackManager {
    private static final long MAX_CATALOG_BYTES = 2L * 1024 * 1024;
    private static final long MAX_PACK_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 10_000;
    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile HttpClient client;
    private volatile List<FeaturePackDescriptor> catalog = List.of();

    public FeaturePackManager(Path dataRoot) {
        root = dataRoot.toAbsolutePath().normalize().resolve("feature-packs");
    }

    public synchronized List<FeaturePackDescriptor> list() {
        var known = new LinkedHashMap<String, FeaturePackDescriptor>();
        for (var descriptor : catalog) {
            known.put(descriptor.id(), installedState(descriptor));
        }
        for (var descriptor : installedDescriptors()) {
            known.putIfAbsent(descriptor.id(), installedState(descriptor));
        }
        var available = new ArrayList<>(known.values());
        if (available.stream().noneMatch(pack -> "pathology-tools".equals(pack.id()))) {
            available.add(unpublished("pathology-tools", "Pathology Tools", "PATHOLOGY"));
        }
        if (available.stream().noneMatch(pack -> "classical-analysis".equals(pack.id()))) {
            available.add(unpublished("classical-analysis", "Classical Analysis", "ANALYSIS"));
        }
        if (available.stream().noneMatch(pack -> "pretrained-ai".equals(pack.id()))) {
            available.add(unpublished("pretrained-ai", "Pretrained AI", "AI").withState(
                    "UNAVAILABLE", "No approved pretrained model is published"));
        }
        if (available.stream().noneMatch(pack -> "training-lab".equals(pack.id()))) {
            available.add(unpublished("training-lab", "Training Lab", "TRAINING").withState(
                    "UNAVAILABLE", "Requires an approved pretrained model and labelled data"));
        }
        available.sort(Comparator.comparing(FeaturePackDescriptor::id));
        return List.copyOf(available);
    }

    public synchronized List<FeaturePackDescriptor> refresh() throws IOException {
        var configured = System.getProperty("pathlab.forge.featureCatalogUrl", "").trim();
        if (configured.isEmpty()) {
            throw new IOException("No PathLab feature catalog is configured");
        }
        var uri = URI.create(configured);
        requireHttps(uri);
        var response = send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build());
        if (response.statusCode() != 200) {
            throw new IOException("Feature catalog response was rejected");
        }
        byte[] catalogBytes;
        try (var body = response.body()) {
            catalogBytes = body.readNBytes(Math.toIntExact(MAX_CATALOG_BYTES + 1));
        }
        if (catalogBytes.length > MAX_CATALOG_BYTES) {
            throw new IOException("Feature catalog response was rejected");
        }
        var envelope = mapper.readValue(catalogBytes, CatalogEnvelope.class);
        var payload = Base64.getDecoder().decode(envelope.payload());
        verify(publicKey(), payload, Base64.getDecoder().decode(envelope.signature()));
        var parsed = mapper.readValue(payload, new TypeReference<List<FeaturePackDescriptor>>() {});
        for (var descriptor : parsed) {
            validateDescriptor(descriptor);
        }
        catalog = List.copyOf(parsed);
        return list();
    }

    public synchronized FeaturePackDescriptor install(String id) throws IOException {
        var descriptor = catalog.stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IOException("Feature pack is not in the verified catalog"));
        validateDescriptor(descriptor);
        if (descriptor.downloadBytes() > Files.getFileStore(root.getParent()).getUsableSpace() / 2) {
            throw new IOException("Insufficient disk space for safe feature-pack installation");
        }
        Files.createDirectories(root);
        var archive = root.resolve(id + ".download.partial").normalize();
        if (!archive.startsWith(root)) {
            throw new IOException("Feature pack path escaped managed storage");
        }
        try {
            download(descriptor, archive);
            verifyPackSignature(descriptor);
            var staging = root.resolve("." + id + "-" + descriptor.version() + ".staging").normalize();
            deleteTree(staging);
            extract(archive, staging, descriptor.installedBytes());
            runSelfTest(staging, descriptor.entrypoint());
            mapper.writeValue(staging.resolve("installed.json").toFile(), descriptor);
            var target = root.resolve(id).resolve(descriptor.version()).normalize();
            Files.createDirectories(target.getParent());
            var backup = target.resolveSibling(target.getFileName() + ".previous");
            deleteTree(backup);
            if (Files.exists(target)) {
                move(target, backup);
            }
            move(staging, target);
            deleteTree(backup);
            return descriptor.withState("INSTALLED", "Installed and verified");
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    public synchronized void uninstall(String id) throws IOException {
        requireId(id);
        var target = root.resolve(id).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Feature pack path escaped managed storage");
        }
        deleteTree(target);
    }

    public synchronized void disable(String id) throws IOException {
        requireId(id);
        var versions = installedVersionDirectories(id);
        if (versions.isEmpty()) {
            throw new IOException("Feature pack is not installed");
        }
        for (var version : versions) {
            Files.writeString(version.resolve(".disabled"), "disabled\n", StandardCharsets.UTF_8);
        }
    }

    public synchronized boolean isInstalled(String id) {
        try {
            requireId(id);
        } catch (IOException ignored) {
            return false;
        }
        var directory = root.resolve(id).normalize();
        if (!directory.startsWith(root) || !Files.isDirectory(directory)) {
            return false;
        }
        try (var versions = Files.list(directory)) {
            return versions.anyMatch(version -> Files.isRegularFile(version.resolve("installed.json"))
                    && !Files.exists(version.resolve(".disabled")));
        } catch (IOException ignored) {
            return false;
        }
    }

    private FeaturePackDescriptor installedState(FeaturePackDescriptor descriptor) {
        var version = root.resolve(descriptor.id()).resolve(descriptor.version());
        if (!Files.isRegularFile(version.resolve("installed.json"))) {
            return descriptor;
        }
        return descriptor.withState(
                Files.exists(version.resolve(".disabled")) ? "DISABLED" : "INSTALLED",
                Files.exists(version.resolve(".disabled")) ? "Installed but disabled" : "Installed and verified");
    }

    private List<FeaturePackDescriptor> installedDescriptors() {
        var result = new ArrayList<FeaturePackDescriptor>();
        if (!Files.isDirectory(root)) return result;
        try (var ids = Files.list(root)) {
            for (var id : ids.filter(Files::isDirectory).toList()) {
                try (var versions = Files.list(id)) {
                    for (var version : versions.filter(Files::isDirectory).toList()) {
                        var manifest = version.resolve("installed.json");
                        if (Files.isRegularFile(manifest)) {
                            result.add(mapper.readValue(manifest.toFile(), FeaturePackDescriptor.class));
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        result.sort(Comparator.comparing(FeaturePackDescriptor::version).reversed());
        return result;
    }

    private List<Path> installedVersionDirectories(String id) throws IOException {
        var directory = root.resolve(id).normalize();
        if (!directory.startsWith(root) || !Files.isDirectory(directory)) return List.of();
        try (var versions = Files.list(directory)) {
            return versions.filter(path -> Files.isRegularFile(path.resolve("installed.json"))).toList();
        }
    }

    private void download(FeaturePackDescriptor descriptor, Path archive) throws IOException {
        var response = send(HttpRequest.newBuilder(descriptor.downloadUri())
                .timeout(Duration.ofMinutes(10)).GET().build());
        if (response.statusCode() != 200) {
            throw new IOException("Feature pack download failed");
        }
        try (var input = response.body(); var output = Files.newOutputStream(archive)) {
            var digest = sha256Digest();
            var buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > descriptor.downloadBytes() || total > MAX_PACK_BYTES) {
                    throw new IOException("Feature pack exceeded its declared size");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            if (total != descriptor.downloadBytes()
                    || !HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(descriptor.sha256())) {
                throw new IOException("Feature pack size or SHA-256 did not match");
            }
        }
    }

    private void extract(Path archive, Path staging, long declaredInstalledBytes) throws IOException {
        Files.createDirectories(staging);
        long total = 0;
        int entries = 0;
        try (var zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("Feature pack contains too many files");
                }
                var name = entry.getName();
                if (name.isBlank() || name.contains("\\") || name.contains(":") || name.startsWith("/")) {
                    throw new IOException("Feature pack contains an unsafe path");
                }
                var target = staging.resolve(name).normalize();
                if (!target.startsWith(staging)) {
                    throw new IOException("Feature pack contains a traversal path");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (var output = Files.newOutputStream(target)) {
                    var buffer = new byte[64 * 1024];
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        total += read;
                        if (total > declaredInstalledBytes || total > MAX_PACK_BYTES) {
                            throw new IOException("Feature pack exceeded its installed size");
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
        if (total == 0 || total > declaredInstalledBytes) {
            throw new IOException("Feature pack contents were invalid");
        }
    }

    private void runSelfTest(Path staging, String entrypoint) throws IOException {
        var executable = staging.resolve(entrypoint).normalize();
        if (!executable.startsWith(staging) || !Files.isRegularFile(executable)) {
            throw new IOException("Feature pack self-test entrypoint is missing");
        }
        List<String> command = entrypoint.endsWith(".jar")
                ? List.of(Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString(),
                        "-jar", executable.toString(), "--self-test")
                : List.of(executable.toString(), "--self-test");
        var process = org.pathlab.forge.runtime.ChildProcessContainment.global()
                .register(new ProcessBuilder(command).directory(staging.toFile()).start());
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new IOException("Feature pack self-test failed");
            }
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Feature pack self-test was interrupted", error);
        }
    }

    private void validateDescriptor(FeaturePackDescriptor descriptor) throws IOException {
        requireId(descriptor.id());
        if (descriptor.version() == null || !descriptor.version().matches("[A-Za-z0-9._-]{1,64}")
                || descriptor.downloadUri() == null
                || descriptor.downloadBytes() < 1 || descriptor.downloadBytes() > MAX_PACK_BYTES
                || descriptor.installedBytes() < 1 || descriptor.installedBytes() > MAX_PACK_BYTES
                || descriptor.sha256() == null || !descriptor.sha256().matches("[0-9a-fA-F]{64}")
                || descriptor.entrypoint() == null || descriptor.entrypoint().isBlank()) {
            throw new IOException("Feature pack descriptor is invalid");
        }
        requireHttps(descriptor.downloadUri());
    }

    private void verifyPackSignature(FeaturePackDescriptor descriptor) throws IOException {
        var signed = (descriptor.id() + "\n" + descriptor.version() + "\n"
                + descriptor.sha256().toLowerCase(java.util.Locale.ROOT)).getBytes(StandardCharsets.UTF_8);
        verify(publicKey(), signed, Base64.getDecoder().decode(descriptor.signature()));
    }

    private PublicKey publicKey() throws IOException {
        var encoded = System.getProperty("pathlab.forge.featureCatalogPublicKey", "").trim();
        if (encoded.isEmpty()) {
            throw new IOException("No PathLab feature-catalog public key is configured");
        }
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (Exception error) {
            throw new IOException("Feature-catalog public key is invalid", error);
        }
    }

    private static void verify(PublicKey key, byte[] value, byte[] signed) throws IOException {
        try {
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(value);
            if (!verifier.verify(signed)) {
                throw new IOException("Feature-pack signature verification failed");
            }
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Feature-pack signature could not be verified", error);
        }
    }

    private HttpResponse<InputStream> send(HttpRequest request) throws IOException {
        try {
            return client().send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Feature catalog request was interrupted", error);
        }
    }

    private HttpClient client() {
        var existing = client;
        if (existing != null) return existing;
        synchronized (this) {
            if (client == null) {
                client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
            }
            return client;
        }
    }

    private static void requireHttps(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Feature packs require HTTPS");
        }
    }

    private static void requireId(String id) throws IOException {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{1,63}")) {
            throw new IOException("Feature pack identifier is invalid");
        }
    }

    private static FeaturePackDescriptor unpublished(String id, String name, String kind) {
        List<String> capabilities = switch (id) {
            case "pathology-tools" -> List.of(
                    "pathology.hierarchy", "pathology.measurements", "pathology.he",
                    "pathology.stain-normalization", "pathology.tma");
            case "classical-analysis" -> List.of(
                    "analysis.tissue", "analysis.cells", "analysis.pixel-classifier",
                    "analysis.qc", "analysis.registration");
            case "pretrained-ai" -> List.of("research.pretrained-inference");
            case "training-lab" -> List.of("research.fine-tuning");
            default -> List.of();
        };
        return new FeaturePackDescriptor(
                id, "", name, kind, "NOT_PUBLISHED", 0, 0, 0, 0,
                false, "training-lab".equals(id), "", null, "", "", "", capabilities,
                "Not present in the verified PathLab catalog");
    }

    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteTree(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        var normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new IOException("Refusing to remove an unsafe feature-pack path");
        }
        try (var paths = Files.walk(normalized)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private record CatalogEnvelope(String payload, String signature) {}
}

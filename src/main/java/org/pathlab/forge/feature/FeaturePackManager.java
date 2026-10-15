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
    private volatile HttpClient client; // Only the package-private deterministic test seam uses this client.
    private volatile org.pathlab.forge.viewer.ViewerAuthorizedClient viewerTransport;
    private volatile OriginProvider viewerOrigin;
    private volatile NetworkScope installScope;
    private record NetworkScope(org.pathlab.forge.viewer.ViewerAuthorizedClient client, URI origin, String key) {}
    @FunctionalInterface interface OriginProvider { String origin() throws IOException; }

    public void setViewerTransport(org.pathlab.forge.viewer.ViewerPairingService viewer) {
        setViewerTransport(viewer, viewer::connectionOrigin);
    }
    void setViewerTransport(org.pathlab.forge.viewer.ViewerAuthorizedClient viewer, OriginProvider origin) {
        viewerTransport = java.util.Objects.requireNonNull(viewer);
        viewerOrigin = java.util.Objects.requireNonNull(origin);
    }

    private volatile List<FeaturePackDescriptor> catalog = List.of();
    private volatile CatalogEnvelope catalogEnvelope;
    private final java.util.concurrent.ConcurrentHashMap<String, ActiveVersion> active = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean installing = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean cancelled;
    private volatile Thread installerThread;
    private volatile InputStream downloadStream;
    private volatile Process selfTestProcess;
    private volatile InstallProgress progress = new InstallProgress("", "IDLE", 0, 0, "No installation running");

    public record InstallProgress(String id, String phase, long completedBytes, long totalBytes, String detail) {}
    private record ActiveVersion(String version, String previousVersion, boolean enabled) {}
    private record InstalledReceipt(FeaturePackDescriptor descriptor, CatalogEnvelope envelope) {}

    public FeaturePackManager(Path dataRoot) {
        root = dataRoot.toAbsolutePath().normalize().resolve("feature-packs");
        try { loadOfflineCatalog(); } catch (IOException ignored) { catalog = List.of(); }
        recoverActivation("pathology-tools");
        recoverActivation("classical-analysis");
    }

    FeaturePackManager(Path dataRoot, HttpClient client) {
        this(dataRoot);
        this.client = client;
    }

    public InstallProgress progress() { return progress; }

    public void cancelInstall(String id) throws IOException {
        requireId(id);
        if (!installing.get() || !progress.id().equals(id)
                || List.of("COMPLETE", "FAILED", "CANCELLED").contains(progress.phase())) throw new IOException("No matching installation is running");
        cancelled = true;
        var stream = downloadStream;
        if (stream != null) try { stream.close(); } catch (IOException ignored) { }
        var process = selfTestProcess;
        if (process != null) process.destroyForcibly();
        var thread = installerThread;
        if (thread != null) thread.interrupt();
    }

    public synchronized List<FeaturePackDescriptor> list() {
        var known = new LinkedHashMap<String, FeaturePackDescriptor>();
        for (var descriptor : catalog.stream().sorted((a, b) -> SemanticVersion.compare(b.version(), a.version())).toList())
            known.putIfAbsent(descriptor.id(), descriptor);
        for (var descriptor : installedDescriptors()) known.putIfAbsent(descriptor.id(), descriptor);
        known.putIfAbsent("pathology-tools", unpublished("pathology-tools", "Pathology Tools", "PATHOLOGY"));
        known.putIfAbsent("classical-analysis", unpublished("classical-analysis", "Classical Analysis", "ANALYSIS"));
        return known.values().stream().map(this::installedState).sorted(Comparator.comparing(FeaturePackDescriptor::id)).toList();
    }

    public synchronized List<FeaturePackDescriptor> refresh() throws IOException {
        var scope = networkScope();
        var configured = System.getProperty("pathlab.forge.featureCatalogUrl", "").trim();
        if (configured.isEmpty() && scope == null) throw new IOException("No PathLab feature catalog is configured");
        URI uri;
        try { uri = configured.isEmpty() ? scope.origin().resolve("/api/v2/forge/features/catalog") : URI.create(configured); }
        catch (IllegalArgumentException error) { throw new IOException("Feature catalog URI is invalid",error); }
        requireHttps(uri);
        requireOrigin(scope,uri);
        try (var response = get(uri,scope); var body = response.body()) {
            if (response.status() != 200) throw new IOException("Feature catalog response was rejected");
            var bytes = body.readNBytes(Math.toIntExact(MAX_CATALOG_BYTES + 1));
            if (bytes.length > MAX_CATALOG_BYTES) throw new IOException("Feature catalog response was rejected");
            var envelope = mapper.readValue(bytes,CatalogEnvelope.class);
            for (var descriptor : verifiedCatalog(envelope)) requireOrigin(scope,descriptor.downloadUri());
            checkAccount(scope);
            acceptCatalog(bytes);
        }
        return list();
    }

    // Also used by offline inspection and deterministic cryptographic contract tests.
    synchronized void acceptCatalog(byte[] bytes) throws IOException {
        if (bytes.length > MAX_CATALOG_BYTES) throw new IOException("Feature catalog response was rejected");
        var envelope = mapper.readValue(bytes, CatalogEnvelope.class);
        var parsed = verifiedCatalog(envelope);
        Files.createDirectories(root);
        atomicJson(root.resolve("catalog-envelope.json"), envelope);
        catalogEnvelope = envelope;
        catalog = parsed;
    }

    private void loadOfflineCatalog() throws IOException {
        var path = root.resolve("catalog-envelope.json");
        if (!physicalFile(path) || Files.size(path) > MAX_CATALOG_BYTES) return;
        var envelope = mapper.readValue(path.toFile(), CatalogEnvelope.class);
        catalog = verifiedCatalog(envelope);
        catalogEnvelope = envelope;
    }

    private List<FeaturePackDescriptor> verifiedCatalog(CatalogEnvelope envelope) throws IOException {
        try {
            var payload = Base64.getDecoder().decode(envelope.payload());
            if (payload.length > MAX_CATALOG_BYTES) throw new IOException("Catalog payload exceeds its bound");
            verify(publicKey(), payload, Base64.getDecoder().decode(envelope.signature()));
            var parsed = mapper.readValue(payload, new TypeReference<List<FeaturePackDescriptor>>() {});
            if (parsed.size() > 256) throw new IOException("Catalog contains too many versions");
            var identities = new java.util.HashSet<String>();
            for (var descriptor : parsed) {
                validateDescriptor(descriptor);
                if (!identities.add(descriptor.id() + "/" + descriptor.version())) throw new IOException("Duplicate catalog version");
            }
            return List.copyOf(parsed);
        } catch (IllegalArgumentException | NullPointerException error) { throw new IOException("Catalog encoding is invalid", error); }
    }

    public FeaturePackDescriptor install(String id) throws IOException {
        return install(id, null, null);
    }

    /** Native selected files; the signed catalog and actual ZIP bytes determine the pack identity. */
    public FeaturePackDescriptor importPack(Path signedCatalog, Path selectedArchive) throws IOException {
        if (signedCatalog == null || selectedArchive == null) throw new IOException("Select a signed catalog and its approved pack archive");
        requireExternalImportFile(signedCatalog);
        requireExternalImportFile(selectedArchive);
        return install("offline-import", signedCatalog, selectedArchive);
    }

    private void requireExternalImportFile(Path selected) throws IOException {
        org.pathlab.forge.runtime.DataRootLock.requireSafePath(root);
        org.pathlab.forge.runtime.DataRootLock.requireSafePath(selected);
        var normalized = selected.toAbsolutePath().normalize();
        var managed = Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? root.toRealPath() : root;
        if (normalized.startsWith(root) || normalized.toRealPath().startsWith(managed))
            throw new IOException("Select feature files outside Forge's managed feature directory");
    }

    private FeaturePackDescriptor install(String id, Path signedCatalog, Path selectedArchive) throws IOException {
        requireId(id);
        if (!installing.compareAndSet(false, true)) throw new IOException("Another feature installation is running");
        cancelled = false;
        installerThread = Thread.currentThread();
        update(id, "VERIFYING", 0, 0, "Checking requested pack metadata");
        Path archive = null, staging = null;
        Throwable failure = null;
        try {
            FeaturePackDescriptor descriptor;
            CatalogEnvelope envelope;
            if (selectedArchive == null) {
                installScope = networkScope();
                var requestedId = id;
                descriptor = catalog.stream().filter(candidate -> candidate.id().equals(requestedId))
                        .max((a, b) -> SemanticVersion.compare(a.version(), b.version()))
                        .orElseThrow(() -> new IOException("Feature pack is not in the verified catalog"));
                envelope = catalogEnvelope;
                if (envelope == null || !verifiedCatalog(envelope).contains(descriptor)) throw new IOException("Catalog changed during installation");
            } else {
                installScope = null;
                org.pathlab.forge.runtime.DataRootLock.requireSafePath(signedCatalog);
                org.pathlab.forge.runtime.DataRootLock.requireSafePath(selectedArchive);
                if (!Files.isRegularFile(signedCatalog, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || !Files.isRegularFile(selectedArchive, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || Files.size(signedCatalog) > MAX_CATALOG_BYTES || Files.size(selectedArchive) < 1
                        || Files.size(selectedArchive) > MAX_PACK_BYTES) throw new IOException("Selected feature files are missing or exceed their bounds");
                try (var input = Files.newInputStream(signedCatalog, java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    var bytes = input.readNBytes(Math.toIntExact(MAX_CATALOG_BYTES + 1));
                    if (bytes.length > MAX_CATALOG_BYTES) throw new IOException("Selected signed catalog exceeds its bound");
                    envelope = mapper.readValue(bytes, CatalogEnvelope.class);
                }
                var verified = verifiedCatalog(envelope);
                var size = Files.size(selectedArchive);
                var hash = hashFile(selectedArchive);
                var matching = verified.stream().filter(candidate -> candidate.downloadBytes() == size && candidate.sha256().equalsIgnoreCase(hash)).toList();
                if (matching.isEmpty() || matching.stream().map(FeaturePackDescriptor::id).distinct().count() != 1)
                    throw new IOException("Selected archive does not identify exactly one approved non-AI pack");
                descriptor = matching.stream().max((a, b) -> SemanticVersion.compare(a.version(), b.version())).orElseThrow();
                id = descriptor.id();
            }
            validateDescriptor(descriptor);
            if (selectedArchive == null) requireOrigin(installScope,descriptor.downloadUri());
            requireCompatible(descriptor);
            ensureManagedDirectory(root);
            Files.createDirectories(root);
            if (descriptor.downloadBytes() + descriptor.installedBytes() > Files.getFileStore(root).getUsableSpace() / 2)
                throw new IOException("Insufficient disk space for safe feature-pack installation");
            archive = root.resolve(id + ".download.partial");
            Files.deleteIfExists(archive);
            staging = root.resolve("." + id + "-" + descriptor.version() + ".staging");
            deleteTree(staging);
            update(id, selectedArchive == null ? "DOWNLOADING" : "COPYING", 0, descriptor.downloadBytes(),
                    selectedArchive == null ? "Downloading after explicit request" : "Copying explicitly selected signed pack");
            if (selectedArchive == null) download(descriptor, archive);
            else {
                org.pathlab.forge.runtime.DataRootLock.requireSafePath(selectedArchive);
                try (var input = Files.newInputStream(selectedArchive, java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    copyArchive(descriptor, input, archive, "COPYING");
                }
            }
            checkCancelled();
            update(id, "VERIFYING", descriptor.downloadBytes(), descriptor.downloadBytes(), "Verifying signed archive");
            verifyPackSignature(descriptor);
            update(id, "EXTRACTING", 0, descriptor.installedBytes(), "Extracting bounded archive");
            extract(archive, staging, descriptor.installedBytes());
            checkCancelled();
            update(id, "SELF_TEST", 0, 1, "Running bounded self-test");
            runSelfTest(staging, descriptor.entrypoint());
            mapper.writeValue(staging.resolve("installed.json").toFile(), new InstalledReceipt(descriptor, envelope));
            Files.move(archive, staging.resolve("payload.zip"));
            archive = null;
            var target = root.resolve(id).resolve(descriptor.version());
            ensureManagedDirectory(target.getParent());
            Files.createDirectories(target.getParent());
            if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                verifyInstalled(id, descriptor.version());
                deleteTree(staging);
            } else move(staging, target);
            staging = null;
            update(id, "ACTIVATING", 0, 1, "Verifying activation before switching active version");
            if (selectedArchive != null) acceptCatalog(mapper.writeValueAsBytes(envelope));
            activate(id, descriptor.version());
            update(id, "COMPLETE", 1, 1, "Installed and activated verified pack");
            return installedState(descriptor);
        } catch (IOException | RuntimeException error) {
            failure = error;
            update(id, cancelled ? "CANCELLED" : "FAILED", progress.completedBytes(), progress.totalBytes(),
                    cancelled ? "Installation cancelled; previous active version preserved" : error.getMessage());
            throw error;
        } finally {
            try {
                if (archive != null) Files.deleteIfExists(archive);
                if (staging != null) deleteTree(staging);
            } catch (IOException cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            } finally {
                downloadStream = null;
                selfTestProcess = null;
                installerThread = null;
                installing.set(false);
                installScope = null;
                if (cancelled) Thread.interrupted();
            }
        }
    }

    public synchronized void activate(String id, String version) throws IOException {
        requireId(id);
        requireVersion(version);
        checkCancelled();
        var descriptor = verifyInstalled(id, version);
        runSelfTest(root.resolve(id).resolve(version), descriptor.entrypoint());
        checkCancelled();
        var before = active.get(id);
        var previous = before == null ? "" : before.version();
        if (version.equals(previous)) previous = before.previousVersion();
        var next = new ActiveVersion(version, previous, true);
        var activationPath = root.resolve(id).resolve("active.json");
        try {
            atomicJson(activationPath,next);
            checkCancelled();
            active.put(id,next);
        } catch (IOException error) {
            // An account may change while the durable pointer is being written. Restore
            // the exact previous pointer before reporting failure to the caller.
            try { if (before == null) Files.deleteIfExists(activationPath); else atomicJson(activationPath,before); }
            catch (IOException rollback) { error.addSuppressed(rollback); }
            throw error;
        }
    }

    public synchronized void rollback(String id) throws IOException {
        requireId(id);
        var before = active.get(id);
        if (before == null || before.previousVersion().isEmpty()) throw new IOException("No verified previous version is available");
        activate(id, before.previousVersion());
    }

    public synchronized void enable(String id) throws IOException {
        requireId(id);
        var before = active.get(id);
        if (before == null) throw new IOException("Feature pack has no verified active version");
        activate(id, before.version());
    }

    public synchronized void disable(String id) throws IOException {
        requireId(id);
        var before = active.get(id);
        if (before == null) throw new IOException("Feature pack is not activated");
        var next = new ActiveVersion(before.version(), before.previousVersion(), false);
        var activationPath = root.resolve(id).resolve("active.json");
        try {
            atomicJson(activationPath,next);
            checkCancelled();
            active.put(id,next);
        } catch (IOException error) {
            // An account may change while the durable pointer is being written. Restore
            // the exact previous pointer before reporting failure to the caller.
            try { if (before == null) Files.deleteIfExists(activationPath); else atomicJson(activationPath,before); }
            catch (IOException rollback) { error.addSuppressed(rollback); }
            throw error;
        }
    }

    public synchronized void uninstall(String id) throws IOException {
        requireId(id);
        if (installing.get() && progress.id().equals(id)) throw new IOException("Cancel the running installation before uninstalling");
        // Settings and analysis results live outside this code-only tree and remain untouched.
        deleteTree(root.resolve(id));
        active.remove(id);
    }

    public boolean isInstalled(String id) {
        var current = active.get(id);
        return current != null && current.enabled();
    }

    public java.util.Optional<String> activeVersion(String id) {
        var current = active.get(id);
        return current == null ? java.util.Optional.empty() : java.util.Optional.of(current.version());
    }

    private void recoverActivation(String id) {
        var state = root.resolve(id).resolve("active.json");
        if (!physicalFile(state)) return;
        try {
            var before = mapper.readValue(state.toFile(), ActiveVersion.class);
            requireVersion(before.version());
            try {
                var descriptor = verifyInstalled(id, before.version());
                runSelfTest(root.resolve(id).resolve(before.version()), descriptor.entrypoint());
                active.put(id, before);
            } catch (IOException error) {
                requireVersion(before.previousVersion());
                var descriptor = verifyInstalled(id, before.previousVersion());
                runSelfTest(root.resolve(id).resolve(before.previousVersion()), descriptor.entrypoint());
                var restored = new ActiveVersion(before.previousVersion(), "", before.enabled());
                atomicJson(state, restored);
                active.put(id, restored);
            }
        } catch (IOException | RuntimeException ignored) { active.remove(id); }
    }

    private FeaturePackDescriptor installedState(FeaturePackDescriptor descriptor) {
        var versions = installedDescriptors().stream().filter(d -> d.id().equals(descriptor.id()))
                .map(FeaturePackDescriptor::version).toList();
        var current = active.get(descriptor.id());
        var result = descriptor;
        if (current != null) result = descriptor.withState(current.enabled() ? "INSTALLED" : "DISABLED",
                "Active version " + current.version() + (current.enabled() ? " verified" : " disabled"));
        else if (!versions.isEmpty()) result = descriptor.withState("INACTIVE", "Installed versions require verified activation");
        else if (!descriptor.version().isBlank()) {
            try { requireCompatible(descriptor); }
            catch (IOException error) { result = descriptor.withState("INCOMPATIBLE", error.getMessage()); }
        }
        return result.withInstallation(current == null ? "" : current.version(), versions);
    }

    private List<FeaturePackDescriptor> installedDescriptors() {
        var result = new ArrayList<FeaturePackDescriptor>();
        for (var id : List.of("pathology-tools", "classical-analysis")) {
            var directory = root.resolve(id);
            if (!Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            try (var versions = Files.list(directory)) {
                for (var version : versions.filter(p -> Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList()) {
                    var receipt = version.resolve("installed.json");
                    if (!physicalFile(receipt) || Files.size(receipt) > MAX_CATALOG_BYTES) continue;
                    try {
                        var installed = mapper.readValue(receipt.toFile(), InstalledReceipt.class);
                        if (verifiedCatalog(installed.envelope()).contains(installed.descriptor())
                                && installed.descriptor().id().equals(id)
                                && installed.descriptor().version().equals(version.getFileName().toString())) result.add(installed.descriptor());
                    } catch (IOException | RuntimeException ignored) { }
                }
            } catch (IOException ignored) { }
        }
        result.sort((a, b) -> SemanticVersion.compare(b.version(), a.version()));
        return result;
    }

    private FeaturePackDescriptor verifyInstalled(String id, String version) throws IOException {
        requireId(id); requireVersion(version);
        var directory = root.resolve(id).resolve(version);
        var receiptPath = directory.resolve("installed.json");
        if (!physicalFile(receiptPath) || Files.size(receiptPath) > MAX_CATALOG_BYTES) throw new IOException("Installed receipt is missing or unsafe");
        var receipt = mapper.readValue(receiptPath.toFile(), InstalledReceipt.class);
        var descriptor = receipt.descriptor();
        if (!id.equals(descriptor.id()) || !version.equals(descriptor.version())
                || !verifiedCatalog(receipt.envelope()).contains(descriptor)) throw new IOException("Installed descriptor is not signed catalog content");
        requireCompatible(descriptor);
        verifyPackSignature(descriptor);
        var archive = directory.resolve("payload.zip");
        if (!physicalFile(archive) || Files.size(archive) != descriptor.downloadBytes()
                || !hashFile(archive).equalsIgnoreCase(descriptor.sha256())) throw new IOException("Installed signed archive has changed");
        verifyExtractedArchive(archive, directory, descriptor.installedBytes());
        return descriptor;
    }

    private void update(String id, String phase, long completed, long total, String detail) {
        progress = new InstallProgress(id, phase, completed, total, detail == null ? phase : detail);
    }

    private void checkCancelled() throws IOException {
        if (Thread.currentThread() == installerThread) checkAccount(installScope);
        if (installing.get() && (cancelled || Thread.currentThread().isInterrupted())) throw new IOException("Feature installation cancelled");
    }

    private void atomicJson(Path target, Object value) throws IOException {
        ensureManagedDirectory(target.getParent());
        Files.createDirectories(target.getParent());
        var partial = target.resolveSibling(target.getFileName() + ".partial");
        Files.deleteIfExists(partial);
        try (var output = Files.newOutputStream(partial, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE)) { mapper.writeValue(output, value); }
        move(partial, target);
    }

    private void download(FeaturePackDescriptor descriptor, Path archive) throws IOException {
        var response = get(descriptor.downloadUri(),installScope);
        try (response; var input = response.body()) {
            if (response.status() != 200) throw new IOException("Feature pack download failed");
            copyArchive(descriptor, input, archive, "DOWNLOADING");
        }
    }

    private void copyArchive(FeaturePackDescriptor descriptor, InputStream input, Path archive, String phase) throws IOException {
        try (var output = Files.newOutputStream(archive, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            downloadStream = input;
            var digest = sha256Digest();
            var buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkCancelled();
                total += read;
                update(descriptor.id(), phase, total, descriptor.downloadBytes(), phase.equals("COPYING") ? "Copying selected approved pack" : "Downloading verified catalog pack");
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
        ensureManagedDirectory(staging);
        Files.createDirectories(staging);
        long total = 0;
        int entries = 0;
        var seen = new java.util.HashSet<String>();
        try (var zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("Feature pack contains too many files");
                }
                checkCancelled();
                var target = archiveTarget(staging, entry.getName(), seen);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (var output = Files.newOutputStream(target)) {
                    var buffer = new byte[64 * 1024];
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        checkCancelled();
                        total += read;
                        update(progress.id(), "EXTRACTING", total, declaredInstalledBytes, "Extracting verified pack");
                        if (total > declaredInstalledBytes || total > MAX_PACK_BYTES) {
                            throw new IOException("Feature pack exceeded its installed size");
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
        if (total == 0 || total != declaredInstalledBytes) {
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
                        "-Xmx" + (org.pathlab.forge.runtime.RuntimeProfile.system().bioFormatsHeapBytes() / (1024 * 1024)) + "m",
                        "-jar", executable.toString(), "--self-test")
                : List.of(executable.toString(), "--self-test");
        var process = org.pathlab.forge.runtime.ChildProcessContainment.global()
                .register(new ProcessBuilder(command).directory(staging.toFile())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start());
        selfTestProcess = process;
        try {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!process.waitFor(100, TimeUnit.MILLISECONDS) && System.nanoTime() < deadline) checkCancelled();
            checkCancelled();
            if (process.isAlive() || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new IOException("Feature pack self-test failed");
            }
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Feature pack self-test was interrupted", error);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                // Await handle release before renaming/removing staged jars on Windows.
                var interrupted = Thread.interrupted();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { interrupted = true; }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
            selfTestProcess = null;
        }
    }

    private void validateDescriptor(FeaturePackDescriptor descriptor) throws IOException {
        requireId(descriptor.id());
        requireVersion(descriptor.version());
        if (!List.of("pathology-tools", "classical-analysis").contains(descriptor.id())
                || !(descriptor.id().equals("pathology-tools") ? "PATHOLOGY" : "ANALYSIS").equals(descriptor.kind())
                || descriptor.pretrained() || descriptor.trainingOnly()
                || descriptor.downloadUri() == null
                || descriptor.downloadBytes() < 1 || descriptor.downloadBytes() > MAX_PACK_BYTES
                || descriptor.installedBytes() < 1 || descriptor.installedBytes() > MAX_PACK_BYTES
                || descriptor.minimumMemoryBytes() < 1 || descriptor.minimumProcessors() < 1
                || descriptor.sha256() == null || !descriptor.sha256().matches("[0-9a-fA-F]{64}")
                || descriptor.signature() == null || descriptor.signature().isBlank()
                || descriptor.entrypoint() == null || !descriptor.entrypoint().endsWith(".jar")
                || descriptor.license() == null || descriptor.license().isBlank()
                || !"APPROVED".equals(descriptor.licenseReviewStatus())
                || descriptor.platforms().isEmpty()
                || !List.of("windows-x86_64", "macos-x86_64", "macos-arm64").containsAll(descriptor.platforms())
                || !SemanticVersion.valid(descriptor.minimumCoreVersion()))
            throw new IOException("Feature pack metadata is invalid, unreviewed or outside the non-AI scope");
        archiveTarget(Path.of("safe").toAbsolutePath(), descriptor.entrypoint(), new java.util.HashSet<>());
        requireHttps(descriptor.downloadUri());
        var allowed = unpublished(descriptor.id(), descriptor.name(), descriptor.kind()).capabilities();
        if (descriptor.capabilities().isEmpty() || !allowed.containsAll(descriptor.capabilities()))
            throw new IOException("Feature pack capability scope is invalid");
    }

    private void requireCompatible(FeaturePackDescriptor descriptor) throws IOException {
        if (!descriptor.platforms().contains(org.pathlab.forge.runtime.ReaderRuntimeManifest.currentPlatform()))
            throw new IOException("Feature pack does not support this platform/architecture");
        var bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        var memory = Long.getLong("pathlab.forge.runtime.memoryBytes",
                bean instanceof com.sun.management.OperatingSystemMXBean system ? system.getTotalMemorySize() : 0L);
        if (descriptor.minimumMemoryBytes() > memory
                || descriptor.minimumProcessors() > org.pathlab.forge.runtime.RuntimeProfile.configuredLogicalProcessors())
            throw new IOException("Feature pack exceeds this machine's memory/CPU capacity");
        var core = System.getProperty("pathlab.forge.coreVersion",
                java.util.Optional.ofNullable(FeaturePackManager.class.getPackage().getImplementationVersion()).orElse("0.2.0"));
        if (!SemanticVersion.valid(core) || SemanticVersion.compare(core, descriptor.minimumCoreVersion()) < 0)
            throw new IOException("Feature pack requires core version " + descriptor.minimumCoreVersion());
    }

    private static void requireVersion(String version) throws IOException {
        if (!SemanticVersion.valid(version)) throw new IOException("Feature pack semantic version is invalid");
    }

    private void verifyPackSignature(FeaturePackDescriptor descriptor) throws IOException {
        var signed = (descriptor.id() + "\n" + descriptor.version() + "\n"
                + descriptor.sha256().toLowerCase(java.util.Locale.ROOT)).getBytes(StandardCharsets.UTF_8);
        try { verify(publicKey(), signed, Base64.getDecoder().decode(descriptor.signature())); }
        catch (IllegalArgumentException error) { throw new IOException("Feature signature encoding is invalid", error); }
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

    private NetworkScope networkScope() throws IOException {
        var transport = viewerTransport;
        if (transport == null) {
            if (client != null) return null; // Explicit injected test transport only.
            throw new IOException("Connect to verified Viewer before refreshing or installing feature packs");
        }
        var originText = viewerOrigin.origin();
        var key = transport.connectionKey();
        if (originText.isBlank() || !key.matches("[0-9a-f]{64}"))
            throw new IOException("Feature packs require verified Viewer organization, user and credential identity");
        URI origin;
        try { origin = URI.create(originText); }
        catch (IllegalArgumentException error) { throw new IOException("Viewer origin is invalid",error); }
        requireHttps(origin);
        if (origin.getRawQuery() != null || !(origin.getPath().isEmpty() || origin.getPath().equals("/")))
            throw new IOException("Viewer origin must contain only scheme, host and port");
        var scope = new NetworkScope(transport,origin,key);
        checkAccount(scope);
        return scope;
    }

    private void checkAccount(NetworkScope scope) throws IOException {
        if (scope == null) return;
        if (scope.client() != viewerTransport || !scope.key().equals(scope.client().connectionKey())
                || !scope.origin().equals(URI.create(viewerOrigin.origin())))
            throw new IOException("Viewer account changed during feature-pack operation; previous active version preserved");
    }

    private static void requireOrigin(NetworkScope scope, URI uri) throws IOException {
        requireHttps(uri);
        if (scope == null) return;
        var base = scope.origin();
        int sourcePort = base.getPort() < 0 ? 443 : base.getPort();
        int targetPort = uri.getPort() < 0 ? 443 : uri.getPort();
        if (!base.getScheme().equalsIgnoreCase(uri.getScheme()) || !base.getHost().equalsIgnoreCase(uri.getHost()) || sourcePort != targetPort)
            throw new IOException("Signed feature catalog/download URI does not match the active Viewer origin");
    }

    private org.pathlab.forge.viewer.ViewerHttpResponse get(URI uri, NetworkScope scope) throws IOException {
        requireOrigin(scope,uri);
        checkAccount(scope);
        if (scope != null) {
            var path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            return scope.client().requestBound(scope.key(),"GET",path,java.util.Map.of(),new byte[0]);
        }
        if (client == null) throw new IOException("Feature packs require authenticated Viewer transport");
        try {
            var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            return new org.pathlab.forge.viewer.ViewerHttpResponse(response.statusCode(),response.headers().map(),response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IOException("Feature catalog request was interrupted",error);
        }
    }

    private static void requireHttps(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
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
        retryFileOperation(() -> {
            try { return Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { return Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
        });
    }

    static void retryFileOperation(java.util.concurrent.Callable<?> operation) throws IOException {
        // ponytail: two-second Windows handle-release ceiling; surface longer locks for recovery.
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (true) {
            try { operation.call(); return; }
            catch (Exception error) {
                if (!(error instanceof java.nio.file.FileSystemException) || !isWindows()
                        || System.nanoTime() >= deadline) {
                    if (error instanceof IOException io) throw io;
                    if (error instanceof RuntimeException runtime) throw runtime;
                    throw new IOException("Feature file operation failed", error);
                }
                try { Thread.sleep(50); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Feature file operation cancelled", interrupted);
                }
            }
        }
    }

    private void deleteTree(Path target) throws IOException {
        if (!Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        var normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new IOException("Refusing to remove an unsafe feature-pack path");
        }
        try (var paths = Files.walk(normalized)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                retryFileOperation(() -> Files.deleteIfExists(path));
            }
        }
    }

    private void ensureManagedDirectory(Path directory) throws IOException {
        if (!directory.startsWith(root)) throw new IOException("Feature directory escaped managed storage");
        org.pathlab.forge.runtime.DataRootLock.requireSafePath(directory);
    }

    private boolean physicalFile(Path path) {
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
        for (var parent = path.getParent(); parent != null && parent.startsWith(root); parent = parent.getParent())
            if (Files.isSymbolicLink(parent)) return false;
        return true;
    }

    private static Path archiveTarget(Path root, String name, java.util.Set<String> seen) throws IOException {
        if (name == null || name.isBlank() || name.contains("\\") || name.contains(":") || name.startsWith("/")
                || name.matches(".*[<>\"|?*].*")
                || java.util.Arrays.stream(name.split("/")).anyMatch(p -> p.equals("..") || p.equals(".")
                        || p.endsWith(".") || p.endsWith(" ")
                        || p.split("\\.", 2)[0].matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")))
            throw new IOException("Feature pack contains an unsafe path");
        var target = root.resolve(name).normalize();
        var relative = root.relativize(target).toString().replace('\\', '/');
        if (!target.startsWith(root) || target.equals(root)
                || List.of("installed.json", "payload.zip", "active.json").contains(relative.toLowerCase(java.util.Locale.ROOT))
                || !seen.add(relative.toLowerCase(java.util.Locale.ROOT)))
            throw new IOException("Feature pack contains a traversal, reserved or duplicate path");
        return target;
    }

    private String hashFile(Path path) throws IOException {
        var digest = sha256Digest();
        long total = 0;
        try (var input = Files.newInputStream(path, java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            var buffer = new byte[65536];
            for (int read; (read = input.read(buffer)) != -1;) {
                checkCancelled();
                total += read;
                if (total > MAX_PACK_BYTES) throw new IOException("Feature payload exceeds its hash bound");
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void verifyExtractedArchive(Path archive, Path directory, long limit) throws IOException {
        var expected = new java.util.HashSet<Path>();
        var seen = new java.util.HashSet<String>();
        long total = 0;
        int entries = 0;
        try (var zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                checkCancelled();
                if (++entries > MAX_ARCHIVE_ENTRIES) throw new IOException("Installed archive contains too many entries");
                var target = archiveTarget(directory, entry.getName(), seen);
                if (entry.isDirectory()) continue;
                if (!physicalFile(target)) throw new IOException("Installed pack file is unsafe or missing");
                expected.add(target);
                var digest = sha256Digest();
                var buffer = new byte[65536];
                for (int read; (read = zip.read(buffer)) != -1;) {
                    checkCancelled();
                    total += read;
                    if (total > limit) throw new IOException("Installed archive exceeds declared size");
                    digest.update(buffer, 0, read);
                }
                if (!HexFormat.of().formatHex(digest.digest()).equals(hashFile(target)))
                    throw new IOException("Installed pack file hash has changed");
            }
        }
        if (total != limit) throw new IOException("Installed archive size changed");
        expected.add(directory.resolve("installed.json")); expected.add(archive);
        try (var paths = Files.walk(directory)) {
            for (var path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Installed pack contains symbolic links");
                if (Files.isRegularFile(path) && !expected.contains(path)) throw new IOException("Installed pack contains unverified files");
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private record CatalogEnvelope(String payload, String signature) {}
}

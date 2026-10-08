package org.pathlab.forge.feature;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.zip.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Synthetic signed contracts only: no real catalog, release key or licensed payload. */
final class FeaturePackManagerTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private KeyPair key;
    private String oldKey;

    @BeforeEach void key() throws Exception {
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        oldKey = System.getProperty("pathlab.forge.featureCatalogPublicKey");
        System.setProperty("pathlab.forge.featureCatalogPublicKey", Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));
    }
    @AfterEach void restore() {
        if (oldKey == null) System.clearProperty("pathlab.forge.featureCatalogPublicKey");
        else System.setProperty("pathlab.forge.featureCatalogPublicKey", oldKey);
    }

    @Test void remainsOfflineAndFailsClosedWithoutSignedContent() throws Exception {
        var manager = new FeaturePackManager(temp);
        assertEquals(2, manager.list().size());
        assertEquals(java.util.Set.of("pathology-tools", "classical-analysis"),
                manager.list().stream().map(FeaturePackDescriptor::id).collect(java.util.stream.Collectors.toSet()));
        assertThrows(IOException.class, () -> manager.install("pathology-tools"));
        assertThrows(IOException.class, () -> manager.uninstall("../outside"));
        var installed = temp.resolve("feature-packs/pathology-tools/1.0.0");
        Files.createDirectories(installed);
        Files.writeString(installed.resolve("installed.json"), "{}");
        assertFalse(new FeaturePackManager(temp).isInstalled("pathology-tools"));
    }

    @Test void freshUnpairedUserImportsSignedSelectedFilesAndRecoversEnabledPacksOffline() throws Exception {
        var zip = archive(false, null);
        var catalogFile = temp.resolve("selected-catalog.json");
        var archiveFile = temp.resolve("selected-pack.zip"); Files.write(archiveFile, zip);
        Files.write(catalogFile, envelope(List.of(descriptor("1.0.0", zip))));
        var manager = new FeaturePackManager(temp); // No Viewer, test HttpClient, identity or credential.
        assertTrue(manager.importPack(catalogFile, archiveFile).state().equals("INSTALLED"));
        assertEquals("COMPLETE", manager.progress().phase());
        assertTrue(manager.isInstalled("pathology-tools"));
        var settings = Files.writeString(temp.resolve("settings.json"), "private settings");
        var results = Files.writeString(temp.resolve("analysis-result.json"), "accepted results");
        Files.write(catalogFile, envelope(List.of(descriptor("1.0.0", zip), descriptor("1.1.0", zip))));
        manager.importPack(catalogFile, archiveFile);
        assertEquals("1.1.0", manager.activeVersion("pathology-tools").orElseThrow(), "Signed metadata determines the latest matching version");
        var restarted = new FeaturePackManager(temp);
        assertTrue(restarted.isInstalled("pathology-tools"));
        assertEquals("1.1.0", pack(restarted).version());
        restarted.disable("pathology-tools"); restarted.enable("pathology-tools"); restarted.rollback("pathology-tools");
        assertEquals("1.0.0", restarted.activeVersion("pathology-tools").orElseThrow());
        assertEquals("private settings", Files.readString(settings)); assertEquals("accepted results", Files.readString(results));
        assertArrayEquals(zip, Files.readAllBytes(archiveFile), "Selected source archive is never moved or removed");
        assertThrows(IOException.class, restarted::refresh, "Offline import grants no Viewer credentials or network privileges");
    }

    @Test void offlineImportRejectsUntrustedAndUnsafeFilesWithoutChangingActiveVersion() throws Exception {
        var good = archive(false, null); var catalogFile = temp.resolve("catalog.json"); var archiveFile = temp.resolve("pack.zip");
        Files.write(archiveFile, good); Files.write(catalogFile, envelope(List.of(descriptor("1.0.0", good))));
        var manager = new FeaturePackManager(temp); manager.importPack(catalogFile, archiveFile);
        var settings = Files.writeString(temp.resolve("settings.json"), "keep");
        var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(envelope(List.of(descriptor("2.0.0", good))));
        invalid.put("signature", Base64.getEncoder().encodeToString(new byte[64])); Files.write(catalogFile, mapper.writeValueAsBytes(invalid));
        assertThrows(IOException.class, () -> manager.importPack(catalogFile, archiveFile));
        Files.write(catalogFile, envelope(List.of(descriptor("2.0.0", good)))); Files.writeString(archiveFile, "unapproved bytes");
        assertThrows(IOException.class, () -> manager.importPack(catalogFile, archiveFile));
        for (var bad : List.of(archive(true, null), archive(false, "../outside"))) {
            Files.write(archiveFile, bad); Files.write(catalogFile, envelope(List.of(descriptor("2.0.0", bad))));
            assertThrows(IOException.class, () -> manager.importPack(catalogFile, archiveFile));
        }
        Files.write(archiveFile, good);
        var incompatible = mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(descriptor("2.0.0", good));
        incompatible.put("minimumCoreVersion", "99.0.0"); Files.write(catalogFile, envelope(List.of(mapper.treeToValue(incompatible, FeaturePackDescriptor.class))));
        assertThrows(IOException.class, () -> manager.importPack(catalogFile, archiveFile));
        var previousKey = System.getProperty("pathlab.forge.featureCatalogPublicKey"); System.clearProperty("pathlab.forge.featureCatalogPublicKey");
        try { assertThrows(IOException.class, () -> manager.importPack(catalogFile, archiveFile)); }
        finally { System.setProperty("pathlab.forge.featureCatalogPublicKey", previousKey); }
        assertEquals("1.0.0", manager.activeVersion("pathology-tools").orElseThrow()); assertEquals("keep", Files.readString(settings));
        try (var children = Files.list(temp.resolve("feature-packs"))) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString().endsWith(".partial") || path.getFileName().toString().endsWith(".staging")));
        }
    }

    @Test void persistsVerifiedCatalogOfflineAndOrdersVersionsSemantically() throws Exception {
        var zip = archive(false, null);
        var descriptors = List.of(descriptor("1.9.0", zip), descriptor("1.10.0", zip));
        var manager = new FeaturePackManager(temp);
        manager.acceptCatalog(envelope(descriptors));
        var restarted = new FeaturePackManager(temp);
        assertEquals("1.10.0", pack(restarted).version());
        var forged = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(envelope(descriptors));
        forged.put("signature", Base64.getEncoder().encodeToString(new byte[64]));
        assertThrows(IOException.class, () -> restarted.acceptCatalog(mapper.writeValueAsBytes(forged)));
        assertEquals("1.10.0", pack(new FeaturePackManager(temp)).version());
        Files.writeString(temp.resolve("feature-packs/catalog-envelope.json"), "{}");
        assertEquals("NOT_PUBLISHED", pack(new FeaturePackManager(temp)).state());
    }

    @Test void rejectsUnreviewedAiAndIncompatibleMetadataBeforeDownload() throws Exception {
        var zip = archive(false, null);
        var valid = descriptor("1.0.0", zip);
        var manager = new FeaturePackManager(temp);
        var ai = mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(valid);
        ai.put("id", "pretrained-ai");
        assertThrows(IOException.class, () -> manager.acceptCatalog(envelope(List.of(mapper.treeToValue(ai, FeaturePackDescriptor.class)))));
        var pending = mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(valid);
        pending.put("licenseReviewStatus", "PENDING_REVIEW");
        assertThrows(IOException.class, () -> manager.acceptCatalog(envelope(List.of(mapper.treeToValue(pending, FeaturePackDescriptor.class)))));
        var incompatible = mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(valid);
        incompatible.put("minimumCoreVersion", "99.0.0");
        manager.acceptCatalog(envelope(List.of(mapper.treeToValue(incompatible, FeaturePackDescriptor.class))));
        assertEquals("INCOMPATIBLE", pack(manager).state());
        assertThrows(IOException.class, () -> manager.install("pathology-tools"));
        assertFalse(Files.exists(temp.resolve("feature-packs/pathology-tools.download.partial")));
        for (var field : List.of("minimumMemoryBytes", "minimumProcessors", "platforms")) {
            var capacity = mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(valid);
            if (field.equals("minimumMemoryBytes")) capacity.put(field, Long.MAX_VALUE);
            else if (field.equals("minimumProcessors")) capacity.put(field, Integer.MAX_VALUE);
            else capacity.putArray(field).add(org.pathlab.forge.runtime.ReaderRuntimeManifest.currentPlatform()
                    .startsWith("windows-") ? "macos-arm64" : "windows-x86_64");
            manager.acceptCatalog(envelope(List.of(mapper.treeToValue(capacity, FeaturePackDescriptor.class))));
            assertEquals("INCOMPATIBLE", pack(manager).state());
            assertThrows(IOException.class, () -> manager.install("pathology-tools"));
        }
    }

    @Test void activatesOneVersionRestoresExactPreviousAndPreservesUserData() throws Exception {
        var zip = archive(false, null);
        var client = new PayloadClient(zip);
        var manager = new FeaturePackManager(temp, client);
        manager.acceptCatalog(envelope(List.of(descriptor("1.9.0", zip))));
        manager.install("pathology-tools");
        manager.acceptCatalog(envelope(List.of(descriptor("1.10.0", zip))));
        manager.install("pathology-tools");
        assertEquals("1.10.0", manager.activeVersion("pathology-tools").orElseThrow());
        assertEquals("COMPLETE", manager.progress().phase());
        manager.disable("pathology-tools");
        assertFalse(manager.isInstalled("pathology-tools"));
        var restarted = new FeaturePackManager(temp);
        assertFalse(restarted.isInstalled("pathology-tools"));
        restarted.enable("pathology-tools");
        restarted.rollback("pathology-tools");
        assertEquals("1.9.0", restarted.activeVersion("pathology-tools").orElseThrow());
        var settings = Files.writeString(temp.resolve("settings.json"), "settings");
        var results = Files.writeString(temp.resolve("analysis-result.json"), "results");
        restarted.uninstall("pathology-tools");
        assertFalse(restarted.isInstalled("pathology-tools"));
        assertEquals("settings", Files.readString(settings));
        assertEquals("results", Files.readString(results));
    }

    @Test void failedActivationAndRestartTamperingKeepVerifiedPreviousVersion() throws Exception {
        var good = archive(false, null);
        var client = new PayloadClient(good);
        var manager = new FeaturePackManager(temp, client);
        manager.acceptCatalog(envelope(List.of(descriptor("1.0.0", good))));
        manager.install("pathology-tools");
        var failed = archive(true, null);
        client.bytes = failed;
        manager.acceptCatalog(envelope(List.of(descriptor("1.1.0", failed))));
        assertThrows(IOException.class, () -> manager.install("pathology-tools"));
        assertEquals("1.0.0", manager.activeVersion("pathology-tools").orElseThrow());
        client.bytes = good;
        manager.acceptCatalog(envelope(List.of(descriptor("1.2.0", good))));
        manager.install("pathology-tools");
        Files.writeString(temp.resolve("feature-packs/pathology-tools/1.2.0/self-test.jar"), "tampered");
        var restarted = new FeaturePackManager(temp);
        assertEquals("1.0.0", restarted.activeVersion("pathology-tools").orElseThrow());
        assertTrue(restarted.isInstalled("pathology-tools"));
    }

    @Test void rejectsSignedTraversalDuplicateAndHashTampering() throws Exception {
        for (var unsafe : List.of("../escape", "NUL.txt", "self-test.jar")) {
            var zip = archive(false, unsafe);
            var manager = new FeaturePackManager(temp.resolve(UUID.randomUUID().toString()), new PayloadClient(zip));
            manager.acceptCatalog(envelope(List.of(descriptor("1.0.0", zip))));
            assertThrows(IOException.class, () -> manager.install("pathology-tools"));
            assertFalse(manager.isInstalled("pathology-tools"));
        }
        var good = archive(false, null);
        var client = new PayloadClient(good);
        var manager = new FeaturePackManager(temp.resolve("hash"), client);
        manager.acceptCatalog(envelope(List.of(descriptor("1.0.0", good))));
        client.bytes = new byte[good.length];
        assertThrows(IOException.class, () -> manager.install("pathology-tools"));
    }

    @Test void cancellationIsCallableDuringDownloadAndLeavesNoPartialInstall() throws Exception {
        var zip = archive(false, null);
        var client = new PayloadClient(zip);
        var manager = new FeaturePackManager(temp, client);
        manager.acceptCatalog(envelope(List.of(descriptor("1.0.0", zip))));
        manager.install("pathology-tools");
        client.block = true;
        client.entered = new CountDownLatch(1);
        manager.acceptCatalog(envelope(List.of(descriptor("1.1.0", zip))));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> manager.install("pathology-tools"));
            assertTrue(client.entered.await(5, TimeUnit.SECONDS));
            assertEquals("DOWNLOADING", manager.progress().phase());
            manager.cancelInstall("pathology-tools");
            assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertEquals("CANCELLED", manager.progress().phase());
            assertTrue(manager.isInstalled("pathology-tools"));
            assertEquals("1.0.0", manager.activeVersion("pathology-tools").orElseThrow());
            assertFalse(Files.exists(temp.resolve("feature-packs/pathology-tools.download.partial")));
        } finally { executor.shutdownNow(); }
    }

    @Test void windowsHandleReleaseRetryIsBoundedAndRejectsOtherErrors() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        FeaturePackManager.retryFileOperation(() -> {
            if (attempts.incrementAndGet() < 3) throw new FileSystemException("staged.jar", null, "sharing lock");
            return null;
        });
        assertEquals(3, attempts.get());
        attempts.set(0);
        assertThrows(IOException.class, () -> FeaturePackManager.retryFileOperation(() -> {
            attempts.incrementAndGet(); throw new IOException("not a sharing violation");
        }));
        assertEquals(1, attempts.get());
    }

    @Test void semanticVersionPrecedenceIncludesPrereleaseAndBuildMetadata() {
        assertTrue(SemanticVersion.compare("1.10.0", "1.9.0") > 0);
        assertTrue(SemanticVersion.compare("1.0.0-rc.10", "1.0.0-rc.9") > 0);
        assertTrue(SemanticVersion.compare("1.0.0", "1.0.0-rc.10") > 0);
        assertEquals(0, SemanticVersion.compare("1.0.0+abc", "1.0.0+def"));
        assertFalse(SemanticVersion.valid("1.0.0-rc.01"));
    }

    @Test void productionTransportRequiresCredentialAndExactConfiguredAndSignedOrigin() throws Exception {
        var zip = archive(false,null); var catalog = envelope(List.of(descriptor("1.0.0",zip)));
        var transport = new BoundTransport(catalog,zip); var manager = new FeaturePackManager(temp);
        manager.setViewerTransport(transport,() -> transport.key.isBlank() ? "" : "https://packs.example.test");
        transport.key="";
        assertThrows(IOException.class,manager::refresh); assertEquals(0,transport.requests);
        transport.key="a".repeat(64);
        var previous = System.getProperty("pathlab.forge.featureCatalogUrl");
        try {
            System.setProperty("pathlab.forge.featureCatalogUrl","https://other.example.test/catalog");
            assertThrows(IOException.class,manager::refresh); assertEquals(0,transport.requests);
            System.clearProperty("pathlab.forge.featureCatalogUrl");
            manager.setViewerTransport(transport,() -> "https://other.example.test");
            assertThrows(IOException.class,manager::refresh); assertEquals(1,transport.requests);
            assertFalse(Files.exists(temp.resolve("feature-packs/catalog-envelope.json")));
            manager.setViewerTransport(transport,() -> "https://packs.example.test");
            manager.refresh(); assertEquals("/api/v2/forge/features/catalog",transport.path);
            assertEquals("1.0.0",pack(manager).version());
        } finally { if(previous==null)System.clearProperty("pathlab.forge.featureCatalogUrl");else System.setProperty("pathlab.forge.featureCatalogUrl",previous); }
    }

    @Test void accountChangeDuringCatalogBodyCannotReplaceVerifiedOfflineCatalog() throws Exception {
        var zip = archive(false,null); var manager = new FeaturePackManager(temp);
        manager.acceptCatalog(envelope(List.of(descriptor("1.0.0",zip))));
        var transport = new BoundTransport(envelope(List.of(descriptor("1.1.0",zip))),zip);
        transport.changeDuringBody=true; manager.setViewerTransport(transport,() -> "https://packs.example.test");
        assertThrows(IOException.class,manager::refresh);
        assertEquals("1.0.0",pack(manager).version()); assertEquals("1.0.0",pack(new FeaturePackManager(temp)).version());
    }

    @Test void accountChangeDuringDownloadPreservesExactActiveVersion() throws Exception {
        var zip=archive(false,null); var transport=new BoundTransport(envelope(List.of(descriptor("1.0.0",zip))),zip);
        var manager=new FeaturePackManager(temp); manager.setViewerTransport(transport,() -> "https://packs.example.test");
        manager.refresh(); manager.install("pathology-tools");
        manager.acceptCatalog(envelope(List.of(descriptor("1.1.0",zip)))); transport.changeDuringBody=true;
        assertThrows(IOException.class,() -> manager.install("pathology-tools"));
        assertEquals("1.0.0",manager.activeVersion("pathology-tools").orElseThrow());
        assertEquals("1.0.0",new FeaturePackManager(temp).activeVersion("pathology-tools").orElseThrow());
        assertFalse(Files.exists(temp.resolve("feature-packs/pathology-tools.download.partial")));
    }

    private static final class BoundTransport implements org.pathlab.forge.viewer.ViewerAuthorizedClient {
        String key="a".repeat(64),path=""; int requests; boolean changeDuringBody;
        final byte[] catalog,zip;
        BoundTransport(byte[] catalog,byte[] zip) { this.catalog=catalog;this.zip=zip; }
        public String connectionKey() { return key; }
        public org.pathlab.forge.viewer.ViewerHttpResponse request(String method,String path,Map<String,String> headers,byte[] body) {
            this.path=path;requests++;
            assertEquals("GET",method);
            byte[] bytes=path.equals("/api/v2/forge/features/catalog") ? catalog : zip;
            InputStream stream=new ByteArrayInputStream(bytes) {
                @Override public synchronized int read(byte[] target,int start,int length) {
                    int read=super.read(target,start,length); if(changeDuringBody) key="b".repeat(64); return read;
                }
            };
            return new org.pathlab.forge.viewer.ViewerHttpResponse(200,Map.of(),stream);
        }
    }

    private FeaturePackDescriptor pack(FeaturePackManager manager) {
        return manager.list().stream().filter(p -> p.id().equals("pathology-tools")).findFirst().orElseThrow();
    }
    private FeaturePackDescriptor descriptor(String version, byte[] zip) throws Exception {
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip));
        return new FeaturePackDescriptor("pathology-tools", version, "Synthetic test pack", "PATHOLOGY", "AVAILABLE",
                zip.length, installedBytes(zip), 1, 1, false, false, "TEST_ONLY_NOT_A_LICENSE_DECISION",
                URI.create("https://packs.example.test/synthetic.zip"), hash,
                sign(("pathology-tools\n" + version + "\n" + hash).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "self-test.jar", List.of("pathology.he"), "Synthetic contract fixture", List.of(
                    org.pathlab.forge.runtime.ReaderRuntimeManifest.currentPlatform()), "0.1.0", "APPROVED", "", List.of());
    }
    private byte[] envelope(List<FeaturePackDescriptor> descriptors) throws Exception {
        var payload = mapper.writeValueAsBytes(descriptors);
        return mapper.writeValueAsBytes(Map.of("payload", Base64.getEncoder().encodeToString(payload), "signature", sign(payload)));
    }
    private String sign(byte[] payload) throws Exception {
        var signer = Signature.getInstance("Ed25519"); signer.initSign(key.getPrivate()); signer.update(payload);
        return Base64.getEncoder().encodeToString(signer.sign());
    }
    private long installedBytes(byte[] archive) throws Exception {
        long total = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) total += zip.readAllBytes().length;
        }
        return total;
    }
    private byte[] archive(boolean fail, String extra) throws Exception {
        var jarBytes = new ByteArrayOutputStream();
        var manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", PackSelfTest.class.getName());
        try (var jar = new JarOutputStream(jarBytes, manifest)) {
            var name = PackSelfTest.class.getName().replace('.', '/') + ".class";
            jar.putNextEntry(new JarEntry(name));
            try (var resource = PackSelfTest.class.getResourceAsStream("/" + name)) { resource.transferTo(jar); }
            jar.closeEntry();
        }
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("self-test.jar")); zip.write(jarBytes.toByteArray()); zip.closeEntry();
            if (fail) { zip.putNextEntry(new ZipEntry("fail-self-test")); zip.write(1); zip.closeEntry(); }
            if (extra != null) {
                // Case alias also duplicates on Windows and must fail on every platform.
                zip.putNextEntry(new ZipEntry(extra.equals("self-test.jar") ? "SELF-TEST.JAR" : extra));
                zip.write(1); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    public static final class PackSelfTest {
        public static void main(String[] args) {
            if (Files.exists(Path.of("fail-self-test"))) System.exit(1);
        }
    }
    private static final class PayloadClient extends HttpClient {
        volatile byte[] bytes;
        volatile boolean block;
        volatile CountDownLatch entered = new CountDownLatch(1);
        PayloadClient(byte[] bytes) { this.bytes = bytes; }
        @Override @SuppressWarnings("unchecked") public <T> HttpResponse<T> send(HttpRequest request,
                HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
            entered.countDown();
            if (block) Thread.sleep(30_000);
            return (HttpResponse<T>) new HttpResponse<InputStream>() {
                public int statusCode() { return 200; }
                public HttpRequest request() { return request; }
                public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
                public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
                public InputStream body() { return new ByteArrayInputStream(bytes); }
                public Optional<SSLSession> sslSession() { return Optional.empty(); }
                public URI uri() { return request.uri(); }
                public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
            };
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) { throw new UnsupportedOperationException(); }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { throw new UnsupportedOperationException(); }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<Duration> connectTimeout() { return Optional.empty(); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        public SSLParameters sslParameters() { return new SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public Optional<Executor> executor() { return Optional.empty(); }
    }
}

package org.pathlab.forge.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Sanitized runtime state for the local Format Center and packaged self-test. */
public final class ReaderRuntimeInventory {
    public record Status(
            String id,
            boolean available,
            String source,
            String version,
            String fingerprint,
            String platform,
            String diagnosticCode,
            String detail) {}

    private ReaderRuntimeInventory() {}

    public static List<Status> inspect(Path dataRoot) {
        var packaged = packaged(dataRoot);
        if (!packaged.isEmpty()) {
            var result = new ArrayList<>(packaged);
            result.add(builtInMds());
            return List.copyOf(result);
        }
        var result = new ArrayList<Status>();
        result.add(external("bioformats", "8.5.x", candidate(
                "pathlab.forge.bftools", "PATHLAB_FORGE_BFTOOLS",
                dataRoot.resolve("runtime/bftools")), "bioformats_package.jar"));
        result.add(external("libvips", "8.18.x", candidate(
                "pathlab.forge.vips", "PATHLAB_FORGE_VIPS",
                dataRoot.resolve("runtime/vips")), windows() ? "bin/vips.exe" : "bin/vips"));
        result.add(external("sdpc", "owner-supplied", candidate(
                "pathlab.forge.sdpcRuntime", "PATHLAB_FORGE_SDPC_RUNTIME",
                dataRoot.resolve("runtime/sdpc")), "DecodeSdpcDll.dll"));
        result.add(external("libisyntax", "pyisyntax 0.1.6", candidate(
                "pathlab.forge.isyntaxRuntime", "PATHLAB_FORGE_ISYNTAX_RUNTIME",
                dataRoot.resolve("runtime/isyntax")), "isyntax/__init__.py"));
        result.add(builtInMds());
        return List.copyOf(result);
    }

    private static List<Status> packaged(Path dataRoot) {
        for (var candidate : ReaderRuntimeLocator.candidateDataRoots(dataRoot)) {
            var found = packagedAt(candidate);
            if (!found.isEmpty()) return found;
        }
        return List.of();
    }

    private static List<Status> packagedAt(Path dataRoot) {
        var runtime = dataRoot.resolve("runtime");
        var pointer = runtime.resolve("readers-current.txt");
        if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try {
            var fingerprint = Files.readString(pointer).trim().toLowerCase(Locale.ROOT);
            if (!fingerprint.matches("[0-9a-f]{64}")) {
                return List.of(invalid("MANIFEST_INVALID", "Reader runtime activation pointer is invalid"));
            }
            var root = runtime.resolve("readers").resolve(fingerprint).normalize();
            var manifest = ReaderRuntimeManifest.read(root.resolve("reader-runtime-manifest.json"));
            if (!ReaderRuntimeManifest.currentPlatform().equals(manifest.platform())) {
                return List.of(invalid("WRONG_ARCHITECTURE", "Reader runtime targets another platform"));
            }
            manifest.verify(root, Boolean.getBoolean("pathlab.forge.runtime.requireProduction"));
            return manifest.components().stream().map(component -> {
                var pending = component.included() && !"APPROVED".equals(component.reviewStatus());
                return new Status(component.id(), component.included(), "PACKAGED", component.version(),
                        manifest.fingerprint(), manifest.platform(),
                        component.included() ? pending ? "LICENSE_PENDING" : "AVAILABLE" : "NOT_INSTALLED",
                        component.included()
                                ? pending ? "Installed for internal validation; redistribution review is pending"
                                        : "Installed and verified"
                                : "Component is not present in this runtime package");
            }).toList();
        } catch (IOException | RuntimeException error) {
            return List.of(invalid("MANIFEST_INVALID", "Reader runtime manifest or files failed verification"));
        }
    }

    private static Status external(String id, String version, Path root, String requiredFile) {
        if (root == null) return unavailable(id, version);
        var direct = root.resolve(requiredFile.replace('/', java.io.File.separatorChar));
        var file = Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS) ? root : direct;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return unavailable(id, version);
        return new Status(id, true, "EXTERNAL", version, fingerprint(file),
                ReaderRuntimeManifest.currentPlatform(), "LICENSE_PENDING",
                "Owner-supplied local runtime; redistribution is not authorized");
    }

    private static Path candidate(String property, String environment, Path fallback) {
        var configured = System.getProperty(property, "").trim();
        if (configured.isEmpty()) configured = System.getenv(environment);
        if (configured != null && !configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        return fallback.toAbsolutePath().normalize();
    }

    private static Status builtInMds() {
        return new Status("motic-mds", true, "BUILT_IN", "motic-mds-v1",
                sha256("motic-mds-v1"), ReaderRuntimeManifest.currentPlatform(), "AVAILABLE",
                "Built into PathLab Forge");
    }

    private static Status unavailable(String id, String version) {
        return new Status(id, false, "NONE", version, "",
                ReaderRuntimeManifest.currentPlatform(), "NOT_INSTALLED",
                "Reader runtime component is not installed");
    }

    private static Status invalid(String code, String detail) {
        return new Status("reader-runtime", false, "PACKAGED", "unknown", "",
                ReaderRuntimeManifest.currentPlatform(), code, detail);
    }

    private static String fingerprint(Path file) {
        try (var input = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException ignored) { return ""; }
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}

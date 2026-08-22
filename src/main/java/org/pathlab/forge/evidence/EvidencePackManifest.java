package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Validated, immutable description of one locally installed Evidence Mentor pack. */
public record EvidencePackManifest(
        Path path,
        String packId,
        String version,
        Capability capability,
        Set<String> acceptedStains,
        String preprocessingId,
        int tilePixels,
        String allowedUse,
        boolean redistributable,
        boolean derivativesAllowed,
        boolean acceptanceOnly,
        RuntimeCompatibility runtimeCompatibility,
        List<LicenseEntry> licenseLedger,
        int maxRamMiB,
        int maxVramMiB,
        int maxSeconds,
        ValidationStatus validationStatus,
        String sha256,
        List<Artifact> artifacts,
        Set<String> markers) {
    public static final String SCHEMA = "pathlab.ai-pack/1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,119}");
    private static final Pattern SHA = Pattern.compile("[a-f0-9]{64}");
    private static final Set<String> STAINS = Set.of("he", "ihc_dab");
    private static final Set<String> MARKERS = Set.of("generic", "er", "pr", "ki-67", "her2", "pd-l1");

    public static EvidencePackManifest load(Path manifest) throws IOException {
        var normalized = manifest.toAbsolutePath().normalize();
        var bytes = Files.readAllBytes(normalized);
        var root = JSON.readTree(bytes);
        require(root.isObject(), "AI pack must be a JSON object");
        require(SCHEMA.equals(text(root, "schema")), "AI pack schema is unsupported");
        var packId = text(root, "packId");
        require(ID.matcher(packId).matches(), "AI pack id is invalid");
        var version = text(root, "version");
        require(version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"), "AI pack version is invalid");
        var capability = Capability.fromWire(text(root, "capability"));
        var acceptedStains = stringSet(root.path("acceptedStains"), STAINS, "accepted stain");
        require(!acceptedStains.isEmpty(), "AI pack requires an accepted stain");
        var preprocessing = object(root, "preprocessing");
        var preprocessingId = text(preprocessing, "id");
        require(preprocessingId.length() <= 120, "AI pack preprocessing id is invalid");
        var tilePixels = boundedInt(preprocessing, "tilePixels", 64, 2_048);
        var rights = object(root, "rights");
        var allowedUse = text(rights, "allowedUse");
        require(Set.of("private-research", "benchmark-only").contains(allowedUse), "AI pack allowed use is invalid");
        var resources = object(root, "resourceEnvelope");
        var maxRam = boundedInt(resources, "maxRamMiB", 64, 16_384);
        var maxVram = boundedInt(resources, "maxVramMiB", 0, 4_608);
        var maxSeconds = boundedInt(resources, "maxSeconds", 1, 1_200);
        require(resources.has("network") && !resources.path("network").asBoolean(true),
                "Analysis packs must be offline");
        var validation = object(root, "validation");
        var status = ValidationStatus.fromWire(text(validation, "status"));
        var acceptanceOnly = false;
        if (root.has("usageLimits")) {
            var usageLimits = object(root, "usageLimits");
            require(usageLimits.size() == 1 && usageLimits.path("acceptanceOnly").isBoolean(),
                    "AI pack usage limits are invalid");
            acceptanceOnly = usageLimits.path("acceptanceOnly").booleanValue();
        }
        var artifacts = parseArtifacts(root.path("artifacts"));
        var markers = root.has("markers")
                ? stringSet(root.path("markers"), MARKERS, "marker")
                : Set.<String>of();
        if (capability == Capability.IHC_DESCRIPTIVE) {
            require(markers.contains("generic"), "IHC packs must include the generic marker");
        } else {
            require(markers.isEmpty(), "Only IHC packs may declare markers");
        }
        var runtime = root.has("runtimeCompatibility")
                ? parseRuntime(root.path("runtimeCompatibility"))
                : new RuntimeCompatibility("java17", "cpu", "none", "none", false);
        if (acceptanceOnly) {
            require("benchmark-only".equals(allowedUse)
                            && status == ValidationStatus.NOT_EVALUABLE
                            && runtime.requiresExternalWorker(),
                    "Acceptance-only packs must remain benchmark-only, not-evaluable external workers");
        }
        var licenseLedger = root.has("licenseLedger")
                ? parseLicenseLedger(root.path("licenseLedger")) : List.<LicenseEntry>of();
        return new EvidencePackManifest(
                normalized, packId, version, capability, acceptedStains, preprocessingId, tilePixels, allowedUse,
                booleanValue(rights, "redistributable"),
                booleanValue(rights, "derivativesAllowed"),
                acceptanceOnly,
                runtime, licenseLedger,
                maxRam, maxVram, maxSeconds, status, sha256(bytes), artifacts, markers);
    }

    public boolean pilotEligible() {
        return !acceptanceOnly && "private-research".equals(allowedUse)
                && validationStatus == ValidationStatus.QUALIFIED;
    }

    public void requirePilotEligible() {
        require(pilotEligible(), "AI pack is not eligible for the private pilot");
    }

    public void requireExecutableForJob(String jobId) {
        if (acceptanceOnly) {
            require(jobId != null && jobId.matches("acceptance-[a-f0-9]{8,64}"),
                    "Acceptance-only AI pack requires a non-identifying acceptance job id");
            return;
        }
        if (jobId != null && jobId.matches("qualification-[a-f0-9]{8,64}")) {
            require("private-research".equals(allowedUse)
                            && validationStatus == ValidationStatus.EXPERIMENTAL,
                    "AI pack is not eligible for qualification execution");
            return;
        }
        requirePilotEligible();
    }

    public void requireStain(String stain) {
        require(acceptedStains.contains(stain), "AI pack does not support stain " + stain);
    }

    private static List<Artifact> parseArtifacts(JsonNode node) {
        require(node.isArray() && node.size() <= 16, "AI pack artifacts are invalid");
        var result = new java.util.ArrayList<Artifact>();
        for (var item : node) {
            var name = text(item, "name");
            var hash = text(item, "sha256");
            var source = text(item, "source");
            require(name.length() <= 160 && SHA.matcher(hash).matches()
                    && source.length() <= 1_000, "AI pack artifact is invalid");
            result.add(new Artifact(name, hash, source));
        }
        return List.copyOf(result);
    }

    private static RuntimeCompatibility parseRuntime(JsonNode node) {
        require(node.isObject(), "AI pack runtime compatibility is invalid");
        var protocol = text(node, "workerProtocol");
        var provider = text(node, "executionProvider");
        var cuda = text(node, "cuda");
        var architecture = text(node, "gpuArchitecture");
        require(protocol.length() <= 80 && Set.of("cpu", "cuda").contains(provider)
                        && cuda.length() <= 40 && architecture.length() <= 40,
                "AI pack runtime compatibility is invalid");
        return new RuntimeCompatibility(protocol, provider, cuda, architecture,
                node.path("requiresExternalWorker").asBoolean(false));
    }

    private static List<LicenseEntry> parseLicenseLedger(JsonNode node) {
        require(node.isArray() && node.size() <= 32, "AI pack license ledger is invalid");
        var result = new java.util.ArrayList<LicenseEntry>();
        for (var item : node) {
            var component = text(item, "component");
            var license = text(item, "license");
            var revision = text(item, "revision");
            var permittedUse = text(item, "permittedUse");
            require(component.length() <= 160 && license.length() <= 200 && revision.length() <= 160
                            && Set.of("private-research", "benchmark-only").contains(permittedUse),
                    "AI pack license ledger is invalid");
            result.add(new LicenseEntry(component, license, revision, permittedUse,
                    item.path("redistributable").asBoolean(false),
                    item.path("derivativesAllowed").asBoolean(false)));
        }
        return List.copyOf(result);
    }

    private static Set<String> stringSet(JsonNode node, Set<String> allowed, String label) {
        require(node.isArray() && node.size() <= 16, "AI pack " + label + " list is invalid");
        var result = new java.util.LinkedHashSet<String>();
        for (var item : node) {
            require(item.isTextual() && allowed.contains(item.textValue()),
                    "AI pack " + label + " is invalid");
            require(result.add(item.textValue()), "AI pack " + label + " is duplicated");
        }
        return Set.copyOf(result);
    }

    private static JsonNode object(JsonNode root, String name) {
        var value = root.path(name);
        require(value.isObject(), "AI pack " + name + " is invalid");
        return value;
    }

    private static String text(JsonNode root, String name) {
        var value = root.path(name);
        require(value.isTextual() && !value.textValue().isBlank(), "AI pack " + name + " is invalid");
        return value.textValue();
    }

    private static int boundedInt(JsonNode root, String name, int minimum, int maximum) {
        var value = root.path(name);
        require(value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue() >= minimum && value.intValue() <= maximum,
                "AI pack " + name + " is outside the supported resource envelope");
        return value.intValue();
    }

    private static boolean booleanValue(JsonNode root, String name) {
        var value = root.path(name);
        require(value.isBoolean(), "AI pack " + name + " is invalid");
        return value.booleanValue();
    }

    private static String sha256(byte[] value) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record Artifact(String name, String sha256, String source) {}
    public record RuntimeCompatibility(String workerProtocol, String executionProvider, String cuda,
            String gpuArchitecture, boolean requiresExternalWorker) {}
    public record LicenseEntry(String component, String license, String revision, String permittedUse,
            boolean redistributable, boolean derivativesAllowed) {}

    public enum Capability {
        HE_EVIDENCE("he-evidence"),
        CELL_MORPHOLOGY("cell-morphology"),
        IHC_DESCRIPTIVE("ihc-descriptive");

        private final String wire;
        Capability(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        static Capability fromWire(String value) {
            return java.util.Arrays.stream(values()).filter(item -> item.wire.equals(value)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("AI pack capability is invalid"));
        }
    }

    public enum ValidationStatus {
        EXPERIMENTAL("experimental"), QUALIFIED("qualified"), NOT_EVALUABLE("not-evaluable"),
        UNSUPPORTED("unsupported"), BLOCKED("blocked");
        private final String wire;
        ValidationStatus(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        static ValidationStatus fromWire(String value) {
            return java.util.Arrays.stream(values()).filter(item -> item.wire.equals(value)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("AI pack validation status is invalid"));
        }
    }
}

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
        String schema,
        String packId,
        String version,
        Capability capability,
        Set<String> acceptedStains,
        String preprocessingId,
        int tilePixels,
        String allowedUse,
        String scope,
        boolean activationEligible,
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
    public static final String SCHEMA_V2 = "pathlab.ai-pack/2";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,119}");
    private static final Pattern SHA = Pattern.compile("[a-f0-9]{64}");
    private static final Set<String> STAINS = Set.of("he", "ihc_dab", "pas", "pas_d", "trichrome",
            "gms", "afb", "papanicolaou", "generic_brightfield");
    private static final Set<String> MARKERS = Set.of("generic", "er", "pr", "ki-67", "her2", "pd-l1");

    public static EvidencePackManifest load(Path manifest) throws IOException {
        var normalized = manifest.toAbsolutePath().normalize();
        var bytes = Files.readAllBytes(normalized);
        var root = JSON.readTree(bytes);
        require(root.isObject(), "AI pack must be a JSON object");
        var schema = text(root, "schema");
        require(Set.of(SCHEMA, SCHEMA_V2).contains(schema), "AI pack schema is unsupported");
        if (SCHEMA_V2.equals(schema)) return loadV2(normalized, bytes, root);
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
                normalized, SCHEMA, packId, version, capability, acceptedStains, preprocessingId, tilePixels, allowedUse,
                "benchmark-only".equals(allowedUse) ? "local-benchmark" : "deployment",
                false,
                booleanValue(rights, "redistributable"),
                booleanValue(rights, "derivativesAllowed"),
                acceptanceOnly,
                runtime, licenseLedger,
                maxRam, maxVram, maxSeconds, status, sha256(bytes), artifacts, markers);
    }

    private static EvidencePackManifest loadV2(Path normalized, byte[] bytes, JsonNode root) throws IOException {
        exact(root, Set.of("schema", "packId", "version", "capability", "scope", "activationEligible",
                "acceptedStains", "preprocessing", "artifacts", "runtimeCompatibility", "licenseLedger",
                "derivativePermissions", "resourceEnvelope", "qualificationPolicy", "outputSchema", "markers"));
        var packId = text(root, "packId");
        require(ID.matcher(packId).matches(), "AI pack id is invalid");
        var version = text(root, "version");
        require(version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"), "AI pack version is invalid");
        var capability = Capability.fromWire(text(root, "capability"));
        var scope = text(root, "scope");
        require(Set.of("deployment", "research-restricted", "local-benchmark").contains(scope),
                "AI pack scope is invalid");
        var activationEligible = booleanValue(root, "activationEligible");
        require(!("local-benchmark".equals(scope) && activationEligible),
                "Benchmark-only packs cannot be activation eligible");
        var stains = stringSet(root.path("acceptedStains"), STAINS, "accepted stain");
        require(!stains.isEmpty(), "AI pack requires an accepted stain");
        var preprocessing = object(root, "preprocessing");
        var preprocessingId = text(preprocessing, "id");
        var tilePixels = boundedInt(preprocessing, "tilePixels", 64, 2_048);
        var resources = object(root, "resourceEnvelope");
        exact(resources, Set.of("lane", "maxRamMiB", "maxVramMiB", "maxSeconds", "network"));
        require(Set.of("gpu", "cpu_io", "external").contains(text(resources, "lane")),
                "AI pack lane is invalid");
        var maxRam = boundedInt(resources, "maxRamMiB", 64, 16_384);
        var maxVram = boundedInt(resources, "maxVramMiB", 0, 4_608);
        var maxSeconds = boundedInt(resources, "maxSeconds", 1, 1_200);
        require(!resources.path("network").asBoolean(true), "Analysis packs must be offline");
        var runtime = parseRuntimeV2(root.path("runtimeCompatibility"));
        var ledger = parseLicenseLedgerV2(root.path("licenseLedger"));
        var permissions = object(root, "derivativePermissions");
        exact(permissions, Set.of("atlasResearch", "atlasClean", "reviewedAt"));
        require(permissions.path("atlasResearch").isBoolean() && permissions.path("atlasClean").isBoolean(),
                "AI pack derivative permissions are invalid");
        var policy = object(root, "qualificationPolicy");
        exact(policy, Set.of("requiresSignedAttestation", "protocolSha256"));
        require(policy.path("requiresSignedAttestation").asBoolean(false)
                        && text(policy, "protocolSha256").matches("[a-f0-9]{64}"),
                "AI pack qualification policy is invalid");
        require(Set.of("pathlab.ai-evidence/2", "pathlab.model-qualification-report/2",
                "pathlab.model-worker-result/2").contains(text(root, "outputSchema")),
                "AI pack output schema is invalid");
        var artifacts = parseArtifactsV2(root.path("artifacts"));
        var markers = stringSet(root.path("markers"), MARKERS, "marker");
        if (capability == Capability.IHC_DESCRIPTIVE) require(markers.contains("generic"),
                "IHC packs must include the generic marker");
        else require(markers.isEmpty(), "Only IHC packs may declare markers");
        var allowedUse = switch (scope) {
            case "deployment" -> "private-research";
            case "research-restricted" -> "research-restricted";
            default -> "benchmark-only";
        };
        return new EvidencePackManifest(normalized, SCHEMA_V2, packId, version, capability, stains,
                preprocessingId, tilePixels, allowedUse, scope, activationEligible, false,
                permissions.path("atlasResearch").asBoolean(false), false, runtime, ledger,
                maxRam, maxVram, maxSeconds, ValidationStatus.NOT_EVALUABLE, sha256(bytes), artifacts, markers);
    }

    public boolean pilotEligible() {
        return SCHEMA.equals(schema) && !acceptanceOnly && "private-research".equals(allowedUse)
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
            if (SCHEMA_V2.equals(schema)) return;
            require("private-research".equals(allowedUse)
                            && validationStatus == ValidationStatus.EXPERIMENTAL,
                    "AI pack is not eligible for qualification execution");
            return;
        }
        requirePilotEligible();
    }

    public void requireExecutableForJob(String jobId, QualifiedPackRegistry registry,
            String qualificationAttestationSha256) throws IOException {
        if (!SCHEMA_V2.equals(schema) || (jobId != null
                && jobId.matches("qualification-[a-f0-9]{8,64}"))) {
            requireExecutableForJob(jobId);
            return;
        }
        require(activationEligible && !"local-benchmark".equals(scope),
                "AI pack candidate is not activation eligible");
        registry.requireQualified(this, qualificationAttestationSha256);
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

    private static List<Artifact> parseArtifactsV2(JsonNode node) {
        require(node.isArray() && node.size() <= 32, "AI pack artifacts are invalid");
        var result = new java.util.ArrayList<Artifact>();
        for (var item : node) {
            exact(item, Set.of("name", "sha256", "source"));
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

    private static RuntimeCompatibility parseRuntimeV2(JsonNode node) {
        exact(node, Set.of("workerProtocol", "executionProvider", "cuda", "gpuArchitecture",
                "requiresExternalWorker", "segmentMaxSeconds"));
        var protocol = text(node, "workerProtocol");
        var provider = text(node, "executionProvider");
        require(Set.of("pathlab.model-worker-result/1", "pathlab.model-worker-result/2").contains(protocol)
                        && Set.of("cpu", "cuda", "webgpu", "external").contains(provider),
                "AI pack runtime compatibility is invalid");
        boundedInt(node, "segmentMaxSeconds", 1, 1_200);
        return new RuntimeCompatibility(protocol, provider, text(node, "cuda"),
                text(node, "gpuArchitecture"), node.path("requiresExternalWorker").asBoolean(false));
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
            result.add(new LicenseEntry(component, "weights", license, revision, permittedUse,
                    item.path("redistributable").asBoolean(false),
                    item.path("derivativesAllowed").asBoolean(false)));
        }
        return List.copyOf(result);
    }

    private static List<LicenseEntry> parseLicenseLedgerV2(JsonNode node) {
        require(node.isArray() && !node.isEmpty() && node.size() <= 64, "AI pack license ledger is invalid");
        var result = new java.util.ArrayList<LicenseEntry>();
        for (var item : node) {
            exact(item, Set.of("component", "kind", "license", "revision", "permittedUse",
                    "redistributable", "derivativesAllowed"));
            var kind = text(item, "kind");
            var permittedUse = text(item, "permittedUse");
            require(Set.of("code", "weights", "dataset", "runtime").contains(kind)
                            && Set.of("private-research", "research-restricted", "benchmark-only").contains(permittedUse),
                    "AI pack license ledger is invalid");
            result.add(new LicenseEntry(text(item, "component"), kind, text(item, "license"),
                    text(item, "revision"), permittedUse, booleanValue(item, "redistributable"),
                    booleanValue(item, "derivativesAllowed")));
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

    private static void exact(JsonNode node, Set<String> expected) {
        require(node.isObject(), "AI pack object is invalid");
        var actual = new java.util.HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(expected), "AI pack fields are invalid");
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
    public record LicenseEntry(String component, String kind, String license, String revision, String permittedUse,
            boolean redistributable, boolean derivativesAllowed) {}

    public enum Capability {
        HE_EVIDENCE("he-evidence"),
        CELL_MORPHOLOGY("cell-morphology"),
        IHC_DESCRIPTIVE("ihc-descriptive"),
        SPECIAL_STAIN_DESCRIPTIVE("special-stain-descriptive"),
        CYTOLOGY_DESCRIPTIVE("cytology-descriptive"),
        GROUNDED_TUTOR("grounded-tutor"),
        ATLAS_DISTILLATION("atlas-distillation");

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

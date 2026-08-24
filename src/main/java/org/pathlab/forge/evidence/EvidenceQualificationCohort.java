package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Frozen patient/source-held-out cohort definition; it reports readiness but never qualifies a model. */
public record EvidenceQualificationCohort(
        Path path,
        String cohortId,
        Instant frozenAt,
        String intendedUse,
        AcceptanceCriteria acceptanceCriteria,
        List<Sample> samples) {
    public static final String SCHEMA = "pathlab.qualification-cohort/1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,119}");
    private static final Pattern SHA = Pattern.compile("[a-f0-9]{64}");
    private static final Set<String> GROUPS = Set.of(
            "breast", "gi", "lung", "lymph-node", "benign-reactive");
    private static final Set<String> SPLITS = Set.of("reference", "query", "ood");
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schema", "cohortId", "frozenAt", "intendedUse", "acceptanceCriteria", "samples");
    private static final Set<String> CRITERIA_FIELDS = Set.of(
            "requiredEvaluationGroups", "minimumReferenceSamplesPerGroup",
            "minimumQuerySamplesPerGroup", "minimumOodSamples", "maximumPatientOverlap",
            "maximumSlideOverlap", "maximumSourceOverlap", "baselineId",
            "minimumMacroRecallAt5Improvement", "minimumMacroNdcgAt10Improvement",
            "minimumOodAuRoc");
    private static final Set<String> SAMPLE_FIELDS = Set.of(
            "id", "tileCacheManifest", "tileCacheManifestSha256", "evaluationGroup",
            "phenotypeGroup", "split", "sourceGroup");

    public static EvidenceQualificationCohort load(Path manifest) throws IOException {
        var normalized = manifest.toAbsolutePath().normalize();
        var root = JSON.readTree(normalized.toFile());
        require(root.isObject() && fields(root).equals(ROOT_FIELDS),
                "Qualification cohort fields are invalid");
        require(SCHEMA.equals(text(root, "schema", 80)), "Qualification cohort schema is unsupported");
        var cohortId = text(root, "cohortId", 120);
        require(ID.matcher(cohortId).matches(), "Qualification cohort id is invalid");
        final Instant frozenAt;
        try {
            frozenAt = Instant.parse(text(root, "frozenAt", 80));
        } catch (java.time.format.DateTimeParseException error) {
            throw new IllegalArgumentException("Qualification cohort freeze timestamp is invalid", error);
        }
        var intendedUse = text(root, "intendedUse", 80);
        require("private-research-model-qualification".equals(intendedUse),
                "Qualification cohort intended use is invalid");
        var criteria = parseCriteria(root.path("acceptanceCriteria"));
        var nodes = root.path("samples");
        require(nodes.isArray() && !nodes.isEmpty() && nodes.size() <= 20_000,
                "Qualification cohort samples are invalid");
        var rootDirectory = normalized.getParent().toRealPath();
        var ids = new HashSet<String>();
        var samples = new ArrayList<Sample>();
        for (var node : nodes) {
            require(node.isObject() && fields(node).equals(SAMPLE_FIELDS),
                    "Qualification sample fields are invalid");
            var id = text(node, "id", 120);
            require(ID.matcher(id).matches() && ids.add(id),
                    "Qualification sample id is invalid or duplicated");
            var relative = Path.of(text(node, "tileCacheManifest", 500));
            require(!relative.isAbsolute(), "Qualification sample paths must be relative");
            var tileManifest = rootDirectory.resolve(relative).normalize();
            require(tileManifest.startsWith(rootDirectory) && Files.isRegularFile(tileManifest),
                    "Qualification tile cache escaped its frozen root or is unavailable");
            tileManifest = tileManifest.toRealPath();
            require(tileManifest.startsWith(rootDirectory), "Qualification tile cache escaped its frozen root");
            var tileManifestSha = text(node, "tileCacheManifestSha256", 64);
            require(SHA.matcher(tileManifestSha).matches(), "Qualification tile-cache checksum is invalid");
            var tileCache = EvidenceTileCacheManifest.load(tileManifest, tileManifestSha);
            var group = text(node, "evaluationGroup", 80);
            require(GROUPS.contains(group), "Qualification evaluation group is invalid");
            var phenotype = text(node, "phenotypeGroup", 160);
            var split = text(node, "split", 20);
            require(SPLITS.contains(split), "Qualification split is invalid");
            var sourceGroup = text(node, "sourceGroup", 160);
            samples.add(new Sample(id, tileManifest, tileManifestSha, group, phenotype,
                    split, sourceGroup, tileCache));
        }
        return new EvidenceQualificationCohort(normalized, cohortId, frozenAt, intendedUse,
                criteria, List.copyOf(samples));
    }

    public Assessment assessReadiness() {
        var reasons = new LinkedHashSet<String>();
        var referenceCounts = counts("reference");
        var queryCounts = counts("query");
        for (var group : acceptanceCriteria.requiredEvaluationGroups()) {
            if (referenceCounts.getOrDefault(group, 0) < acceptanceCriteria.minimumReferenceSamplesPerGroup()) {
                reasons.add("INSUFFICIENT_REFERENCE_" + wireReason(group));
            }
            if (queryCounts.getOrDefault(group, 0) < acceptanceCriteria.minimumQuerySamplesPerGroup()) {
                reasons.add("INSUFFICIENT_QUERY_" + wireReason(group));
            }
        }
        var ood = samples.stream().filter(sample -> "ood".equals(sample.split())).count();
        if (ood < acceptanceCriteria.minimumOodSamples()) reasons.add("INSUFFICIENT_OOD");
        if (overlap(sample -> sample.tileCache().sample().source() + "::"
                + sample.tileCache().sample().patientGroup()) > acceptanceCriteria.maximumPatientOverlap()) {
            reasons.add("PATIENT_SPLIT_OVERLAP");
        }
        if (overlap(sample -> sample.tileCache().sample().source() + "::"
                + sample.tileCache().sample().slideId()) > acceptanceCriteria.maximumSlideOverlap()) {
            reasons.add("SLIDE_SPLIT_OVERLAP");
        }
        if (overlap(Sample::sourceGroup) > acceptanceCriteria.maximumSourceOverlap()) {
            reasons.add("SOURCE_SPLIT_OVERLAP");
        }
        if (samples.stream().anyMatch(sample ->
                !"private-research".equals(sample.tileCache().sample().permittedUse()))) {
            reasons.add("UNAPPROVED_SAMPLE_RIGHTS");
        }
        return new Assessment(reasons.isEmpty() ? "ready" : "not_evaluable", List.copyOf(reasons));
    }

    private Map<String, Integer> counts(String split) {
        var result = new HashMap<String, Integer>();
        samples.stream().filter(sample -> split.equals(sample.split())).forEach(sample ->
                result.merge(sample.evaluationGroup(), 1, Integer::sum));
        return result;
    }

    private int overlap(java.util.function.Function<Sample, String> key) {
        var splitsByKey = new HashMap<String, Set<String>>();
        for (var sample : samples) {
            splitsByKey.computeIfAbsent(key.apply(sample), ignored -> new HashSet<>()).add(sample.split());
        }
        return (int) splitsByKey.values().stream().filter(splits -> splits.size() > 1).count();
    }

    private static AcceptanceCriteria parseCriteria(JsonNode node) {
        require(node.isObject() && fields(node).equals(CRITERIA_FIELDS),
                "Qualification acceptance criteria are invalid");
        var groupsNode = node.path("requiredEvaluationGroups");
        require(groupsNode.isArray(), "Qualification evaluation groups are invalid");
        var groups = new HashSet<String>();
        groupsNode.forEach(value -> {
            require(value.isTextual() && GROUPS.contains(value.textValue()) && groups.add(value.textValue()),
                    "Qualification evaluation groups are invalid or duplicated");
        });
        require(groups.equals(GROUPS), "Qualification cohort must pre-register every MVP evaluation group");
        var baseline = text(node, "baselineId", 120);
        require(ID.matcher(baseline).matches(), "Qualification baseline id is invalid");
        var patientOverlap = boundedInt(node, "maximumPatientOverlap", 0, 0);
        var slideOverlap = boundedInt(node, "maximumSlideOverlap", 0, 0);
        var sourceOverlap = boundedInt(node, "maximumSourceOverlap", 0, 0);
        return new AcceptanceCriteria(Set.copyOf(groups),
                boundedInt(node, "minimumReferenceSamplesPerGroup", 1, 10_000),
                boundedInt(node, "minimumQuerySamplesPerGroup", 1, 10_000),
                boundedInt(node, "minimumOodSamples", 1, 10_000),
                patientOverlap, slideOverlap, sourceOverlap, baseline,
                boundedDouble(node, "minimumMacroRecallAt5Improvement", 0, 1),
                boundedDouble(node, "minimumMacroNdcgAt10Improvement", 0, 1),
                boundedDouble(node, "minimumOodAuRoc", 0.5, 1));
    }

    private static int boundedInt(JsonNode node, String field, int minimum, int maximum) {
        var value = node.path(field);
        require(value.isIntegralNumber() && value.canConvertToInt()
                        && value.intValue() >= minimum && value.intValue() <= maximum,
                "Qualification criterion is invalid: " + field);
        return value.intValue();
    }

    private static double boundedDouble(JsonNode node, String field, double minimum, double maximum) {
        var value = node.path(field);
        require(value.isNumber() && Double.isFinite(value.doubleValue())
                        && value.doubleValue() >= minimum && value.doubleValue() <= maximum,
                "Qualification criterion is invalid: " + field);
        return value.doubleValue();
    }

    private static Set<String> fields(JsonNode node) {
        var result = new HashSet<String>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static String text(JsonNode node, String field, int maximum) {
        var value = node.path(field);
        require(value.isTextual() && !value.textValue().isBlank() && value.textValue().length() <= maximum,
                "Qualification field is invalid: " + field);
        return value.textValue();
    }

    private static String wireReason(String value) {
        return value.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record AcceptanceCriteria(
            Set<String> requiredEvaluationGroups,
            int minimumReferenceSamplesPerGroup,
            int minimumQuerySamplesPerGroup,
            int minimumOodSamples,
            int maximumPatientOverlap,
            int maximumSlideOverlap,
            int maximumSourceOverlap,
            String baselineId,
            double minimumMacroRecallAt5Improvement,
            double minimumMacroNdcgAt10Improvement,
            double minimumOodAuRoc) {}

    public record Sample(
            String id,
            Path tileCacheManifest,
            String tileCacheManifestSha256,
            String evaluationGroup,
            String phenotypeGroup,
            String split,
            String sourceGroup,
            EvidenceTileCacheManifest tileCache) {}

    public record Assessment(String status, List<String> reasons) {}
}

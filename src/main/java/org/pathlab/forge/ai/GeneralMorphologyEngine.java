package org.pathlab.forge.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bounded, diagnosis-free morphology indexing and exact retrieval core.
 * Model adapters provide frozen vectors; this class owns compatibility,
 * float16 storage, deterministic ranking, abstention and evidence limits.
 */
public final class GeneralMorphologyEngine {
    public static final int MAX_PATCHES_PER_SLIDE = 2_048;
    public static final int MAX_EXACT_SCOPE = 100_000;
    public static final int MAX_QUERY_MATCHES = 20;
    public static final int MAX_EVIDENCE_REGIONS = 5;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root;

    public GeneralMorphologyEngine(Path managedRoot) {
        root = managedRoot.resolve("morphology-index").toAbsolutePath().normalize();
    }

    public IndexSummary indexSlide(IndexRequest request, FrozenEncoder encoder) throws IOException {
        requireConfirmed(request.stainProfile(), request.confirmedBy());
        if (request.patches().isEmpty() || request.patches().size() > MAX_PATCHES_PER_SLIDE) {
            throw new IllegalArgumentException("Indexing requires 1 to 2048 bounded tissue patches");
        }
        if (!encoder.id().equals(request.encoderId())
                || !encoder.version().equals(request.encoderVersion())
                || !encoder.preprocessingVersion().equals(request.preprocessingVersion())) {
            throw new IllegalArgumentException("The pinned encoder does not match the requested index partition");
        }
        var partition = Partition.of(request.encoderId(), request.encoderVersion(), request.preprocessingVersion(), request.stainProfile());
        var directory = partitionPath(partition);
        Files.createDirectories(directory);
        var existing = read(directory.resolve("embeddings.f16"));
        if ((long) existing.size() + request.patches().size() > MAX_EXACT_SCOPE) {
            throw new IllegalStateException("Exact morphology scopes are limited to 100000 embeddings");
        }
        var additions = new ArrayList<StoredEmbedding>();
        for (var patch : request.patches()) {
            var vector = encoder.encode(patch);
            validateVector(vector, encoder.dimension());
            additions.add(new StoredEmbedding(request.slideId(), request.sourceFingerprintSha256(), patch.rectangle(), patch.micronsPerPixel(), normalized(vector)));
        }
        existing.addAll(additions);
        existing.sort(Comparator.comparing(StoredEmbedding::slideId)
                .thenComparingDouble(value -> value.rectangle().y())
                .thenComparingDouble(value -> value.rectangle().x()));
        writeAtomically(directory.resolve("embeddings.f16"), existing);
        var manifest = Map.of(
                "schema", "pathlab-morphology-index/v1", "partition", partition.key(),
                "stain_profile", request.stainProfile().id, "embedding_count", existing.size(),
                "dimension", encoder.dimension(), "storage_dtype", "float16",
                "sampling_method", "bounded-tissue-stratified", "research_only", true,
                "not_diagnostic", true, "contains_diagnosis", false);
        writeJsonAtomically(directory.resolve("manifest.json"), manifest);
        return new IndexSummary(partition.key(), additions.size(), existing.size(), encoder.dimension(), "float16");
    }

    public QueryResult querySimilar(QueryRequest request, FrozenEncoder encoder) throws IOException {
        requireConfirmed(request.stainProfile(), request.confirmedBy());
        if (request.limit() < 1 || request.limit() > MAX_QUERY_MATCHES) {
            throw new IllegalArgumentException("Morphology retrieval is limited to 20 matches");
        }
        var partition = Partition.of(encoder.id(), encoder.version(), encoder.preprocessingVersion(), request.stainProfile());
        var values = read(partitionPath(partition).resolve("embeddings.f16"));
        if (values.size() > MAX_EXACT_SCOPE) {
            throw new IllegalStateException("Approximate retrieval is not approved for this scope");
        }
        var query = normalized(encoder.encode(request.patch()));
        var ranked = values.stream()
                .map(value -> new Scored(value, cosine(query, value.vector())))
                .sorted(Comparator.comparingDouble(Scored::similarity).reversed()
                        .thenComparing(value -> value.embedding().slideId())
                        .thenComparingDouble(value -> value.embedding().rectangle().y())
                        .thenComparingDouble(value -> value.embedding().rectangle().x()))
                .limit(request.limit()).toList();
        var matches = new ArrayList<Match>();
        for (var index = 0; index < ranked.size(); index++) {
            var scored = ranked.get(index);
            var item = scored.embedding();
            matches.add(new Match(index + 1, item.slideId(), item.sourceFingerprintSha256(), item.rectangle(), scored.similarity(), false, EvidenceKind.SIMILAR));
        }
        return new QueryResult(partition.key(), false, null, matches);
    }

    public QueryResult abstainUnknown(StainProfile stain, String reason) {
        if (stain != StainProfile.UNKNOWN) {
            throw new IllegalArgumentException("Only an unknown stain uses this fail-closed result");
        }
        return new QueryResult("unavailable/unknown", true, reason, List.of());
    }

    public PrototypeComparison comparePrototypes(float[] query, List<float[]> positive, List<float[]> contrast, String prototypeVersion) {
        if (positive.isEmpty() || contrast.isEmpty() || prototypeVersion == null || prototypeVersion.isBlank()) {
            throw new IllegalArgumentException("Teacher-approved positive and contrasting prototypes are required");
        }
        var normalizedQuery = normalized(query);
        var positiveScore = positive.stream().mapToDouble(value -> cosine(normalizedQuery, normalized(value))).average().orElseThrow();
        var contrastScore = contrast.stream().mapToDouble(value -> cosine(normalizedQuery, normalized(value))).average().orElseThrow();
        return new PrototypeComparison(prototypeVersion, positiveScore, contrastScore, positiveScore - contrastScore, false);
    }

    public List<Match> buildEvidence(List<Match> reviewedMatches) {
        if (reviewedMatches.size() > MAX_EVIDENCE_REGIONS) {
            throw new IllegalArgumentException("Only five reviewed morphology regions may be promoted");
        }
        return List.copyOf(reviewedMatches);
    }

    private Path partitionPath(Partition partition) {
        return root.resolve(sha256(partition.key().getBytes(StandardCharsets.UTF_8)));
    }

    private static void requireConfirmed(StainProfile stain, String confirmedBy) {
        if (stain == StainProfile.UNKNOWN || confirmedBy == null || confirmedBy.isBlank()) {
            throw new AbstentionRequiredException("Unknown or unconfirmed stains must abstain");
        }
    }

    private static void validateVector(float[] value, int dimension) {
        if (value == null || value.length != dimension || dimension < 1 || dimension > 8_192) {
            throw new IllegalArgumentException("Encoder returned an invalid embedding dimension");
        }
        for (var item : value) if (!Float.isFinite(item)) throw new IllegalArgumentException("Encoder returned a non-finite embedding");
    }

    private static float[] normalized(float[] value) {
        var copy = value.clone();
        double norm = 0;
        for (var item : copy) norm += item * item;
        if (norm == 0) throw new IllegalArgumentException("A zero embedding cannot be indexed");
        norm = Math.sqrt(norm);
        for (var index = 0; index < copy.length; index++) copy[index] = (float) (copy[index] / norm);
        return copy;
    }

    private static double cosine(float[] left, float[] right) {
        if (left.length != right.length) throw new IllegalArgumentException("Incompatible embedding spaces cannot be compared");
        double result = 0;
        for (var index = 0; index < left.length; index++) result += left[index] * right[index];
        return Math.max(-1, Math.min(1, result));
    }

    private static String sha256(byte[] input) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void writeJsonAtomically(Path target, Object value) throws IOException {
        var partial = target.resolveSibling(target.getFileName() + ".partial");
        JSON.writerWithDefaultPrettyPrinter().writeValue(partial.toFile(), value);
        move(partial, target);
    }

    private static void writeAtomically(Path target, List<StoredEmbedding> values) throws IOException {
        var partial = target.resolveSibling(target.getFileName() + ".partial");
        try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(partial)))) {
            output.writeInt(0x504C4D31);
            for (var value : values) {
                output.writeUTF(value.slideId()); output.writeUTF(value.sourceFingerprintSha256());
                output.writeDouble(value.rectangle().x()); output.writeDouble(value.rectangle().y());
                output.writeDouble(value.rectangle().width()); output.writeDouble(value.rectangle().height());
                output.writeDouble(value.micronsPerPixel()); output.writeInt(value.vector().length);
                for (var item : value.vector()) output.writeShort(floatToHalf(item));
            }
        }
        move(partial, target);
    }

    private static ArrayList<StoredEmbedding> read(Path target) throws IOException {
        var values = new ArrayList<StoredEmbedding>();
        if (!Files.isRegularFile(target)) return values;
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(target)))) {
            if (input.readInt() != 0x504C4D31) throw new IOException("Unsupported morphology index format");
            while (true) {
                try {
                    var slide = input.readUTF(); var fingerprint = input.readUTF();
                    var rectangle = new SourceRectangle(input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble());
                    var mpp = input.readDouble(); var dimension = input.readInt();
                    if (dimension < 1 || dimension > 8_192) throw new IOException("Invalid morphology index dimension");
                    var vector = new float[dimension];
                    for (var index = 0; index < dimension; index++) vector[index] = halfToFloat(input.readUnsignedShort());
                    values.add(new StoredEmbedding(slide, fingerprint, rectangle, mpp, vector));
                } catch (EOFException complete) { break; }
            }
        }
        return values;
    }

    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    static int floatToHalf(float value) {
        int bits = Float.floatToIntBits(value), sign = bits >>> 16 & 0x8000, exponent = (bits >>> 23 & 0xff) - 127 + 15, mantissa = bits & 0x7fffff;
        if (exponent <= 0) return sign;
        if (exponent >= 31) return sign | 0x7c00;
        return sign | exponent << 10 | (mantissa + 0x1000 >>> 13);
    }

    static float halfToFloat(int value) {
        int sign = value & 0x8000, exponent = value >>> 10 & 0x1f, mantissa = value & 0x3ff;
        if (exponent == 0) return Float.intBitsToFloat(sign << 16);
        if (exponent == 31) return Float.intBitsToFloat(sign << 16 | 0x7f800000 | mantissa << 13);
        return Float.intBitsToFloat(sign << 16 | (exponent - 15 + 127) << 23 | mantissa << 13);
    }

    public enum StainProfile { HE("he"), IHC_DAB("ihc_dab"), PAS("pas"), MASSON_TRICHROME("masson_trichrome"), RETICULIN("reticulin"), UNKNOWN("unknown"); final String id; StainProfile(String id) { this.id = id; } }
    public enum EvidenceKind { SIMILAR, CONTRAST, PROTOTYPE, ARTIFACT, ANNOTATION_CANDIDATE }
    public interface FrozenEncoder { String id(); String version(); String preprocessingVersion(); int dimension(); float[] encode(PatchSource patch) throws IOException; }
    public record SourceRectangle(double x, double y, double width, double height) { public SourceRectangle { if (x < 0 || y < 0 || width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid source rectangle"); } }
    public record PatchSource(SourceRectangle rectangle, double micronsPerPixel, Path image) { public PatchSource { if (micronsPerPixel <= 0) throw new IllegalArgumentException("Physical resolution is required"); } }
    public record Partition(String encoderId, String encoderVersion, String preprocessingVersion, StainProfile stain) { static Partition of(String id, String version, String preprocess, StainProfile stain) { if (id == null || id.isBlank() || version == null || version.isBlank() || preprocess == null || preprocess.isBlank()) throw new IllegalArgumentException("Pinned encoder and preprocessing versions are required"); return new Partition(id, version, preprocess, stain); } String key() { return encoderId + "@" + encoderVersion + "/" + preprocessingVersion + "/" + stain.id; } }
    public record IndexRequest(String slideId, String sourceFingerprintSha256, StainProfile stainProfile, String confirmedBy, String encoderId, String encoderVersion, String preprocessingVersion, List<PatchSource> patches) { public IndexRequest { if (slideId == null || slideId.isBlank() || sourceFingerprintSha256 == null || !sourceFingerprintSha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("A slide identity and SHA-256 fingerprint are required"); patches = List.copyOf(patches); } }
    public record QueryRequest(StainProfile stainProfile, String confirmedBy, PatchSource patch, int limit) {}
    public record IndexSummary(String partition, int added, int total, int dimension, String storageDtype) {}
    public record Match(int rank, String slideId, String sourceFingerprintSha256, SourceRectangle sourceRectangle, double similarity, boolean crossStain, EvidenceKind evidenceKind) {}
    public record QueryResult(String partition, boolean abstained, String abstentionReason, List<Match> matches) {}
    public record PrototypeComparison(String prototypeSetVersion, double positiveSimilarity, double contrastSimilarity, double margin, boolean containsDiagnosis) {}
    private record StoredEmbedding(String slideId, String sourceFingerprintSha256, SourceRectangle rectangle, double micronsPerPixel, float[] vector) {}
    private record Scored(StoredEmbedding embedding, double similarity) {}
    public static final class AbstentionRequiredException extends IllegalStateException { private static final long serialVersionUID = 1L; public AbstentionRequiredException(String message) { super(message); } }
}

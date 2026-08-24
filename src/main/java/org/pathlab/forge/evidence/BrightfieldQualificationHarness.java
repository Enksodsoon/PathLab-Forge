package org.pathlab.forge.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** Runs bounded synthetic checks that inform qualification without qualifying a pack. */
public final class BrightfieldQualificationHarness {
    public static final String SCHEMA = "pathlab.model-qualification-report/1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROTOCOL = String.join("\n",
            "pathlab-brightfield-synthetic-qualification-v1",
            "separated-nuclei-count=2",
            "touching-nuclei-separation=2",
            "generic-dab-area=0.2+-1e-12",
            "weak-separation=not_evaluable",
            "marker-specific=not_evaluable-until-independent-fixtures",
            "pd-l1-compartments=not_evaluable-without-reviewed-geometry");

    private BrightfieldQualificationHarness() {}

    public static void main(String[] arguments) throws Exception {
        Path packs = null;
        Path output = null;
        for (var index = 0; index < arguments.length; index++) {
            if ("--packs".equals(arguments[index]) && index + 1 < arguments.length) {
                packs = Path.of(arguments[++index]);
            } else if ("--output".equals(arguments[index]) && index + 1 < arguments.length) {
                output = Path.of(arguments[++index]);
            } else {
                throw new IllegalArgumentException(
                        "Usage: BrightfieldQualificationHarness --packs PATH --output FILE");
            }
        }
        if (packs == null || output == null) {
            throw new IllegalArgumentException(
                    "Usage: BrightfieldQualificationHarness --packs PATH --output FILE");
        }
        run(packs, output, Instant.now());
        System.out.println("Synthetic qualification report written: " + output.toAbsolutePath().normalize());
        System.out.println("Outcome: experimental (never an activation decision)");
    }

    static void run(Path packRoot, Path output, Instant generatedAt) throws IOException {
        if (packRoot == null || output == null || generatedAt == null) {
            throw new IllegalArgumentException("Qualification inputs are required");
        }
        var normalizedRoot = packRoot.toAbsolutePath().normalize();
        var cellPack = EvidencePackManifest.load(normalizedRoot.resolve("cell-od-watershed-v1.json"));
        var ihcPack = EvidencePackManifest.load(normalizedRoot.resolve("ihc-descriptive-v1.json"));
        require(cellPack.capability() == EvidencePackManifest.Capability.CELL_MORPHOLOGY,
                "Cell qualification pack capability is invalid");
        require(ihcPack.capability() == EvidencePackManifest.Capability.IHC_DESCRIPTIVE,
                "IHC qualification pack capability is invalid");
        require(cellPack.validationStatus() == EvidencePackManifest.ValidationStatus.EXPERIMENTAL
                        && ihcPack.validationStatus() == EvidencePackManifest.ValidationStatus.EXPERIMENTAL,
                "Synthetic harness may run only against experimental brightfield packs");

        var report = JSON.createObjectNode();
        report.put("schema", SCHEMA);
        report.put("protocolId", "pathlab-brightfield-synthetic-qualification-v1");
        report.put("protocolSha256", sha256(PROTOCOL.getBytes(StandardCharsets.UTF_8)));
        report.put("generatedAt", generatedAt.toString());
        report.put("syntheticOnly", true);
        report.put("researchOnly", true);
        report.put("notDiagnostic", true);
        report.put("overallStatus", "experimental");
        var tracks = report.putArray("tracks");
        tracks.add(cellTrack(cellPack));
        tracks.add(ihcTrack(ihcPack));

        var normalizedOutput = output.toAbsolutePath().normalize();
        var parent = normalizedOutput.getParent();
        if (parent == null) throw new IllegalArgumentException("Qualification output parent is unavailable");
        Files.createDirectories(parent);
        var partial = normalizedOutput.resolveSibling(normalizedOutput.getFileName() + ".partial");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(partial.toFile(), report);
            Files.move(partial, normalizedOutput,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static ObjectNode cellTrack(EvidencePackManifest pack) {
        var separated = BrightfieldTileAnalyzer.analyze(separatedNuclei(), "generic");
        var touching = BrightfieldTileAnalyzer.analyze(touchingNuclei(), "generic");
        var repeated = BrightfieldTileAnalyzer.analyze(separatedNuclei(), "generic");
        var track = track(pack, "cell-morphology", "experimental");
        var checks = track.putArray("checks");
        checks.add(numericCheck("separated-nuclei-count", separated.cellCount(), 2,
                separated.cellCount() == 2 ? "pass" : "fail"));
        checks.add(numericCheck("touching-nuclei-separation", touching.cellCount(), 2,
                touching.cellCount() == 2 ? "pass" : "fail"));
        checks.add(textCheck("deterministic-repeat",
                separated.equals(repeated) ? "pass" : "fail",
                "Exact repeated analysis result"));
        checks.add(textCheck("instance-mask-output",
                touching.instances().size() == 2
                        && touching.instances().stream().allMatch(instance -> !instance.rle().isEmpty())
                        ? "pass" : "fail",
                "Deterministic reviewed-region RLE instance masks"));
        var reasons = track.putArray("reasons");
        reasons.add("CROSS_TISSUE_HELD_OUT_FIXTURES_PENDING");
        return track;
    }

    private static ObjectNode ihcTrack(EvidencePackManifest pack) {
        var dab = BrightfieldTileAnalyzer.analyze(dabAreaFixture(), "generic");
        var mixedQc = BrightfieldStainQc.inspect(mixedStainFixture(), false);
        var weakQc = BrightfieldStainQc.inspect(weakSeparationFixture(), false);
        var track = track(pack, "ihc-descriptive", "experimental");
        var checks = track.putArray("checks");
        var dabCheck = JSON.createObjectNode();
        dabCheck.put("id", "generic-dab-area");
        dabCheck.put("outcome", Math.abs(dab.dabAreaFraction() - 0.2) <= 1e-12 ? "pass" : "fail");
        dabCheck.put("observed", dab.dabAreaFraction());
        dabCheck.put("required", 0.2);
        dabCheck.put("tolerance", 1e-12);
        checks.add(dabCheck);
        checks.add(textCheck("relative-only-without-controls",
                "relative_only".equals(mixedQc.calibrationStatus()) ? "pass" : "fail",
                mixedQc.calibrationStatus()));
        checks.add(textCheck("weak-separation-refusal",
                "not_evaluable".equals(weakQc.calibrationStatus())
                        && weakQc.reasons().contains("weak_stain_separation") ? "pass" : "fail",
                weakQc.calibrationStatus()));
        checks.add(textCheck("marker-specific-measurement", "not_evaluable",
                "Nuclear and membrane algorithms await independent fixtures"));
        checks.add(textCheck("pd-l1-compartment-measurement", "not_evaluable",
                "Reviewed compartment geometry is not present in the v1 request"));
        var reasons = track.putArray("reasons");
        reasons.add("VALIDATED_STAIN_VECTOR_DECONVOLUTION_PENDING");
        reasons.add("MARKER_SPECIFIC_FIXTURES_PENDING");
        reasons.add("REVIEWED_COMPARTMENT_GEOMETRY_PENDING");
        return track;
    }

    private static ObjectNode track(
            EvidencePackManifest pack, String capability, String status) {
        var track = JSON.createObjectNode();
        track.put("capability", capability);
        track.put("packId", pack.packId());
        track.put("packVersion", pack.version());
        track.put("packManifestSha256", pack.sha256());
        track.put("status", status);
        return track;
    }

    private static ObjectNode numericCheck(String id, int observed, int required, String outcome) {
        var check = JSON.createObjectNode();
        check.put("id", id);
        check.put("outcome", outcome);
        check.put("observed", observed);
        check.put("required", required);
        return check;
    }

    private static ObjectNode textCheck(String id, String outcome, String detail) {
        var check = JSON.createObjectNode();
        check.put("id", id);
        check.put("outcome", outcome);
        check.put("detail", detail);
        return check;
    }

    private static BufferedImage separatedNuclei() {
        var image = whiteImage(24, 16);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(75, 45, 125));
        graphics.fillRect(3, 5, 5, 5);
        graphics.fillRect(15, 5, 5, 5);
        graphics.dispose();
        return image;
    }

    private static BufferedImage touchingNuclei() {
        var image = whiteImage(24, 16);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(75, 45, 125));
        graphics.fillRect(6, 5, 5, 5);
        graphics.fillRect(11, 5, 5, 5);
        graphics.dispose();
        return image;
    }

    private static BufferedImage dabAreaFixture() {
        var image = whiteImage(10, 10);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(135, 90, 45));
        graphics.fillRect(0, 0, 10, 2);
        graphics.dispose();
        return image;
    }

    private static BufferedImage mixedStainFixture() {
        var image = whiteImage(16, 16);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(75, 45, 125));
        graphics.fillRect(0, 0, 8, 16);
        graphics.setColor(new Color(135, 90, 45));
        graphics.fillRect(8, 0, 8, 16);
        graphics.dispose();
        return image;
    }

    private static BufferedImage weakSeparationFixture() {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(170, 170, 170));
        graphics.fillRect(0, 0, 16, 16);
        graphics.dispose();
        return image;
    }

    private static BufferedImage whiteImage(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    private static String sha256(byte[] value) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

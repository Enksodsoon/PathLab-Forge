package org.pathlab.forge.pivot;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.pathlab.forge.conversion.LocalPreview;
import org.pathlab.forge.library.LocalDataset;

public final class PivotCompiler {
    public static final String SCHEMA = "pathlab-pivot/v1";
    public static final String ALGORITHM_VERSION = "physical-tile-v1";
    private static final Pattern DZI_TILE_SIZE = Pattern.compile("TileSize\\s*=\\s*\"(\\d+)\"");
    private static final Pattern DZI_OVERLAP = Pattern.compile("Overlap\\s*=\\s*\"(\\d+)\"");
    private static final Pattern DZI_FORMAT = Pattern.compile("Format\\s*=\\s*\"([A-Za-z0-9]+)\"");
    private static final Pattern DZI_WIDTH = Pattern.compile("\\bWidth\\s*=\\s*\"(\\d+)\"");
    private static final Pattern DZI_HEIGHT = Pattern.compile("\\bHeight\\s*=\\s*\"(\\d+)\"");
    private final PivotRepository repository;
    private final int maximumCandidates;
    private final int maximumTasks;
    private final Clock clock;

    public PivotCompiler(PivotRepository repository, int maximumCandidates, int maximumTasks) {
        this(repository, maximumCandidates, maximumTasks, Clock.systemUTC());
    }

    PivotCompiler(
            PivotRepository repository,
            int maximumCandidates,
            int maximumTasks,
            Clock clock) {
        if (maximumCandidates < 1 || maximumCandidates > 2_048
                || maximumTasks < 1 || maximumTasks > 64
                || maximumTasks > maximumCandidates) {
            throw new IllegalArgumentException("PIVOT compiler bounds are invalid");
        }
        this.repository = repository;
        this.maximumCandidates = maximumCandidates;
        this.maximumTasks = maximumTasks;
        this.clock = clock;
    }

    public PivotManifest compile(LocalDataset dataset, LocalPreview preview) throws IOException {
        return compile(dataset, previewSource(dataset, preview));
    }

    public PivotManifest compile(LocalDataset dataset, PivotImageSource source) throws IOException {
        validate(dataset, source);
        var existing = repository.findCurrent(dataset);
        if (existing.isPresent() && existing.orElseThrow().inputRevision().equals(source.revision())) {
            return existing.orElseThrow();
        }
        var started = System.nanoTime();
        var descriptor = readDescriptor(source);
        var identity = identity(dataset, source.revision());
        var manifestId = sha256(identity).substring(0, 16);
        var seed = seed(identity);
        var positions = samplePositions(descriptor, seed);
        var accepted = new ArrayList<Candidate>();
        var rejectedBlank = 0;
        var rejectedMissing = 0;
        for (var position : positions) {
            var tileX = (int) (position % descriptor.tilesAcross());
            var tileY = (int) (position / descriptor.tilesAcross());
            var bytes = source.tile(
                    descriptor.maximumLevel(), tileX, tileY, descriptor.format());
            if (bytes == null || bytes.length == 0) {
                rejectedMissing++;
                continue;
            }
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                rejectedMissing++;
                continue;
            }
            var features = features(image);
            if (features.tissueFraction() < 0.18
                    || features.lumaDeviation() < 5
                    || features.edgeStrength() < 4) {
                rejectedBlank++;
                continue;
            }
            accepted.add(new Candidate(tileX, tileY, bytes, image.getWidth(), image.getHeight(), features));
        }
        if (accepted.isEmpty()) {
            throw new IllegalStateException(
                    "PIVOT could not find a sufficiently textured tissue region in the preview");
        }
        addAmbiguity(accepted);
        accepted.forEach(candidate -> candidate.difficulty = difficulty(candidate));
        accepted.sort(Comparator.comparingDouble(candidate -> candidate.difficulty));
        var selected = selectTasks(accepted);
        var sourceScaleX = source.sourceScaleX();
        var sourceScaleY = source.sourceScaleY();
        var tasks = new ArrayList<PivotTask>();
        var images = new HashMap<String, byte[]>();
        for (var candidate : selected) {
            var tileId = sha256(manifestId + "|" + candidate.tileX + "|" + candidate.tileY)
                    .substring(0, 12);
            var taskId = "task-" + tileId;
            var queryFile = taskId + ".jpg";
            var previewX = Math.max(
                    0, candidate.tileX * descriptor.tileSize()
                            - (candidate.tileX == 0 ? 0 : descriptor.overlap()));
            var previewY = Math.max(
                    0, candidate.tileY * descriptor.tileSize()
                            - (candidate.tileY == 0 ? 0 : descriptor.overlap()));
            var targetX = source.sourceOriginX() + previewX * sourceScaleX;
            var targetY = source.sourceOriginY() + previewY * sourceScaleY;
            var targetWidth = Math.min(
                    candidate.imageWidth * sourceScaleX, dataset.width() - targetX);
            var targetHeight = Math.min(
                    candidate.imageHeight * sourceScaleY, dataset.height() - targetY);
            tasks.add(new PivotTask(
                    taskId,
                    queryFile,
                    targetX,
                    targetY,
                    targetWidth,
                    targetHeight,
                    descriptor.maximumLevel(),
                    candidate.tileX,
                    candidate.tileY,
                    candidate.scaleGap(),
                    candidate.features.tissueFraction(),
                    candidate.ambiguity,
                    candidate.difficulty));
            images.put(queryFile, candidate.jpeg);
        }
        var elapsedMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        var manifest = new PivotManifest(
                SCHEMA,
                ALGORITHM_VERSION,
                manifestId,
                dataset.id(),
                dataset.sourceFingerprint(),
                source.revision(),
                dataset.selectedSeries(),
                descriptor.width(),
                descriptor.height(),
                dataset.width(),
                dataset.height(),
                seed,
                clock.millis(),
                elapsedMs,
                positions.size(),
                rejectedBlank,
                rejectedMissing,
                tasks);
        return repository.save(manifest, images);
    }

    public static String identity(LocalDataset dataset) {
        return identity(dataset, expectedInputRevision(dataset));
    }

    public static String identity(LocalDataset dataset, String inputRevision) {
        return SCHEMA + "|" + ALGORITHM_VERSION + "|" + dataset.sourceFingerprint()
                + "|" + dataset.selectedSeries() + "|" + dataset.width() + "|" + dataset.height()
                + "|" + dataset.configurationRevision() + "|" + inputRevision;
    }

    public static String expectedInputRevision(LocalDataset dataset) {
        return dataset.currentArtifactRevision().isBlank()
                ? "preview:" + dataset.configurationRevision()
                : "artifact:" + dataset.currentArtifactRevision();
    }

    private static void validate(LocalDataset dataset, PivotImageSource source) {
        if (dataset.sourceFingerprint().isBlank()) {
            throw new IllegalStateException("Wait for source verification before building PIVOT tasks");
        }
        if (dataset.selectedSeries() < 0 || dataset.width() <= 0 || dataset.height() <= 0) {
            throw new IllegalStateException("Inspect and select an image series first");
        }
        if (!source.revision().equals(expectedInputRevision(dataset))
                || source.imageWidth() <= 0 || source.imageHeight() <= 0
                || source.sourceOriginX() < 0 || source.sourceOriginY() < 0
                || source.sourceScaleX() <= 0 || source.sourceScaleY() <= 0
                || source.sourceOriginX() + source.imageWidth() * source.sourceScaleX()
                        > dataset.width() + source.sourceScaleX()
                || source.sourceOriginY() + source.imageHeight() * source.sourceScaleY()
                        > dataset.height() + source.sourceScaleY()) {
            throw new IllegalStateException("PIVOT image geometry no longer matches the selected source series");
        }
    }

    private static PivotImageSource previewSource(LocalDataset dataset, LocalPreview preview) {
        return new PivotImageSource() {
            public String revision() { return "preview:" + dataset.configurationRevision(); }
            public int imageWidth() { return preview.width(); }
            public int imageHeight() { return preview.height(); }
            public double sourceOriginX() { return 0; }
            public double sourceOriginY() { return 0; }
            public double sourceScaleX() { return (double) preview.sourceWidth() / preview.width(); }
            public double sourceScaleY() { return (double) preview.sourceHeight() / preview.height(); }
            public byte[] descriptor() throws IOException {
                return Files.readAllBytes(preview.root().resolve("slide.dzi"));
            }
            public byte[] tile(int level, int x, int y, String format) throws IOException {
                var path = preview.root().resolve("slide_files").resolve(Integer.toString(level))
                        .resolve(x + "_" + y + "." + format);
                return Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
            }
        };
    }

    private List<Long> samplePositions(DziDescriptor descriptor, long seed) {
        var total = Math.multiplyExact((long) descriptor.tilesAcross(), descriptor.tilesDown());
        var count = (int) Math.min(total, maximumCandidates);
        var positions = new LinkedHashSet<Long>(count);
        if (total <= maximumCandidates) {
            for (long position = 0; position < total; position++) {
                positions.add(position);
            }
            return List.copyOf(positions);
        }
        var random = new SplittableRandom(seed);
        while (positions.size() < count) {
            positions.add(random.nextLong(total));
        }
        return List.copyOf(positions);
    }

    private List<Candidate> selectTasks(List<Candidate> candidates) {
        if (candidates.size() <= maximumTasks) {
            return List.copyOf(candidates);
        }
        var selected = new ArrayList<Candidate>(maximumTasks);
        for (var index = 0; index < maximumTasks; index++) {
            var sourceIndex = (int) Math.round(
                    index * (candidates.size() - 1.0) / Math.max(1, maximumTasks - 1));
            selected.add(candidates.get(sourceIndex));
        }
        return selected;
    }

    private static DziDescriptor readDescriptor(PivotImageSource source) throws IOException {
        var bytes = source.descriptor();
        if (bytes == null || bytes.length == 0 || bytes.length > 65_536) {
            throw new IllegalStateException("Reusable PIVOT preview is not ready");
        }
        var xml = new String(bytes, StandardCharsets.UTF_8);
        var tileSize = matchInteger(DZI_TILE_SIZE, xml, "tile size");
        var overlap = matchInteger(DZI_OVERLAP, xml, "overlap");
        var formatMatcher = DZI_FORMAT.matcher(xml);
        if (!formatMatcher.find()) {
            throw new IllegalStateException("PIVOT preview descriptor is invalid");
        }
        var format = formatMatcher.group(1).toLowerCase(java.util.Locale.ROOT);
        if (!"jpg".equals(format) && !"jpeg".equals(format)) {
            throw new IllegalStateException("PIVOT requires JPEG DZI tiles");
        }
        var width = matchInteger(DZI_WIDTH, xml, "width");
        var height = matchInteger(DZI_HEIGHT, xml, "height");
        if (width != source.imageWidth() || height != source.imageHeight()
                || tileSize < 32 || tileSize > 2_048 || overlap < 0 || overlap > 16) {
            throw new IllegalStateException("PIVOT preview geometry is inconsistent");
        }
        var maximumLevel = maximumLevel(width, height);
        return new DziDescriptor(
                width,
                height,
                tileSize,
                overlap,
                format,
                maximumLevel,
                divideRoundUp(width, tileSize),
                divideRoundUp(height, tileSize));
    }

    private static int matchInteger(Pattern pattern, String value, String label) {
        var matcher = pattern.matcher(value);
        if (!matcher.find()) {
            throw new IllegalStateException("PIVOT preview " + label + " is missing");
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static int maximumLevel(int width, int height) {
        var maximum = Math.max(width, height);
        return maximum <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(maximum - 1);
    }

    private static int divideRoundUp(int value, int divisor) {
        return (int) (((long) value + divisor - 1) / divisor);
    }

    private static Features features(BufferedImage image) {
        var samples = 0;
        var background = 0;
        double sumLuma = 0;
        double sumLumaSquared = 0;
        double sumRed = 0;
        double sumGreen = 0;
        double sumBlue = 0;
        double edge = 0;
        var edgeSamples = 0;
        var step = Math.max(1, Math.min(image.getWidth(), image.getHeight()) / 48);
        for (var y = 0; y < image.getHeight(); y += step) {
            for (var x = 0; x < image.getWidth(); x += step) {
                var rgb = image.getRGB(x, y);
                var red = (rgb >>> 16) & 0xff;
                var green = (rgb >>> 8) & 0xff;
                var blue = rgb & 0xff;
                var maximum = Math.max(red, Math.max(green, blue));
                var minimum = Math.min(red, Math.min(green, blue));
                if (red > 238 && green > 238 && blue > 238 && maximum - minimum < 12) {
                    background++;
                }
                var luma = 0.2126 * red + 0.7152 * green + 0.0722 * blue;
                sumLuma += luma;
                sumLumaSquared += luma * luma;
                sumRed += red;
                sumGreen += green;
                sumBlue += blue;
                samples++;
                if (x + step < image.getWidth()) {
                    edge += Math.abs(luma - luma(image.getRGB(x + step, y)));
                    edgeSamples++;
                }
                if (y + step < image.getHeight()) {
                    edge += Math.abs(luma - luma(image.getRGB(x, y + step)));
                    edgeSamples++;
                }
            }
        }
        var meanLuma = sumLuma / Math.max(1, samples);
        var variance = Math.max(0, sumLumaSquared / Math.max(1, samples) - meanLuma * meanLuma);
        return new Features(
                1 - (double) background / Math.max(1, samples),
                Math.sqrt(variance),
                edge / Math.max(1, edgeSamples),
                sumRed / Math.max(1, samples) / 255,
                sumGreen / Math.max(1, samples) / 255,
                sumBlue / Math.max(1, samples) / 255);
    }

    private static double luma(int rgb) {
        return 0.2126 * ((rgb >>> 16) & 0xff)
                + 0.7152 * ((rgb >>> 8) & 0xff)
                + 0.0722 * (rgb & 0xff);
    }

    private static void addAmbiguity(List<Candidate> candidates) {
        for (var candidate : candidates) {
            var nearest = Double.POSITIVE_INFINITY;
            for (var other : candidates) {
                if (candidate == other) {
                    continue;
                }
                nearest = Math.min(nearest, distance(candidate.features, other.features));
            }
            candidate.ambiguity = Double.isFinite(nearest)
                    ? clamp(1 - nearest / 1.35)
                    : 0;
        }
    }

    private static double distance(Features first, Features second) {
        var red = first.meanRed() - second.meanRed();
        var green = first.meanGreen() - second.meanGreen();
        var blue = first.meanBlue() - second.meanBlue();
        var deviation = (first.lumaDeviation() - second.lumaDeviation()) / 96;
        var edge = (first.edgeStrength() - second.edgeStrength()) / 96;
        var tissue = first.tissueFraction() - second.tissueFraction();
        return Math.sqrt(red * red + green * green + blue * blue
                + deviation * deviation + edge * edge + tissue * tissue);
    }

    private static double difficulty(Candidate candidate) {
        var scale = Math.log(candidate.scaleGap()) / Math.log(2) / 3;
        var structuralComplexity = clamp(candidate.features.edgeStrength() / 72);
        var tissueBalance = 1 - Math.min(1, Math.abs(candidate.features.tissueFraction() - 0.62));
        return clamp(0.42 * candidate.ambiguity
                + 0.32 * scale
                + 0.16 * structuralComplexity
                + 0.10 * tissueBalance);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static long seed(String value) {
        var bytes = digest(value);
        long seed = 0;
        for (var index = 0; index < Long.BYTES; index++) {
            seed = (seed << 8) | (bytes[index] & 0xffL);
        }
        return seed;
    }

    private static String sha256(String value) {
        return HexFormat.of().formatHex(digest(value));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private record DziDescriptor(
            int width,
            int height,
            int tileSize,
            int overlap,
            String format,
            int maximumLevel,
            int tilesAcross,
            int tilesDown) {}

    private record Features(
            double tissueFraction,
            double lumaDeviation,
            double edgeStrength,
            double meanRed,
            double meanGreen,
            double meanBlue) {}

    private static final class Candidate {
        private final int tileX;
        private final int tileY;
        private final byte[] jpeg;
        private final int imageWidth;
        private final int imageHeight;
        private final Features features;
        private double ambiguity;
        private double difficulty;

        private Candidate(
                int tileX,
                int tileY,
                byte[] jpeg,
                int imageWidth,
                int imageHeight,
                Features features) {
            this.tileX = tileX;
            this.tileY = tileY;
            this.jpeg = jpeg.clone();
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.features = features;
        }

        private int scaleGap() {
            return 1 << (1 + Math.floorMod(tileX * 31 + tileY * 17, 3));
        }
    }
}

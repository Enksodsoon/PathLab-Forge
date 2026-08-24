package org.pathlab.forge.evidence;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic marker-controlled watershed fallback for bounded brightfield regions. */
public final class OpticalDensityWatershed {
    private OpticalDensityWatershed() { }

    public static Result segment(BufferedImage image) {
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1
                || (long) image.getWidth() * image.getHeight() > 4_194_304) {
            throw new IllegalArgumentException("Brightfield image geometry is invalid");
        }
        var width = image.getWidth();
        var height = image.getHeight();
        var mask = new boolean[width * height];
        for (var y = 0; y < height; y++) for (var x = 0; x < width; x++) {
            var rgb = image.getRGB(x, y);
            var red = rgb >>> 16 & 0xff;
            var green = rgb >>> 8 & 0xff;
            var blue = rgb & 0xff;
            var opticalDensity = -Math.log((red + 1) / 256.0)
                    + -Math.log((green + 1) / 256.0)
                    + -Math.log((blue + 1) / 256.0);
            mask[y * width + x] = blue - red >= 25 && blue - green >= 25
                    && opticalDensity >= 0.25;
        }
        var components = connected(mask, width, height);
        var instances = new ArrayList<Instance>();
        for (var component : components) split(component, image, width, height, instances);
        instances.sort(Comparator.comparingInt(Instance::firstPixel));
        var numbered = new ArrayList<Instance>();
        for (var index = 0; index < instances.size(); index++) {
            var item = instances.get(index);
            numbered.add(new Instance("cell-" + (index + 1), item.firstPixel(), item.areaPx2(),
                    item.perimeterPx(), item.eccentricity(), item.solidity(), item.meanIntensity(),
                    item.centroidX(), item.centroidY(), item.rle()));
        }
        return new Result(List.copyOf(numbered), "od-watershed", true);
    }

    private static List<int[]> connected(boolean[] mask, int width, int height) {
        var seen = new boolean[mask.length];
        var queue = new ArrayDeque<Integer>();
        var result = new ArrayList<int[]>();
        for (var start = 0; start < mask.length; start++) {
            if (!mask[start] || seen[start]) continue;
            var pixels = new ArrayList<Integer>();
            seen[start] = true;
            queue.add(start);
            while (!queue.isEmpty()) {
                var current = queue.removeFirst();
                pixels.add(current);
                var x = current % width;
                var y = current / width;
                if (x > 0) enqueue(current - 1, mask, seen, queue);
                if (x + 1 < width) enqueue(current + 1, mask, seen, queue);
                if (y > 0) enqueue(current - width, mask, seen, queue);
                if (y + 1 < height) enqueue(current + width, mask, seen, queue);
            }
            if (pixels.size() >= 4) result.add(pixels.stream().mapToInt(Integer::intValue).toArray());
        }
        return result;
    }

    private static void enqueue(int index, boolean[] mask, boolean[] seen, ArrayDeque<Integer> queue) {
        if (mask[index] && !seen[index]) {
            seen[index] = true;
            queue.addLast(index);
        }
    }

    private static void split(int[] component, BufferedImage image, int width, int height,
            List<Instance> output) {
        var minX = width;
        var minY = height;
        var maxX = -1;
        var maxY = -1;
        for (var pixel : component) {
            var x = pixel % width;
            var y = pixel / width;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        var boxWidth = maxX - minX + 1;
        var boxHeight = maxY - minY + 1;
        var major = Math.max(boxWidth, boxHeight);
        var minor = Math.max(1, Math.min(boxWidth, boxHeight));
        var seedCount = Math.max(1, Math.min(8, (int) Math.round((double) major / minor)));
        if (component.length < seedCount * 4) seedCount = Math.max(1, component.length / 4);
        var groups = new ArrayList<List<Integer>>();
        for (var index = 0; index < seedCount; index++) groups.add(new ArrayList<>());
        var horizontal = boxWidth >= boxHeight;
        for (var pixel : component) {
            var coordinate = horizontal ? pixel % width - minX : pixel / width - minY;
            var group = Math.min(seedCount - 1, coordinate * seedCount / major);
            groups.get(group).add(pixel);
        }
        // Empty marker basins are merged rather than emitted as fabricated instances.
        for (var group : groups) {
            if (group.size() >= 4) output.add(measure(group, image, width, height));
        }
    }

    private static Instance measure(List<Integer> pixels, BufferedImage image, int width, int height) {
        var included = new java.util.HashSet<Integer>(pixels);
        var minX = width;
        var minY = height;
        var maxX = -1;
        var maxY = -1;
        var perimeter = 0;
        double xSum = 0;
        double ySum = 0;
        double intensity = 0;
        pixels.sort(Integer::compareTo);
        for (var pixel : pixels) {
            var x = pixel % width;
            var y = pixel / width;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            xSum += x;
            ySum += y;
            var rgb = image.getRGB(x, y);
            var red = rgb >>> 16 & 0xff;
            var green = rgb >>> 8 & 0xff;
            var blue = rgb & 0xff;
            intensity += 1 - (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0;
            if (x == 0 || !included.contains(pixel - 1)) perimeter++;
            if (x + 1 == width || !included.contains(pixel + 1)) perimeter++;
            if (y == 0 || !included.contains(pixel - width)) perimeter++;
            if (y + 1 == height || !included.contains(pixel + width)) perimeter++;
        }
        var boxWidth = maxX - minX + 1.0;
        var boxHeight = maxY - minY + 1.0;
        var major = Math.max(boxWidth, boxHeight);
        var minor = Math.min(boxWidth, boxHeight);
        var eccentricity = major == 0 ? 0 : Math.sqrt(Math.max(0, 1 - (minor * minor) / (major * major)));
        var rle = new ArrayList<Integer>();
        var runStart = pixels.get(0);
        var prior = runStart;
        for (var index = 1; index < pixels.size(); index++) {
            var current = pixels.get(index);
            if (current != prior + 1 || current / width != prior / width) {
                rle.add(runStart);
                rle.add(prior - runStart + 1);
                runStart = current;
            }
            prior = current;
        }
        rle.add(runStart);
        rle.add(prior - runStart + 1);
        return new Instance("", pixels.get(0), pixels.size(), perimeter, eccentricity,
                pixels.size() / (boxWidth * boxHeight), intensity / pixels.size(),
                xSum / pixels.size(), ySum / pixels.size(), List.copyOf(rle));
    }

    public record Result(List<Instance> instances, String method, boolean deterministic) { }
    public record Instance(
            String id, int firstPixel, double areaPx2, double perimeterPx,
            double eccentricity, double solidity, double meanIntensity,
            double centroidX, double centroidY, List<Integer> rle) { }
}

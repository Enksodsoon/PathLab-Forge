package org.pathlab.forge.feature;

import java.math.BigInteger;

/** SemVer precedence, including numeric prerelease identifiers and ignored build metadata. */
final class SemanticVersion {
    private SemanticVersion() {}
    static boolean valid(String value) {
        if (value == null || value.length() > 64 || !value.matches(
                "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?")) return false;
        var parts = value.split("\\+", 2)[0].split("-", 2);
        if (parts.length == 2) for (var part : parts[1].split("\\."))
            if (part.matches("[0-9]+") && part.length() > 1 && part.startsWith("0")) return false;
        return true;
    }
    static int compare(String left, String right) {
        if (!valid(left) || !valid(right)) throw new IllegalArgumentException("Invalid semantic version");
        var a = left.split("\\+", 2)[0].split("-", 2);
        var b = right.split("\\+", 2)[0].split("-", 2);
        var ac = a[0].split("\\.");
        var bc = b[0].split("\\.");
        for (int i = 0; i < 3; i++) {
            var result = new BigInteger(ac[i]).compareTo(new BigInteger(bc[i]));
            if (result != 0) return result;
        }
        if (a.length != b.length) return a.length == 1 ? 1 : -1;
        if (a.length == 1) return 0;
        var ap = a[1].split("\\.");
        var bp = b[1].split("\\.");
        for (int i = 0; i < Math.min(ap.length, bp.length); i++) {
            boolean an = ap[i].matches("[0-9]+"), bn = bp[i].matches("[0-9]+");
            var result = an && bn ? new BigInteger(ap[i]).compareTo(new BigInteger(bp[i]))
                    : an != bn ? (an ? -1 : 1) : ap[i].compareTo(bp[i]);
            if (result != 0) return result;
        }
        return Integer.compare(ap.length, bp.length);
    }
}

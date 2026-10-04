package com.creatorcrm.update;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compares app versions like {@code 1.2.3}, {@code v1.2.3} and {@code 1.2.3-beta.1} (a pre-release sorts before its release). */
public final class Versions {
    private static final Pattern VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?");

    private Versions() {}

    public static boolean isValid(String v) {
        return v != null && VERSION.matcher(v.trim()).matches();
    }

    /** {@code 1.2.3} from {@code v1.2.3}; the input unchanged when it isn't a version. */
    public static String normalize(String v) {
        return v != null && v.startsWith("v") ? v.substring(1) : v;
    }

    /** Negative, zero or positive like {@link Comparable}; anything unparseable sorts lowest. */
    public static int compare(String a, String b) {
        Matcher x = match(a), y = match(b);
        if (x == null || y == null) return x == null ? (y == null ? 0 : -1) : 1;
        for (int i = 1; i <= 3; i++) {
            int c = Long.compare(Long.parseLong(x.group(i)), Long.parseLong(y.group(i)));
            if (c != 0) return c;
        }
        String px = x.group(4), py = y.group(4);
        if (px == null || py == null) return px == null ? (py == null ? 0 : 1) : -1;
        return px.compareTo(py);
    }

    /** {@code 1.3.0} for {@code 1.2.5} (or {@code 1.2.5-beta.1}). */
    public static String nextMinor(String v) {
        Matcher m = match(v);
        if (m == null) return "1.0.0";
        return m.group(1) + "." + (Long.parseLong(m.group(2)) + 1) + ".0";
    }

    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    private static Matcher match(String v) {
        if (v == null) return null;
        Matcher m = VERSION.matcher(v.trim());
        return m.matches() ? m : null;
    }
}

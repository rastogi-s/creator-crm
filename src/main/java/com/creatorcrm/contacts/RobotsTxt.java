package com.creatorcrm.contacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A site's robots.txt rules for one crawler (RFC 9309): the group naming our token, else the {@code *} group.
 * The longest matching Allow or Disallow wins, an Allow winning a tie; {@code *} and a trailing {@code $} work.
 */
public final class RobotsTxt {
    private record Rule(boolean allow, String pattern) {}

    private final List<Rule> rules;
    private final Double crawlDelaySeconds;
    private final boolean disallowAll;

    private RobotsTxt(List<Rule> rules, Double crawlDelaySeconds, boolean disallowAll) {
        this.rules = rules;
        this.crawlDelaySeconds = crawlDelaySeconds;
        this.disallowAll = disallowAll;
    }

    /** No robots.txt (404 and other 4xx answers): everything may be read. */
    public static RobotsTxt allowAll() {
        return new RobotsTxt(List.of(), null, false);
    }

    /** robots.txt couldn't be read (server error, timeout): read nothing, as RFC 9309 says. */
    public static RobotsTxt disallowAll() {
        return new RobotsTxt(List.of(), null, true);
    }

    public static RobotsTxt parse(String text, String token) {
        String me = token.toLowerCase(Locale.ROOT);
        List<Rule> mine = new ArrayList<>(), star = new ArrayList<>();
        Double mineDelay = null, starDelay = null;
        boolean haveMine = false;
        // A group = one or more User-agent lines, then its rules.
        List<String> agents = new ArrayList<>();
        boolean inRules = false;
        for (String raw : (text == null ? "" : text).split("\\r?\\n|\\r")) {
            String line = raw.replaceFirst("#.*$", "").strip();
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (key.equals("user-agent")) {
                if (inRules) {
                    agents.clear();
                    inRules = false;
                }
                agents.add(value.toLowerCase(Locale.ROOT));
                continue;
            }
            if (!key.equals("allow") && !key.equals("disallow") && !key.equals("crawl-delay")) continue;
            inRules = true;
            boolean forMe = agents.stream().anyMatch(a -> a.replaceFirst("/.*$", "").strip().equals(me));
            boolean forStar = agents.contains("*");
            if (!forMe && !forStar) continue;
            if (forMe) haveMine = true;
            if (key.equals("crawl-delay")) {
                Double d = number(value);
                if (forMe) mineDelay = d;
                if (forStar) starDelay = d;
                continue;
            }
            if (value.isEmpty()) continue; // "Disallow:" with nothing after it allows everything
            Rule r = new Rule(key.equals("allow"), value);
            if (forMe) mine.add(r);
            if (forStar) star.add(r);
        }
        return haveMine ? new RobotsTxt(mine, mineDelay, false) : new RobotsTxt(star, starDelay, false);
    }

    private static Double number(String v) {
        try {
            double d = Double.parseDouble(v);
            return d >= 0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whether a path (with its query, e.g. "/pages/contact?x=1") may be read. */
    public boolean allows(String path) {
        if (disallowAll) return false;
        String p = path == null || path.isEmpty() ? "/" : path;
        Rule best = null;
        for (Rule r : rules) {
            if (!matches(r.pattern(), p)) continue;
            if (best == null || r.pattern().length() > best.pattern().length()
                    || (r.pattern().length() == best.pattern().length() && r.allow())) best = r;
        }
        return best == null || best.allow();
    }

    /** The Crawl-delay the site asked for, or null. */
    public Double crawlDelaySeconds() {
        return crawlDelaySeconds;
    }

    static boolean matches(String pattern, String path) {
        boolean anchored = pattern.endsWith("$");
        String pat = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
        return match(pat, 0, path, 0, anchored);
    }

    private static boolean match(String pat, int i, String path, int j, boolean anchored) {
        while (i < pat.length()) {
            char c = pat.charAt(i);
            if (c == '*') {
                for (int k = j; k <= path.length(); k++) {
                    if (match(pat, i + 1, path, k, anchored)) return true;
                }
                return false;
            }
            if (j >= path.length() || path.charAt(j) != c) return false;
            i++;
            j++;
        }
        return !anchored || j == path.length();
    }
}

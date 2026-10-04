package com.creatorcrm.rates;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a deal asks for, read in code from the deliverables and usage-rights wording the classifier already writes
 * ("1 Reel + 3 Stories", "3 months paid usage, 30-day exclusivity"). Best effort: anything it can't read is left out,
 * and she can always fix the wording on the deal.
 */
public record Deliverables(Map<Kind, Integer> counts, int usageMonths, boolean exclusive, int exclusivityMonths) {

    public enum Kind {
        REEL("Reel", "Reels"), TIKTOK("TikTok", "TikToks"), POST("feed post", "feed posts"),
        STORY("Story", "Stories"), UGC("UGC video", "UGC videos");

        final String one;
        final String many;

        Kind(String one, String many) {
            this.one = one;
            this.many = many;
        }

        public String label(int n) {
            return n + " " + (n == 1 ? one : many);
        }
    }

    /** Usage "in perpetuity" is priced as this many months. */
    static final int PERPETUAL_MONTHS = 12;

    private static final Pattern PARENS = Pattern.compile("\\([^)]*\\)");
    private static final Pattern SPLIT = Pattern.compile("\\s*(?:\\+|,|;|&|\\band\\b|\\bplus\\b|\\bwith\\b)\\s*");
    private static final Pattern COUNT = Pattern.compile("^(?:x\\s*)?(a couple of|a few|\\d{1,2}|an|a|one|two|three|four|five|six|seven|eight|nine|ten)\\b(?:\\s*[x×-])?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION = Pattern.compile(
            "(\\d{1,3}|one|two|three|four|five|six|seven|eight|nine|ten|twelve)\\s*[- ]?\\s*(day|week|month|mo|year|yr)s?\\b",
            Pattern.CASE_INSENSITIVE);

    public static Deliverables parse(String deliverables, String usageRights) {
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        String text = PARENS.matcher(deliverables == null ? "" : deliverables).replaceAll(" ");
        for (String part : SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            Kind kind = kindOf(part);
            if (kind == null) continue;
            counts.merge(kind, countOf(part.trim()), Integer::sum);
        }

        int usage = 0, exclusivity = 0;
        boolean exclusive = false;
        String terms = ((usageRights == null ? "" : usageRights) + ". " + (deliverables == null ? "" : deliverables)).toLowerCase(Locale.ROOT);
        for (String clause : terms.split("[,;.+]|\\band\\b")) {
            if (clause.contains("exclusiv")) {
                exclusive = true;
                exclusivity = Math.max(exclusivity, months(clause));
            } else if (isPaidUsage(clause)) {
                usage = Math.max(usage, clause.contains("perpetu") ? PERPETUAL_MONTHS : Math.max(1, months(clause)));
            }
        }
        return new Deliverables(counts, usage, exclusive, exclusivity);
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    public int count(Kind k) {
        return counts.getOrDefault(k, 0);
    }

    /** "1 Reel + 3 Stories, 3 months of paid usage, exclusivity". */
    public String describe() {
        List<String> parts = new ArrayList<>();
        counts.forEach((k, n) -> parts.add(k.label(n)));
        String out = String.join(" + ", parts);
        if (usageMonths > 0) out += ", " + usageMonths + (usageMonths == 1 ? " month" : " months") + " of paid usage";
        if (exclusive) out += exclusivityMonths > 0 ? ", " + exclusivityMonths + (exclusivityMonths == 1 ? " month" : " months") + " exclusivity" : ", exclusivity";
        return out;
    }

    static Kind kindOf(String part) {
        if (part.contains("ugc")) return Kind.UGC;
        if (part.matches(".*\\bstor(y|ies)\\b.*") || part.contains("story")) return Kind.STORY;
        if (part.contains("tiktok") || part.contains("tik tok")) return Kind.TIKTOK;
        if (part.contains("reel") || part.matches(".*\\b(videos?|shorts?)\\b.*")) return Kind.REEL;
        if (part.matches(".*\\b(posts?|carousels?|photos?|static)\\b.*")) return Kind.POST;
        return null;
    }

    private static int countOf(String part) {
        Matcher m = COUNT.matcher(part);
        if (!m.find()) return 1;
        return Math.max(1, number(m.group(1)));
    }

    /** Paid usage is what costs the brand extra: ads, whitelisting, licensing. Organic reposts are free. */
    private static boolean isPaidUsage(String clause) {
        boolean ads = clause.matches(".*\\b(ads?|advertising|whitelist\\w*|spark|licen[sc]\\w*|boost\\w*)\\b.*");
        boolean usage = ads || clause.matches(".*\\busage\\b.*");
        boolean organicOnly = clause.contains("organic") && !ads && !clause.matches(".*\\bpaid\\b.*");
        boolean none = clause.matches(".*\\bno\\s+(paid\\s+)?(usage|ads?)\\b.*");
        return usage && !organicOnly && !none && (ads || clause.contains("perpetu") || DURATION.matcher(clause).find());
    }

    /** Longest duration in the clause, in whole months (30 days or 4 weeks round up to a month). 0 if none. */
    static int months(String clause) {
        int best = 0;
        Matcher m = DURATION.matcher(clause);
        while (m.find()) {
            int n = number(m.group(1));
            String unit = m.group(2).toLowerCase(Locale.ROOT);
            int months = switch (unit) {
                case "day" -> (n + 29) / 30;
                case "week" -> (n * 7 + 29) / 30;
                case "year", "yr" -> n * 12;
                default -> n;
            };
            best = Math.max(best, months);
        }
        return best;
    }

    private static int number(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        return switch (w) {
            case "a", "an", "one" -> 1;
            case "two", "a couple of" -> 2;
            case "three", "a few" -> 3;
            case "four" -> 4;
            case "five" -> 5;
            case "six" -> 6;
            case "seven" -> 7;
            case "eight" -> 8;
            case "nine" -> 9;
            case "ten" -> 10;
            case "twelve" -> 12;
            default -> Integer.parseInt(w);
        };
    }
}

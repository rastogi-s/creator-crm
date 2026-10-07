package com.creatorcrm.campaigns;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Fills a template's merge fields: {@code {brand}}, or {@code {why_you|text to use when there's nothing}}. A fallback
 * can hold fields too ({@code {opening_line|I love {brand}}}). A field with no value and no fallback becomes a blank
 * like [PRODUCT], which the app won't send until she fills it in. Unknown names are left exactly as written.
 */
public final class MergeFields {
    private MergeFields() {}

    /** Every field a template can use, with what it means, for the template editor. */
    public static final Map<String, String> FIELDS = new LinkedHashMap<>();
    static {
        FIELDS.put("first_name", "The contact's first name, or \"<brand> team\" when there's no name");
        FIELDS.put("brand", "The brand's name");
        FIELDS.put("product", "A product of theirs: there's no saved product, so add a fallback or fill it in per email");
        FIELDS.put("why_you", "Why you fit the brand, from Find brands research");
        FIELDS.put("pitch_idea", "The collab idea from Find brands research");
        FIELDS.put("opening_line", "One personal opening line, when Personalise is on");
        FIELDS.put("media_kit", "Your media kit link, from My links");
        FIELDS.put("rates", "Your rate card link, from My links");
        FIELDS.put("instagram", "Your Instagram link, from My links");
        FIELDS.put("my_name", "Your name, from Settings");
    }

    public static String fill(String template, Map<String, String> values) {
        if (template == null) return "";
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = close(template, i);
                if (end > 0) {
                    String inner = template.substring(i + 1, end);
                    int bar = inner.indexOf('|');
                    String key = (bar < 0 ? inner : inner.substring(0, bar)).strip().toLowerCase(Locale.ROOT);
                    if (FIELDS.containsKey(key)) {
                        String v = values.get(key);
                        if (v != null && !v.isBlank()) out.append(v.strip());
                        else if (bar >= 0) out.append(fill(inner.substring(bar + 1), values));
                        else out.append(blank(key));
                        i = end + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** The index of the brace closing the one at {@code open}, counting nested braces; -1 when there isn't one. */
    private static int close(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    /** The blank she fills in by hand, in the form the send check knows ({@code Placeholders}). */
    static String blank(String key) {
        return switch (key) {
            case "media_kit" -> "[MEDIA KIT LINK]";
            case "rates" -> "[RATE CARD LINK]";
            case "instagram" -> "[INSTAGRAM LINK]";
            case "why_you" -> "[WHY YOU FIT]";
            case "pitch_idea" -> "[COLLAB IDEA]";
            case "opening_line" -> "[OPENING LINE]";
            case "first_name" -> "[NAME]";
            default -> "[" + key.replace('_', ' ').toUpperCase(Locale.ROOT) + "]";
        };
    }
}

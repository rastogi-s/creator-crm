package com.creatorcrm.drafts;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blanks Claude leaves for her to fill in, like [RATE FOR 1 REEL] or [MEDIA KIT LINK], when a number or link
 * isn't in her profile (see writer-system.md). A draft that still has one must never reach a brand.
 */
public final class Placeholders {

    /** Square brackets around capitals, at least three characters with a letter first: not [1], [x] or [sic]. */
    private static final Pattern BLANK = Pattern.compile("\\[[A-Z][A-Z0-9 &'/+.,:#$%-]{2,80}\\]");

    private Placeholders() {}

    /** The distinct blanks in these texts, in the order they appear. */
    public static List<String> find(String... texts) {
        Set<String> found = new LinkedHashSet<>();
        for (String t : texts) {
            if (t == null) continue;
            Matcher m = BLANK.matcher(t);
            while (m.find()) found.add(m.group());
        }
        return List.copyOf(found);
    }

    /** What to tell her, or null when nothing is left to fill in. */
    public static String message(String... texts) {
        List<String> blanks = find(texts);
        if (blanks.isEmpty()) return null;
        return (blanks.size() == 1 ? "Fill in the blank before sending: " : "Fill in the " + blanks.size() + " blanks before sending: ")
                + String.join(", ", blanks);
    }
}

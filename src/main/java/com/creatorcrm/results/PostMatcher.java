package com.creatorcrm.results;

import com.creatorcrm.domain.Brand;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the post a deal produced among her recent Instagram posts: by its link, or by brand mention near the posting date. */
final class PostMatcher {

    /** How far a post may be from the deal's posting date and still count as that deal's post. */
    static final int DAYS_AROUND = 14;
    private static final Pattern SHORTCODE = Pattern.compile("instagram\\.com/(?:[A-Za-z0-9_.]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)");

    record Media(String id, String caption, String permalink, OffsetDateTime timestamp, String type) {}

    private PostMatcher() {}

    /** The post's code in an Instagram link ("instagram.com/reel/C8xYz12/" → "C8xYz12"), if it is one. */
    static Optional<String> shortcode(String url) {
        if (url == null) return Optional.empty();
        Matcher m = SHORTCODE.matcher(url);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    static Optional<Media> byLink(List<Media> media, String url) {
        return shortcode(url).flatMap(code -> media.stream()
                .filter(m -> shortcode(m.permalink()).map(code::equals).orElse(false)).findFirst());
    }

    /**
     * Her post that mentions the brand (name, @handle or #hashtag) closest to {@code around}, within
     * {@link #DAYS_AROUND} days of it.
     */
    static Optional<Media> byMention(List<Media> media, String brandName, String handle, LocalDate around) {
        return media.stream()
                .filter(m -> m.timestamp() != null && mentions(m.caption(), brandName, handle))
                .filter(m -> Math.abs(ChronoUnit.DAYS.between(around, m.timestamp().toLocalDate())) <= DAYS_AROUND)
                .min(Comparator.comparingLong(m -> Math.abs(ChronoUnit.DAYS.between(around, m.timestamp().toLocalDate()))));
    }

    static boolean mentions(String caption, String brandName, String handle) {
        if (caption == null || caption.isBlank()) return false;
        String text = caption.toLowerCase(Locale.ROOT);
        if (handle != null && !handle.isBlank()) {
            String h = handle.strip().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
            if (!h.isEmpty() && text.contains("@" + h)) return true;
        }
        // "Petal & Pine" is also written "Petal and Pine".
        String flat = text.replace("&", "and").replaceAll("[^a-z0-9]", "");
        for (String name : new String[] {brandName, brandName == null ? null : brandName.replace("&", "and")}) {
            String key = Brand.key(name);
            if (key.length() >= 4 && flat.contains(key)) return true; // shorter, like "Co", would match everything
        }
        return false;
    }
}

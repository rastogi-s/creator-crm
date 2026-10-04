package com.creatorcrm.channels.instagram;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Looks up another Instagram business or creator account by handle (Meta's "business discovery", which needs
 * the Facebook connection): name, bio, website, followers and the creators it tags in sponsored-looking posts.
 * Personal accounts can't be looked up, which is also how fans are told apart from brands.
 */
@Service
public class BrandLookupService {
    static final int POSTS_SCANNED = 25;
    static final int MAX_PARTNERS = 8;
    private static final Pattern MENTION = Pattern.compile("(?<![A-Za-z0-9._])@([A-Za-z0-9._]{1,30})");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    private static final Pattern SPONSORED = Pattern.compile(
            "#(ad|ads|sponsored|partner|partnership|collab|gifted|ambassador)\\b|paid partnership|in partnership with|thanks to @",
            Pattern.CASE_INSENSITIVE);

    public record Profile(String username, String name, String biography, String website, long followers,
                          long mediaCount, List<String> partners, String bioEmail) {}

    private final InstagramApi api;
    private final FacebookConnection facebook;

    public BrandLookupService(InstagramApi api, FacebookConnection facebook) {
        this.api = api;
        this.facebook = facebook;
    }

    public boolean available() {
        return facebook.isConnected();
    }

    /**
     * Empty when the handle isn't a business or creator account (or doesn't exist). Throws on anything else,
     * such as a network error or an expired connection, so callers don't mistake an outage for "not a brand".
     */
    public Optional<Profile> lookup(String handle) throws IOException, InterruptedException {
        String h = handle == null ? "" : handle.strip().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
        if (!h.matches("[a-z0-9._]{1,30}")) return Optional.empty();
        JsonNode res;
        try {
            res = api.getFacebook(facebook.token(), "/" + facebook.igUserId(), Map.of("fields",
                    "business_discovery.username(" + h + "){username,name,biography,website,followers_count,media_count,"
                            + "media.limit(" + POSTS_SCANNED + "){caption}}"));
        } catch (InstagramApi.InstagramApiException e) {
            // Meta answers 400 for personal accounts and unknown handles; anything else is a real failure.
            if (e.status == 400 && !e.getMessage().toLowerCase(Locale.ROOT).contains("token")) return Optional.empty();
            throw e;
        }
        JsonNode bd = res.path("business_discovery");
        if (bd.isMissingNode() || bd.path("username").asText().isBlank()) return Optional.empty();
        List<String> captions = new ArrayList<>();
        for (JsonNode m : bd.path("media").path("data")) captions.add(m.path("caption").asText(""));
        String username = bd.path("username").asText();
        String bio = bd.path("biography").asText("");
        return Optional.of(new Profile(username, bd.path("name").asText(""), bio, bd.path("website").asText(""),
                bd.path("followers_count").asLong(), bd.path("media_count").asLong(),
                partners(captions, username), firstEmail(bio)));
    }

    /** Handles tagged in sponsored-looking captions, most frequent first, excluding the brand itself. */
    static List<String> partners(List<String> captions, String self) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        Set<String> order = new LinkedHashSet<>();
        for (String c : captions) {
            if (c == null || !SPONSORED.matcher(c).find()) continue;
            Matcher m = MENTION.matcher(c);
            while (m.find()) {
                String h = m.group(1).replaceAll("\\.+$", "").toLowerCase(Locale.ROOT);
                if (h.isEmpty() || h.equalsIgnoreCase(self)) continue;
                counts.merge(h, 1, Integer::sum);
                order.add(h);
            }
        }
        return order.stream().sorted((a, b) -> counts.get(b) - counts.get(a)).limit(MAX_PARTNERS).toList();
    }

    static String firstEmail(String text) {
        if (text == null) return null;
        Matcher m = EMAIL.matcher(text);
        return m.find() ? m.group().toLowerCase(Locale.ROOT) : null;
    }
}

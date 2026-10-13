package com.creatorcrm.shop;

import com.creatorcrm.domain.Product;
import com.creatorcrm.llm.CaptionDraft;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns Claude's captions into ready-to-paste text for each platform: the app adds her links, discount codes, ad
 * label and always-on hashtags itself, so a link is never mistyped and Amazon's disclosure is always there.
 */
public final class CaptionComposer {

    public record ProductLink(Long id, String name, String store, String url, String code, String warning) {}

    public record YouTubeOut(String title, String description, String tags, String pinnedComment) {}

    public record InstagramOut(String caption, String hashtags, String storyText) {}

    public record TikTokOut(String caption) {}

    /** Everything she copies for one post. A platform she didn't ask for is null. */
    public record Result(String title, String keyword, List<String> hooks, YouTubeOut youtube, InstagramOut instagram, TikTokOut tiktok,
                         String dmReply, List<ProductLink> products, String bioList, List<String> warnings, List<String> tips) {}

    /** How she likes her captions finished, from Settings. */
    public record Style(String disclosure, List<String> alwaysHashtags) {}

    static final String AMAZON_DISCLOSURE = "As an Amazon Associate I earn from qualifying purchases.";
    static final String AFFILIATE_DISCLOSURE = "Some links are affiliate links: I may earn a small commission, at no extra cost to you.";
    static final int INSTAGRAM_MAX_HASHTAGS = 30;
    static final int TIKTOK_MAX_HASHTAGS = 8;
    static final int YOUTUBE_TAGS_MAX_CHARS = 480;

    private CaptionComposer() {}

    /** {@code deal}: the brand's name when the post is for a deal, else null. */
    public static Result compose(CaptionDraft d, List<Product> products, List<String> warningsByProduct, Set<String> platforms,
                                 Style style, String deal) {
        String keyword = keyword(d.keyword(), products);
        List<ProductLink> links = new ArrayList<>();
        for (int i = 0; i < products.size(); i++) {
            Product p = products.get(i);
            links.add(new ProductLink(p.id, p.name, p.store.name(), p.url, p.code, i < warningsByProduct.size() ? warningsByProduct.get(i) : null));
        }
        boolean hasProducts = !links.isEmpty();
        boolean disclose = hasProducts || deal != null;
        String label = style.disclosure() == null || style.disclosure().isBlank() ? "#ad" : style.disclosure().strip();
        String shopLines = lines(links, " ");
        List<String> warnings = new ArrayList<>();
        links.stream().filter(l -> l.warning() != null).forEach(l -> warnings.add(l.name() + ": " + l.warning()));
        List<String> tips = new ArrayList<>();

        YouTubeOut youtube = null;
        if (platforms.contains("YOUTUBE") && d.youtube() != null) {
            CaptionDraft.YouTube y = d.youtube();
            StringBuilder desc = new StringBuilder(text(y.description()));
            if (hasProducts) desc.append(desc.isEmpty() ? "" : "\n\n").append("🛍️ Shop this video:\n").append(shopLines);
            List<String> footer = new ArrayList<>();
            if (links.stream().anyMatch(l -> l.store().equals("AMAZON"))) footer.add(AMAZON_DISCLOSURE);
            if (links.stream().anyMatch(l -> !l.store().equals("AMAZON"))) footer.add(AFFILIATE_DISCLOSURE);
            if (!footer.isEmpty()) desc.append("\n\n").append(String.join("\n", footer));
            StringBuilder pinned = new StringBuilder(text(y.pinnedComment()));
            if (hasProducts) pinned.append(pinned.isEmpty() ? "" : "\n\n").append(shopLines);
            String title = text(y.title());
            if (title.length() > 100) warnings.add("The YouTube title is " + title.length() + " characters; YouTube allows 100.");
            if (desc.length() > 5000) warnings.add("The YouTube description is over 5,000 characters, YouTube's limit.");
            youtube = new YouTubeOut(title, desc.toString(), tags(y.tags()), pinned.toString());
            if (deal != null) tips.add("On YouTube, tick \"My video contains paid promotion\" when you upload.");
        }

        InstagramOut instagram = null;
        if (platforms.contains("INSTAGRAM") && d.instagram() != null) {
            CaptionDraft.Instagram ig = d.instagram();
            String caption = text(ig.caption());
            if (hasProducts && !saysKeyword(caption, keyword)) {
                caption += (caption.isEmpty() ? "" : "\n\n") + "Comment " + keyword + " and I'll DM you the links 💌";
            }
            List<String> tags = hashtags(ig.hashtags(), style.alwaysHashtags(), INSTAGRAM_MAX_HASHTAGS, caption);
            String tagLine = tagLine(disclose && !containsWord(caption, label) ? label : null, tags);
            String full = caption + (tagLine.isEmpty() ? "" : "\n\n" + tagLine);
            if (full.length() > 2200) warnings.add("The Instagram caption is over 2,200 characters, Instagram's limit.");
            instagram = new InstagramOut(full, String.join(" ", tags), text(ig.storyText()));
            if (deal != null) tips.add("On Instagram, turn on the \"Paid partnership\" label with " + deal + " before you share.");
            if (hasProducts) tips.add("Set your comment-to-DM tool (like ManyChat) to answer " + keyword + " with the DM message below.");
        }

        TikTokOut tiktok = null;
        if (platforms.contains("TIKTOK") && d.tiktok() != null) {
            CaptionDraft.TikTok tt = d.tiktok();
            String caption = text(tt.caption());
            if (hasProducts && !caption.toLowerCase(Locale.ROOT).contains("bio") && !saysKeyword(caption, keyword)) caption += (caption.isEmpty() ? "" : "\n") + "Links in my bio 🛍️";
            List<String> tags = hashtags(tt.hashtags(), style.alwaysHashtags(), TIKTOK_MAX_HASHTAGS, caption);
            String tagLine = tagLine(disclose && !containsWord(caption, label) ? label : null, tags);
            String full = caption + (tagLine.isEmpty() ? "" : "\n\n" + tagLine);
            if (full.length() > 4000) warnings.add("The TikTok caption is over 4,000 characters, TikTok's limit.");
            tiktok = new TikTokOut(full);
            if (deal != null) tips.add("On TikTok, switch on \"Content disclosure\" and \"Paid partnership\" in the post settings.");
        }

        String dm = text(d.dmReply());
        if (hasProducts) dm = (dm.isEmpty() ? "Here you go! 💕" : dm) + "\n\n" + lines(links, "\n");
        String bio = String.join("\n", links.stream().map(l -> l.name() + (l.code() == null ? "" : " (code " + l.code() + ")") + " → " + l.url()).toList());
        List<String> hooks = d.hooks() == null ? List.of() : d.hooks().stream().map(CaptionComposer::text).filter(h -> !h.isEmpty()).limit(5).toList();
        return new Result(text(d.title()), hasProducts ? keyword : null, hooks, youtube, instagram, tiktok, dm.isEmpty() ? null : dm, links,
                bio.isEmpty() ? null : bio, warnings, tips);
    }

    /** "• Dew Serum: https://… (code AVA20)", one per product; {@code between}: what separates name and link. */
    private static String lines(List<ProductLink> links, String between) {
        StringBuilder sb = new StringBuilder();
        for (ProductLink l : links) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("• ").append(l.name()).append(':').append(between).append(l.url());
            if (l.code() != null && !l.code().isBlank()) sb.append(between.equals("\n") ? "\n" : " ").append("Code ").append(l.code()).append(" for a discount");
        }
        return sb.toString();
    }

    /** Claude's keyword, cleaned to one word in capitals; a word from the first product's name if it gave none. */
    static String keyword(String raw, List<Product> products) {
        String k = raw == null ? "" : raw.replaceAll("[^\\p{L}]", "").toUpperCase(Locale.ROOT);
        if (k.length() > 12) k = k.substring(0, 12);
        if (k.length() >= 3) return k;
        if (!products.isEmpty()) {
            for (String w : products.get(0).name.split("[^\\p{L}]+")) {
                if (w.length() >= 4 && w.length() <= 12) return w.toUpperCase(Locale.ROOT);
            }
        }
        return "LINK";
    }

    /** Claude's hashtags then hers, each once (any case), as #words, up to {@code max}; ones already in the caption are left out. */
    static List<String> hashtags(List<String> fromClaude, List<String> hers, int max, String caption) {
        Map<String, String> seen = new LinkedHashMap<>();
        String inCaption = caption.toLowerCase(Locale.ROOT);
        for (List<String> list : List.of(fromClaude == null ? List.<String>of() : fromClaude, hers == null ? List.<String>of() : hers)) {
            for (String t : list) {
                String tag = t == null ? "" : t.strip().replaceAll("\\s+", "");
                if (tag.isEmpty() || tag.equals("#")) continue;
                if (!tag.startsWith("#")) tag = "#" + tag;
                String key = tag.toLowerCase(Locale.ROOT);
                if (containsWord(inCaption, key)) continue;
                seen.putIfAbsent(key, tag);
            }
        }
        // Her own hashtags always make it in: drop Claude's from the end when there are too many.
        List<String> all = new ArrayList<>(seen.values());
        while (all.size() > max) {
            int drop = -1;
            for (int i = all.size() - 1; i >= 0 && drop < 0; i--) {
                String key = all.get(i).toLowerCase(Locale.ROOT);
                if (hers == null || hers.stream().noneMatch(h -> ("#" + h.replaceFirst("^#", "")).toLowerCase(Locale.ROOT).equals(key))) drop = i;
            }
            all.remove(drop < 0 ? all.size() - 1 : drop);
        }
        return all;
    }

    private static String tagLine(String label, List<String> tags) {
        List<String> parts = new ArrayList<>();
        if (label != null) parts.add(label);
        parts.addAll(tags);
        return String.join(" ", parts);
    }

    /** YouTube's tags box: comma-separated, under its 500-character limit. */
    private static String tags(List<String> tags) {
        if (tags == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String t : tags) {
            String v = t == null ? "" : t.strip().replaceFirst("^#", "").replace(",", " ").replaceAll("\\s+", " ");
            if (v.isEmpty() || sb.length() + v.length() + 2 > YOUTUBE_TAGS_MAX_CHARS) continue;
            sb.append(sb.isEmpty() ? "" : ", ").append(v);
        }
        return sb.toString();
    }

    /** The keyword written in capitals as its own word ("Comment SERUM"), not just the word "serum" in a sentence. */
    static boolean saysKeyword(String caption, String keyword) {
        return java.util.regex.Pattern.compile("(?<![\\p{L}])" + java.util.regex.Pattern.quote(keyword) + "(?![\\p{L}])").matcher(caption).find();
    }

    private static boolean containsWord(String text, String word) {
        String t = text.toLowerCase(Locale.ROOT), w = word.toLowerCase(Locale.ROOT);
        int i = t.indexOf(w);
        while (i >= 0) {
            int end = i + w.length();
            if (end >= t.length() || !Character.isLetterOrDigit(t.charAt(end))) return true;
            i = t.indexOf(w, i + 1);
        }
        return false;
    }

    private static String text(String s) {
        return s == null ? "" : s.strip();
    }
}

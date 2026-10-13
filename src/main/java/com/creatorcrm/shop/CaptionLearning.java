package com.creatorcrm.shop;

import com.creatorcrm.channels.instagram.InstagramStatsService;
import com.creatorcrm.domain.CaptionSet;
import com.creatorcrm.domain.CaptionUse;
import com.creatorcrm.llm.Untrusted;
import com.creatorcrm.repo.CaptionSetRepo;
import com.creatorcrm.repo.CaptionUseRepo;
import com.creatorcrm.settings.SettingsService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How Post captions keep learning her voice: every caption she copies is kept next to what was shown to her, and new
 * captions get her latest ones (edited ones first, as "you wrote / she changed it to"), her best Instagram posts, and
 * the tweaks she keeps asking for. Like {@link com.creatorcrm.learning.LearningService}, it's plain retrieval from the
 * app's own database, and off when she turns off learning in Settings.
 */
@Service
public class CaptionLearning {

    static final int MAX_PAST = 8;
    static final int MAX_PER_FIELD = 2;
    static final int TOP_POSTS = 5;
    static final int POSTS_TO_RANK = 25;
    static final int MAX_EXAMPLE_CHARS = 1_200;
    static final int MAX_USED_CHARS = 6_000;
    /** A tweak she pressed on this many recent captions becomes a standing wish. */
    static final int TWEAK_HABIT = 2;
    static final List<String> TWEAKS = List.of("shorter", "funnier", "more emojis", "more professional");

    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern HASHTAG_LINE = Pattern.compile("^(#\\S+\\s*)+$");

    public record Stats(boolean enabled, long used, long edited) {}

    private final CaptionUseRepo uses;
    private final CaptionSetRepo sets;
    private final InstagramStatsService instagram;
    private final SettingsService settings;

    public CaptionLearning(CaptionUseRepo uses, CaptionSetRepo sets, InstagramStatsService instagram, SettingsService settings) {
        this.uses = uses;
        this.sets = sets;
        this.instagram = instagram;
        this.settings = settings;
    }

    /** She copied {@code used} from a caption box that showed {@code written}. The last copy of a box wins. */
    @Transactional
    public void recordUse(Long captionSetId, CaptionUse.Field field, String written, String used) {
        String text = used == null ? "" : used.strip();
        if (text.isEmpty()) return;
        if (text.length() > MAX_USED_CHARS) text = text.substring(0, MAX_USED_CHARS);
        CaptionUse u = uses.findByCaptionSetIdAndField(captionSetId, field).orElseGet(CaptionUse::new);
        u.captionSetId = captionSetId;
        u.field = field;
        u.writtenText = written == null ? null : written.length() > MAX_USED_CHARS ? written.substring(0, MAX_USED_CHARS) : written;
        u.usedText = text;
        u.edited = written != null && !norm(stripAppParts(written)).equals(norm(stripAppParts(text)));
        u.usedAt = OffsetDateTime.now();
        uses.save(u);
    }

    public Stats stats() {
        return new Stats(settings.learnFromHistory(), uses.count(), uses.countByEdited(true));
    }

    /** Captions she copied for these platforms, rendered for the prompt: edited ones first, then the newest. */
    public List<String> pastCaptions(List<String> platforms) {
        if (!settings.learnFromHistory()) return List.of();
        return pick(uses.findTop200ByOrderByUsedAtDesc(), platforms).stream().map(CaptionLearning::render).toList();
    }

    static List<CaptionUse> pick(List<CaptionUse> newestFirst, List<String> platforms) {
        Map<CaptionUse.Field, Integer> perField = new LinkedHashMap<>();
        List<CaptionUse> out = new ArrayList<>();
        // Stable sort: edited ones first, newest first within each.
        List<CaptionUse> ordered = newestFirst.stream()
                .filter(u -> platforms.contains(platformOf(u.field)) || (u.field == CaptionUse.Field.DM_REPLY && !platforms.equals(List.of("YOUTUBE"))))
                .filter(u -> !stripAppParts(u.usedText).isBlank())
                .sorted(Comparator.comparing((CaptionUse u) -> !u.edited))
                .toList();
        for (CaptionUse u : ordered) {
            if (out.size() >= MAX_PAST) break;
            int n = perField.getOrDefault(u.field, 0);
            if (n >= MAX_PER_FIELD) continue;
            perField.put(u.field, n + 1);
            out.add(u);
        }
        return out;
    }

    static String render(CaptionUse u) {
        StringBuilder sb = new StringBuilder("<past_caption field=\"").append(label(u.field)).append("\" edited=\"")
                .append(u.edited ? "yes" : "no").append("\">\n");
        if (u.edited && u.writtenText != null) {
            sb.append("What you wrote:\n").append(clip(u.writtenText)).append("\n\nWhat she changed it to and used:\n");
        } else {
            sb.append("She used it as you wrote it:\n");
        }
        return sb.append(clip(u.usedText)).append("\n</past_caption>").toString();
    }

    /** Her Instagram posts that did best lately, most likes and comments first. Instagram's own data, so always on. */
    public List<String> topPosts() {
        return instagram.recentPosts(POSTS_TO_RANK).stream()
                .filter(p -> p.caption() != null && !p.caption().isBlank())
                .sorted(Comparator.comparingLong((InstagramStatsService.Post p) -> engagement(p)).reversed())
                .limit(TOP_POSTS)
                .map(p -> "<her_post likes=\"" + n(p.likes()) + "\" comments=\"" + n(p.comments()) + "\">\n" + clip(p.caption()) + "\n</her_post>")
                .toList();
    }

    /** Tweaks she pressed on several recent captions ("shorter"), so new ones start that way. */
    public List<String> habits() {
        if (!settings.learnFromHistory()) return List.of();
        return habits(sets.findTop30ByOrderByIdDesc());
    }

    static List<String> habits(List<CaptionSet> recent) {
        List<String> out = new ArrayList<>();
        for (String t : TWEAKS) {
            long count = recent.stream().filter(s -> s.note != null && Set.of(s.note.toLowerCase(Locale.ROOT).split("\\.\\s*")).contains(t)).count();
            if (count >= TWEAK_HABIT) out.add(t + " (she asked for this on " + count + " of her last " + recent.size() + " captions)");
        }
        return out;
    }

    /**
     * The text without what the app adds itself (links, disclosure lines, the shop list heading, hashtag-only lines),
     * so an example teaches her wording and Claude never sees her links.
     */
    static String stripAppParts(String text) {
        if (text == null) return "";
        List<String> kept = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String l = line.strip();
            if (URL.matcher(l).find() || l.equals(CaptionComposer.AMAZON_DISCLOSURE) || l.equals(CaptionComposer.AFFILIATE_DISCLOSURE)
                    || l.startsWith("🛍️ Shop this video") || l.startsWith("Code ") && l.endsWith("for a discount")
                    || HASHTAG_LINE.matcher(l).matches()) continue;
            kept.add(line);
        }
        return String.join("\n", kept).replaceAll("\n{3,}", "\n\n").strip();
    }

    static String platformOf(CaptionUse.Field f) {
        return switch (f) {
            case YOUTUBE_TITLE, YOUTUBE_DESCRIPTION, YOUTUBE_PINNED -> "YOUTUBE";
            case INSTAGRAM_CAPTION, INSTAGRAM_STORY -> "INSTAGRAM";
            case TIKTOK_CAPTION -> "TIKTOK";
            case DM_REPLY -> "DM";
        };
    }

    private static String label(CaptionUse.Field f) {
        return switch (f) {
            case YOUTUBE_TITLE -> "YouTube title";
            case YOUTUBE_DESCRIPTION -> "YouTube description";
            case YOUTUBE_PINNED -> "YouTube pinned comment";
            case INSTAGRAM_CAPTION -> "Instagram caption";
            case INSTAGRAM_STORY -> "Instagram Story text";
            case TIKTOK_CAPTION -> "TikTok caption";
            case DM_REPLY -> "DM reply";
        };
    }

    private static long engagement(InstagramStatsService.Post p) {
        return n(p.likes()) + 3 * n(p.comments());
    }

    private static long n(Long v) {
        return v == null ? 0 : v;
    }

    private static String clip(String s) {
        String v = Untrusted.escape(stripAppParts(s)).replace("</past_caption", "&lt;/past_caption").replace("</her_post", "&lt;/her_post");
        return v.length() > MAX_EXAMPLE_CHARS ? v.substring(0, MAX_EXAMPLE_CHARS) + "\n[...]" : v;
    }

    private static String norm(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
    }
}

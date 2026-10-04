package com.creatorcrm.results;

import com.creatorcrm.channels.instagram.InstagramApi;
import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.CampaignResult;
import com.creatorcrm.domain.Deadline;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DeadlineType;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.llm.DraftText;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.CampaignResultRepo;
import com.creatorcrm.repo.DeadlineRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.WorkflowEngine;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Campaign wrap-up: link a deal to the post it produced, read that post's numbers from Instagram a week after it went
 * up (or take the numbers she types in), and draft a results recap to the brand with a one-page PDF attached. The recap
 * waits in Drafts like every other message.
 */
@Service
public class CampaignResults {
    private static final Logger log = LoggerFactory.getLogger(CampaignResults.class);

    /** Days after posting before the numbers are read: most reach comes in the first week. */
    public static final int NUMBERS_AFTER_DAYS = 7;
    /** Recent posts searched for a deal's post. */
    static final int POSTS_SEARCHED = 50;
    /** Deals whose post may be found automatically: posted, and changed in the last month. */
    static final Set<OpportunityStatus> POSTED = EnumSet.of(OpportunityStatus.POSTED, OpportunityStatus.PAYMENT_PENDING);
    static final List<String> METRICS = List.of("reach", "views", "likes", "comments", "saved", "shares");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMMM d", Locale.US);

    public record Numbers(Long reach, Long views, Long likes, Long comments, Long saves, Long shares) {}

    public record View(Long id, String postUrl, String mediaId, String mediaType, OffsetDateTime postedAt, String source,
                       Long reach, Long views, Long likes, Long comments, Long saves, Long shares, Double engagementRate,
                       OffsetDateTime fetchedAt, LocalDate numbersDue, String error, OffsetDateTime recapDraftedAt,
                       boolean instagramConnected) {}

    private final CampaignResultRepo results;
    private final OpportunityRepo opportunities;
    private final BrandRepo brands;
    private final DeadlineRepo deadlines;
    private final InstagramApi instagram;
    private final SecretStore secrets;
    private final DraftService drafts;
    private final WorkflowEngine workflow;
    private final SettingsService settings;
    private final ActivityRepo activity;

    public CampaignResults(CampaignResultRepo results, OpportunityRepo opportunities, BrandRepo brands, DeadlineRepo deadlines,
                           InstagramApi instagram, SecretStore secrets, DraftService drafts, WorkflowEngine workflow,
                           SettingsService settings, ActivityRepo activity) {
        this.results = results;
        this.opportunities = opportunities;
        this.brands = brands;
        this.deadlines = deadlines;
        this.instagram = instagram;
        this.secrets = secrets;
        this.drafts = drafts;
        this.workflow = workflow;
        this.settings = settings;
        this.activity = activity;
    }

    public Optional<View> forDeal(Long opportunityId) {
        return results.findByOpportunityId(opportunityId).map(this::view);
    }

    public Optional<CampaignResult> get(Long opportunityId) {
        return results.findByOpportunityId(opportunityId);
    }

    /** She pasted the post's link. Her own Instagram post is looked up so its numbers can be read; any other link is kept as is. */
    public View link(Long opportunityId, String url) {
        String u = url == null ? "" : url.strip();
        if (!u.matches("https?://\\S{4,490}")) throw new IllegalArgumentException("Paste the post's full link, starting with https://");
        Opportunity o = deal(opportunityId);
        CampaignResult r = results.findByOpportunityId(o.id).orElseGet(() -> blank(o.id));
        r.postUrl = u;
        r.mediaId = null;
        r.mediaType = null;
        r.caption = null;
        r.fetchedAt = null;
        r.error = null;
        if (connected() && PostMatcher.shortcode(u).isPresent()) {
            try {
                Optional<PostMatcher.Media> m = PostMatcher.byLink(recentMedia(), u);
                if (m.isPresent()) use(r, m.get());
                else r.error = "That post isn't among your last " + POSTS_SEARCHED + " Instagram posts, so type its numbers in.";
            } catch (Exception e) {
                r.error = "Couldn't look the post up on Instagram: " + e.getMessage();
            }
        }
        if (r.postedAt == null) r.postedAt = OffsetDateTime.now();
        return view(save(r));
    }

    /** Looks for her post that mentions the brand near the deal's posting date. */
    public View find(Long opportunityId) {
        Opportunity o = deal(opportunityId);
        if (!connected()) throw new IllegalStateException("Connect Instagram in Settings to find your post, or paste its link.");
        PostMatcher.Media m;
        try {
            m = match(o, recentMedia()).orElseThrow(() -> new IllegalStateException(
                    "No post mentioning " + workflow.brandName(o) + " near the posting date in your last " + POSTS_SEARCHED
                            + " posts. Paste its link instead."));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't read your Instagram posts: " + e.getMessage(), e);
        }
        CampaignResult r = results.findByOpportunityId(o.id).orElseGet(() -> blank(o.id));
        r.postUrl = m.permalink();
        r.fetchedAt = null;
        r.error = null;
        use(r, m);
        return view(save(r));
    }

    /** Numbers she typed in, for a TikTok, a Story, or a post on another account. They replace fetched ones. */
    public View saveNumbers(Long opportunityId, Numbers n) {
        Opportunity o = deal(opportunityId);
        CampaignResult r = results.findByOpportunityId(o.id).orElseGet(() -> blank(o.id));
        for (Long v : new Long[] {n.reach(), n.views(), n.likes(), n.comments(), n.saves(), n.shares()}) {
            if (v != null && v < 0) throw new IllegalArgumentException("Numbers can't be negative");
        }
        r.reach = n.reach();
        r.views = n.views();
        r.likes = n.likes();
        r.comments = n.comments();
        r.saves = n.saves();
        r.shares = n.shares();
        r.source = CampaignResult.Source.MANUAL;
        r.error = null;
        if (r.postedAt == null) r.postedAt = OffsetDateTime.now();
        return view(save(r));
    }

    /** Reads the post's numbers from Instagram now, without waiting for the week to pass. */
    public View fetch(Long opportunityId) {
        CampaignResult r = results.findByOpportunityId(opportunityId)
                .orElseThrow(() -> new IllegalStateException("Link the post first"));
        if (r.mediaId == null) throw new IllegalStateException("This post isn't on your connected Instagram account, so type its numbers in.");
        if (!connected()) throw new IllegalStateException("Connect Instagram in Settings first.");
        readNumbers(r);
        save(r);
        if (r.error != null) throw new IllegalStateException(r.error);
        return view(r);
    }

    public byte[] pdf(Long opportunityId) {
        CampaignResult r = results.findByOpportunityId(opportunityId).orElseThrow(() -> new IllegalStateException("No results yet"));
        return drafts.resultsPdf(r);
    }

    /** The recap email to the brand, with the results PDF. Waits in Drafts; replaces an unsent recap. */
    public Draft draftRecap(Long opportunityId) {
        Opportunity o = deal(opportunityId);
        CampaignResult r = results.findByOpportunityId(o.id).orElseThrow(() -> new IllegalStateException("Add the post's numbers first"));
        if (!r.hasNumbers()) throw new IllegalStateException("Add the post's numbers first");
        String brand = workflow.brandName(o);
        String numbers = numbersText(r);
        boolean gifted = o.compensation == Compensation.GIFTED;
        String instructions = "Results recap: share how the " + (o.campaign == null || o.campaign.isBlank() ? "collab" : o.campaign)
                + " post did" + (r.postedAt == null ? "" : " (posted " + DAY.format(r.postedAt.toLocalDate()) + ")") + ". "
                + "Numbers: " + numbers + ". Quote these exactly and never add others. "
                + "A one-page results PDF is attached when this goes by email. Thank them, point out the strongest number, "
                + "and suggest working together again" + (gifted ? ", this time as a paid collab, without naming a rate." : ".");
        DraftText fallback = new DraftText("Results from our " + brand + " collab",
                "Hi,\n\nThanks again for the collab! Here's how the post did: " + numbers + ". The full results are in the "
                        + "attached PDF.\n\nI'd love to work together again" + (gifted ? " on a paid collab" : "") + ".\n\nBest,\n"
                        + settings.creatorName());
        Draft d = drafts.generateRecap(r, instructions, fallback);
        r.recapDraftedAt = OffsetDateTime.now();
        save(r);
        activity.save(Activity.of(o.id, Activity.RESULTS, "Results recap drafted for " + brand + ": " + numbers));
        return d;
    }

    /**
     * Daily: find the post for deals marked Posted, read numbers for posts a week old, and draft their recaps.
     * Returns how many recaps were drafted. Never throws.
     */
    public int daily() {
        if (!connected()) return 0;
        List<PostMatcher.Media> media = null;
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(30);
        for (Opportunity o : opportunities.findAll()) {
            if (!POSTED.contains(o.status) || o.updatedAt == null || o.updatedAt.isBefore(cutoff)) continue;
            if (results.findByOpportunityId(o.id).isPresent()) continue;
            try {
                if (media == null) media = recentMedia();
                Optional<PostMatcher.Media> m = match(o, media);
                if (m.isEmpty()) continue;
                CampaignResult r = blank(o.id);
                r.postUrl = m.get().permalink();
                use(r, m.get());
                save(r);
                activity.save(Activity.of(o.id, Activity.RESULTS, "Found the " + workflow.brandName(o) + " post: " + r.postUrl));
            } catch (Exception e) {
                log.warn("Couldn't look for the post for deal {}: {}", o.id, e.getMessage());
                break; // Instagram is unhappy; try again tomorrow
            }
        }
        int recaps = 0;
        OffsetDateTime weekAgo = OffsetDateTime.now().minusDays(NUMBERS_AFTER_DAYS);
        for (CampaignResult r : results.findByMediaIdNotNullAndFetchedAtIsNull()) {
            if (r.postedAt == null || r.postedAt.isAfter(weekAgo) || r.source == CampaignResult.Source.MANUAL) continue;
            readNumbers(r);
            save(r);
            if (r.error != null || r.recapDraftedAt != null || !r.hasNumbers()) continue;
            try {
                draftRecap(r.opportunityId);
                recaps++;
            } catch (RuntimeException e) {
                log.warn("Couldn't draft the results recap for deal {}: {}", r.opportunityId, e.getMessage());
            }
        }
        return recaps;
    }

    // ---------- Instagram ----------

    private boolean connected() {
        return secrets.has(SecretName.INSTAGRAM_ACCESS_TOKEN);
    }

    List<PostMatcher.Media> recentMedia() throws Exception {
        JsonNode res = instagram.get(secrets.require(SecretName.INSTAGRAM_ACCESS_TOKEN), "/me/media", Map.of(
                "fields", "id,caption,permalink,timestamp,media_type,media_product_type", "limit", String.valueOf(POSTS_SEARCHED)));
        List<PostMatcher.Media> out = new ArrayList<>();
        for (JsonNode m : res.path("data")) {
            String type = m.path("media_product_type").asText(m.path("media_type").asText(""));
            out.add(new PostMatcher.Media(m.path("id").asText(), m.path("caption").asText(""), m.path("permalink").asText(""),
                    parseTime(m.path("timestamp").asText("")), type));
        }
        return out;
    }

    /** Reads what Instagram has for the post. A metric this kind of post doesn't have is left empty. */
    void readNumbers(CampaignResult r) {
        String token = secrets.require(SecretName.INSTAGRAM_ACCESS_TOKEN);
        Map<String, Long> got = new LinkedHashMap<>();
        try {
            got.putAll(values(instagram.get(token, "/" + r.mediaId + "/insights", Map.of("metric", String.join(",", METRICS)))));
        } catch (Exception all) {
            // One unsupported metric fails the whole request: ask for each on its own.
            String firstError = all.getMessage();
            for (String metric : METRICS) {
                try {
                    got.putAll(values(instagram.get(token, "/" + r.mediaId + "/insights", Map.of("metric", metric))));
                } catch (Exception one) {
                    log.debug("Instagram has no {} for media {}: {}", metric, r.mediaId, one.getMessage());
                }
            }
            if (got.isEmpty()) {
                r.error = firstError != null && firstError.contains("permission")
                        ? "Instagram didn't allow reading post insights. Press Reconnect Instagram in Settings."
                        : "Instagram didn't return numbers for this post: " + firstError;
                return;
            }
        }
        r.reach = got.get("reach");
        r.views = got.get("views");
        r.likes = got.get("likes");
        r.comments = got.get("comments");
        r.saves = got.get("saved");
        r.shares = got.get("shares");
        r.source = CampaignResult.Source.INSTAGRAM;
        r.fetchedAt = OffsetDateTime.now();
        r.error = null;
    }

    private static Map<String, Long> values(JsonNode res) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (JsonNode m : res.path("data")) {
            JsonNode v = m.path("total_value").path("value");
            if (v.isMissingNode()) v = m.path("values").path(0).path("value");
            if (v.isNumber()) out.put(m.path("name").asText(), v.asLong());
        }
        return out;
    }

    private Optional<PostMatcher.Media> match(Opportunity o, List<PostMatcher.Media> media) {
        Brand b = brands.findById(o.brandId).orElse(null);
        return PostMatcher.byMention(media, b == null ? "" : b.name, b == null ? null : b.instagram, postingDate(o));
    }

    /** The deal's posting date, else when its content was due, else when it last changed (marked Posted). */
    private LocalDate postingDate(Opportunity o) {
        List<Deadline> ds = deadlines.findByOpportunityIdOrderByDueDateAsc(o.id);
        for (DeadlineType t : List.of(DeadlineType.POSTING, DeadlineType.CONTENT_DUE)) {
            Optional<LocalDate> d = ds.stream().filter(x -> x.type == t && x.dueDate != null).map(x -> x.dueDate).reduce((a, b) -> b);
            if (d.isPresent()) return d.get();
        }
        return o.updatedAt == null ? settings.today() : o.updatedAt.toLocalDate();
    }

    private static void use(CampaignResult r, PostMatcher.Media m) {
        r.mediaId = m.id();
        r.mediaType = m.type();
        r.caption = m.caption() == null ? null : m.caption().length() > 1000 ? m.caption().substring(0, 1000) : m.caption();
        r.postedAt = m.timestamp();
        r.source = CampaignResult.Source.INSTAGRAM;
    }

    private static OffsetDateTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s.replaceFirst("([+-]\\d{2})(\\d{2})$", "$1:$2"));
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- helpers ----------

    /** Likes, comments, saves and shares over accounts reached, as a percentage with one decimal. Null without reach. */
    public static Double engagementRate(CampaignResult r) {
        if (r.reach == null || r.reach <= 0) return null;
        long interactions = nz(r.likes) + nz(r.comments) + nz(r.saves) + nz(r.shares);
        if (interactions == 0) return null;
        return Math.round(1000.0 * interactions / r.reach) / 10.0;
    }

    static String numbersText(CampaignResult r) {
        List<String> parts = new ArrayList<>();
        part(parts, r.reach, "accounts reached");
        part(parts, r.views, "views");
        part(parts, r.likes, "likes");
        part(parts, r.comments, "comments");
        part(parts, r.saves, "saves");
        part(parts, r.shares, "shares");
        Double rate = engagementRate(r);
        if (rate != null) parts.add(String.format(Locale.US, "%.1f%% engagement rate", rate));
        return String.join(", ", parts);
    }

    private static void part(List<String> parts, Long v, String what) {
        if (v != null) parts.add(String.format(Locale.US, "%,d %s", v, what));
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    private Opportunity deal(Long id) {
        return opportunities.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown deal"));
    }

    private static CampaignResult blank(Long opportunityId) {
        CampaignResult r = new CampaignResult();
        r.opportunityId = opportunityId;
        r.source = CampaignResult.Source.MANUAL;
        r.createdAt = OffsetDateTime.now();
        return r;
    }

    private CampaignResult save(CampaignResult r) {
        r.updatedAt = OffsetDateTime.now();
        return results.save(r);
    }

    View view(CampaignResult r) {
        LocalDate due = r.mediaId != null && r.fetchedAt == null && r.postedAt != null && r.source != CampaignResult.Source.MANUAL
                ? r.postedAt.toLocalDate().plusDays(NUMBERS_AFTER_DAYS) : null;
        return new View(r.id, r.postUrl, r.mediaId, r.mediaType, r.postedAt, r.source == null ? null : r.source.name(),
                r.reach, r.views, r.likes, r.comments, r.saves, r.shares, engagementRate(r), r.fetchedAt, due, r.error,
                r.recapDraftedAt, connected());
    }
}

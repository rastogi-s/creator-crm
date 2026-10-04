package com.creatorcrm.outreach;

import com.creatorcrm.channels.instagram.BrandLookupService;
import com.creatorcrm.channels.instagram.FacebookConnection;
import com.creatorcrm.channels.instagram.InstagramApi;
import com.creatorcrm.domain.AppState;
import com.creatorcrm.domain.BrandLead;
import com.creatorcrm.domain.InstagramEngagement;
import com.creatorcrm.domain.InstagramEngagement.Kind;
import com.creatorcrm.domain.InstagramSeenEvent;
import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.repo.InstagramEngagementRepo;
import com.creatorcrm.repo.InstagramSeenEventRepo;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Brands already engaging with the creator: accounts that tag her in a post, @mention her, or comment on her
 * posts. Comments come from polling her recent posts (and the "comments" webhook); tags from the Facebook
 * connection when present (and the "mentions" webhook). With the Facebook connection, each account is checked
 * once: personal accounts (fans) are hidden, business and creator accounts stay. Nothing is contacted.
 */
@Service
public class InstagramEngagementService {
    private static final Logger log = LoggerFactory.getLogger(InstagramEngagementService.class);
    static final String ERROR_KEY = "instagram.engagement.error";
    static final int POSTS_CHECKED = 10;
    static final String NEEDS_RECONNECT = "Reconnect Instagram on the Settings page so the app may read comments on your posts.";
    private static final DateTimeFormatter IG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

    public record Row(Long id, String username, int mentions, int comments, Kind lastKind, String lastText,
                      String lastPermalink, OffsetDateTime lastSeenAt, Boolean isBusiness, Long followers) {}

    private final InstagramApi api;
    private final SecretStore secrets;
    private final FacebookConnection facebook;
    private final BrandLookupService lookup;
    private final InstagramEngagementRepo engagements;
    private final InstagramSeenEventRepo seen;
    private final AppStateRepo state;
    private final BrandDiscoveryService discovery;

    public InstagramEngagementService(InstagramApi api, SecretStore secrets, FacebookConnection facebook,
                                      BrandLookupService lookup, InstagramEngagementRepo engagements,
                                      InstagramSeenEventRepo seen, AppStateRepo state, BrandDiscoveryService discovery) {
        this.api = api;
        this.secrets = secrets;
        this.facebook = facebook;
        this.lookup = lookup;
        this.engagements = engagements;
        this.seen = seen;
        this.state = state;
        this.discovery = discovery;
    }

    /** New accounts worth a look: not dismissed, not already leads, and not known to be personal accounts. */
    public List<Row> open() {
        return engagements.findByStatusOrderByLastSeenAtDesc(InstagramEngagement.Status.NEW).stream()
                .filter(e -> !Boolean.FALSE.equals(e.isBusiness))
                .sorted(Comparator.comparing((InstagramEngagement e) -> e.mentions > 0 ? 0 : 1)
                        .thenComparing(e -> e.lastSeenAt, Comparator.reverseOrder()))
                .map(e -> new Row(e.id, e.username, e.mentions, e.comments, e.lastKind, e.lastText, e.lastPermalink,
                        e.lastSeenAt, e.isBusiness, e.followers))
                .toList();
    }

    public Optional<String> lastError() {
        return state.findById(ERROR_KEY).map(s -> s.stateValue).filter(v -> v != null && !v.isBlank());
    }

    /** Runs with every sync. Quiet when Instagram isn't connected. Returns the new comments and tags counted. */
    public int poll() {
        if (!secrets.has(SecretName.INSTAGRAM_ACCESS_TOKEN)) return 0;
        int n = 0;
        try {
            n += pollComments();
            setError(null);
        } catch (InstagramApi.InstagramApiException e) {
            log.warn("Could not read Instagram comments: {}", e.getMessage());
            setError(e.status == 400 || e.status == 403 ? NEEDS_RECONNECT : "Instagram comments: " + e.getMessage());
        } catch (Exception e) {
            log.warn("Could not read Instagram comments: {}", e.getMessage());
        }
        n += pollTags();
        checkAccounts();
        return n;
    }

    int pollComments() throws Exception {
        String token = secrets.require(SecretName.INSTAGRAM_ACCESS_TOKEN);
        JsonNode media = api.get(token, "/me/media", Map.of(
                "fields", "id,permalink,comments_count", "limit", String.valueOf(POSTS_CHECKED)));
        int n = 0;
        for (JsonNode m : media.path("data")) {
            if (m.path("comments_count").asLong(1) == 0) continue;
            JsonNode comments = api.get(token, "/" + m.path("id").asText() + "/comments",
                    Map.of("fields", "id,text,username,timestamp", "limit", "50"));
            for (JsonNode c : comments.path("data")) {
                if (record(Kind.COMMENT, "c:" + c.path("id").asText(), c.path("username").asText(),
                        c.path("text").asText(), m.path("permalink").asText(null), parseTime(c.path("timestamp").asText()))) n++;
            }
        }
        return n;
    }

    /** Posts she's tagged in. Reliable through the Facebook connection; tried quietly without it. */
    int pollTags() {
        try {
            Map<String, String> q = Map.of("fields", "id,username,caption,permalink,timestamp", "limit", "25");
            JsonNode tags = facebook.isConnected()
                    ? api.getFacebook(facebook.token(), "/" + facebook.igUserId() + "/tags", q)
                    : api.get(secrets.require(SecretName.INSTAGRAM_ACCESS_TOKEN), "/me/tags", q);
            int n = 0;
            for (JsonNode t : tags.path("data")) {
                if (record(Kind.TAG, "t:" + t.path("id").asText(), t.path("username").asText(), t.path("caption").asText(),
                        t.path("permalink").asText(null), parseTime(t.path("timestamp").asText()))) n++;
            }
            return n;
        } catch (Exception e) {
            if (facebook.isConnected()) log.warn("Could not read posts you're tagged in: {}", e.getMessage());
            else log.debug("Tagged posts aren't readable without the Facebook connection: {}", e.getMessage());
            return 0;
        }
    }

    /** One webhook change ("comments" or "mentions") from Instagram. */
    public void onWebhookChange(String field, JsonNode value) {
        try {
            if ("comments".equals(field)) {
                record(Kind.COMMENT, "c:" + value.path("id").asText(), value.path("from").path("username").asText(),
                        value.path("text").asText(), null, OffsetDateTime.now());
            } else if ("mentions".equals(field)) {
                onMention(value.path("media_id").asText(), value.path("comment_id").asText());
            }
        } catch (Exception e) {
            log.warn("Could not process Instagram {} webhook: {}", field, e.getMessage());
        }
    }

    private void onMention(String mediaId, String commentId) throws Exception {
        boolean inComment = !commentId.isBlank();
        String field = inComment
                ? "mentioned_comment.comment_id(" + commentId + "){text,username,timestamp}"
                : "mentioned_media.media_id(" + mediaId + "){caption,username,permalink,timestamp}";
        if (!(inComment ? commentId : mediaId).matches("[0-9_]{1,40}")) return;
        JsonNode res = facebook.isConnected()
                ? api.getFacebook(facebook.token(), "/" + facebook.igUserId(), Map.of("fields", field))
                : api.get(secrets.require(SecretName.INSTAGRAM_ACCESS_TOKEN), "/me", Map.of("fields", field));
        JsonNode m = res.path(inComment ? "mentioned_comment" : "mentioned_media");
        record(Kind.MENTION, "m:" + (inComment ? commentId : mediaId), m.path("username").asText(),
                m.path(inComment ? "text" : "caption").asText(), m.path("permalink").asText(null),
                parseTime(m.path("timestamp").asText()));
    }

    /** Counts one comment/tag/mention once. Returns true when it was new. */
    synchronized boolean record(Kind kind, String externalId, String username, String text, String permalink,
                                OffsetDateTime at) {
        String user = username == null ? "" : username.strip().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
        if (!user.matches("[a-z0-9._]{1,30}") || externalId.endsWith(":")) return false;
        String me = secrets.get(SecretName.INSTAGRAM_USERNAME).orElse("");
        if (user.equalsIgnoreCase(me)) return false; // her own replies
        if (seen.existsById(externalId)) return false;
        InstagramSeenEvent ev = new InstagramSeenEvent();
        ev.externalId = externalId;
        ev.seenAt = OffsetDateTime.now();
        seen.save(ev);

        InstagramEngagement e = engagements.findByUsername(user).orElseGet(() -> {
            InstagramEngagement n = new InstagramEngagement();
            n.username = user;
            n.status = InstagramEngagement.Status.NEW;
            return n;
        });
        if (kind == Kind.COMMENT) e.comments++;
        else e.mentions++;
        OffsetDateTime when = at == null ? OffsetDateTime.now() : at;
        if (e.lastSeenAt == null || !when.isBefore(e.lastSeenAt)) {
            e.lastKind = kind;
            e.lastText = shorten(text);
            e.lastPermalink = safePermalink(permalink);
            e.lastSeenAt = when;
        }
        engagements.save(e);
        return true;
    }

    /** With the Facebook connection, tell brands from fans: personal accounts can't be looked up. */
    void checkAccounts() {
        if (!lookup.available()) return;
        for (InstagramEngagement e : engagements.findTop10ByStatusAndCheckedAtIsNullOrderByLastSeenAtDesc(InstagramEngagement.Status.NEW)) {
            try {
                Optional<BrandLookupService.Profile> p = lookup.lookup(e.username);
                e.isBusiness = p.isPresent();
                e.followers = p.map(BrandLookupService.Profile::followers).orElse(null);
                e.checkedAt = OffsetDateTime.now();
                engagements.save(e);
            } catch (Exception ex) {
                log.warn("Could not check @{} on Instagram: {}", e.username, ex.getMessage());
                return; // try the rest on the next sync
            }
        }
    }

    public BrandLead makeLead(Long id) {
        InstagramEngagement e = engagements.findById(id).orElseThrow();
        BrandLead lead = discovery.fromEngagement(e);
        e.status = InstagramEngagement.Status.LEAD;
        engagements.save(e);
        return lead;
    }

    public void dismiss(Long id) {
        InstagramEngagement e = engagements.findById(id).orElseThrow();
        e.status = InstagramEngagement.Status.DISMISSED;
        engagements.save(e);
    }

    private void setError(String message) {
        if (message == null) {
            if (state.existsById(ERROR_KEY)) state.deleteById(ERROR_KEY);
            return;
        }
        AppState s = new AppState();
        s.stateKey = ERROR_KEY;
        s.stateValue = message.length() > 500 ? message.substring(0, 500) : message;
        state.save(s);
    }

    private static String shorten(String text) {
        if (text == null) return null;
        String v = text.strip().replaceAll("\\s+", " ");
        if (v.isEmpty()) return null;
        return v.length() > 300 ? v.substring(0, 297) + "..." : v;
    }

    static String safePermalink(String url) {
        return url != null && url.matches("https://(www\\.)?instagram\\.com/[A-Za-z0-9_./?=&-]{1,900}") ? url : null;
    }

    private static OffsetDateTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s, IG_TIME);
        } catch (Exception e) {
            try {
                return OffsetDateTime.parse(s);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}

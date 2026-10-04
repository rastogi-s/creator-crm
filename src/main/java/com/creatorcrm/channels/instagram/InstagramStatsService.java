package com.creatorcrm.channels.instagram;

import com.creatorcrm.domain.AppState;
import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The creator's own Instagram numbers (followers, engagement on recent posts and, when the insights
 * permission was granted, 28-day reach), so drafts and rate replies can quote real figures.
 */
@Service
public class InstagramStatsService {
    private static final Logger log = LoggerFactory.getLogger(InstagramStatsService.class);
    static final String STATE_KEY = "instagram.stats";
    static final int POSTS_SAMPLED = 12;
    private static final Duration REFRESH_EVERY = Duration.ofHours(20);
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Stats(String username, long followers, long follows, long mediaCount, int postsSampled,
                        long avgLikes, long avgComments, double engagementRatePct, Long reach28d,
                        String updatedAt) {}

    private final InstagramApi api;
    private final SecretStore secrets;
    private final AppStateRepo state;

    public InstagramStatsService(InstagramApi api, SecretStore secrets, AppStateRepo state) {
        this.api = api;
        this.secrets = secrets;
        this.state = state;
    }

    public Optional<Stats> current() {
        return state.findById(STATE_KEY).map(s -> s.stateValue).filter(v -> v != null && !v.isBlank()).flatMap(v -> {
            try {
                return Optional.of(JSON.readValue(v, Stats.class));
            } catch (Exception e) {
                log.warn("Ignoring unreadable Instagram stats: {}", e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** Daily refresh from the scheduler; quiet when Instagram isn't connected or the numbers are fresh. */
    public void refreshIfStale() {
        if (!secrets.has(SecretName.INSTAGRAM_ACCESS_TOKEN)) return;
        boolean fresh = current().map(s -> OffsetDateTime.parse(s.updatedAt()).isAfter(OffsetDateTime.now().minus(REFRESH_EVERY))).orElse(false);
        if (fresh) return;
        try {
            refresh();
        } catch (Exception e) {
            log.warn("Could not refresh Instagram stats: {}", e.getMessage());
        }
    }

    public Stats refresh() throws Exception {
        String token = secrets.require(SecretName.INSTAGRAM_ACCESS_TOKEN);
        JsonNode me = api.get(token, "/me", Map.of("fields", "user_id,username,followers_count,follows_count,media_count"));
        long followers = me.path("followers_count").asLong();

        JsonNode media = api.get(token, "/me/media", Map.of(
                "fields", "like_count,comments_count,timestamp", "limit", String.valueOf(POSTS_SAMPLED)));
        long likes = 0, comments = 0;
        int posts = 0;
        for (JsonNode m : media.path("data")) {
            if (!m.has("like_count")) continue; // the owner hid likes on this post
            likes += m.path("like_count").asLong();
            comments += m.path("comments_count").asLong();
            posts++;
        }
        long avgLikes = posts == 0 ? 0 : Math.round((double) likes / posts);
        long avgComments = posts == 0 ? 0 : Math.round((double) comments / posts);
        double rate = followers == 0 || posts == 0 ? 0
                : Math.round(1000.0 * (likes + comments) / posts / followers) / 10.0;

        Stats s = new Stats(me.path("username").asText(""), followers, me.path("follows_count").asLong(),
                me.path("media_count").asLong(), posts, avgLikes, avgComments, rate,
                reach28d(token, me.path("user_id").asText(me.path("id").asText())), OffsetDateTime.now().toString());
        AppState row = new AppState();
        row.stateKey = STATE_KEY;
        row.stateValue = JSON.writeValueAsString(s);
        state.save(row);
        return s;
    }

    /** Needs instagram_business_manage_insights; older connections without it just skip reach. */
    private Long reach28d(String token, String userId) {
        if (userId == null || userId.isBlank()) return null;
        long until = OffsetDateTime.now().toEpochSecond();
        long since = OffsetDateTime.now().minusDays(28).toEpochSecond();
        try {
            JsonNode r = api.get(token, "/" + userId + "/insights", Map.of("metric", "reach", "period", "day",
                    "metric_type", "total_value", "since", String.valueOf(since), "until", String.valueOf(until)));
            JsonNode v = r.path("data").path(0).path("total_value").path("value");
            return v.isMissingNode() || v.isNull() ? null : v.asLong();
        } catch (Exception e) {
            log.info("Instagram reach not available ({}); reconnect Instagram to grant insights access.", e.getMessage());
            return null;
        }
    }

    /** The numbers as a section of the creator profile given to the draft writer. Empty when unknown. */
    public String profileSection() {
        return current().map(s -> {
            StringBuilder sb = new StringBuilder("# My Instagram stats (as of ")
                    .append(OffsetDateTime.parse(s.updatedAt()).toLocalDate()).append("; quote these exact numbers, never round up)\n")
                    .append("- Followers: ").append(String.format("%,d", s.followers())).append('\n');
            if (s.postsSampled() > 0) {
                sb.append("- Engagement rate: ").append(s.engagementRatePct()).append("% (average ")
                        .append(String.format("%,d", s.avgLikes())).append(" likes and ")
                        .append(String.format("%,d", s.avgComments())).append(" comments over the last ")
                        .append(s.postsSampled()).append(" posts)\n");
            }
            if (s.reach28d() != null) {
                sb.append("- Accounts reached in the last 28 days: ").append(String.format("%,d", s.reach28d())).append('\n');
            }
            return sb.toString();
        }).orElse("");
    }
}

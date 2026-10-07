package com.creatorcrm.campaigns;

import com.creatorcrm.domain.AppState;
import com.creatorcrm.domain.CampaignEvent;
import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.repo.CampaignEventRepo;
import com.creatorcrm.repo.CampaignTargetRepo;
import com.creatorcrm.settings.SettingsService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The send queue's own state, kept in app_state so it survives a restart: whether sending is paused (and why), when
 * the next email may go, and how many new pitches today may bring (the daily cap and the warm-up).
 */
@Component
public class CampaignState {
    static final String PAUSED = "campaigns.pausedReason";
    static final String PAUSED_AT = "campaigns.pausedAt";
    static final String RESUMED_AT = "campaigns.resumedAt";
    static final String NEXT_SEND = "campaigns.nextSendAt";
    static final String LAST_SCANNED = "campaigns.lastScannedMessageId";

    /** Warm-up: new pitches a day in the first week, and how many more each week after. */
    static final int WARMUP_START = 10;
    static final int WARMUP_STEP = 5;
    /** Above this share of bounces over the last two weeks, the warm-up goes back to its first-week pace. */
    static final double MAX_BOUNCE_RATE = 0.02;

    private final AppStateRepo state;
    private final SettingsService settings;
    private final CampaignTargetRepo targets;
    private final CampaignEventRepo events;

    public CampaignState(AppStateRepo state, SettingsService settings, CampaignTargetRepo targets, CampaignEventRepo events) {
        this.state = state;
        this.settings = settings;
        this.targets = targets;
        this.events = events;
    }

    public Optional<String> pausedReason() {
        return get(PAUSED);
    }

    public Optional<String> pausedAt() {
        return get(PAUSED_AT);
    }

    public void pause(String reason) {
        put(PAUSED, reason);
        put(PAUSED_AT, OffsetDateTime.now().toString());
    }

    public void resume() {
        put(PAUSED, "");
        put(PAUSED_AT, "");
        put(RESUMED_AT, OffsetDateTime.now().toString());
    }

    /**
     * Where today's count of bounces and opt-outs starts for the auto-pause: midnight, or when she last pressed
     * Resume, so the problems she already looked at don't pause sending again.
     */
    public OffsetDateTime pauseCountFrom() {
        OffsetDateTime today = startOfToday();
        return get(RESUMED_AT).map(OffsetDateTime::parse).filter(r -> r.isAfter(today)).orElse(today);
    }

    public Optional<OffsetDateTime> nextSendAt() {
        return get(NEXT_SEND).map(OffsetDateTime::parse);
    }

    public void nextSendAt(OffsetDateTime at) {
        put(NEXT_SEND, at.toString());
    }

    public long lastScanned() {
        return get(LAST_SCANNED).map(Long::parseLong).orElse(-1L);
    }

    public void lastScanned(long id) {
        put(LAST_SCANNED, String.valueOf(id));
    }

    /** Midnight today in her time zone: "today" for the cap and the one-per-brand rule. */
    public OffsetDateTime startOfToday() {
        return settings.today().atStartOfDay(settings.zone()).toOffsetDateTime();
    }

    public long sentToday() {
        return targets.countBySentAtGreaterThanEqual(startOfToday());
    }

    /**
     * New pitches allowed today: her cap, or less while warming up (10 a day the first week, 5 more each week, back
     * to 10 when more than 2% bounced over the last two weeks).
     */
    public int dailyAllowance() {
        int cap = settings.campaignDailyCap();
        if (!settings.campaignWarmup()) return cap;
        OffsetDateTime first = targets.findFirstBySentAtNotNullOrderBySentAtAsc().map(t -> t.sentAt).orElse(null);
        if (first == null) return Math.min(cap, WARMUP_START);
        OffsetDateTime twoWeeks = OffsetDateTime.now().minusDays(14);
        long sent = targets.countBySentAtGreaterThanEqual(twoWeeks);
        long bounced = events.countByKindAndAtGreaterThanEqual(CampaignEvent.Kind.BOUNCE, twoWeeks);
        if (sent >= 10 && (double) bounced / sent > MAX_BOUNCE_RATE) return Math.min(cap, WARMUP_START);
        long weeks = Duration.between(first, OffsetDateTime.now()).toDays() / 7;
        return (int) Math.min(cap, WARMUP_START + WARMUP_STEP * weeks);
    }

    public ZonedDateTime now() {
        return ZonedDateTime.now(settings.zone());
    }

    private Optional<String> get(String key) {
        return state.findById(key).map(s -> s.stateValue).filter(v -> v != null && !v.isBlank());
    }

    private void put(String key, String value) {
        AppState s = new AppState();
        s.stateKey = key;
        s.stateValue = value;
        state.save(s);
    }
}

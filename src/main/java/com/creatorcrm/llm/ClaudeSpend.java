package com.creatorcrm.llm;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.Usage;
import com.creatorcrm.domain.AppState;
import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.settings.SettingsService;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * What the app has spent on the Claude API, and whether the credits are running out.
 *
 * <p>The API has no balance endpoint for a normal key, so spend is estimated from the token counts in every reply
 * and the published per-token prices. The credit balance is whatever was last typed in from the Console; what's
 * left is that minus the spend since. Totals live in app_state as whole micro-dollars.
 */
@Service
public class ClaudeSpend {
    private static final Logger log = LoggerFactory.getLogger(ClaudeSpend.class);

    /** What a call was for; shown in Settings. */
    public enum Feature { CLASSIFY, DRAFT, RESEARCH }

    /** USD per million tokens. Cache writes cost 1.25× input (5-minute cache). */
    record Price(double input, double output, double cacheRead) {}

    /** USD per web search. */
    static final double WEB_SEARCH = 0.01;
    /** Warn when this share of the last entered balance is left. */
    static final double LOW_SHARE = 0.2;

    static final String P = "claude.spend.";
    static final String SINCE = P + "since";
    static final String TOTAL = P + "total";
    static final String CALLS = P + "calls";
    static final String MONTH = P + "month.";
    static final String FEATURE = P + "feature.";
    static final String BEFORE = P + "before";
    static final String BALANCE = "claude.balance.amount";
    static final String BALANCE_SPENT = "claude.balance.spentAtEntry";
    static final String BALANCE_AT = "claude.balance.at";
    static final String OUT = "claude.credits.outSince";

    public record Month(String month, double usd) {}

    public record Alert(String level, String message) {}

    public record Summary(double totalUsd, double trackedUsd, double beforeUsd, String trackedSince, long calls,
                          double thisMonthUsd, List<Month> months, Map<String, Double> byFeature,
                          Double balanceUsd, String balanceAt, Double remainingUsd, String outOfCreditsSince,
                          Alert alert) {}

    private final AppStateRepo state;
    private final SettingsService settings;

    public ClaudeSpend(AppStateRepo state, SettingsService settings) {
        this.state = state;
        this.settings = settings;
    }

    static Price price(String model) {
        String m = model == null ? "" : model;
        if (m.startsWith("claude-fable-5-1") || m.startsWith("claude-mythos-5-1")) return new Price(10, 50, 0.25);
        if (m.startsWith("claude-fable") || m.startsWith("claude-mythos")) return new Price(10, 50, 1.0);
        if (m.startsWith("claude-opus-5-5")) return new Price(4, 20, 0.20);
        if (m.startsWith("claude-sonnet-5")) return new Price(2, 10, 0.20);
        if (m.startsWith("claude-sonnet-4")) return new Price(3, 15, 0.30);
        if (m.startsWith("claude-haiku-4")) return new Price(1, 5, 0.10);
        // Opus 5 / 4.x, and anything unknown: Opus rates, so an unknown model is never under-counted much.
        return new Price(5, 25, 0.50);
    }

    /** Estimated cost in USD of one reply. */
    static double cost(String model, Usage u) {
        Price p = price(model);
        double tokens = u.inputTokens() * p.input()
                + u.cacheCreationInputTokens().orElse(0L) * p.input() * 1.25
                + u.cacheReadInputTokens().orElse(0L) * p.cacheRead()
                + u.outputTokens() * p.output();
        long searches = u.serverToolUse().map(s -> s.webSearchRequests()).orElse(0L);
        return tokens / 1_000_000 + searches * WEB_SEARCH;
    }

    /** Adds one reply's cost to the totals. A reply means the credits work again. */
    public synchronized void record(Feature feature, Message response) {
        long micros = Math.round(cost(response.model().asString(), response.usage()) * 1_000_000);
        if (get(SINCE).isEmpty()) put(SINCE, settings.today().toString());
        add(TOTAL, micros);
        add(CALLS, 1);
        add(MONTH + YearMonth.from(settings.today()), micros);
        add(FEATURE + feature.name(), micros);
        if (get(OUT).isPresent()) {
            put(OUT, "");
            log.info("Claude credits work again");
        }
    }

    /** The API said the credits are used up. Logged as an error once per outage, which files an error report. */
    public synchronized void markOutOfCredits(String apiMessage) {
        if (get(OUT).isPresent()) return;
        put(OUT, Instant.now().toString());
        log.error("Claude credits have run out. Not a bug: add credits in the Claude Console (Billing). API said: {}",
                apiMessage);
    }

    public boolean outOfCredits() {
        return get(OUT).isPresent();
    }

    /** "Spent before tracking started", typed in from the Console's cost page, so the total covers everything. */
    public synchronized void setSpentBefore(double usd) {
        if (usd < 0 || usd > 1_000_000) throw new IllegalArgumentException("Enter an amount between 0 and 1,000,000");
        put(BEFORE, Long.toString(Math.round(usd * 1_000_000)));
    }

    /** The Console's credit balance right now. What's left is counted down from here. Blank (null) clears it. */
    public synchronized void setBalance(Double usd) {
        if (usd == null) {
            put(BALANCE, "");
            return;
        }
        if (usd < 0 || usd > 1_000_000) throw new IllegalArgumentException("Enter an amount between 0 and 1,000,000");
        put(BALANCE, Long.toString(Math.round(usd * 1_000_000)));
        put(BALANCE_SPENT, Long.toString(micros(TOTAL)));
        put(BALANCE_AT, Instant.now().toString());
        if (usd > 0) put(OUT, ""); // topped up: try again right away
    }

    public synchronized Summary summary() {
        long tracked = micros(TOTAL);
        long before = micros(BEFORE);
        YearMonth now = YearMonth.from(settings.today());
        List<Month> months = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            YearMonth m = now.minusMonths(i);
            long v = micros(MONTH + m);
            if (v > 0 || i == 0) months.add(new Month(m.toString(), usd(v)));
        }
        Map<String, Double> byFeature = new LinkedHashMap<>();
        for (Feature f : Feature.values()) byFeature.put(f.name(), usd(micros(FEATURE + f.name())));

        Double balance = get(BALANCE).map(v -> usd(Long.parseLong(v))).orElse(null);
        Double remaining = balance == null ? null
                : Math.max(0, balance - usd(tracked - get(BALANCE_SPENT).map(Long::parseLong).orElse(tracked)));
        String out = get(OUT).orElse(null);
        Alert alert = out != null
                ? new Alert("OUT", "Claude credits have run out, so new messages aren't being read and drafts can't be written. "
                        + "Add credits in the Claude Console, then enter the new balance in Settings.")
                : remaining != null && remaining <= balance * LOW_SHARE
                ? new Alert("LOW", String.format("Claude credits are running low: about $%.2f left. Top up soon in the Claude Console.", remaining))
                : null;
        return new Summary(usd(tracked + before), usd(tracked), usd(before), get(SINCE).orElse(null),
                micros(CALLS), usd(micros(MONTH + now)), months, byFeature, balance, get(BALANCE_AT).orElse(null),
                remaining, out, alert);
    }

    private static double usd(long micros) {
        return Math.round(micros / 10_000.0) / 100.0;
    }

    private long micros(String key) {
        return get(key).map(Long::parseLong).orElse(0L);
    }

    private void add(String key, long delta) {
        put(key, Long.toString(micros(key) + delta));
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

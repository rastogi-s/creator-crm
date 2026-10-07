package com.creatorcrm.contacts;

import com.creatorcrm.domain.AppState;
import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.settings.SettingsService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Credits the contact finders have used this month, and her own monthly limit for each, so the app never spends
 * past Hunter's free 50 (or whatever she sets). Hunter's own count, read from its free account endpoint after every
 * paid call, wins when it says less is left. Counts live in app_state, in half credits (a Hunter check costs 0.5).
 */
@Service
public class FinderCredits {

    public enum Provider { HUNTER, APOLLO }

    /** Hunter Free gives 50 credits a month. */
    public static final int HUNTER_DEFAULT_LIMIT = 50;
    /** Apollo's free plan has a small monthly allowance; reveals cost one each. */
    public static final int APOLLO_DEFAULT_LIMIT = 25;
    /** Hunter credit costs: a domain search is 1 credit per 10 addresses it returns, a check is half a credit. */
    public static final double SEARCH_PER_10 = 1.0;
    public static final double VERIFY = 0.5;
    /** Warn when this share of the month's limit is left. */
    static final double LOW_SHARE = 0.2;

    static final String P = "finder.";

    public record Alert(String level, String message) {}

    /**
     * {@code serviceUsed}/{@code serviceAvailable}: what Hunter itself last reported for its billing period
     * (null until it has been asked, and for Apollo).
     */
    public record Status(String provider, boolean connected, double usedThisMonth, int monthlyLimit, double remaining,
                         String plan, Double serviceUsed, Double serviceAvailable, String resetDate, String checkedAt,
                         Alert alert) {}

    private final AppStateRepo state;
    private final SettingsService settings;

    public FinderCredits(AppStateRepo state, SettingsService settings) {
        this.state = state;
        this.settings = settings;
    }

    public synchronized double usedThisMonth(Provider p) {
        return halves(key(p, "month." + YearMonth.from(settings.today()))) / 2.0;
    }

    public synchronized int limit(Provider p) {
        return get(key(p, "limit")).map(Integer::parseInt)
                .orElse(p == Provider.HUNTER ? HUNTER_DEFAULT_LIMIT : APOLLO_DEFAULT_LIMIT);
    }

    public synchronized void setLimit(Provider p, int credits) {
        if (credits < 0 || credits > 100_000) throw new IllegalArgumentException("Enter a monthly limit between 0 and 100,000 credits");
        put(key(p, "limit"), String.valueOf(credits));
    }

    /** Credits still allowed this month: her limit minus what's used, or less if the service itself says so. */
    public synchronized double remaining(Provider p) {
        double left = Math.max(0, limit(p) - usedThisMonth(p));
        if (p == Provider.HUNTER) {
            Optional<Double> used = get(key(p, "service.used")).map(Double::parseDouble);
            Optional<Double> avail = get(key(p, "service.available")).map(Double::parseDouble);
            if (used.isPresent() && avail.isPresent() && serviceFresh(p)) left = Math.min(left, Math.max(0, avail.get() - used.get()));
        }
        return left;
    }

    /** Stops before a call that would go past the month's limit. */
    public synchronized void require(Provider p, double credits) {
        if (remaining(p) + 1e-9 >= credits) return;
        String name = p == Provider.HUNTER ? "Hunter" : "Apollo";
        throw new IllegalStateException("This month's " + name + " credits are used up (" + fmt(usedThisMonth(p)) + " of "
                + limit(p) + "). They come back " + nextMonth() + ", or raise the limit in Settings, Accounts, Contact finders.");
    }

    public synchronized void spend(Provider p, double credits) {
        if (credits <= 0) return;
        String k = key(p, "month." + YearMonth.from(settings.today()));
        put(k, String.valueOf(halves(k) + Math.round(credits * 2)));
    }

    /** What Hunter says about her plan. */
    public synchronized void recordAccount(HunterApi.Account a) {
        put(key(Provider.HUNTER, "service.used"), String.valueOf(a.used()));
        put(key(Provider.HUNTER, "service.available"), String.valueOf(a.available()));
        put(key(Provider.HUNTER, "service.plan"), a.plan() == null ? "" : a.plan());
        put(key(Provider.HUNTER, "service.reset"), a.resetDate() == null ? "" : a.resetDate());
        put(key(Provider.HUNTER, "service.at"), Instant.now().toString());
    }

    /** When Hunter was last asked to search this domain, so the same search isn't paid for twice. */
    public synchronized Optional<LocalDate> searchedOn(Provider p, String domain) {
        return get(key(p, "searched." + domain)).map(LocalDate::parse);
    }

    public synchronized void markSearched(Provider p, String domain) {
        put(key(p, "searched." + domain), settings.today().toString());
    }

    public synchronized Status status(Provider p, boolean connected) {
        double used = usedThisMonth(p);
        int limit = limit(p);
        double left = remaining(p);
        String name = p == Provider.HUNTER ? "Hunter" : "Apollo";
        Alert alert = !connected ? null
                : left <= 0 ? new Alert("OUT", "This month's " + name + " credits are used up. They come back " + nextMonth() + ".")
                : limit > 0 && left <= limit * LOW_SHARE ? new Alert("LOW", "Only " + fmt(left) + " " + name + " credits left this month.")
                : null;
        boolean svc = p == Provider.HUNTER && serviceFresh(p);
        return new Status(p.name(), connected, used, limit, left,
                svc ? get(key(p, "service.plan")).orElse(null) : null,
                svc ? get(key(p, "service.used")).map(Double::parseDouble).orElse(null) : null,
                svc ? get(key(p, "service.available")).map(Double::parseDouble).orElse(null) : null,
                svc ? get(key(p, "service.reset")).orElse(null) : null,
                get(key(p, "service.at")).orElse(null), alert);
    }

    /** Hunter's numbers count only until its period resets. */
    private boolean serviceFresh(Provider p) {
        Optional<String> reset = get(key(p, "service.reset"));
        if (reset.isPresent()) {
            try {
                return settings.today().isBefore(LocalDate.parse(reset.get().substring(0, 10)));
            } catch (RuntimeException e) {
                // fall through to the age check
            }
        }
        return get(key(p, "service.at")).map(Instant::parse)
                .map(at -> YearMonth.from(at.atZone(settings.zone())).equals(YearMonth.from(settings.today())))
                .orElse(false);
    }

    private String nextMonth() {
        LocalDate d = settings.today().withDayOfMonth(1).plusMonths(1);
        return "on " + d.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " 1";
    }

    static String fmt(double credits) {
        return credits == Math.rint(credits) ? String.valueOf((long) credits) : String.valueOf(credits);
    }

    /** app_state keys hold 100 characters; a very long domain is shortened to a hash so it still fits. */
    static String key(Provider p, String rest) {
        String k = P + p.name().toLowerCase(java.util.Locale.ROOT) + "." + rest;
        return k.length() <= 100 ? k : k.substring(0, 80) + "#" + Integer.toHexString(k.hashCode());
    }

    private long halves(String key) {
        return get(key).map(Long::parseLong).orElse(0L);
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

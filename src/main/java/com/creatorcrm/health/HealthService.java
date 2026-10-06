package com.creatorcrm.health;

import com.creatorcrm.backup.AutoBackupService;
import com.creatorcrm.channels.ChannelConnector;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.ClaudeSpend;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.update.UpdateService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * "Everything working?": one plain-words line each for Claude, Gmail, Instagram, backups and updates, built from what
 * the app already remembers (last sync, last error, last backup, last update check). Nothing here calls out to the
 * internet, so the card is instant and can't make things worse.
 *
 * <p>Every check that needs attention carries exactly one fix: a page to open, a sign-in to redo, or a button to press.
 */
@Service
public class HealthService {

    public enum State { ok, warn, problem, off }

    /** How the page acts on a fix: open a link, start a sign-in (POST, then go to the returned url), press a button. */
    public enum FixKind { link, oauth, post, install }

    public record Fix(String label, FixKind kind, String target) {}

    /**
     * {@code headline}: a few words for the one-line strip on Today ("Gmail needs reconnecting").
     * {@code summary}: one plain sentence for the card ("Stopped connecting on Tuesday. Sign in to Google again.").
     */
    public record Check(String id, String name, State state, String headline, String summary, String lastCheckedAt, Fix fix) {}

    /** What the app remembers about Claude. {@code aiError} as stored: "timestamp message". */
    record ClaudeFacts(boolean configured, String outOfCreditsSince, ClaudeSpend.Alert alert, String aiError) {}

    /** What the app remembers about one channel. Times are ISO strings as stored, or null. */
    record ChannelFacts(boolean connected, boolean canSignIn, String lastSync, String error, String failingSince,
                        String tokenExpiresAt) {}

    /** A sync error this long is a problem even when it doesn't look like a sign-in one (same as the page banner). */
    static final Duration PROBLEM_AFTER = Duration.ofHours(1);
    /** Syncs run every 30 minutes; this long without one means something is stuck. */
    static final Duration STALE_SYNC = Duration.ofHours(6);
    /** Backups run nightly; two days without one is worth a nudge. */
    static final Duration STALE_BACKUP = Duration.ofHours(48);

    private static final Pattern SIGN_IN = Pattern.compile(
            "(?i)invalid_grant|invalid_token|unauthori[sz]ed|\\b401\\b|expired|revoked|OAuthException|token has been");
    private static final Pattern BAD_KEY = Pattern.compile("(?i)\\(401\\)|\\b401\\b|authentication|invalid x-api-key|api key");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    private static final Fix SETTINGS = new Fix("Open Settings", FixKind.link, "#settings");
    private static final Fix SYNC_NOW = new Fix("Check now", FixKind.post, "/api/sync");

    private final IngestionService ingestion;
    private final List<ChannelConnector> connectors;
    private final SecretStore secrets;
    private final ClaudeSpend spend;
    private final AutoBackupService backups;
    private final UpdateService updates;
    private final SettingsService settings;

    public HealthService(IngestionService ingestion, List<ChannelConnector> connectors, SecretStore secrets,
                         ClaudeSpend spend, AutoBackupService backups, UpdateService updates, SettingsService settings) {
        this.ingestion = ingestion;
        this.connectors = connectors;
        this.secrets = secrets;
        this.spend = spend;
        this.backups = backups;
        this.updates = updates;
        this.settings = settings;
    }

    /** All five checks, from remembered state only. */
    public List<Check> checks() {
        ZonedDateTime now = ZonedDateTime.now(settings.zone());
        ClaudeSpend.Summary sp = spend.summary();
        IngestionService.Status st = ingestion.status();
        ClaudeFacts claude = new ClaudeFacts(st.aiConfigured(), sp.outOfCreditsSince(), sp.alert(), st.aiError());
        return List.of(
                claude(claude, now),
                gmail(channelFacts(Platform.EMAIL, secrets.has(SecretName.GOOGLE_CLIENT_ID)
                        && secrets.has(SecretName.GOOGLE_CLIENT_SECRET), null), now),
                instagram(channelFacts(Platform.INSTAGRAM, secrets.has(SecretName.INSTAGRAM_APP_ID)
                        && secrets.has(SecretName.INSTAGRAM_APP_SECRET),
                        secrets.get(SecretName.INSTAGRAM_TOKEN_EXPIRES_AT).orElse(null)), now),
                backups(backups.status(), now),
                updates(updates.status(), now));
    }

    /** Asks GitHub for the latest version again (the only check that is cheap to redo), then reports everything. */
    public List<Check> recheck() {
        if (updates.status().enabled()) {
            try {
                updates.check();
            } catch (RuntimeException e) {
                // remembered as the check error; the card shows it
            }
        }
        return checks();
    }

    private ChannelFacts channelFacts(Platform platform, boolean canSignIn, String tokenExpiresAt) {
        Optional<ChannelConnector> c = connectors.stream().filter(x -> x.platform() == platform).findFirst();
        String key = "sync." + platform;
        return new ChannelFacts(c.map(ChannelConnector::isConnected).orElse(false), canSignIn,
                ingestion.read(key).orElse(null), ingestion.read(key + ".error").orElse(null),
                ingestion.read(key + ".failingSince").orElse(null), tokenExpiresAt);
    }

    // ---------------------------------------------------------------- the rules, one per check

    static Check claude(ClaudeFacts f, ZonedDateTime now) {
        String id = "claude", name = "Claude";
        if (!f.configured()) {
            return new Check(id, name, State.off, "Claude isn't set up",
                    "Not set up yet. Add your Claude key so new messages get read and drafts get written.", null,
                    new Fix("Set up", FixKind.link, "#settings"));
        }
        Fix credits = new Fix("Top up", FixKind.link, "#settings?spend");
        if (f.outOfCreditsSince() != null) {
            return new Check(id, name, State.problem, "Claude is out of credits",
                    "Ran out of credits " + day(parse(f.outOfCreditsSince()), now) + ", so new messages aren't being read.",
                    null, credits);
        }
        if (f.aiError() != null && !f.aiError().isBlank()) {
            OffsetDateTime at = errorTime(f.aiError());
            String reason = f.aiError().replaceFirst("^\\S+\\s+", "");
            if (BAD_KEY.matcher(reason).find()) {
                return new Check(id, name, State.problem, "Claude's key stopped working",
                        "The Claude key stopped working" + (at == null ? "" : " " + day(at, now))
                                + ". Paste a new key in Settings.", null, new Fix("Update key", FixKind.link, "#settings"));
            }
            return new Check(id, name, State.warn, "Claude had trouble reading a message",
                    "Had trouble reading a message" + (at == null ? "" : " " + day(at, now))
                            + ". It tries again by itself.", null, new Fix("Try again", FixKind.post, "/api/sync"));
        }
        if (f.alert() != null && "LOW".equals(f.alert().level())) {
            return new Check(id, name, State.warn, "Claude credits are running low",
                    f.alert().message().replaceFirst("^Claude credits are running low:\\s*", "Credits are running low: "),
                    null, credits);
        }
        return new Check(id, name, State.ok, "Claude is working", "Connected and reading your messages.", null, null);
    }

    static Check gmail(ChannelFacts f, ZonedDateTime now) {
        return channel("gmail", "Gmail", "Google", "/oauth/google/start", "mail", f, now);
    }

    static Check instagram(ChannelFacts f, ZonedDateTime now) {
        return channel("instagram", "Instagram", "Instagram", "/oauth/instagram/start", "messages", f, now);
    }

    private static Check channel(String id, String name, String signInWith, String oauthStart, String what,
                                 ChannelFacts f, ZonedDateTime now) {
        if (!f.connected()) {
            return new Check(id, name, State.off, name + " isn't set up", "Not set up yet.", null,
                    new Fix("Set up", FixKind.link, "#settings"));
        }
        Fix reconnect = f.canSignIn() ? new Fix("Reconnect", FixKind.oauth, oauthStart)
                : new Fix("Reconnect", FixKind.link, "#settings");
        OffsetDateTime last = parse(f.lastSync());
        OffsetDateTime failing = parse(f.failingSince());
        OffsetDateTime expires = parse(f.tokenExpiresAt());
        String needsReconnecting = name + " needs reconnecting";
        if (f.error() != null && !f.error().isBlank() && failing != null) {
            if (SIGN_IN.matcher(f.error()).find()) {
                return new Check(id, name, State.problem, needsReconnecting,
                        "Stopped connecting " + day(failing, now) + ". Sign in to " + signInWith + " again.",
                        f.lastSync(), reconnect);
            }
            if (failing.toInstant().isBefore(now.toInstant().minus(PROBLEM_AFTER))) {
                return new Check(id, name, State.problem, name + " stopped connecting",
                        "Hasn't been able to check for new " + what + " since " + time(failing, now)
                                + ". Check the internet, or reconnect.", f.lastSync(), reconnect);
            }
            return new Check(id, name, State.warn, name + " missed a check",
                    "The last check didn't work. It tries again by itself.", f.lastSync(), SYNC_NOW);
        }
        if (expires != null && expires.toInstant().isBefore(now.toInstant())) {
            return new Check(id, name, State.problem, needsReconnecting,
                    "The sign-in ran out " + day(expires, now) + ". Sign in to " + signInWith + " again.",
                    f.lastSync(), reconnect);
        }
        if (last == null) {
            return new Check(id, name, State.ok, name + " is connected", "Connected. The first check is coming up.",
                    null, null);
        }
        if (last.toInstant().isBefore(now.toInstant().minus(STALE_SYNC))) {
            return new Check(id, name, State.warn, name + " hasn't checked lately",
                    "Hasn't checked for new " + what + " since " + time(last, now) + ".", f.lastSync(), SYNC_NOW);
        }
        return new Check(id, name, State.ok, name + " is connected", "Connected, last checked " + time(last, now) + ".",
                f.lastSync(), null);
    }

    static Check backups(AutoBackupService.Status b, ZonedDateTime now) {
        String id = "backups", name = "Backups";
        Fix runNow = new Fix("Back up now", FixKind.post, "/api/backup/auto/run");
        OffsetDateTime last = parse(b.lastBackupAt());
        if (!b.enabled()) {
            return new Check(id, name, State.warn, "Backups are off",
                    "Automatic backups are off, so a broken laptop could lose your deals.", b.lastBackupAt(),
                    new Fix("Turn on", FixKind.link, "#settings?backup"));
        }
        if (b.lastError() != null) {
            return new Check(id, name, State.problem, "The last backup didn't save",
                    "The last backup didn't save" + (last == null ? "." : ". The newest good one is from " + time(last, now) + "."),
                    b.lastBackupAt(), runNow);
        }
        if (last == null) {
            return new Check(id, name, State.warn, "No backup yet", "Turned on, but there's no backup yet.", null, runNow);
        }
        if (last.toInstant().isBefore(now.toInstant().minus(STALE_BACKUP))) {
            return new Check(id, name, State.warn, "Backups are behind",
                    "The last backup was " + day(last, now) + ".", b.lastBackupAt(), runNow);
        }
        return new Check(id, name, State.ok, "Backups are saving", "Saved " + day(last, now) + " at "
                + CLOCK.format(last.atZoneSameInstant(now.getZone())) + ".", b.lastBackupAt(), null);
    }

    static Check updates(UpdateService.Status u, ZonedDateTime now) {
        String id = "updates", name = "Updates";
        String checked = u.checkedAt() == null ? null : u.checkedAt().toString();
        if (!u.enabled()) {
            return new Check(id, name, State.off, "Update checks are off",
                    "You're on version " + u.currentVersion() + ". Update checks are turned off for this install.",
                    null, null);
        }
        if (u.installing()) {
            return new Check(id, name, State.ok, "Updating",
                    u.installState() != null ? u.installState() : "Updating now…", checked, null);
        }
        if (u.installState() != null && u.installState().toLowerCase(Locale.ROOT).contains("failed") && u.updateAvailable()) {
            return new Check(id, name, State.problem, "The update didn't finish",
                    "The update to version " + u.latestVersion() + " didn't finish. Nothing was changed.", checked,
                    u.canInstall() ? new Fix("Try again", FixKind.install, "/api/updates/install") : SETTINGS);
        }
        if (u.updateAvailable()) {
            Fix fix = u.canInstall() ? new Fix("Update now", FixKind.install, "/api/updates/install")
                    : u.releaseUrl() != null && !u.releaseUrl().isBlank() ? new Fix("Download", FixKind.link, u.releaseUrl())
                    : SETTINGS;
            return new Check(id, name, State.warn, "An update is ready",
                    "Version " + u.latestVersion() + " is ready (you have " + u.currentVersion() + ").", checked, fix);
        }
        Fix checkNow = new Fix("Check now", FixKind.post, "/api/updates/check");
        if (u.checkError() != null) {
            return new Check(id, name, State.warn, "Couldn't check for updates",
                    "Couldn't check for updates" + (u.checkedAt() == null ? "" : " " + day(u.checkedAt(), now))
                            + ". Usually the internet was down.", checked, checkNow);
        }
        if (u.checkedAt() == null) {
            return u.autoCheck()
                    ? new Check(id, name, State.ok, "On version " + u.currentVersion(),
                            "You're on version " + u.currentVersion() + ".", null, null)
                    : new Check(id, name, State.off, "Update checks are off",
                            "You're on version " + u.currentVersion() + ". Automatic update checks are off.", null, checkNow);
        }
        return new Check(id, name, State.ok, "Up to date", "Up to date (version " + u.currentVersion() + "), checked "
                + time(u.checkedAt(), now) + ".", checked, null);
    }

    // ---------------------------------------------------------------- plain-words times

    /** "9:30 AM" today, "yesterday at 9:30 AM", "Tuesday" this week, else "Oct 3". */
    static String time(OffsetDateTime t, ZonedDateTime now) {
        ZonedDateTime z = t.atZoneSameInstant(now.getZone());
        long days = java.time.temporal.ChronoUnit.DAYS.between(z.toLocalDate(), now.toLocalDate());
        if (days <= 0) return CLOCK.format(z);
        if (days == 1) return "yesterday at " + CLOCK.format(z);
        if (days < 7) return z.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return DATE.format(z);
    }

    /** "today", "yesterday", "on Tuesday" this week, else "on Oct 3". */
    static String day(OffsetDateTime t, ZonedDateTime now) {
        if (t == null) return "recently";
        ZonedDateTime z = t.atZoneSameInstant(now.getZone());
        long days = java.time.temporal.ChronoUnit.DAYS.between(z.toLocalDate(), now.toLocalDate());
        if (days <= 0) return "today";
        if (days == 1) return "yesterday";
        if (days < 7) return "on " + z.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        return "on " + DATE.format(z);
    }

    private static OffsetDateTime parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return OffsetDateTime.parse(iso.trim());
        } catch (RuntimeException e) {
            try {
                return java.time.Instant.parse(iso.trim()).atOffset(java.time.ZoneOffset.UTC);
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    /** Stored errors start with the time they happened. */
    private static OffsetDateTime errorTime(String stored) {
        return parse(stored.split("\\s+", 2)[0]);
    }
}

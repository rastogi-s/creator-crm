package com.creatorcrm.diagnostics;

import ch.qos.logback.classic.LoggerContext;
import com.creatorcrm.domain.AppState;
import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.update.UpdateService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sends the app's errors to the developer as GitHub issues, so problems on a computer nobody is watching still
 * get seen and fixed.
 *
 * <p>Every error the app logs is grouped by a fingerprint; each group becomes one issue (title prefixed
 * {@code [auto-report]}) and later repeats add a comment with how many more times it happened. Everything is
 * redacted first (see {@link Redactor}). Nothing is sent until an error-report token is saved in Settings, and it
 * can be switched off there. Daily limits keep a crash loop from flooding the repo.
 */
@Service
public class ErrorReporter {
    private static final Logger log = LoggerFactory.getLogger(ErrorReporter.class);

    static final String AUTO_KEY = "diagnostics.autoReport";
    static final String ISSUE_KEY = "diagnostics.issue.";
    static final String QUOTA_KEY = "diagnostics.quota";
    static final int MAX_NEW_PER_DAY = 10;
    static final int MAX_COMMENTS_PER_DAY = 30;
    static final int MAX_USER_REPORTS_PER_DAY = 5;
    static final int MAX_PENDING = 30;
    static final Duration COMMENT_EVERY = Duration.ofHours(6);
    private static final int MAX_BODY = 60_000;
    private static final long MAX_LOG_DOWNLOAD = 1024 * 1024;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public record Recent(String title, int count, Instant lastSeen, String issueUrl) {}

    public record Status(boolean configured, boolean autoReport, String repo, int waiting, Instant lastSentAt,
                         String lastError, List<Recent> recent) {}

    public record Sent(int number, String url) {}

    /** Errors not yet sent, by fingerprint. */
    private static final class Pending {
        LogCapture.Captured first;
        LogCapture.Captured latest;
        int count;
        String title;
    }

    private final AppStateRepo state;
    private final SecretStore secrets;
    private final SettingsService settings;
    private final UpdateService updates;
    private final Environment env;
    private final String repo;
    private final String apiBaseUrl;
    private final LogCapture capture = new LogCapture(this::record);

    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, Instant> lastSent = new LinkedHashMap<>();
    private final Map<String, Recent> recent = new LinkedHashMap<>();
    private volatile Instant lastSentAt;
    private volatile String lastError;

    public ErrorReporter(AppStateRepo state, SecretStore secrets, SettingsService settings, UpdateService updates,
                         Environment env,
                         @Value("${crm.diagnostics.repo:${crm.updates.repo:}}") String repo,
                         @Value("${crm.diagnostics.api-base-url:https://api.github.com}") String apiBaseUrl) {
        this.state = state;
        this.secrets = secrets;
        this.settings = settings;
        this.updates = updates;
        this.env = env;
        this.repo = repo;
        this.apiBaseUrl = apiBaseUrl;
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx) {
            capture.setContext(ctx);
            capture.start();
            ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(capture);
        }
    }

    @PreDestroy
    void detach() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx) {
            ctx.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(capture);
        }
        capture.stop();
    }

    // ---------------------------------------------------------------- collecting

    void record(LogCapture.Captured c) {
        synchronized (pending) {
            Pending p = pending.get(c.fingerprint());
            if (p == null) {
                if (pending.size() >= MAX_PENDING) return;
                p = new Pending();
                p.first = c;
                p.title = title(c);
                pending.put(c.fingerprint(), p);
            }
            p.latest = c;
            p.count++;
            Recent r = recent.get(c.fingerprint());
            recent.remove(c.fingerprint()); // keep newest last
            recent.put(c.fingerprint(), new Recent(p.title, (r == null ? 0 : r.count()) + 1, c.at(), r == null ? null : r.issueUrl()));
            while (recent.size() > 20) recent.remove(recent.keySet().iterator().next());
        }
    }

    // ---------------------------------------------------------------- sending

    @Scheduled(fixedDelayString = "${crm.diagnostics.send-interval:PT5M}", initialDelayString = "${crm.diagnostics.initial-delay:PT1M}")
    public void sendPending() {
        if (!isConfigured() || !autoReport()) return;
        List<Map.Entry<String, Pending>> batch;
        synchronized (pending) {
            batch = new ArrayList<>(pending.entrySet());
        }
        GitHubIssues gh = client();
        Redactor redactor = redactor();
        for (Map.Entry<String, Pending> e : batch) {
            String fp = e.getKey();
            Pending p = e.getValue();
            Instant prev = lastSent.get(fp);
            if (prev != null && prev.plus(COMMENT_EVERY).isAfter(Instant.now())) continue; // gather repeats for a while
            try {
                int count;
                synchronized (pending) {
                    count = p.count;
                }
                String url = send(gh, redactor, fp, p, count);
                if (url == null) return; // daily limit reached
                synchronized (pending) {
                    p.count -= count;
                    if (p.count <= 0) pending.remove(fp);
                    Recent r = recent.get(fp);
                    if (r != null) recent.put(fp, new Recent(r.title(), r.count(), r.lastSeen(), url));
                }
                lastSent.put(fp, Instant.now());
                lastSentAt = Instant.now();
                lastError = null;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException ex) {
                lastError = "Couldn't send error reports: " + ex.getMessage();
                log.info("{}", lastError); // INFO and from this package: never reported itself
                return; // offline or bad token: try the whole batch again next round
            }
        }
    }

    /** Opens a new issue, or comments on the open one for this fingerprint. Returns null at the daily limit. */
    private String send(GitHubIssues gh, Redactor r, String fp, Pending p, int count) throws IOException, InterruptedException {
        Optional<Integer> known = state.findById(ISSUE_KEY + fp).map(s -> Integer.parseInt(s.stateValue));
        GitHubIssues.Issue existing = known.isPresent() ? gh.get(known.get()) : null;
        if (existing != null && existing.open()) {
            if (!takeQuota(Quota.COMMENT)) return null;
            gh.comment(existing.number(), cap(commentBody(r, p, count)));
            return existing.url();
        }
        if (!takeQuota(Quota.NEW)) return null;
        String body = issueBody(r, fp, p, count, existing);
        GitHubIssues.Issue created = gh.create(cap(r.redact(p.title)), cap(body), List.of("auto-report"));
        put(ISSUE_KEY + fp, Integer.toString(created.number()));
        return created.url();
    }

    /** "Report a problem": her own words plus recent log lines, sent right away. */
    public Sent reportProblem(String note) {
        if (!isConfigured()) {
            throw new IllegalStateException("Problem reports aren't set up on this computer yet. Use Save log file instead and send it by email.");
        }
        if (!takeQuota(Quota.USER)) throw new IllegalStateException("That's a lot of reports today. Please try again tomorrow.");
        Redactor r = redactor();
        String text = note == null ? "" : note.strip();
        String firstLine = text.isEmpty() ? "No description" : text.lines().findFirst().orElse("");
        String title = "[user-report] " + clip(r.redact(firstLine), 80);
        StringBuilder b = new StringBuilder()
                .append("**Problem reported from Creator CRM** ").append(environment()).append("\n\n")
                .append("> ").append(fence(r.redact(text.isEmpty() ? "(no description)" : text)).replace("\n", "\n> ")).append("\n\n");
        synchronized (pending) {
            if (!recent.isEmpty()) {
                b.append("**Errors since the app started:**\n");
                recent.values().forEach(x -> b.append("- ").append(fence(r.redact(x.title()))).append(" (").append(x.count())
                        .append("×)").append(x.issueUrl() == null ? "" : " " + x.issueUrl()).append('\n'));
                b.append('\n');
            }
        }
        b.append(details("Last " + LogCapture.KEEP_LINES + " log lines", r, capture.recentLines())).append(footer());
        try {
            GitHubIssues.Issue i = client().create(cap(title), cap(b.toString()), List.of("user-report"));
            lastSentAt = Instant.now();
            return new Sent(i.number(), i.url());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sending was interrupted");
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't send the report: " + e.getMessage()
                    + ". Check the internet connection, or use Save log file instead.");
        }
    }

    // ---------------------------------------------------------------- settings page

    public Status status() {
        synchronized (pending) {
            List<Recent> list = new ArrayList<>(recent.values());
            java.util.Collections.reverse(list);
            return new Status(isConfigured(), autoReport(), repo, pending.size(), lastSentAt, lastError, list);
        }
    }

    public boolean autoReport() {
        return state.findById(AUTO_KEY).map(s -> !"false".equals(s.stateValue)).orElse(true);
    }

    public void setAutoReport(boolean on) {
        put(AUTO_KEY, Boolean.toString(on));
    }

    /** The end of the log file with personal data removed, for sending by hand. */
    public String redactedLog() {
        Redactor r = redactor();
        String file = env.getProperty("logging.file.name");
        String text = null;
        if (file != null && !file.isBlank() && Files.isRegularFile(Path.of(file))) {
            try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
                long start = Math.max(0, f.length() - MAX_LOG_DOWNLOAD);
                byte[] buf = new byte[(int) (f.length() - start)];
                f.seek(start);
                f.readFully(buf);
                text = new String(buf, StandardCharsets.UTF_8);
                if (start > 0) text = text.substring(Math.max(0, text.indexOf('\n') + 1));
            } catch (IOException e) {
                log.info("Couldn't read the log file: {}", e.getMessage());
            }
        }
        if (text == null) text = String.join("\n", capture.recentLines());
        return "Creator CRM " + environment() + "\nPersonal data (emails, tokens, phone numbers, handles) has been removed.\n\n"
                + r.redact(text);
    }

    // ---------------------------------------------------------------- helpers

    boolean isConfigured() {
        return repo != null && repo.contains("/") && secrets.has(SecretName.ERROR_REPORT_TOKEN);
    }

    GitHubIssues client() {
        return new GitHubIssues(apiBaseUrl, repo, secrets.require(SecretName.ERROR_REPORT_TOKEN),
                "creator-crm/" + updates.currentVersion());
    }

    /** Every credential and account name the app knows, so they're removed even where no pattern would catch them. */
    Redactor redactor() {
        List<String> known = new ArrayList<>();
        for (SecretName n : SecretName.values()) {
            try {
                secrets.get(n).ifPresent(known::add);
            } catch (RuntimeException ignored) {
                // an unreadable secret can't leak either
            }
        }
        known.add(System.getProperty("user.name"));
        if (!"Creator".equals(settings.creatorName())) known.add(settings.creatorName()); // skip the default name
        return new Redactor(known);
    }

    private static String title(LogCapture.Captured c) {
        String what = c.exception() != null ? c.exception().substring(c.exception().lastIndexOf('.') + 1) : "Error";
        String where = c.logger().substring(c.logger().lastIndexOf('.') + 1);
        return "[auto-report] " + what + " in " + where + ": " + clip(c.message(), 90);
    }

    private String issueBody(Redactor r, String fp, Pending p, int count, GitHubIssues.Issue closedBefore) {
        LogCapture.Captured c = p.latest;
        StringBuilder b = new StringBuilder()
                .append("<!-- crm-fingerprint: ").append(fp).append(" -->\n")
                .append("**Automatic error report** from Creator CRM ").append(environment()).append("\n\n");
        if (closedBefore != null) b.append("This happened again after ").append(closedBefore.url()).append(" was closed.\n\n");
        b.append("- **Where:** `").append(c.logger()).append("` (thread `").append(fence(r.redact(c.thread()))).append("`)\n")
                .append("- **Message:** ").append(fence(r.redact(c.message()))).append('\n')
                .append("- **Times:** ").append(count).append(", first ").append(WHEN.format(p.first.at()))
                .append(", latest ").append(WHEN.format(c.at())).append("\n\n");
        if (c.stackTrace() != null) b.append(details("Stack trace", r, List.of(c.stackTrace())));
        b.append(details("Log lines before it", r, c.recentLines())).append(footer());
        return b.toString();
    }

    private String commentBody(Redactor r, Pending p, int count) {
        LogCapture.Captured c = p.latest;
        return "Happened " + count + " more time" + (count == 1 ? "" : "s") + " (latest " + WHEN.format(c.at()) + ") on "
                + environment() + ".\n\nLatest message: " + fence(r.redact(c.message())) + "\n\n"
                + details("Log lines before it", r, c.recentLines());
    }

    private static String details(String summary, Redactor r, List<String> lines) {
        return "<details><summary>" + summary + "</summary>\n\n```\n" + fence(r.redact(String.join("\n", lines))) + "\n```\n</details>\n\n";
    }

    private String environment() {
        return "version " + updates.currentVersion() + " on " + System.getProperty("os.name", "?") + " (Java "
                + System.getProperty("java.version", "?") + ")";
    }

    private static String footer() {
        return "_Sent by the app. Emails, phone numbers, tokens and handles were removed before sending._\n";
    }

    /** Stops log text from closing the code block it sits in. */
    private static String fence(String s) {
        return s == null ? "" : s.replace("```", "'''");
    }

    private static String clip(String s, int max) {
        String one = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    private static String cap(String s) {
        return s.length() <= MAX_BODY ? s : s.substring(0, MAX_BODY) + "\n\n… (cut)";
    }

    private enum Quota { NEW, COMMENT, USER }

    /** Daily counters, stored so restarting (or a crash loop) doesn't reset them. */
    private synchronized boolean takeQuota(Quota q) {
        String today = LocalDate.now().toString();
        String[] v = state.findById(QUOTA_KEY).map(s -> s.stateValue.split(",")).orElse(new String[0]);
        int[] used = new int[3];
        if (v.length == 4 && v[0].equals(today)) {
            for (int i = 0; i < 3; i++) used[i] = Integer.parseInt(v[i + 1]);
        }
        int limit = switch (q) {
            case NEW -> MAX_NEW_PER_DAY;
            case COMMENT -> MAX_COMMENTS_PER_DAY;
            case USER -> MAX_USER_REPORTS_PER_DAY;
        };
        if (used[q.ordinal()] >= limit) return false;
        used[q.ordinal()]++;
        put(QUOTA_KEY, today + "," + used[0] + "," + used[1] + "," + used[2]);
        return true;
    }

    private void put(String key, String value) {
        AppState s = new AppState();
        s.stateKey = key;
        s.stateValue = value;
        state.save(s);
    }
}

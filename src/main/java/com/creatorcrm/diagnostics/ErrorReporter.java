package com.creatorcrm.diagnostics;

import ch.qos.logback.classic.LoggerContext;
import com.creatorcrm.channels.gmail.GmailConnector;
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
    static final String EMAIL_KEY = "diagnostics.emailTo";
    static final String SEEN_KEY = "diagnostics.seen.";
    static final int MAX_NEW_PER_DAY = 10;
    static final int MAX_COMMENTS_PER_DAY = 30;
    static final int MAX_USER_REPORTS_PER_DAY = 5;
    static final int MAX_PENDING = 30;
    static final Duration COMMENT_EVERY = Duration.ofHours(6);
    private static final int MAX_BODY = 60_000;
    private static final long MAX_LOG_DOWNLOAD = 1024 * 1024;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public record Recent(String title, int count, Instant lastSeen, String issueUrl) {}

    public record Status(boolean configured, boolean autoReport, String repo, boolean github, String emailTo,
                         boolean gmailConnected, int waiting, Instant lastSentAt,
                         String lastError, List<Recent> recent) {}

    /** {@code url} is the GitHub issue, or null when the report only went by email. */
    public record Sent(String url, boolean emailed) {}

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
    private final GmailConnector gmail;
    private final LogCapture capture = new LogCapture(this::record);

    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, Instant> lastSent = new LinkedHashMap<>();
    private final Map<String, Recent> recent = new LinkedHashMap<>();
    private volatile Instant lastSentAt;
    private volatile String lastError;
    /** Whether the issues repo is public; null until asked. */
    private volatile Boolean repoPublic;

    public ErrorReporter(AppStateRepo state, SecretStore secrets, SettingsService settings, UpdateService updates,
                         Environment env, GmailConnector gmail,
                         @Value("${crm.diagnostics.repo:${crm.updates.repo:}}") String repo,
                         @Value("${crm.diagnostics.api-base-url:https://api.github.com}") String apiBaseUrl) {
        this.state = state;
        this.secrets = secrets;
        this.settings = settings;
        this.updates = updates;
        this.env = env;
        this.repo = repo;
        this.apiBaseUrl = apiBaseUrl;
        this.gmail = gmail;
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
        GitHubIssues gh = githubReady() ? client() : null;
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
                if (url.isEmpty()) url = null;
                synchronized (pending) {
                    p.count -= count;
                    if (p.count <= 0) pending.remove(fp);
                    Recent r = recent.get(fp);
                    if (r != null) recent.put(fp, new Recent(r.title(), r.count(), r.lastSeen(), url));
                }
                lastSent.put(fp, Instant.now());
                lastSentAt = Instant.now();
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

    /**
     * Sends one group of errors on every channel that's set up: a new GitHub issue or a comment on the open one, and
     * an email. Returns the issue link ("" if none), or null at the daily limit. Throws only if every channel failed.
     */
    private String send(GitHubIssues gh, Redactor r, String fp, Pending p, int count) throws IOException, InterruptedException {
        boolean repeat = state.existsById(SEEN_KEY + fp);
        if (!takeQuota(repeat ? Quota.COMMENT : Quota.NEW)) return null;
        String url = "";
        String body = null;
        IOException failed = null;
        String partial = null;
        if (gh != null) {
            try {
                Optional<Integer> known = state.findById(ISSUE_KEY + fp).map(s -> Integer.parseInt(s.stateValue));
                GitHubIssues.Issue existing = known.isPresent() ? gh.get(known.get()) : null;
                boolean open = isPublic(gh);
                if (existing != null && existing.open()) {
                    body = open ? publicCommentBody(p, count) : commentBody(r, p, count);
                    gh.comment(existing.number(), cap(body));
                    url = existing.url();
                } else {
                    body = open ? publicIssueBody(fp, p, count, existing) : issueBody(r, fp, p, count, existing);
                    String title = open ? publicTitle(p.latest) : r.redact(p.title);
                    GitHubIssues.Issue created = gh.create(cap(title), cap(body), List.of("auto-report"));
                    put(ISSUE_KEY + fp, Integer.toString(created.number()));
                    url = created.url();
                }
            } catch (IOException e) {
                failed = e;
            }
        }
        if (emailReady()) {
            String subject = "Creator CRM error" + (repeat ? " (again, " + count + "×)" : "") + ": "
                    + r.redact(p.title).replaceFirst("^\\[auto-report\\] ", "");
            String text = repeat ? commentBody(r, p, count) : issueBody(r, fp, p, count, null);
            try {
                email(subject, text + (url.isEmpty() ? "" : "\nGitHub issue: " + url));
                if (failed != null) partial = "Emailed, but GitHub failed: " + failed.getMessage();
                failed = null; // it got through
            } catch (Exception e) {
                if (gh == null || failed != null) throw e instanceof IOException io ? io : new IOException("email failed: " + e.getMessage(), e);
                partial = "Reported on GitHub, but the email failed: " + e.getMessage();
            }
        }
        if (failed != null) throw failed;
        lastError = partial;
        put(SEEN_KEY + fp, "1");
        return url;
    }

    private void email(String subject, String markdown) throws Exception {
        gmail.sendPlain(emailTo().orElseThrow(), clip(subject, 150), cap(plain(markdown)));
    }

    /** The issue markdown, made readable as a plain-text email. */
    static String plain(String markdown) {
        return markdown.replaceAll("<!--.*?-->\\n?", "")
                .replaceAll("<details><summary>(.*?)</summary>\\n*", "--- $1 ---\n")
                .replace("</details>", "")
                .replaceAll("(?m)^```\\n?", "")
                .replace("**", "")
                .replaceAll("(?m)^_(.*)_$", "$1");
    }

    /** "Report a problem": her own words plus recent log lines, sent right away. */
    public Sent reportProblem(String note) {
        if (!isConfigured()) {
            throw new IllegalStateException("Problem reports aren't set up on this computer yet. Use Save log file instead and send the file by email.");
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
        String url = null;
        String failure = null;
        if (githubReady()) {
            try {
                GitHubIssues gh = client();
                url = isPublic(gh)
                        ? gh.create("[user-report] Problem reported from the app", cap(publicReportBody()), List.of("user-report")).url()
                        : gh.create(cap(title), cap(b.toString()), List.of("user-report")).url();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Sending was interrupted");
            } catch (IOException e) {
                failure = e.getMessage();
            }
        }
        boolean emailed = false;
        if (emailReady()) {
            try {
                email("Creator CRM problem report: " + title.replaceFirst("^\\[user-report\\] ", ""),
                        b + (url == null ? "" : "\nGitHub issue: " + url));
                emailed = true;
            } catch (Exception e) {
                failure = e.getMessage();
            }
        }
        if (url == null && !emailed) {
            throw new IllegalStateException("Couldn't send the report: " + failure
                    + ". Check the internet connection, or use Save log file instead.");
        }
        lastSentAt = Instant.now();
        return new Sent(url, emailed);
    }

    // ---------------------------------------------------------------- settings page

    public Status status() {
        synchronized (pending) {
            List<Recent> list = new ArrayList<>(recent.values());
            java.util.Collections.reverse(list);
            return new Status(isConfigured(), autoReport(), repo, githubReady(), emailTo().orElse(null), gmail.isConnected(),
                    pending.size(), lastSentAt, lastError, list);
        }
    }

    public boolean autoReport() {
        return state.findById(AUTO_KEY).map(s -> !"false".equals(s.stateValue)).orElse(true);
    }

    public void setAutoReport(boolean on) {
        put(AUTO_KEY, Boolean.toString(on));
    }

    public Optional<String> emailTo() {
        return state.findById(EMAIL_KEY).map(s -> s.stateValue).filter(v -> !v.isBlank());
    }

    /** Where to email reports (sent from the connected Gmail account). Blank turns email reports off. */
    public void setEmailTo(String address) {
        String a = address == null ? "" : address.strip();
        if (a.isEmpty()) {
            state.deleteById(EMAIL_KEY);
            return;
        }
        if (a.length() > 254 || !a.matches("[^@\\s,;<>]+@[^@\\s,;<>]+\\.[^@\\s,;<>]+")) {
            throw new IllegalArgumentException("That doesn't look like an email address");
        }
        put(EMAIL_KEY, a);
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
        return githubReady() || emailReady();
    }

    boolean githubReady() {
        return repo != null && repo.contains("/") && secrets.has(SecretName.ERROR_REPORT_TOKEN);
    }

    boolean emailReady() {
        return emailTo().isPresent() && gmail.isConnected();
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

    // ---------------------------------------------------------------- public repos

    /**
     * Anyone can read a public repo's issues, so those get only what the code says (exception types, where, stack
     * frames). Her note, error messages and log lines can name brands and deals; they go by email only.
     */
    boolean isPublic(GitHubIssues gh) throws InterruptedException {
        Boolean known = repoPublic;
        if (known != null) return known;
        boolean open = gh.isPublic(); // a failed check counts as public until the app restarts: the safe side
        repoPublic = open;
        return open;
    }

    void forgetRepoVisibility() {
        repoPublic = null;
    }

    private static String publicTitle(LogCapture.Captured c) {
        String what = c.exception() != null ? c.exception().substring(c.exception().lastIndexOf('.') + 1) : "Error";
        String where = c.logger().substring(c.logger().lastIndexOf('.') + 1);
        return "[auto-report] " + what + " in " + where;
    }

    private String publicIssueBody(String fp, Pending p, int count, GitHubIssues.Issue closedBefore) {
        LogCapture.Captured c = p.latest;
        StringBuilder b = new StringBuilder()
                .append("<!-- crm-fingerprint: ").append(fp).append(" -->\n")
                .append("**Automatic error report** from Creator CRM ").append(environment()).append("\n\n");
        if (closedBefore != null) b.append("This happened again after ").append(closedBefore.url()).append(" was closed.\n\n");
        b.append("- **Where:** `").append(c.logger()).append("`\n")
                .append("- **Times:** ").append(count).append(", first ").append(WHEN.format(p.first.at()))
                .append(", latest ").append(WHEN.format(c.at())).append("\n\n");
        if (c.stackTrace() != null) {
            b.append("<details><summary>Stack trace (code only)</summary>\n\n```\n").append(fence(codeOnly(c.stackTrace())))
                    .append("\n```\n</details>\n\n");
        }
        return b.append(PUBLIC_NOTE).toString();
    }

    private String publicCommentBody(Pending p, int count) {
        return "Happened " + count + " more time" + (count == 1 ? "" : "s") + " (latest " + WHEN.format(p.latest.at()) + ") on "
                + environment() + ".\n\n" + PUBLIC_NOTE;
    }

    private String publicReportBody() {
        StringBuilder b = new StringBuilder("**Problem reported from Creator CRM** ").append(environment()).append("\n\n");
        synchronized (pending) {
            if (!recent.isEmpty()) {
                b.append("**Errors since the app started:**\n");
                recent.values().forEach(x -> b.append("- ").append(fence(x.title().replaceFirst("^(\\[auto-report\\] \\S+ in \\S+):.*$", "$1")))
                        .append(" (").append(x.count()).append("×)").append(x.issueUrl() == null ? "" : " " + x.issueUrl()).append('\n'));
                b.append('\n');
            }
        }
        return b.append(emailReady() ? PUBLIC_NOTE
                : "_This repository is public, so her note and the log lines were left out. Turn on email reports in Settings to receive them._\n")
                .toString();
    }

    private static final String PUBLIC_NOTE =
            "_This repository is public, so messages, her note and log lines were left out here. They were emailed to the developer if email reports are on._\n";

    /** Stack frames and exception class names; the messages after "Exception:" are dropped. */
    static String codeOnly(String trace) {
        StringBuilder b = new StringBuilder();
        for (String line : trace.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("at ") || t.matches("\\.\\.\\. \\d+ (more|common frames omitted)")) {
                b.append(line).append('\n');
            } else {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("^(\\s*(?:Caused by: |Suppressed: )?)([\\w$.]+(?:Exception|Error|Throwable))\\b.*").matcher(line);
                if (m.matches()) b.append(m.group(1)).append(m.group(2)).append('\n');
            }
        }
        return b.toString().stripTrailing();
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

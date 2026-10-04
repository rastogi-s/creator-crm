package com.creatorcrm.update;

import com.creatorcrm.config.CrmProperties;
import com.creatorcrm.desktop.DesktopMode;
import com.creatorcrm.domain.AppState;
import com.creatorcrm.repo.AppStateRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps an installed app up to date from the project's GitHub releases.
 *
 * <p>Checking is read-only and needs no account (the repo is public). Installing only happens when the user
 * clicks "Update now", and only on the Windows desktop app: download the {@code .msi}, verify it against the
 * release's {@code SHA256SUMS.txt}, back up the embedded database, then hand over to a small PowerShell script
 * that waits for this app to quit, runs the installer (it upgrades in place) and starts the app again.
 */
@Service
public class UpdateService {
    private static final Logger log = LoggerFactory.getLogger(UpdateService.class);
    private static final String AUTO_CHECK_KEY = "updates.autoCheck";
    private static final String WINDOWS_SUFFIX = "-windows-x64.msi";
    private static final long MAX_INSTALLER_BYTES = 600L * 1024 * 1024;
    private static final int KEEP_BACKUPS = 3;

    public record Asset(String name, String url) {}

    public record Release(String version, String tag, String name, String notes, String pageUrl, List<Asset> assets) {
        Optional<Asset> asset(String suffix) {
            return assets.stream().filter(a -> a.name().endsWith(suffix)).findFirst();
        }
    }

    public record Status(String currentVersion, String latestVersion, boolean updateAvailable, String releaseName,
                         String releaseNotes, String releaseUrl, boolean canInstall, String installHint,
                         OffsetDateTime checkedAt, String checkError, String installState, boolean installing,
                         boolean autoCheck, boolean enabled) {}

    private final CrmProperties props;
    private final CrmProperties.Updates config;
    private final AppStateRepo state;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final ConfigurableApplicationContext context;
    private final String currentVersion;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicBoolean installing = new AtomicBoolean();

    private volatile Release latest;
    private volatile OffsetDateTime checkedAt;
    private volatile String checkError;
    private volatile String installState;

    public UpdateService(CrmProperties props, AppStateRepo state, JdbcTemplate jdbc, DataSource dataSource,
                         ConfigurableApplicationContext context, ObjectProvider<BuildProperties> build) {
        this.props = props;
        this.config = props.updates() != null ? props.updates()
                : new CrmProperties.Updates(false, "", "https://api.github.com", "https://github.com", "-", "", "");
        this.state = state;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.context = context;
        BuildProperties b = build.getIfAvailable();
        this.currentVersion = b != null ? b.getVersion() : "0.0.0-dev";
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public String currentVersion() {
        return currentVersion;
    }

    public String repo() {
        return config.repo();
    }

    public String downloadBaseUrl() {
        return config.downloadBaseUrl();
    }

    // ---------------------------------------------------------------- checking

    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        if (config.enabled() && autoCheck()) Thread.ofVirtual().name("update-check").start(this::checkQuietly);
    }

    @Scheduled(cron = "${crm.updates.check-cron:-}")
    public void scheduledCheck() {
        if (config.enabled() && autoCheck()) checkQuietly();
    }

    private void checkQuietly() {
        try {
            check();
        } catch (RuntimeException e) {
            log.info("Update check failed: {}", e.getMessage());
        }
    }

    /** Ask GitHub for the latest (non-pre-release) version. */
    public Status check() {
        if (!config.enabled()) throw new IllegalStateException("Update checks are turned off for this install.");
        try {
            latest = isSimulated() ? simulatedRelease() : fetchLatest();
            checkError = null;
        } catch (IOException | RuntimeException e) {
            checkError = "Couldn't reach GitHub: " + e.getMessage();
            throw new IllegalStateException(checkError, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Update check interrupted", e);
        } finally {
            checkedAt = OffsetDateTime.now();
        }
        return status();
    }

    Release fetchLatest() throws IOException, InterruptedException {
        URI uri = URI.create(config.apiBaseUrl() + "/repos/" + config.repo() + "/releases/latest");
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "creator-crm/" + currentVersion).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) throw new IOException("no published release yet");
        if (r.statusCode() != 200) throw new IOException("GitHub answered " + r.statusCode());
        return parseRelease(r.body());
    }

    Release parseRelease(String body) throws IOException {
        JsonNode n = json.readTree(body);
        String tag = n.path("tag_name").asText("");
        if (!Versions.isValid(tag)) throw new IOException("latest release has an unexpected tag: " + tag);
        List<Asset> assets = new ArrayList<>();
        for (JsonNode a : n.path("assets")) {
            assets.add(new Asset(a.path("name").asText(""), a.path("browser_download_url").asText("")));
        }
        return new Release(Versions.normalize(tag), tag, n.path("name").asText(tag), n.path("body").asText(""),
                n.path("html_url").asText(""), List.copyOf(assets));
    }

    public Status status() {
        Release l = latest;
        boolean available = l != null && Versions.isNewer(l.version(), currentVersion);
        boolean installable = available && installSupported() && l.asset(WINDOWS_SUFFIX).isPresent();
        String hint = !available ? null
                : installable ? null
                : DesktopMode.enabled() ? "Download the installer for your computer from the release page and run it. Your data is kept."
                : "Update this install the way you set it up (for Docker: pull the new image and restart).";
        return new Status(currentVersion, l == null ? null : l.version(), available, l == null ? null : l.name(),
                l == null ? null : l.notes(), l == null ? null : l.pageUrl(), installable, hint, checkedAt, checkError,
                installState, installing.get(), autoCheck(), config.enabled());
    }

    public boolean autoCheck() {
        return state.findById(AUTO_CHECK_KEY).map(s -> !"false".equals(s.stateValue)).orElse(true);
    }

    public void setAutoCheck(boolean on) {
        AppState s = new AppState();
        s.stateKey = AUTO_CHECK_KEY;
        s.stateValue = Boolean.toString(on);
        state.save(s);
    }

    private boolean installSupported() {
        return isSimulated() || (DesktopMode.enabled()
                && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    }

    private boolean isSimulated() {
        return config.simulateLatest() != null && !config.simulateLatest().isBlank();
    }

    private Release simulatedRelease() {
        String v = Versions.normalize(config.simulateLatest().trim());
        if ("next".equals(v)) v = Versions.nextMinor(currentVersion);
        return new Release(v, "v" + v, "Creator CRM " + v, "Demo release used for walkthrough videos.",
                config.downloadBaseUrl() + "/" + config.repo() + "/releases",
                List.of(new Asset("Creator-CRM-" + v + WINDOWS_SUFFIX, "")));
    }

    // ---------------------------------------------------------------- installing

    /** Starts the update in the background. The app quits by itself once the installer is ready to run. */
    public Status install() {
        Status s = status();
        if (!s.updateAvailable()) throw new IllegalStateException("You're already on the latest version.");
        if (!s.canInstall()) throw new IllegalStateException(s.installHint());
        if (!installing.compareAndSet(false, true)) throw new IllegalStateException("An update is already in progress.");
        Release release = latest;
        installState = "Downloading version " + release.version() + "…";
        Thread.ofVirtual().name("update-install").start(() -> {
            try {
                runInstall(release);
            } catch (Exception e) {
                log.warn("Update to {} failed", release.version(), e);
                installState = "Update failed: " + e.getMessage() + ". Nothing was changed; you can try again.";
                installing.set(false);
            }
        });
        return status();
    }

    private void runInstall(Release release) throws Exception {
        if (isSimulated()) { // demo: walk through the same messages without touching anything
            for (String step : List.of("Checking the download…", "Backing up your data…",
                    "Installing version " + release.version() + ". Creator CRM will close and reopen by itself.")) {
                Thread.sleep(1500);
                installState = step;
            }
            Thread.sleep(5000);
            installState = null;
            installing.set(false);
            return;
        }
        Asset msi = release.asset(WINDOWS_SUFFIX).orElseThrow();
        Asset sums = release.asset("SHA256SUMS.txt")
                .orElseThrow(() -> new IOException("the release has no SHA256SUMS.txt to check the download against"));

        Path dir = Files.createDirectories(installRoot().resolve("updates"));
        try (Stream<Path> old = Files.list(dir)) {
            old.filter(p -> p.getFileName().toString().endsWith(".msi")).forEach(p -> p.toFile().delete());
        }
        String expected = expectedHash(fetchText(sums.url()), msi.name());
        Path file = dir.resolve(msi.name());
        download(msi.url(), file);

        installState = "Checking the download…";
        String actual = sha256(file);
        if (!actual.equalsIgnoreCase(expected)) {
            Files.deleteIfExists(file);
            throw new IOException("the download doesn't match the published checksum");
        }

        installState = "Backing up your data…";
        Path backup = backupDatabase();
        log.info("Pre-update backup: {}", backup == null ? "skipped (not the embedded database)" : backup);

        installState = "Installing version " + release.version() + ". Creator CRM will close and reopen by itself.";
        Path script = dir.resolve("install-update.ps1");
        Files.writeString(script, windowsScript(ProcessHandle.current().pid(), file, dir.resolve("install-update.log"),
                launcherPath()), StandardCharsets.UTF_8);
        new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-File", script.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        // Give the browser a moment to show the message, then quit so the installer can replace our files.
        Thread.sleep(2500);
        new Thread(() -> System.exit(SpringApplication.exit(context, () -> 0)), "creator-crm-update-quit").start();
    }

    private Path installRoot() {
        Path data = Path.of(props.dataDir()).toAbsolutePath();
        return data.getParent() != null ? data.getParent() : data;
    }

    private String launcherPath() {
        String p = System.getProperty("jpackage.app-path");
        if (p != null && !p.isBlank()) return p;
        return ProcessHandle.current().info().command().orElse("");
    }

    /**
     * PowerShell, not cmd: it can wait on a process id and run hidden. Paths are single-quoted, so the only
     * character to escape is the single quote itself.
     */
    static String windowsScript(long pid, Path msi, Path logFile, String launcher) {
        String args = "/i \"" + msi + "\" /passive /norestart /l*v \"" + logFile + "\"";
        return String.join("\r\n",
                "$ErrorActionPreference = 'SilentlyContinue'",
                "Wait-Process -Id " + pid + " -Timeout 120",
                "Start-Sleep -Seconds 1",
                "$p = Start-Process -FilePath 'msiexec.exe' -ArgumentList " + ps(args) + " -Wait -PassThru",
                "$app = " + ps(launcher),
                "if (-not (Test-Path -LiteralPath $app)) { $app = Join-Path $env:LOCALAPPDATA 'Creator CRM\\Creator CRM.exe' }",
                "if (Test-Path -LiteralPath $app) { Start-Process -FilePath $app }",
                "");
    }

    private static String ps(String s) {
        return "'" + (s == null ? "" : s.replace("'", "''")) + "'";
    }

    /** Reads {@code sha256sum} output: "&lt;hash&gt;  &lt;file&gt;" (or "*file" in binary mode). */
    static String expectedHash(String sums, String fileName) throws IOException {
        for (String line : sums.split("\\R")) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length == 2 && parts[1].replaceFirst("^\\*", "").equals(fileName) && parts[0].matches("[0-9a-fA-F]{64}")) {
                return parts[0];
            }
        }
        throw new IOException("SHA256SUMS.txt has no entry for " + fileName);
    }

    private String fetchText(String url) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(get(url), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("download failed (" + r.statusCode() + ")");
        return r.body();
    }

    void download(String url, Path target) throws IOException, InterruptedException {
        HttpResponse<InputStream> r = http.send(get(url), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) {
            if (r.statusCode() != 200) throw new IOException("download failed (" + r.statusCode() + ")");
            Path part = target.resolveSibling(target.getFileName() + ".part");
            long total = 0;
            try (OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > MAX_INSTALLER_BYTES) throw new IOException("the download is unexpectedly large");
                    out.write(buf, 0, n);
                }
            }
            Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10))
                .header("User-Agent", "creator-crm/" + currentVersion).GET().build();
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A consistent copy of the embedded database, taken while the app runs (H2's {@code BACKUP TO}). Kept next to
     * the data folder, newest three only. Returns null for PostgreSQL, which has its own backup tools.
     */
    Path backupDatabase() throws Exception {
        String url;
        try (var c = dataSource.getConnection()) {
            url = c.getMetaData().getURL();
        }
        if (url == null || !url.startsWith("jdbc:h2:file:")) return null;
        Path dir = Files.createDirectories(installRoot().resolve("backups"));
        Path target = dir.resolve("pre-update-" + currentVersion + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip");
        jdbc.execute("BACKUP TO '" + target.toString().replace("'", "''") + "'");
        try (Stream<Path> all = Files.list(dir)) {
            List<Path> old = all.filter(p -> p.getFileName().toString().startsWith("pre-update-"))
                    .sorted((a, b) -> Long.compare(b.toFile().lastModified(), a.toFile().lastModified()))
                    .skip(KEEP_BACKUPS).toList();
            for (Path p : old) Files.deleteIfExists(p);
        }
        return target;
    }
}

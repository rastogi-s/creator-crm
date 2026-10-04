package com.creatorcrm.backup;

import com.creatorcrm.config.CrmProperties;
import com.creatorcrm.domain.AppState;
import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.creatorcrm.security.SetupService;
import com.creatorcrm.settings.SettingsService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Nightly backups without anyone clicking: once a day, at or after 02:00 (creator's time zone), the same encrypted
 * file as "Download backup" is written to a folder the creator picks. If the computer was off or asleep at 02:00,
 * the backup runs soon after the app is back. The newest {@value #KEEP} files are kept.
 *
 * <p>The passphrase is stored like the other credentials, so backups run unattended. The default folder is inside
 * OneDrive when Windows has it, so the file leaves the laptop on its own.
 */
@Service
public class AutoBackupService {
    private static final Logger log = LoggerFactory.getLogger(AutoBackupService.class);

    static final int KEEP = 14;
    static final LocalTime RUN_AFTER = LocalTime.of(2, 0);
    static final Duration RETRY_AFTER = Duration.ofHours(1);
    static final String FOLDER_NAME = "Creator CRM Backups";
    static final Pattern FILE = Pattern.compile("creator-crm-\\d{4}-\\d{2}-\\d{2}\\.crmbak");

    static final String ENABLED_KEY = "backup.auto.enabled";
    static final String FOLDER_KEY = "backup.auto.folder";
    static final String LAST_OK_KEY = "backup.auto.lastOk";
    static final String LAST_FILE_KEY = "backup.auto.lastFile";
    static final String LAST_SIZE_KEY = "backup.auto.lastSize";
    static final String LAST_ATTEMPT_KEY = "backup.auto.lastAttempt";
    static final String LAST_ERROR_KEY = "backup.auto.lastError";

    public record Status(boolean enabled, boolean passphraseSet, String folder, String defaultFolder,
                         String lastBackupAt, String lastFile, long lastSizeBytes, String lastError, int keep) {}

    private final BackupService backups;
    private final SecretStore secrets;
    private final AppStateRepo state;
    private final SettingsService settings;
    private final SetupService setup;
    private final CrmProperties props;

    public AutoBackupService(BackupService backups, SecretStore secrets, AppStateRepo state, SettingsService settings,
                             SetupService setup, CrmProperties props) {
        this.backups = backups;
        this.secrets = secrets;
        this.state = state;
        this.settings = settings;
        this.setup = setup;
        this.props = props;
    }

    // ---------------------------------------------------------------- schedule

    @Scheduled(cron = "${crm.schedule.backup-check-cron:0 */10 * * * *}")
    public void check() {
        if (!setup.isSetupComplete() || !due(ZonedDateTime.now(settings.zone()))) return;
        runNow();
    }

    boolean due(ZonedDateTime now) {
        if (!enabled() || !secrets.has(SecretName.BACKUP_PASSPHRASE)) return false;
        if (now.toLocalTime().isBefore(RUN_AFTER)) return false;
        OffsetDateTime lastOk = time(LAST_OK_KEY);
        if (lastOk != null && !lastOk.atZoneSameInstant(now.getZone()).toLocalDate().isBefore(now.toLocalDate())) return false;
        OffsetDateTime lastAttempt = time(LAST_ATTEMPT_KEY);
        return lastAttempt == null || lastAttempt.toInstant().plus(RETRY_AFTER).isBefore(now.toInstant());
    }

    /** Writes today's backup now. Failures are recorded for the Settings card and logged as errors (so they get reported). */
    public synchronized Status runNow() {
        String passphrase = secrets.get(SecretName.BACKUP_PASSPHRASE)
                .orElseThrow(() -> new IllegalStateException("Set a backup passphrase first."));
        OffsetDateTime now = OffsetDateTime.now();
        put(LAST_ATTEMPT_KEY, now.toString());
        try {
            Path dir = Files.createDirectories(folder());
            Path target = dir.resolve("creator-crm-" + now.atZoneSameInstant(settings.zone()).toLocalDate() + ".crmbak");
            Path part = dir.resolve(target.getFileName() + ".part");
            Files.write(part, backups.export(passphrase.toCharArray()));
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            prune(dir);
            put(LAST_OK_KEY, now.toString());
            put(LAST_FILE_KEY, target.toString());
            put(LAST_SIZE_KEY, Long.toString(Files.size(target)));
            put(LAST_ERROR_KEY, "");
            log.info("Automatic backup written to {}", target);
        } catch (IOException | RuntimeException e) {
            put(LAST_ERROR_KEY, describe(e));
            log.error("Automatic backup failed", e);
        }
        return status();
    }

    private static String describe(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return "Couldn't save the backup: " + (m.length() > 300 ? m.substring(0, 300) : m);
    }

    /** Deletes the oldest automatic backups beyond {@value #KEEP}. Other files in the folder are never touched. */
    static void prune(Path dir) throws IOException {
        List<Path> ours;
        try (Stream<Path> files = Files.list(dir)) {
            ours = files.filter(p -> FILE.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        }
        for (Path old : ours.subList(Math.min(KEEP, ours.size()), ours.size())) Files.deleteIfExists(old);
    }

    // ---------------------------------------------------------------- settings

    public Status status() {
        return new Status(enabled(), secrets.has(SecretName.BACKUP_PASSPHRASE), folder().toString(), defaultFolder().toString(),
                value(LAST_OK_KEY), value(LAST_FILE_KEY), parseLong(value(LAST_SIZE_KEY)), blankToNull(value(LAST_ERROR_KEY)), KEEP);
    }

    /**
     * Saves the card. A null field is left as it is; a blank folder means the default one. Setting a passphrase for
     * the first time switches automatic backups on.
     */
    public synchronized Status update(Boolean enabled, String folder, String passphrase) {
        if (passphrase != null) {
            if (passphrase.length() < 12) throw new IllegalArgumentException("Backup passphrase must be at least 12 characters");
            boolean first = !secrets.has(SecretName.BACKUP_PASSPHRASE);
            secrets.put(SecretName.BACKUP_PASSPHRASE, passphrase);
            if (first && enabled == null) enabled = true;
        }
        if (folder != null) {
            String f = folder.trim();
            if (!f.isEmpty()) {
                Path p = Path.of(f);
                if (!p.isAbsolute()) throw new IllegalArgumentException("Use a full folder path, e.g. " + defaultFolder());
                try {
                    Files.createDirectories(p);
                } catch (IOException | RuntimeException e) {
                    throw new IllegalArgumentException("Can't use that folder: " + e.getMessage());
                }
                if (!Files.isWritable(p)) throw new IllegalArgumentException("Can't save files in that folder");
            }
            put(FOLDER_KEY, f);
        }
        if (enabled != null) {
            if (enabled && !secrets.has(SecretName.BACKUP_PASSPHRASE)) {
                throw new IllegalArgumentException("Set a backup passphrase first");
            }
            put(ENABLED_KEY, enabled.toString());
        }
        return status();
    }

    boolean enabled() {
        return "true".equals(value(ENABLED_KEY));
    }

    /**
     * The chosen folder, or the default. A saved path that isn't absolute here (say, a Windows path restored onto a
     * Mac) falls back to the default rather than landing somewhere unexpected.
     */
    Path folder() {
        String f = value(FOLDER_KEY);
        if (f != null && !f.isBlank()) {
            try {
                Path p = Path.of(f.trim());
                if (p.isAbsolute()) return p;
            } catch (RuntimeException ignored) {
                // not a valid path on this computer
            }
        }
        return defaultFolder();
    }

    /** OneDrive (Windows sets %OneDrive%), else Documents, else next to the data folder (servers, Docker). */
    Path defaultFolder() {
        String oneDrive = System.getenv("OneDrive");
        if (oneDrive != null && !oneDrive.isBlank() && Files.isDirectory(Path.of(oneDrive))) {
            return Path.of(oneDrive, FOLDER_NAME);
        }
        Path documents = Path.of(System.getProperty("user.home"), "Documents");
        if (Files.isDirectory(documents)) return documents.resolve(FOLDER_NAME);
        return Path.of(props.dataDir()).toAbsolutePath().resolve("auto-backups");
    }

    // ---------------------------------------------------------------- app_state helpers

    private String value(String key) {
        return state.findById(key).map(s -> s.stateValue).orElse(null);
    }

    private OffsetDateTime time(String key) {
        String v = value(key);
        try {
            return v == null || v.isBlank() ? null : OffsetDateTime.parse(v);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void put(String key, String v) {
        AppState s = new AppState();
        s.stateKey = key;
        s.stateValue = v;
        state.save(s);
    }

    private static long parseLong(String v) {
        try {
            return v == null ? 0 : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}

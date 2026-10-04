package com.creatorcrm.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:autobackuptest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
class AutoBackupServiceTest {

    private static final String PASS = "correct horse battery staple";
    private static final ZoneId ZONE = ZoneId.of("UTC");

    @Autowired AutoBackupService auto;
    @Autowired BackupService backups;
    @Autowired SecretStore secrets;
    @Autowired AppStateRepo state;

    @TempDir Path dir;

    @BeforeEach
    void reset() {
        secrets.delete(SecretName.BACKUP_PASSPHRASE);
        state.findAll().stream().filter(s -> s.stateKey.startsWith("backup.auto.")).forEach(state::delete);
    }

    @Test
    void choosingAPassphraseTurnsBackupsOnAndWritesARestorableFile() {
        assertThat(auto.status().enabled()).isFalse();
        assertThatThrownBy(() -> auto.update(true, null, null)).hasMessageContaining("passphrase first");

        AutoBackupService.Status s = auto.update(null, dir.toString(), PASS);
        assertThat(s.enabled()).isTrue();
        assertThat(s.passphraseSet()).isTrue();
        assertThat(s.folder()).isEqualTo(dir.toString());

        s = auto.runNow();
        assertThat(s.lastError()).isNull();
        assertThat(s.lastBackupAt()).isNotNull();
        Path file = Path.of(s.lastFile());
        assertThat(file.getParent()).isEqualTo(dir);
        assertThat(file.getFileName().toString()).matches(AutoBackupService.FILE.pattern());
        assertThat(s.lastSizeBytes()).isPositive();
        // Same format as "Download backup": opens with the passphrase.
        assertThat(backups.inspect(readAll(file), PASS.toCharArray()).rows()).containsKey("opportunities");
    }

    @Test
    void dueOncePerDayAfterTwoAndRetriesAfterAnHour() {
        auto.update(null, dir.toString(), PASS);
        ZonedDateTime night = ZonedDateTime.of(2026, 10, 5, 1, 30, 0, 0, ZONE);
        assertThat(auto.due(night)).isFalse();                // before 02:00
        assertThat(auto.due(night.withHour(9))).isTrue();     // laptop opened in the morning: catch up

        auto.runNow();
        assertThat(auto.due(ZonedDateTime.now(ZONE).withHour(23))).isFalse();       // done today
        assertThat(auto.due(ZonedDateTime.now(ZONE).plusDays(1).withHour(3))).isTrue(); // tomorrow

        auto.update(false, null, null);
        assertThat(auto.due(ZonedDateTime.now(ZONE).plusDays(1).withHour(3))).isFalse();
    }

    @Test
    void failedBackupIsShownAndRetriedLater() throws Exception {
        auto.update(null, dir.toString(), PASS);
        Path blocker = dir.resolve("not-a-folder");
        Files.writeString(blocker, "x");
        // Point the folder at a file: creating the directory fails.
        state.findById(AutoBackupService.FOLDER_KEY).ifPresent(s -> { s.stateValue = blocker.toString(); state.save(s); });

        AutoBackupService.Status s = auto.runNow();
        assertThat(s.lastError()).startsWith("Couldn't save the backup");
        assertThat(s.lastBackupAt()).isNull();
        assertThat(auto.due(ZonedDateTime.now(ZONE).plusDays(1).withHour(3))).isTrue(); // tried again later
    }

    @Test
    void keepsTheNewestFourteenAndLeavesOtherFilesAlone() throws Exception {
        for (int day = 1; day <= 20; day++) {
            Files.writeString(dir.resolve(String.format("creator-crm-2026-09-%02d.crmbak", day)), "x");
        }
        Files.writeString(dir.resolve("notes.txt"), "mine");
        AutoBackupService.prune(dir);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()).sorted().toList())
                    .hasSize(15)
                    .contains("notes.txt", "creator-crm-2026-09-07.crmbak", "creator-crm-2026-09-20.crmbak")
                    .doesNotContain("creator-crm-2026-09-06.crmbak");
        }
    }

    @Test
    void relativeFoldersAreRefusedAndForeignPathsFallBackToDefault() {
        assertThatThrownBy(() -> auto.update(null, "backups", null)).hasMessageContaining("full folder path");
        auto.update(null, "", null);
        assertThat(auto.status().folder()).isEqualTo(auto.status().defaultFolder());
    }

    @Test
    void shortPassphraseIsRefused() {
        assertThatThrownBy(() -> auto.update(null, null, "too short")).hasMessageContaining("12 characters");
    }

    private static byte[] readAll(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

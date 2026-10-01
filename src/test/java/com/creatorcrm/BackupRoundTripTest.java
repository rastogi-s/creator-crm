package com.creatorcrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.backup.BackupService;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.repo.FollowUpRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import com.creatorcrm.workflow.OutreachService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:backuptest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
class BackupRoundTripTest {

    @Autowired BackupService backups;
    @Autowired OutreachService outreach;
    @Autowired OpportunityRepo opportunities;
    @Autowired FollowUpRepo followUps;
    @Autowired SecretStore secrets;

    private static final char[] PASS = "correct horse battery staple".toCharArray();

    @Test
    void exportThenRestoreBringsBackDataAndCredentials() {
        Opportunity kept = outreach.logPitch(new OutreachService.PitchRequest("Aurora Labs", "Ana", "ana@aurora.test", null,
                "EMAIL", "UGC", LocalDate.now().minusDays(2), null, false));
        secrets.put(SecretName.ANTHROPIC_API_KEY, "sk-ant-before-backup");
        long countAtBackup = opportunities.count();

        byte[] file = backups.export(PASS);
        assertThat(new String(file, StandardCharsets.ISO_8859_1)).doesNotContain("sk-ant-before-backup", "Aurora");

        // Change things after the backup
        outreach.logPitch(new OutreachService.PitchRequest("Later Brand", null, null, null, "EMAIL", "", null, null, false));
        kept.status = OpportunityStatus.CLOSED;
        opportunities.save(kept);
        secrets.put(SecretName.ANTHROPIC_API_KEY, "sk-ant-after-backup");

        BackupService.Summary s = backups.restore(file, PASS);
        assertThat(s.rows().get("opportunities")).isEqualTo((int) countAtBackup);
        assertThat(s.credentials()).isEqualTo(1);

        assertThat(opportunities.count()).isEqualTo(countAtBackup);
        assertThat(opportunities.findAll()).noneMatch(o -> "Later Brand".equals(o.campaign) || o.status == OpportunityStatus.CLOSED);
        assertThat(opportunities.findById(kept.id).orElseThrow().status).isEqualTo(OpportunityStatus.PITCHED);
        assertThat(followUps.findByOpportunityIdOrderByNumberAsc(kept.id)).hasSize(1);
        assertThat(secrets.get(SecretName.ANTHROPIC_API_KEY)).contains("sk-ant-before-backup");

        // Ids continue after the restored rows (identity sequences reset)
        Opportunity next = outreach.logPitch(new OutreachService.PitchRequest("Next Brand", null, null, null, "EMAIL", "", null, null, false));
        assertThat(next.id).isGreaterThan(kept.id);
    }

    @Test
    void wrongPassphraseOrTamperedFileIsRejectedWithoutTouchingData() {
        outreach.logPitch(new OutreachService.PitchRequest("Solace " + System.nanoTime(), null, null, null, "EMAIL", "", null, null, true));
        long before = opportunities.count();
        byte[] file = backups.export(PASS);

        assertThatThrownBy(() -> backups.restore(file, "wrong passphrase!!".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Wrong passphrase");
        file[file.length - 5] ^= 1;
        assertThatThrownBy(() -> backups.restore(file, PASS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backups.restore("not a backup".getBytes(), PASS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backups.export("short".toCharArray())).isInstanceOf(IllegalArgumentException.class);
        assertThat(opportunities.count()).isEqualTo(before);
    }
}

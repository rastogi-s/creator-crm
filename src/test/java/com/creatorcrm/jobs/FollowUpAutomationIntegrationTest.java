package com.creatorcrm.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.ChannelConnector.SentMessage;
import com.creatorcrm.channels.gmail.GmailConnector;
import com.creatorcrm.domain.AppState;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.FollowUpStatus;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.FollowUp;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.FollowUpRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.OutreachService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Daily follow-up run: configurable time, and opt-in automatic sending of email follow-ups only. */
@SpringBootTest
@ActiveProfiles("test")
class FollowUpAutomationIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @MockitoSpyBean GmailConnector gmail;
    @Autowired ScheduledJobs jobs;
    @Autowired OutreachService outreach;
    @Autowired SettingsService settings;
    @Autowired DraftRepo drafts;
    @Autowired FollowUpRepo followUps;
    @Autowired ActivityRepo activity;
    @Autowired AppStateRepo state;

    @BeforeEach
    void connectFakeGmail() throws Exception {
        doReturn(true).when(gmail).isConnected();
        doReturn(java.util.Optional.empty()).when(gmail).pushDraft(any());
        doAnswer(inv -> new SentMessage(UUID.randomUUID().toString(), UUID.randomUUID().toString()))
                .when(gmail).send(any());
    }

    private Opportunity pitch(String email, String instagram) {
        String brand = "Brand " + UUID.randomUUID().toString().substring(0, 8);
        return outreach.logPitch(new OutreachService.PitchRequest(brand, "Sam", email, instagram, "EMAIL", "UGC",
                settings.today().minusDays(10), null, false));
    }

    private Draft pendingDraft(Opportunity o) {
        return drafts.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING).stream().findFirst().orElseThrow();
    }

    @Test
    void autoSendSendsDueEmailFollowUpsOnly() {
        Opportunity emailDeal = pitch("sam@" + UUID.randomUUID() + ".test", null);
        Opportunity dmDeal = pitch(null, "brand_" + UUID.randomUUID().toString().substring(0, 6));
        jobs.prepareMorning();
        assertThat(pendingDraft(emailDeal).channel).isEqualTo(Platform.EMAIL);
        assertThat(pendingDraft(dmDeal).channel).isEqualTo(Platform.INSTAGRAM);

        jobs.autoSendFollowUps();

        // Email follow-up #1 went out and #2 is scheduled; the DM still waits for approval.
        assertThat(drafts.findByOpportunityIdAndStatus(emailDeal.id, DraftStatus.SENT)).hasSize(1);
        List<FollowUp> fus = followUps.findByOpportunityIdOrderByNumberAsc(emailDeal.id);
        assertThat(fus).extracting(f -> f.status).containsExactly(FollowUpStatus.DONE, FollowUpStatus.SCHEDULED);
        assertThat(activity.findByOpportunityIdOrderByAtDesc(emailDeal.id))
                .anyMatch(a -> a.text.contains("sent automatically"));
        assertThat(pendingDraft(dmDeal).status).isEqualTo(DraftStatus.PENDING);
    }

    @Test
    void autoSendSkipsFollowUpsTheBrandAlreadyAnswered() {
        Opportunity o = pitch("sam@" + UUID.randomUUID() + ".test", null);
        jobs.prepareMorning();
        FollowUp fu = followUps.findByOpportunityIdOrderByNumberAsc(o.id).getFirst();
        fu.status = FollowUpStatus.CANCELLED;
        followUps.save(fu);

        jobs.autoSendFollowUps();

        assertThat(pendingDraft(o).status).isEqualTo(DraftStatus.PENDING);
    }

    @Test
    void morningRunsOnceADayAtOrAfterTheConfiguredTime() {
        settings.update(Map.of(SettingsService.FOLLOWUP_TIME, "09:30"));
        try {
            ZonedDateTime day = LocalDate.of(2030, 1, 15).atStartOfDay(settings.zone());
            assertThat(jobs.morningDue(day.with(LocalTime.of(9, 29)))).isFalse();
            assertThat(jobs.morningDue(day.with(LocalTime.of(9, 30)))).isTrue();
            assertThat(jobs.morningDue(day.with(LocalTime.of(18, 0)))).isTrue(); // app was off at 09:30

            AppState ran = new AppState();
            ran.stateKey = ScheduledJobs.LAST_MORNING_RUN;
            ran.stateValue = "2030-01-15";
            state.save(ran);
            assertThat(jobs.morningDue(day.with(LocalTime.of(18, 0)))).isFalse();
            assertThat(jobs.morningDue(day.plusDays(1).with(LocalTime.of(9, 30)))).isTrue();
        } finally {
            settings.update(Map.of(SettingsService.FOLLOWUP_TIME, ""));
            state.deleteById(ScheduledJobs.LAST_MORNING_RUN);
        }
    }

    @Test
    void followUpSettingsAreValidated() {
        assertThat(settings.followupTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(settings.followupAutoSend()).isFalse();
        assertThatThrownBy(() -> settings.update(Map.of(SettingsService.FOLLOWUP_TIME, "25:00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.update(Map.of(SettingsService.FOLLOWUP_AUTO_SEND, "yes")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

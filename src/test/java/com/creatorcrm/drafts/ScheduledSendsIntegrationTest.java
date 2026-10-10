package com.creatorcrm.drafts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.ChannelConnector.SentMessage;
import com.creatorcrm.channels.gmail.GmailConnector;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.jobs.ScheduledJobs;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.OutreachService;
import java.time.OffsetDateTime;
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

/** Send later: a scheduled email goes out once its time has passed (also when the app was closed then), not before. */
@SpringBootTest(properties = "crm.send.undo-seconds=1")
@ActiveProfiles("test")
class ScheduledSendsIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @MockitoSpyBean GmailConnector gmail;
    @Autowired ScheduledSends scheduled;
    @Autowired DraftService draftService;
    @Autowired SendQueue queue;
    @Autowired ScheduledJobs jobs;
    @Autowired OutreachService outreach;
    @Autowired SettingsService settings;
    @Autowired DraftRepo drafts;

    @BeforeEach
    void connectFakeGmail() throws Exception {
        reset(gmail);
        doReturn(true).when(gmail).isConnected();
        doReturn(java.util.Optional.empty()).when(gmail).pushDraft(any());
        doAnswer(inv -> new SentMessage(UUID.randomUUID().toString(), UUID.randomUUID().toString()))
                .when(gmail).send(any());
    }

    private Draft followUpDraft() throws Exception {
        Opportunity o = outreach.logPitch(new OutreachService.PitchRequest("Brand " + UUID.randomUUID().toString().substring(0, 8),
                "Sam", "sam@" + UUID.randomUUID() + ".test", null, "EMAIL", "UGC", settings.today().minusDays(10), null, false));
        jobs.prepareMorning();
        Draft d = drafts.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING).stream().findFirst().orElseThrow();
        connectFakeGmail(); // forget the calls made while writing it
        return d;
    }

    /** As if the time came while the app was closed: what the next round after startup finds. */
    private void makeDue(Long id) {
        Draft d = drafts.findById(id).orElseThrow();
        d.scheduledAt = OffsetDateTime.now().minusHours(3);
        drafts.save(d);
    }

    @Test
    void waitsUntilItsTimeThenGoesOutWithHerEdits() throws Exception {
        Draft d = followUpDraft();
        Draft s = draftService.schedule(d.id, "Checking in", "Hi Sam, just checking in.", OffsetDateTime.now().plusHours(2));
        assertThat(s.scheduledAt).isNotNull();

        scheduled.sendDue();
        verify(gmail, never()).send(any());
        assertThat(drafts.findById(d.id).orElseThrow().status).isEqualTo(DraftStatus.PENDING);

        makeDue(d.id);
        scheduled.sendDue();
        verify(gmail, times(1)).send(argThat(x -> x.id.equals(d.id)));
        Draft sent = drafts.findById(d.id).orElseThrow();
        assertThat(sent.status).isEqualTo(DraftStatus.SENT);
        assertThat(sent.subject).isEqualTo("Checking in");
        assertThat(sent.body).isEqualTo("Hi Sam, just checking in.");

        scheduled.sendDue();
        verify(gmail, times(1)).send(any());
    }

    @Test
    void takenOffTheScheduleItWaitsForHer() throws Exception {
        Draft d = followUpDraft();
        draftService.schedule(d.id, null, null, OffsetDateTime.now().plusHours(2));
        makeDue(d.id);
        draftService.unschedule(d.id);

        scheduled.sendDue();
        verify(gmail, never()).send(any());
        assertThat(drafts.findById(d.id).orElseThrow().scheduledAt).isNull();
    }

    @Test
    void refusesThePastAndBlanksStraightAway() throws Exception {
        Draft d = followUpDraft();
        assertThatThrownBy(() -> draftService.schedule(d.id, null, null, OffsetDateTime.now().minusMinutes(5)))
                .hasMessageContaining("later than now");
        assertThatThrownBy(() -> draftService.schedule(d.id, null, "My rate is [RATE FOR 1 REEL].", OffsetDateTime.now().plusDays(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(drafts.findById(d.id).orElseThrow().scheduledAt).isNull();
    }

    @Test
    void aFailedScheduledSendStaysInDraftsWithTheReasonAndIsNotRetried() throws Exception {
        Draft d = followUpDraft();
        draftService.schedule(d.id, null, null, OffsetDateTime.now().plusHours(2));
        makeDue(d.id);
        doThrow(new RuntimeException("Gmail said no")).when(gmail).send(any());

        scheduled.sendDue();
        scheduled.sendDue();

        verify(gmail, times(1)).send(any());
        Draft after = drafts.findById(d.id).orElseThrow();
        assertThat(after.status).isEqualTo(DraftStatus.PENDING);
        assertThat(after.scheduledAt).isNull();
        assertThat(after.error).contains("Gmail said no").contains("scheduled");
    }

    @Test
    void leavesADraftSheIsSendingRightNowToTheUndoWindow() throws Exception {
        Draft d = followUpDraft();
        draftService.schedule(d.id, null, null, OffsetDateTime.now().plusHours(2));
        makeDue(d.id);

        queue.send(d.id, null, null);
        scheduled.sendDue();
        verify(gmail, never()).send(any());

        for (int i = 0; i < 100 && queue.isWaiting(d.id); i++) Thread.sleep(50);
        assertThat(drafts.findById(d.id).orElseThrow().status).isEqualTo(DraftStatus.SENT);
        verify(gmail, times(1)).send(any());
    }
}

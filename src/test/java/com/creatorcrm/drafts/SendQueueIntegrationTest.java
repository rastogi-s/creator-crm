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

/** Send waits out a short undo window before the message actually leaves; Undo stops it. */
@SpringBootTest(properties = "crm.send.undo-seconds=1")
@ActiveProfiles("test")
class SendQueueIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @MockitoSpyBean GmailConnector gmail;
    @Autowired SendQueue queue;
    @Autowired ScheduledJobs jobs;
    @Autowired OutreachService outreach;
    @Autowired SettingsService settings;
    @Autowired DraftRepo drafts;

    @BeforeEach
    void connectFakeGmail() throws Exception {
        doReturn(true).when(gmail).isConnected();
        doReturn(java.util.Optional.empty()).when(gmail).pushDraft(any());
        doAnswer(inv -> new SentMessage(UUID.randomUUID().toString(), UUID.randomUUID().toString()))
                .when(gmail).send(any());
    }

    /** An email follow-up draft waiting in Drafts. */
    private Draft followUpDraft() {
        Opportunity o = outreach.logPitch(new OutreachService.PitchRequest("Brand " + UUID.randomUUID().toString().substring(0, 8),
                "Sam", "sam@" + UUID.randomUUID() + ".test", null, "EMAIL", "UGC", settings.today().minusDays(10), null, false));
        jobs.prepareMorning();
        return drafts.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING).stream().findFirst().orElseThrow();
    }

    private DraftStatus statusAfterWindow(Long id) throws InterruptedException {
        for (int i = 0; i < 100 && queue.isWaiting(id); i++) Thread.sleep(50);
        return drafts.findById(id).orElseThrow().status;
    }

    @Test
    void sendGoesOutAfterTheUndoWindowWithHerEdits() throws Exception {
        Draft d = followUpDraft();
        reset(gmail);
        connectFakeGmail();

        SendQueue.Waiting w = queue.send(d.id, "Checking in", "Hi Sam, just checking in.");

        assertThat(queue.isWaiting(d.id)).isTrue();
        assertThat(drafts.findById(d.id).orElseThrow().status).isEqualTo(DraftStatus.PENDING);
        verify(gmail, never()).send(any());
        assertThat(statusAfterWindow(d.id)).isEqualTo(DraftStatus.SENT);
        verify(gmail, times(1)).send(argThat(x -> x.id.equals(d.id)));
        Draft sent = drafts.findById(d.id).orElseThrow();
        assertThat(sent.body).isEqualTo("Hi Sam, just checking in.");
        assertThat(sent.subject).isEqualTo("Checking in");
        assertThat(w.sendAt()).isNotNull();
    }

    @Test
    void undoStopsTheSendAndLeavesTheDraftInDrafts() throws Exception {
        Draft d = followUpDraft();
        reset(gmail);
        connectFakeGmail();

        queue.send(d.id, null, null);
        assertThatThrownBy(() -> queue.requireNotWaiting(d.id)).hasMessageContaining("Undo");
        assertThatThrownBy(() -> queue.send(d.id, null, null)).hasMessageContaining("being sent");
        assertThat(queue.cancel(d.id)).isTrue();

        Thread.sleep(1500);
        verify(gmail, never()).send(any());
        assertThat(drafts.findById(d.id).orElseThrow().status).isEqualTo(DraftStatus.PENDING);
        assertThat(queue.cancel(d.id)).isFalse();
    }

    @Test
    void blanksAreRefusedStraightAwayNotAfterTheWait() {
        Draft d = followUpDraft();
        assertThatThrownBy(() -> queue.send(d.id, null, "My rate is [RATE FOR 1 REEL]."))
                .isInstanceOf(IllegalStateException.class);
        assertThat(queue.isWaiting(d.id)).isFalse();
    }

    @Test
    void aFailedSendIsReportedAndTheDraftStays() throws Exception {
        Draft d = followUpDraft();
        doThrow(new RuntimeException("Gmail said no")).when(gmail).send(any());

        queue.send(d.id, null, null);

        assertThat(statusAfterWindow(d.id)).isEqualTo(DraftStatus.PENDING);
        assertThat(queue.failure(d.id)).hasValueSatisfying(m -> assertThat(m).contains("Gmail said no"));
    }

    @Test
    void autoSendLeavesAFollowUpSheAlreadyPressedSendOn() throws Exception {
        Draft d = followUpDraft();
        reset(gmail);
        connectFakeGmail();

        queue.send(d.id, null, null);
        jobs.autoSendFollowUps();
        assertThat(drafts.findById(d.id).orElseThrow().status).isEqualTo(DraftStatus.PENDING);

        assertThat(statusAfterWindow(d.id)).isEqualTo(DraftStatus.SENT);
        verify(gmail, times(1)).send(argThat(x -> x.id.equals(d.id)));
    }

    @Test
    void autoSendStillSendsDueFollowUpsRightAway() throws Exception {
        Draft d = followUpDraft();

        jobs.autoSendFollowUps();

        assertThat(drafts.findById(d.id).orElseThrow().status).isEqualTo(DraftStatus.SENT);
    }
}

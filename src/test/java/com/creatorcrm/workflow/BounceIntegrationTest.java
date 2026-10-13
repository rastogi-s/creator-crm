package com.creatorcrm.workflow;

import static com.creatorcrm.FakeLlm.analysis;
import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.contacts.ContactService;
import com.creatorcrm.contacts.Suppressions;
import com.creatorcrm.domain.ContactSource;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.FollowUpStatus;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.FollowUp;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Suppression;
import com.creatorcrm.domain.Task;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.ingest.PreFilter;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.FollowUpRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.TaskRepo;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/** A pitch bounces: the address is blocked, follow-ups stop, and the brand's next contact is offered. */
@SpringBootTest
@ActiveProfiles("test")
class BounceIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @Autowired FakeLlm llm;
    @Autowired IngestionService ingestion;
    @Autowired BounceWatcher bounces;
    @Autowired ConversationRepo conversations;
    @Autowired OpportunityRepo opportunities;
    @Autowired FollowUpRepo followUps;
    @Autowired DraftRepo drafts;
    @Autowired TaskRepo tasks;
    @Autowired MessageRepo messages;
    @Autowired Suppressions suppressions;
    @Autowired ContactService contacts;
    @Autowired DraftService draftService;

    private String thread;
    private String maya;

    @BeforeEach
    void reset() {
        llm.next.clear();
        llm.failDraftsWith = null;
        llm.lastDraftInput = null;
        messages.findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc().forEach(m -> {
            m.aiProcessed = true;
            messages.save(m);
        });
        thread = "bounce" + UUID.randomUUID().toString().substring(0, 8);
        maya = "maya@" + thread + ".test";
    }

    private Message store(Direction dir, String from, String subject, String text, int hoursAgo) {
        String id = UUID.randomUUID().toString();
        ingestion.store(List.of(new NormalizedMessage(Platform.EMAIL, id, thread, dir, from, "",
                dir == Direction.INBOUND ? "me@creator.test" : maya, dir == Direction.INBOUND ? from : maya,
                subject, text, "<" + id + "@mail>", "", OffsetDateTime.now().minusHours(hoursAgo), false)));
        return messages.findByExternalId("email:" + id).orElseThrow();
    }

    private Opportunity pitch() {
        llm.next.add(analysis(Intent.PITCH, "Glow " + thread, false, List.of()));
        store(Direction.OUTBOUND, "me@creator.test", "Collab idea", "Hi! I'd love to make a UGC video for you.", 5);
        ingestion.processPending();
        Long convId = conversations.findByPlatformAndExternalId(Platform.EMAIL, thread).orElseThrow().id;
        Opportunity o = opportunities.findFirstByConversationIdOrderByIdDesc(convId).orElseThrow();
        assertThat(followUps.findFirstByOpportunityIdAndStatus(o.id, FollowUpStatus.SCHEDULED)).isPresent();
        return o;
    }

    private Message bounce() {
        Message m = store(Direction.INBOUND, "mailer-daemon@googlemail.com", "Delivery Status Notification (Failure)",
                "Address not found\n\nYour message wasn't delivered to " + maya
                        + " because the address couldn't be found, or is unable to receive mail.\n\n"
                        + "----- Original message -----\nFrom: me@creator.test\nTo: " + maya + "\nSubject: Collab idea", 1);
        ingestion.syncAll(); // nothing is connected in tests: this runs the bounce check and the analysis
        return m;
    }

    @Test
    void aBounceStopsFollowUpsAndOffersTheBrandsNextContact() {
        Opportunity o = pitch();
        String pr = "pr@" + thread + ".test";
        contacts.add(o.brandId, ContactService.Found.of(pr, "Priya", ContactSource.Kind.WEBSITE, null));

        Message notice = bounce();

        assertThat(messages.findById(notice.id).orElseThrow().filteredReason).isEqualTo(PreFilter.BOUNCE);
        assertThat(conversations.findById(o.conversationId).orElseThrow().counterparty).isEqualTo(maya);
        assertThat(suppressions.blocked(maya)).isTrue();

        List<FollowUp> fus = followUps.findByOpportunityIdOrderByNumberAsc(o.id);
        assertThat(fus).noneMatch(f -> f.status == FollowUpStatus.SCHEDULED);
        assertThat(fus).anySatisfy(f -> assertThat(f.stoppedReason).isEqualTo("Emails to " + maya + " bounced"));

        Task t = tasks.findByOpportunityIdAndStatus(o.id, TaskStatus.OPEN).stream()
                .filter(x -> x.description.startsWith("Emails to " + maya + " bounced")).findFirst().orElseThrow();
        assertThat(t.description).contains("Write to " + pr);
        assertThat(t.brief).contains("ready in Drafts");

        List<Draft> pending = drafts.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING);
        assertThat(pending).singleElement().satisfies(d -> {
            assertThat(d.type).isEqualTo(DraftType.PITCH);
            assertThat(d.toAddress).isEqualTo(pr);
            assertThat(d.conversationId).isEqualTo(o.conversationId);
            assertThat(d.taskId).isEqualTo(t.id);
        });
        assertThat(llm.lastDraftInput.extraInstructions()).contains(maya).contains(pr);

        // The same address bouncing again doesn't add a second to-do
        bounce();
        assertThat(tasks.findByOpportunityIdAndStatus(o.id, TaskStatus.OPEN))
                .filteredOn(x -> x.description.startsWith("Emails to " + maya)).hasSize(1);
    }

    @Test
    void withNoOtherContactSheIsAskedToFindOne() {
        Opportunity o = pitch();
        bounce();
        assertThat(tasks.findByOpportunityIdAndStatus(o.id, TaskStatus.OPEN))
                .anySatisfy(t -> assertThat(t.description).isEqualTo("Emails to " + maya + " bounced. Find another email for Glow " + thread));
        assertThat(drafts.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING)).isEmpty();
    }

    @Test
    void aFollowUpDraftWaitingForTheBouncedAddressIsTakenOut() {
        Opportunity o = pitch();
        FollowUp fu = followUps.findFirstByOpportunityIdAndStatus(o.id, FollowUpStatus.SCHEDULED).orElseThrow();
        Draft d = draftService.draftForFollowUp(fu).orElseThrow();
        assertThat(d.toAddress).isEqualTo(maya);
        bounce();
        assertThat(drafts.findById(d.id).orElseThrow().status).isEqualTo(DraftStatus.SUPERSEDED);
    }

    @Test
    void theMorningDoesNotChaseAnAddressThatCantBeEmailed() {
        Opportunity o = pitch();
        suppressions.add(maya, Suppression.Reason.BOUNCED);
        FollowUp fu = followUps.findFirstByOpportunityIdAndStatus(o.id, FollowUpStatus.SCHEDULED).orElseThrow();
        assertThat(bounces.stopIfUnreachable(fu)).isTrue();
        FollowUp stopped = followUps.findById(fu.id).orElseThrow();
        assertThat(stopped.status).isEqualTo(FollowUpStatus.CANCELLED);
        assertThat(stopped.stoppedReason).isEqualTo("Emails to " + maya + " bounced");
    }

    @Test
    void aBrandReplyAboutAnUndeliveredParcelIsStillAnalyzed() {
        pitch();
        llm.next.add(analysis(Intent.GENERAL_REPLY, "Glow " + thread, true, List.of()));
        Message reply = store(Direction.INBOUND, maya, "Re: Undelivered package", "Sorry, the courier lost it! Sending another.", 1);
        ingestion.processPending();
        assertThat(messages.findById(reply.id).orElseThrow().filteredReason).isNull();
        assertThat(suppressions.blocked(maya)).isFalse();
    }
}

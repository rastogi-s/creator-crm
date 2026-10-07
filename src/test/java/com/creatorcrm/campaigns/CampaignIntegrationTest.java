package com.creatorcrm.campaigns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.ChannelConnector.SentMessage;
import com.creatorcrm.channels.gmail.GmailConnector;
import com.creatorcrm.contacts.ContactService;
import com.creatorcrm.contacts.Suppressions;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandContact.Role;
import com.creatorcrm.domain.Campaign;
import com.creatorcrm.domain.CampaignTarget;
import com.creatorcrm.domain.CampaignTarget.State;
import com.creatorcrm.domain.ContactList;
import com.creatorcrm.domain.ContactSource;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.FollowUpStatus;
import com.creatorcrm.domain.FollowUp;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.PitchTemplate;
import com.creatorcrm.drafts.SendQueue;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.CampaignTargetRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.FollowUpRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.web.CrmController;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

/**
 * A campaign from list to sent pitch: one templated draft per brand to its best person, no Claude, approved into a
 * slow queue that keeps to the daily rules, and stopped by replies, bounces and "no thanks".
 */
@SpringBootTest(properties = {"crm.campaigns.min-gap-seconds=0", "crm.campaigns.max-gap-seconds=0",
        "crm.campaigns.working-hours-only=false"})
@ActiveProfiles("test")
class CampaignIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @MockitoSpyBean GmailConnector gmail;
    @Autowired FakeLlm llm;
    @Autowired CampaignService campaigns;
    @Autowired CampaignSender sender;
    @Autowired CampaignWatcher watcher;
    @Autowired CampaignState state;
    @Autowired ContactService contacts;
    @Autowired Suppressions suppressions;
    @Autowired SettingsService settings;
    @Autowired BrandRepo brands;
    @Autowired DraftRepo drafts;
    @Autowired CampaignTargetRepo targets;
    @Autowired OpportunityRepo opportunities;
    @Autowired ConversationRepo conversations;
    @Autowired MessageRepo messages;
    @Autowired FollowUpRepo followUps;
    @Autowired CrmController crm;
    @Autowired SendQueue sendQueue;

    String tag;

    @BeforeEach
    void setUp() throws Exception {
        reset(gmail);
        doReturn(true).when(gmail).isConnected();
        doReturn(Optional.empty()).when(gmail).pushDraft(any());
        doAnswer(inv -> new SentMessage(UUID.randomUUID().toString(), UUID.randomUUID().toString())).when(gmail).send(any());
        settings.update(Map.of(SettingsService.CAMPAIGN_ADDRESS, "PO Box 12\nAustin, TX 78701", SettingsService.CAMPAIGN_DAILY_CAP, "30",
                SettingsService.CAMPAIGN_WARMUP, "true", SettingsService.CREATOR_NAME, "Maya"));
        state.resume();
        state.nextSendAt(OffsetDateTime.now().minusMinutes(1));
        watcher.scan(); // start reading from here
        tag = "Cmp" + UUID.randomUUID().toString().substring(0, 6);
        llm.draftCalls = 0;
    }

    private Brand brand(String name) {
        Brand b = new Brand();
        b.name = tag + " " + name;
        b.nameKey = Brand.key(b.name);
        b.createdAt = OffsetDateTime.now();
        return brands.save(b);
    }

    private void contact(Brand b, String email, String name, Role role) {
        contacts.add(b.id, new ContactService.Found(email, name, null, role, null, null, null, null, ContactSource.Kind.MANUAL, null));
    }

    private Campaign startCampaign() {
        ContactList list = campaigns.saveList(null, tag + " list", new ContactFilter(null, false, false, null, tag, null, true));
        PitchTemplate t = campaigns.templates().get(0);
        Campaign c = campaigns.start(null, list.id, t.id, false, false);
        campaigns.draftMore(c.id);
        return c;
    }

    private List<CampaignTarget> targetsOf(Campaign c) {
        return targets.findByCampaignIdOrderByIdAsc(c.id);
    }

    private Message inbound(Long conversationId, String from, String subject, String body) {
        Message m = new Message();
        m.conversationId = conversationId;
        m.externalId = "email:test-" + UUID.randomUUID();
        m.direction = Direction.INBOUND;
        m.sender = from;
        m.subject = subject;
        m.content = body;
        m.sentAt = OffsetDateTime.now().plusSeconds(1);
        m.aiProcessed = true;
        return messages.save(m);
    }

    private Conversation conversationOf(CampaignTarget t) {
        Opportunity o = opportunities.findById(t.opportunityId).orElseThrow();
        return conversations.findById(o.conversationId).orElseThrow();
    }

    @Test
    void draftsOnePitchPerBrandToItsBestPersonWithNoClaude() {
        Brand a = brand("Alpha");
        contact(a, "hello@" + tag.toLowerCase() + "-alpha.com", null, Role.GENERAL);
        contact(a, "ana@" + tag.toLowerCase() + "-alpha.com", "Ana Diaz", Role.PARTNERSHIPS);
        Brand b = brand("Beta");
        contact(b, "collabs@" + tag.toLowerCase() + "-beta.com", null, Role.PARTNERSHIPS);

        Campaign c = startCampaign();

        List<CampaignTarget> list = targetsOf(c);
        assertThat(list).hasSize(2).allMatch(t -> t.state == State.DRAFTED);
        Draft da = drafts.findById(list.stream().filter(t -> t.brandId.equals(a.id)).findFirst().orElseThrow().draftId).orElseThrow();
        assertThat(da.toAddress).isEqualTo("ana@" + tag.toLowerCase() + "-alpha.com");
        assertThat(da.type).isEqualTo(DraftType.PITCH);
        assertThat(da.subject).isEqualTo("Collab idea for " + a.name);
        assertThat(da.body).startsWith("Hi Ana,").contains("I'm Maya").contains("PO Box 12, Austin, TX 78701")
                .contains(CampaignFooter.OPT_OUT_LINE).doesNotContain("{");
        Draft db = drafts.findById(list.stream().filter(t -> t.brandId.equals(b.id)).findFirst().orElseThrow().draftId).orElseThrow();
        assertThat(db.body).startsWith("Hi " + b.name + " team,");
        assertThat(llm.draftCalls).isZero();

        // In Drafts with the campaign named; the normal Send path refuses it
        assertThat(crm.pendingDrafts()).anyMatch(v -> v.draft().id.equals(da.id) && c.name.equals(v.campaign()));
        assertThatThrownBy(() -> sendQueue.send(da.id, null, null)).hasMessageContaining("Approve");

        // Drafting again adds nobody new: each brand is in once
        assertThat(campaigns.draftMore(c.id)).isZero();
    }

    @Test
    void approvedPitchesGoOutSlowlyAndKeepTheRules() throws Exception {
        Brand a = brand("Alpha");
        contact(a, "ana@" + tag.toLowerCase() + "-alpha.com", "Ana Diaz", Role.PARTNERSHIPS);
        contact(a, "pr@" + tag.toLowerCase() + "-alpha.com", null, Role.PR);
        Brand b = brand("Beta");
        contact(b, "collabs@" + tag.toLowerCase() + "-beta.com", null, Role.PARTNERSHIPS);
        Campaign c = startCampaign();
        List<CampaignTarget> list = targetsOf(c);
        CampaignTarget ta = list.stream().filter(t -> t.brandId.equals(a.id)).findFirst().orElseThrow();
        CampaignTarget tb = list.stream().filter(t -> t.brandId.equals(b.id)).findFirst().orElseThrow();

        // Approving takes it out of Drafts and into the queue; she can take it back
        campaigns.approve(ta.draftId, null, null);
        assertThat(crm.pendingDrafts()).noneMatch(v -> v.draft().id.equals(ta.draftId));
        campaigns.unapprove(ta.draftId);
        assertThat(crm.pendingDrafts()).anyMatch(v -> v.draft().id.equals(ta.draftId));
        assertThat(campaigns.approveAll(c.id).get("approved")).isEqualTo(2);

        // Paused: nothing goes
        state.pause("test");
        assertThat(sender.sendNext()).isEmpty();
        state.resume();

        // One at a time
        assertThat(sender.sendNext()).isPresent();
        assertThat(sender.sendNext()).isPresent();
        assertThat(sender.sendNext()).isEmpty();
        verify(gmail, times(2)).send(argThat(d -> d.body.contains("PO Box 12") && d.body.contains(CampaignFooter.OPT_OUT_LINE)));
        assertThat(targets.findById(ta.id).orElseThrow().state).isEqualTo(State.SENT);
        Opportunity oa = opportunities.findById(ta.opportunityId).orElseThrow();
        assertThat(oa.pitchedAt).isNotNull();
        assertThat(followUps.findFirstByOpportunityIdAndStatus(oa.id, FollowUpStatus.SCHEDULED)).isPresent();

        // Ana's address bounces: do-not-email, and the brand's next person gets a pitch, but not on the same day
        Message bounce = inbound(conversationOf(ta).id, "mailer-daemon@googlemail.com", "Delivery Status Notification (Failure)",
                "Address not found. Your message wasn't delivered to ana@" + tag.toLowerCase() + "-alpha.com because the address couldn't be found.");
        watcher.scan();
        assertThat(targets.findById(ta.id).orElseThrow().state).isEqualTo(State.BOUNCED);
        assertThat(suppressions.blocked("ana@" + tag.toLowerCase() + "-alpha.com")).isTrue();
        assertThat(state.pausedReason()).isEmpty(); // one bounce isn't enough to pause
        campaigns.reconcile();
        CampaignTarget next = targetsOf(c).stream().filter(t -> t.attempt == 2).findFirst().orElseThrow();
        assertThat(next.email).isEqualTo("pr@" + tag.toLowerCase() + "-alpha.com");
        assertThat(targets.findById(ta.id).orElseThrow().state).isEqualTo(State.MOVED_ON);
        campaigns.approve(next.draftId, null, null);
        assertThat(sender.sendNext()).isEmpty(); // someone at Alpha already got a pitch today
        assertThat(bounce.id).isNotNull();

        // Beta writes back: the brand's campaign stops, follow-ups too
        Conversation convB = conversationOf(tb);
        inbound(convB.id, "collabs@" + tag.toLowerCase() + "-beta.com", "Re: Collab idea", "Sounds great! What are your rates?");
        watcher.scan();
        assertThat(targets.findById(tb.id).orElseThrow().state).isEqualTo(State.REPLIED);
        assertThat(followUps.findFirstByOpportunityIdAndStatus(tb.opportunityId, FollowUpStatus.SCHEDULED)).isEmpty();
        assertThat(state.pausedReason()).isEmpty();
    }

    @Test
    void noThanksGoesOnTheDoNotEmailListAndPausesSending() {
        Brand a = brand("Alpha");
        String email = "ana@" + tag.toLowerCase() + "-alpha.com";
        contact(a, email, "Ana Diaz", Role.PARTNERSHIPS);
        Campaign c = startCampaign();
        CampaignTarget t = targetsOf(c).get(0);
        campaigns.approve(t.draftId, null, null);
        assertThat(sender.sendNext()).isPresent();

        inbound(conversationOf(t).id, email, "Re: Collab idea", "No thanks.\n\nOn Tue, Maya wrote:\n> Hi Ana");
        watcher.scan();

        assertThat(targets.findById(t.id).orElseThrow().state).isEqualTo(State.OPTED_OUT);
        assertThat(suppressions.blocked(email)).isTrue();
        assertThat(state.pausedReason()).hasValueSatisfying(r -> assertThat(r).contains("asked not to be emailed"));
        state.resume();
    }

    @Test
    void campaignFollowUpsUseTheTemplateNotClaude() {
        Brand a = brand("Alpha");
        contact(a, "ana@" + tag.toLowerCase() + "-alpha.com", "Ana Diaz", Role.PARTNERSHIPS);
        Campaign c = startCampaign();
        CampaignTarget t = targetsOf(c).get(0);
        campaigns.approve(t.draftId, null, null);
        assertThat(sender.sendNext()).isPresent();
        FollowUp fu = followUps.findFirstByOpportunityIdAndStatus(t.opportunityId, FollowUpStatus.SCHEDULED).orElseThrow();

        assertThat(campaigns.draftFollowUp(fu)).isTrue();

        Draft d = drafts.findByCampaignTargetIdAndStatus(t.id, DraftStatus.PENDING).get(0);
        assertThat(d.type).isEqualTo(DraftType.FOLLOW_UP);
        assertThat(d.subject).startsWith("Re: ");
        assertThat(d.body).startsWith("Hi Ana,").contains(CampaignFooter.OPT_OUT_LINE);
        assertThat(llm.draftCalls).isZero();
    }

    @Test
    void needsHerPostalAddressBeforeAnything() throws Exception {
        settings.update(Map.of(SettingsService.CAMPAIGN_ADDRESS, ""));
        ContactList list = campaigns.saveList(null, tag, new ContactFilter(null, false, false, null, tag, null, true));
        assertThatThrownBy(() -> campaigns.start(null, list.id, campaigns.templates().get(0).id, false, false))
                .hasMessageContaining("postal address");
        verify(gmail, never()).send(any());
    }

    @Test
    void warmUpStartsAtTenADay() {
        settings.update(Map.of(SettingsService.CAMPAIGN_DAILY_CAP, "50"));
        assertThat(state.dailyAllowance()).isBetween(10, 50);
        settings.update(Map.of(SettingsService.CAMPAIGN_WARMUP, "false"));
        assertThat(state.dailyAllowance()).isEqualTo(50);
        assertThatThrownBy(() -> settings.update(Map.of(SettingsService.CAMPAIGN_DAILY_CAP, "80"))).hasMessageContaining("10 to 50");
    }
}

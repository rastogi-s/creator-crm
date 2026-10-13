package com.creatorcrm.drafts;

import static com.creatorcrm.FakeLlm.analysis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.contacts.Suppressions;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Suppression;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.llm.MessageAnalysis;
import com.creatorcrm.repo.BrandContactRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
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

/** A brand answers a pitch with "please email someone else": the pitch is drafted to that address, and To is editable. */
@SpringBootTest
@ActiveProfiles("test")
class EmailInsteadIntegrationTest {

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
    @Autowired ConversationRepo conversations;
    @Autowired OpportunityRepo opportunities;
    @Autowired DraftRepo drafts;
    @Autowired MessageRepo messages;
    @Autowired BrandRepo brands;
    @Autowired BrandContactRepo contacts;
    @Autowired Suppressions suppressions;
    @Autowired DraftService draftService;

    private String thread;

    @BeforeEach
    void reset() {
        llm.next.clear();
        llm.failDraftsWith = null;
        llm.lastDraftInput = null;
        messages.findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc().forEach(m -> {
            m.aiProcessed = true;
            messages.save(m);
        });
        thread = "instead" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static MessageAnalysis writeTo(MessageAnalysis a, String email) {
        return new MessageAnalysis(a.brandRelated(), a.brandName(), a.contactName(), a.intent(), a.opportunityType(),
                a.compensation(), a.budgetAmount(), a.currency(), a.budgetText(), a.deliverables(), a.usageRights(),
                a.campaign(), a.deadlines(), a.missingInfo(), a.requiresReply(), a.urgency(), a.suggestedAction(),
                a.updatedSummary(), a.taskBrief(), a.links(), a.dealStage(), email);
    }

    private void email(Direction dir, int hoursAgo, String text, MessageAnalysis a) {
        llm.next.add(a);
        String maya = "maya@" + thread + ".test";
        ingestion.store(List.of(new NormalizedMessage(Platform.EMAIL, UUID.randomUUID().toString(), thread, dir,
                dir == Direction.INBOUND ? maya : "me@creator.test", "Maya Lee",
                dir == Direction.INBOUND ? "me@creator.test" : maya, maya,
                "Collab idea", text, "<" + UUID.randomUUID() + "@mail>", "", OffsetDateTime.now().minusHours(hoursAgo), false)));
        ingestion.processPending();
    }

    /** She pitched; Maya answers "not me, write to {@code address}" (as Claude read it: {@code seen}). */
    private Opportunity pitchAndRedirect(String text, String seen) {
        email(Direction.OUTBOUND, 5, "Hi! I'd love to make a UGC video for you.", analysis(Intent.PITCH, "Glow " + thread, false, List.of()));
        email(Direction.INBOUND, 1, text, writeTo(analysis(Intent.GENERAL_REPLY, "Glow " + thread, true, List.of()), seen));
        Long convId = conversations.findByPlatformAndExternalId(Platform.EMAIL, thread).orElseThrow().id;
        return opportunities.findFirstByConversationIdOrderByIdDesc(convId).orElseThrow();
    }

    @Test
    void draftsThePitchToTheAddressTheBrandGave() {
        String partners = "partners@" + thread + ".test";
        Opportunity o = pitchAndRedirect("Thanks! I'm not the right person, please send this to " + partners + " and they'll take a look.",
                partners.toUpperCase());

        List<Draft> pending = drafts.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING);
        assertThat(pending).singleElement().satisfies(d -> {
            assertThat(d.toAddress).isEqualTo(partners);
            assertThat(d.type).isEqualTo(DraftType.PITCH);
            assertThat(d.channel).isEqualTo(Platform.EMAIL);
            assertThat(d.conversationId).isEqualTo(o.conversationId); // stays in the deal's thread
        });
        assertThat(llm.lastDraftInput.extraInstructions()).contains(partners).contains("Maya Lee");
        assertThat(contacts.findByEmail(partners)).get().satisfies(c -> assertThat(c.brandId).isEqualTo(o.brandId));
        assertThat(brands.findById(o.brandId).orElseThrow().contactEmail).isEqualTo(partners);
    }

    @Test
    void anAddressThatIsNotInTheEmailIsIgnored() {
        Opportunity o = pitchAndRedirect("Thanks, we'll get back to you next week!", "made-up@" + thread + ".test");

        List<Draft> pending = drafts.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING);
        assertThat(pending).singleElement().satisfies(d -> {
            assertThat(d.type).isEqualTo(DraftType.REPLY);
            assertThat(d.toAddress).isEqualTo("maya@" + thread + ".test");
        });
        assertThat(contacts.findByEmail("made-up@" + thread + ".test")).isEmpty();
    }

    @Test
    void theSendersOwnAddressIsNotARedirect() {
        String maya = "maya@" + thread + ".test";
        Opportunity o = pitchAndRedirect("Sure, tell me more!\n\nMaya Lee | " + maya, maya);
        assertThat(drafts.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING))
                .singleElement().satisfies(d -> assertThat(d.type).isEqualTo(DraftType.REPLY));
    }

    @Test
    void aDoNotEmailAddressGetsNoPitch() {
        String partners = "partners@" + thread + ".test";
        suppressions.add(partners, Suppression.Reason.OPTED_OUT);
        Opportunity o = pitchAndRedirect("Please write to " + partners + " instead.", partners);
        assertThat(drafts.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING))
                .noneMatch(d -> partners.equals(d.toAddress));
    }

    @Test
    void sheCanChangeWhoAnEmailGoesTo() {
        Opportunity o = pitchAndRedirect("Sounds fun, what are your rates?", "");
        Draft d = drafts.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING).getFirst();

        Draft saved = draftService.edit(d.id, "  jane@" + thread + ".test ", d.subject, d.body);
        assertThat(saved.toAddress).isEqualTo("jane@" + thread + ".test");
        // null leaves it as it is
        assertThat(draftService.edit(d.id, null, d.subject, d.body).toAddress).isEqualTo("jane@" + thread + ".test");
        assertThatThrownBy(() -> draftService.edit(d.id, "jane at brand", d.subject, d.body))
                .hasMessageContaining("one email address");
        assertThatThrownBy(() -> draftService.edit(d.id, "a@x.test, b@x.test", d.subject, d.body))
                .hasMessageContaining("one email address");
    }
}

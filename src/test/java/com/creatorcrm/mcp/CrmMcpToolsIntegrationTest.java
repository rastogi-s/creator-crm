package com.creatorcrm.mcp;

import static com.creatorcrm.FakeLlm.analysis;
import static org.assertj.core.api.Assertions.assertThat;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.domain.BrandLead;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.InvoiceStatus;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.BrandLeads;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.repo.BrandLeadRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.InvoiceRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/** The money, invoice and outreach MCP tools drive the same services as the app, and nothing they do sends. */
@SpringBootTest
@ActiveProfiles("test")
class CrmMcpToolsIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @Autowired FakeLlm llm;
    @Autowired CrmMcpTools tools;
    @Autowired IngestionService ingestion;
    @Autowired OpportunityRepo opportunities;
    @Autowired ConversationRepo conversations;
    @Autowired MessageRepo messages;
    @Autowired InvoiceRepo invoiceRepo;
    @Autowired DraftRepo drafts;
    @Autowired BrandLeadRepo leads;

    @BeforeEach
    void reset() {
        llm.next.clear();
        llm.failDraftsWith = null;
        for (Message m : messages.findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc()) {
            m.filteredReason = "left over from another test";
            messages.save(m);
        }
    }

    private Opportunity paidDeal() {
        String thread = "mcp" + UUID.randomUUID().toString().substring(0, 8);
        llm.next.add(analysis(Intent.INVOICE_REQUEST, "Brand " + thread, true, List.of()));
        ingestion.store(List.of(new NormalizedMessage(Platform.EMAIL, UUID.randomUUID().toString(), thread, Direction.INBOUND,
                "maya@" + thread + ".test", "Maya", "me@creator.test", "maya@" + thread + ".test", "Invoice please",
                "Please send your invoice", "<" + UUID.randomUUID() + "@mail>", "", OffsetDateTime.now().minusHours(1), false)));
        ingestion.processPending();
        Long conv = conversations.findByPlatformAndExternalId(Platform.EMAIL, thread).orElseThrow().id;
        return opportunities.findFirstByConversationIdOrderByIdDesc(conv).orElseThrow();
    }

    private static Long id(String label, String text) {
        Matcher m = Pattern.compile("\\(" + label + " (\\d+)\\)").matcher(text);
        assertThat(m.find()).as(text).isTrue();
        return Long.valueOf(m.group(1));
    }

    @Test
    void invoiceFromDealToPaidThroughTools() {
        Opportunity o = paidDeal();
        String brand = "Brand " + conversations.findById(o.conversationId).orElseThrow().externalId;
        assertThat(tools.moneySummary()).contains("Ready to invoice:").contains("[opp " + o.id + "] " + brand);

        String created = tools.createInvoice(o.id);
        Long invoiceId = id("invoice", created);
        assertThat(created).contains("USD 500.00");

        String email = tools.draftInvoiceEmail(invoiceId);
        assertThat(email).startsWith("Draft #");
        assertThat(drafts.findByStatusOrderByCreatedAtAsc(DraftStatus.PENDING)).anyMatch(d -> o.id.equals(d.opportunityId));

        tools.markInvoicePaid(invoiceId, null);
        assertThat(invoiceRepo.findById(invoiceId).orElseThrow().status).isEqualTo(InvoiceStatus.PAID);
        assertThat(opportunities.findById(o.id).orElseThrow().status).isEqualTo(OpportunityStatus.CLOSED);
        assertThat(tools.moneySummary()).doesNotContain("[opp " + o.id + "]");
    }

    @Test
    void researchedLeadsCanBeListedPitchedAndDismissed() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        llm.nextLeads = new BrandLeads(List.of(
                new BrandLeads.Lead("Glowco " + suffix, "https://glowco.test", "glowco", "hi@glowco.test", "https://glowco.test/contact",
                        "Works with UGC creators", "Morning routine reel"),
                new BrandLeads.Lead("Dewly " + suffix, null, "dewly", null, null, "Gifting program", "Shelfie")));

        String found = tools.findBrandsToPitch("clean skincare brands", 2, "QUICK");
        assertThat(found).contains("Added 2 lead(s)").contains("Glowco " + suffix).contains("Idea: Morning routine reel");
        BrandLead glow = leads.findAll().stream().filter(l -> l.name.equals("Glowco " + suffix)).findFirst().orElseThrow();
        BrandLead dew = leads.findAll().stream().filter(l -> l.name.equals("Dewly " + suffix)).findFirst().orElseThrow();
        assertThat(tools.brandLeads()).contains("[lead " + glow.id + "]").contains("@dewly");

        assertThat(tools.draftPitchForLead(glow.id)).startsWith("Draft #");
        assertThat(tools.dismissBrandLead(dew.id)).isEqualTo("Dismissed Dewly " + suffix + ".");
        assertThat(tools.brandLeads()).doesNotContain("Glowco " + suffix).doesNotContain("Dewly " + suffix);
    }
}

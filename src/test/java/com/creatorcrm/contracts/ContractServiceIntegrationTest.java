package com.creatorcrm.contracts;

import static com.creatorcrm.FakeLlm.analysis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.channels.gmail.GmailConnector;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Task;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.TaskRepo;
import com.creatorcrm.settings.SettingsService;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** A contract email end to end: the PDF is read, Claude's terms are checked, and the flags become a task. */
@SpringBootTest
@ActiveProfiles("test")
class ContractServiceIntegrationTest {

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
    @Autowired ContractService contracts;
    @Autowired IngestionService ingestion;
    @Autowired ConversationRepo conversations;
    @Autowired OpportunityRepo opportunities;
    @Autowired MessageRepo messages;
    @Autowired TaskRepo tasks;
    @Autowired SettingsService settings;

    @BeforeEach
    void reset() throws Exception {
        llm.next.clear();
        llm.contractCalls = 0;
        doReturn(true).when(gmail).isConnected();
        doReturn(Optional.empty()).when(gmail).pushDraft(any());
        doReturn(List.of()).when(gmail).pdfAttachments(anyString(), anyInt(), anyLong());
        // The database is shared with other test classes: set aside their unanalyzed messages.
        for (Message m : messages.findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc()) {
            m.filteredReason = "left over from another test";
            messages.save(m);
        }
    }

    @AfterEach
    void restore() {
        settings.update(Map.of(SettingsService.CONTRACT_MAX_PAYMENT_DAYS, "30"));
    }

    static byte[] pdf(String line) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(40, 700);
                for (int i = 0; i < 6; i++) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -14);
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private Opportunity contractEmail(String externalId, String body, OffsetDateTime sentAt) {
        String thread = "contract" + UUID.randomUUID().toString().substring(0, 8);
        llm.next.add(analysis(Intent.CONTRACT_SENT, "Brand " + thread, true, List.of()));
        ingestion.store(List.of(new NormalizedMessage(Platform.EMAIL, externalId, thread, Direction.INBOUND,
                "maya@" + thread + ".test", "Maya", "me@creator.test", "maya@" + thread + ".test", "Contract", body,
                "<" + UUID.randomUUID() + "@mail>", "", sentAt, false)));
        ingestion.processPending();
        Long conv = conversations.findByPlatformAndExternalId(Platform.EMAIL, thread).orElseThrow().id;
        return opportunities.findFirstByConversationIdOrderByIdDesc(conv).orElseThrow();
    }

    private Task reviewTask(Opportunity o) {
        return tasks.findByOpportunityIdAndStatus(o.id, TaskStatus.OPEN).stream()
                .filter(t -> t.type == TaskType.REVIEW_CONTRACT).findFirst().orElseThrow();
    }

    @Test
    void readsTheAttachedPdfAndFlagsItsTerms() throws Exception {
        String id = UUID.randomUUID().toString();
        doReturn(List.of(new GmailConnector.FileAttachment("agreement.pdf",
                pdf("Brand pays Creator USD 500 net 60. Brand may run the video as paid ads for 12 months. "))))
                .when(gmail).pdfAttachments(org.mockito.ArgumentMatchers.eq(id), anyInt(), anyLong());
        Opportunity o = contractEmail(id, "Hi! The contract is attached.", OffsetDateTime.now().minusHours(1));

        assertThat(llm.contractCalls).isEqualTo(1);
        assertThat(llm.lastContractInput.contractText()).startsWith("<untrusted_contract>").contains("Brand pays Creator USD 500 net 60");
        List<ContractService.View> list = contracts.forDeal(o.id);
        assertThat(list).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo("CHECKED");
            assertThat(c.fileName()).isEqualTo("agreement.pdf");
            assertThat(c.red()).isEqualTo(1); // net 60
            assertThat(c.amber()).isGreaterThanOrEqualTo(3); // a year of ads, no kill fee, the brand owns the content
            assertThat(c.flags().get(0).text()).contains("60 days");
        });
        assertThat(reviewTask(o).description).contains("1 thing to push back on");
    }

    @Test
    void aSigningLinkAsksHerToPasteTheText() {
        Opportunity o = contractEmail(UUID.randomUUID().toString(),
                "Please sign here: https://app.docusign.example/sign/abc", OffsetDateTime.now().minusHours(1));
        assertThat(llm.contractCalls).isZero();
        assertThat(contracts.forDeal(o.id)).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo("LINK_ONLY");
            assertThat(c.note()).contains("DocuSign");
        });
        assertThat(reviewTask(o).description).contains("DocuSign");

        assertThatThrownBy(() -> contracts.checkPasted(o.id, "too short")).hasMessageContaining("whole contract");
        ContractService.View pasted = contracts.checkPasted(o.id, "Agreement. ".repeat(40));
        assertThat(pasted.status()).isEqualTo("CHECKED");
        assertThat(pasted.source()).isEqualTo("PASTED");
        assertThat(reviewTask(o).description).contains("push back on");

        // New limits apply on a re-check without asking Claude again.
        settings.update(Map.of(SettingsService.CONTRACT_MAX_PAYMENT_DAYS, "90"));
        ContractService.View again = contracts.recheck(pasted.id());
        assertThat(again.red()).isZero();
        assertThat(llm.contractCalls).isEqualTo(1);
    }

    @Test
    void oldContractsInAnImportAreSkipped() {
        Opportunity o = contractEmail(UUID.randomUUID().toString(), "Contract attached.", OffsetDateTime.now().minusDays(90));
        assertThat(contracts.forDeal(o.id)).isEmpty();
        assertThat(llm.contractCalls).isZero();
    }
}

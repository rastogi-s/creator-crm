package com.creatorcrm.invoices;

import static com.creatorcrm.FakeLlm.analysis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.digest.DigestService;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.InvoiceStatus;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.Invoice;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.llm.LlmException;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.TaskRepo;
import com.creatorcrm.settings.SettingsService;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

/** Invoice lifecycle: brand asks -> invoice from the deal -> email draft with PDF -> sent -> overdue -> paid. */
@SpringBootTest
@ActiveProfiles("test")
class InvoiceIntegrationTest {

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
    @Autowired InvoiceService invoices;
    @Autowired DraftService drafts;
    @Autowired DigestService digest;
    @Autowired OpportunityRepo opportunities;
    @Autowired ConversationRepo conversations;
    @Autowired BrandRepo brands;
    @Autowired TaskRepo tasks;
    @Autowired SettingsService settings;
    @Autowired InvoicePdf pdf;
    @Autowired MessageRepo messages;

    @BeforeEach
    void reset() {
        llm.next.clear();
        llm.failDraftsWith = null;
        // The database is shared with other test classes. Messages they left unanalyzed would be processed first
        // (oldest first) and take this test's scripted analysis, so set them aside.
        for (Message m : messages.findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc()) {
            m.filteredReason = "left over from another test";
            messages.save(m);
        }
    }

    /** A paid deal for a new brand whose last email was the given intent. */
    private Opportunity deal(Intent intent) {
        String thread = "inv" + UUID.randomUUID().toString().substring(0, 8);
        llm.next.add(analysis(intent, "Brand " + thread, true, List.of()));
        ingestion.store(List.of(new NormalizedMessage(Platform.EMAIL, UUID.randomUUID().toString(), thread, Direction.INBOUND,
                "maya@" + thread + ".test", "Maya", "me@creator.test", "maya@" + thread + ".test", "Invoice please",
                "Please send your invoice", "<" + UUID.randomUUID() + "@mail>", "", OffsetDateTime.now().minusHours(1), false)));
        ingestion.processPending();
        Long conv = conversations.findByPlatformAndExternalId(Platform.EMAIL, thread).orElseThrow().id;
        return opportunities.findFirstByConversationIdOrderByIdDesc(conv).orElseThrow();
    }

    private List<TaskType> openTasks(Opportunity o) {
        return tasks.findByOpportunityIdAndStatus(o.id, TaskStatus.OPEN).stream().map(t -> t.type).toList();
    }

    @Test
    void brandAsksForInvoiceThenItIsEmailedWithThePdfAndPaid() {
        Opportunity o = deal(Intent.INVOICE_REQUEST);
        assertThat(o.status).isEqualTo(OpportunityStatus.PAYMENT_PENDING);
        assertThat(openTasks(o)).containsExactly(TaskType.SEND_INVOICE);

        LocalDate today = settings.today();
        Invoice inv = invoices.createForDeal(o.id, today);
        assertThat(inv.status).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(inv.amount).isEqualByComparingTo("500.00");
        assertThat(inv.currency).isEqualTo("USD");
        assertThat(inv.dueDate).isEqualTo(today.plusDays(30));
        assertThat(inv.billToEmail).startsWith("maya@inv");
        assertThat(LineItem.parse(inv.lineItems)).containsExactly(new LineItem("Fall launch: 1 UGC video", new BigDecimal("500.00")));
        assertThat(invoices.createForDeal(o.id, today).id).as("one draft invoice per deal").isEqualTo(inv.id);

        inv = invoices.update(inv.id, new InvoiceService.InvoiceEdit(null, null, null,
                List.of(new LineItem("Reel", new BigDecimal("400")), new LineItem("Usage rights, 3 months", new BigDecimal("150.5"))),
                "PO 1234", null, null));
        assertThat(inv.amount).isEqualByComparingTo("550.50");

        Draft d = invoices.emailDraft(inv.id);
        assertThat(d.type).isEqualTo(DraftType.INVOICE);
        assertThat(d.invoiceId).isEqualTo(inv.id);
        assertThat(d.channel).isEqualTo(Platform.EMAIL);
        assertThat(d.toAddress).isEqualTo(inv.billToEmail);
        assertThat(llm.lastDraftInput.extraInstructions()).contains(inv.number, "USD 550.50");

        drafts.markSentManually(d.id);
        Invoice sent = invoices.get(inv.id);
        assertThat(sent.status).isEqualTo(InvoiceStatus.SENT);
        assertThat(sent.sentAt).isNotNull();
        o = opportunities.findById(o.id).orElseThrow();
        assertThat(o.status).isEqualTo(OpportunityStatus.PAYMENT_PENDING);
        assertThat(openTasks(o)).isEmpty();
        assertThatThrownBy(() -> invoices.update(sent.id, new InvoiceService.InvoiceEdit("x", null, null, null, null, null, null)))
                .isInstanceOf(IllegalStateException.class);

        invoices.markPaid(inv.id, today);
        assertThat(invoices.get(inv.id).status).isEqualTo(InvoiceStatus.PAID);
        o = opportunities.findById(o.id).orElseThrow();
        assertThat(o.status).isEqualTo(OpportunityStatus.CLOSED);
        assertThat(o.closedReason).isEqualTo("Paid");
        assertThat(invoices.money(today.getYear()).paidThisMonth().get("USD")).isGreaterThanOrEqualTo(new BigDecimal("550.50"));
    }

    @Test
    void invoiceEmailFallsBackToAStandardNoteWhenClaudeIsUnavailable() {
        Opportunity o = deal(Intent.INVOICE_REQUEST);
        Invoice inv = invoices.createForDeal(o.id, settings.today());
        llm.failDraftsWith = new LlmException("Claude is down");
        Draft d = invoices.emailDraft(inv.id);
        assertThat(d.subject).isNotBlank();
        assertThat(d.body).contains(inv.number, "USD 500.00", "attached");
    }

    @Test
    void numbersRunPerYearAndRestartInJanuary() {
        Opportunity a = deal(Intent.INVOICE_REQUEST);
        Opportunity b = deal(Intent.INVOICE_REQUEST);
        Opportunity c = deal(Intent.INVOICE_REQUEST);
        Invoice first = invoices.createForDeal(a.id, LocalDate.of(2031, 12, 30));
        Invoice second = invoices.createForDeal(b.id, LocalDate.of(2031, 12, 31));
        Invoice third = invoices.createForDeal(c.id, LocalDate.of(2032, 1, 2));
        assertThat(List.of(first.number, second.number, third.number))
                .containsExactly("INV-2031-001", "INV-2031-002", "INV-2032-001");

        // Voided numbers are never reused
        invoices.voidInvoice(second.id);
        Invoice fourth = invoices.createForDeal(b.id, LocalDate.of(2031, 12, 31));
        assertThat(fourth.number).isEqualTo("INV-2031-003");

        // Moving a draft into another year renumbers it in that year
        Invoice moved = invoices.update(fourth.id, new InvoiceService.InvoiceEdit(null, null, null, null, null,
                LocalDate.of(2032, 1, 5), LocalDate.of(2032, 2, 4)));
        assertThat(moved.number).isEqualTo("INV-2032-002");
        assertThatThrownBy(() -> invoices.update(moved.id, new InvoiceService.InvoiceEdit(null, null, null, null, null,
                null, LocalDate.of(2031, 1, 1)))).hasMessageContaining("before the invoice date");
    }

    @Test
    void overdueInvoicesShowOnTodayAndInTheMoneyTotals() {
        Opportunity o = deal(Intent.PAYMENT_UPDATE);
        assertThat(openTasks(o)).containsExactly(TaskType.CONFIRM_PAYMENT);
        LocalDate today = settings.today();
        Invoice inv = invoices.createForDeal(o.id, today.minusDays(40));
        assertThat(inv.dueDate).isEqualTo(today.minusDays(10));
        assertThat(invoices.money(null).readyToInvoice()).noneMatch(r -> r.opportunityId().equals(o.id));

        invoices.markSent(inv.id);
        Invoice sent = invoices.get(inv.id);
        assertThat(sent.isOverdue(today)).isTrue();
        assertThat(invoices.view(sent).daysOverdue()).isEqualTo(10);
        assertThat(digest.morning().urgent()).anyMatch(i -> i.kind().equals("INVOICE") && i.refId().equals(inv.id)
                && i.overdueDays() == 10 && i.title().endsWith("payment 10 days late") && i.detail().contains(inv.number));
        assertThat(invoices.money(null).overdue().get("USD")).isGreaterThanOrEqualTo(new BigDecimal("500"));
        assertThat(invoices.unpaid()).anyMatch(v -> v.id().equals(inv.id));

        invoices.markPaid(inv.id, today);
        assertThat(openTasks(opportunities.findById(o.id).orElseThrow())).isEmpty();
        assertThat(digest.morning().urgent()).noneMatch(i -> i.kind().equals("INVOICE") && i.refId().equals(inv.id));
    }

    @Test
    void bookedDealsWithoutAnInvoiceAreReadyToInvoice() {
        Opportunity o = deal(Intent.CONTRACT_SENT);
        assertThat(invoices.money(null).readyToInvoice()).anyMatch(r -> r.opportunityId().equals(o.id)
                && r.amount().compareTo(new BigDecimal("500")) == 0);
        invoices.createForDeal(o.id, settings.today());
        assertThat(invoices.money(null).readyToInvoice()).noneMatch(r -> r.opportunityId().equals(o.id));
    }

    @Test
    void pdfOpensAndShowsTheInvoice() throws Exception {
        settings.update(Map.of(SettingsService.INVOICE_BUSINESS_NAME, "Maya Studio",
                SettingsService.INVOICE_PAYMENT_DETAILS, "IBAN XX00 1234"));
        try {
            Opportunity o = deal(Intent.INVOICE_REQUEST);
            Invoice inv = invoices.createForDeal(o.id, LocalDate.of(2030, 3, 1));
            byte[] bytes = invoices.pdf(inv.id);
            assertThat(new String(bytes, 0, 5)).isEqualTo("%PDF-");
            PdfReader reader = new PdfReader(bytes);
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            reader.close();
            assertThat(text).contains("INVOICE", inv.number, "Maya Studio", "IBAN XX00 1234", "USD 500.00",
                    "Fall launch: 1 UGC video", "March 31, 2030");
            assertThat(text).contains("Add your address in Settings"); // placeholder until she fills it in
        } finally {
            settings.update(Map.of(SettingsService.INVOICE_BUSINESS_NAME, "", SettingsService.INVOICE_PAYMENT_DETAILS, ""));
        }
    }

    @Test
    void csvExportIsSpreadsheetSafe() {
        Opportunity o = deal(Intent.INVOICE_REQUEST);
        var brand = brands.findById(o.brandId).orElseThrow();
        brand.name = "=HYPERLINK(\"http://x\")";
        brands.save(brand);
        Invoice inv = invoices.createForDeal(o.id, LocalDate.of(2029, 6, 1));
        String csv = invoices.csv(2029);
        assertThat(csv).startsWith("Invoice,Brand,Issued,Due,Status,Currency,Amount,Sent,Paid\r\n");
        assertThat(csv).contains("\"" + inv.number + "\",\"'=HYPERLINK(\"\"http://x\"\")\",2029-06-01,2029-07-01,DRAFT,USD,500.00,,");
        assertThat(invoices.csv(2028)).doesNotContain(inv.number);
    }

    @Test
    void invoiceSettingsAreValidated() {
        assertThatThrownBy(() -> settings.update(Map.of(SettingsService.INVOICE_PREFIX, "IN V"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.update(Map.of(SettingsService.INVOICE_TERMS_DAYS, "thirty"))).isInstanceOf(IllegalArgumentException.class);
    }
}

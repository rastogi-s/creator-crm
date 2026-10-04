package com.creatorcrm.invoices;

import static com.creatorcrm.FakeLlm.analysis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creatorcrm.FakeLlm;
import com.creatorcrm.channels.NormalizedMessage;
import com.creatorcrm.digest.DigestService;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.InvoiceStatus;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Invoice;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.llm.LlmException;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.InvoiceRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.FollowUpEngine;
import java.time.LocalDate;
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

/** Payment reminders: when they're due, how they're drafted, and that they always wait for approval. */
@SpringBootTest
@ActiveProfiles("test")
class PaymentRemindersIntegrationTest {

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
    @Autowired InvoiceRepo invoiceRepo;
    @Autowired PaymentReminders reminders;
    @Autowired DraftService drafts;
    @Autowired DraftRepo draftRepo;
    @Autowired DigestService digest;
    @Autowired FollowUpEngine followUps;
    @Autowired OpportunityRepo opportunities;
    @Autowired ConversationRepo conversations;
    @Autowired MessageRepo messages;
    @Autowired SettingsService settings;

    @BeforeEach
    void reset() {
        llm.next.clear();
        llm.failDraftsWith = null;
        settings.update(Map.of(SettingsService.PAYMENT_REMINDER_DAYS, "3,7,14"));
        // The database is shared with other test classes; set aside messages they left unanalyzed (see InvoiceIntegrationTest).
        for (Message m : messages.findByAiProcessedFalseAndFilteredReasonIsNullOrderBySentAtAsc()) {
            m.filteredReason = "left over from another test";
            messages.save(m);
        }
        // Only this test's invoices should be chased.
        for (Invoice i : invoiceRepo.findByStatusOrderByDueDateAsc(InvoiceStatus.SENT)) {
            i.status = InvoiceStatus.VOID;
            invoiceRepo.save(i);
        }
    }

    /** A sent invoice whose due date is {@code daysLate} days before today. */
    private Invoice lateInvoice(Intent lastEmail, int daysLate) {
        String thread = "rem" + UUID.randomUUID().toString().substring(0, 8);
        llm.next.add(analysis(lastEmail, "Brand " + thread, true, List.of()));
        ingestion.store(List.of(new NormalizedMessage(Platform.EMAIL, UUID.randomUUID().toString(), thread, Direction.INBOUND,
                "maya@" + thread + ".test", "Maya", "me@creator.test", "maya@" + thread + ".test", "Invoice",
                "Please send your invoice", "<" + UUID.randomUUID() + "@mail>", "", OffsetDateTime.now().minusHours(1), false)));
        ingestion.processPending();
        Long conv = conversations.findByPlatformAndExternalId(Platform.EMAIL, thread).orElseThrow().id;
        Opportunity o = opportunities.findFirstByConversationIdOrderByIdDesc(conv).orElseThrow();
        Invoice inv = invoices.createForDeal(o.id, settings.today().minusDays(30L + daysLate));
        return invoices.markSent(inv.id);
    }

    private List<Draft> pendingReminders(Invoice inv) {
        return draftRepo.findByOpportunityIdAndStatus(inv.opportunityId, DraftStatus.PENDING).stream()
                .filter(d -> d.type == DraftType.PAYMENT_REMINDER).toList();
    }

    @Test
    void remindersFallThreeSevenAndFourteenDaysAfterTheDueDate() {
        Invoice inv = lateInvoice(Intent.INVOICE_REQUEST, 0);
        LocalDate due = inv.dueDate;
        assertThat(reminders.dueReminder(inv, due.plusDays(2))).isEmpty();
        assertThat(reminders.dueReminder(inv, due.plusDays(3))).contains(1);

        inv.remindersSent = 1;
        inv.lastReminderOn = due.plusDays(3);
        assertThat(reminders.dueReminder(inv, due.plusDays(6))).isEmpty();
        assertThat(reminders.dueReminder(inv, due.plusDays(7))).contains(2);

        // The first reminder went out late (day 6): the second waits at least 3 days after it.
        inv.lastReminderOn = due.plusDays(6);
        assertThat(reminders.dueReminder(inv, due.plusDays(8))).isEmpty();
        assertThat(reminders.dueReminder(inv, due.plusDays(9))).contains(2);

        inv.remindersSent = 2;
        inv.lastReminderOn = due.plusDays(7);
        assertThat(reminders.dueReminder(inv, due.plusDays(14))).contains(3);
        inv.remindersSent = 3;
        assertThat(reminders.dueReminder(inv, due.plusDays(60))).as("three reminders at most").isEmpty();

        inv.remindersSent = 0;
        inv.status = InvoiceStatus.PAID;
        assertThat(reminders.dueReminder(inv, due.plusDays(30))).as("paid invoices aren't chased").isEmpty();
    }

    @Test
    void dueReminderIsDraftedOnceWithTheInvoiceAndSendingItCountsIt() {
        Invoice inv = lateInvoice(Intent.INVOICE_REQUEST, 4);
        LocalDate today = settings.today();

        assertThat(reminders.draftDue(today)).isEqualTo(1);
        assertThat(reminders.draftDue(today)).as("no second draft while one is waiting").isZero();
        List<Draft> pending = pendingReminders(inv);
        assertThat(pending).hasSize(1);
        Draft d = pending.get(0);
        assertThat(d.invoiceId).isEqualTo(inv.id);
        assertThat(d.channel).isEqualTo(Platform.EMAIL);
        assertThat(llm.lastDraftInput.draftType()).isEqualTo("PAYMENT_REMINDER");
        assertThat(llm.lastDraftInput.extraInstructions()).contains("Payment reminder 1 of 3", inv.number, "4 days late", "friendly");
        assertThat(digest.morning().urgent()).anyMatch(i -> i.kind().equals("INVOICE") && i.refId().equals(inv.id)
                && i.title().endsWith("payment 4 days late") && i.detail().contains("reminder ready in Drafts"));

        // Never sent automatically, even with automatic follow-ups on
        assertThatThrownBy(() -> drafts.sendFollowUpAutomatically(d.id)).isInstanceOf(IllegalStateException.class);

        drafts.markSentManually(d.id);
        Invoice after = invoiceRepo.findById(inv.id).orElseThrow();
        assertThat(after.remindersSent).isEqualTo(1);
        assertThat(after.lastReminderOn).isEqualTo(today);
        assertThat(after.status).isEqualTo(InvoiceStatus.SENT);
        assertThat(followUps.scheduled(inv.opportunityId)).as("deal follow-ups don't restart").isEmpty();
        assertThat(reminders.draftDue(today)).as("next one isn't due yet").isZero();

        // Paid: nothing more is drafted, even on the day the next reminder would be due
        invoices.markPaid(inv.id, today);
        assertThat(reminders.draftDue(today.plusDays(30))).isZero();
    }

    @Test
    void remindersPauseWhileSheChecksAPaymentTheBrandSaysItSent() {
        Invoice inv = lateInvoice(Intent.PAYMENT_UPDATE, 10);
        assertThat(reminders.dueReminder(inv, settings.today())).contains(1);
        assertThat(reminders.draftDue(settings.today())).isZero();
        assertThat(pendingReminders(inv)).isEmpty();
    }

    @Test
    void finalReminderIsFirmAndHasAStandardNoteWhenClaudeIsUnavailable() {
        Invoice inv = lateInvoice(Intent.INVOICE_REQUEST, 20);
        inv.remindersSent = 2;
        inv.lastReminderOn = settings.today().minusDays(6);
        invoiceRepo.save(inv);
        llm.failDraftsWith = new LlmException("Claude is down");
        Draft d = reminders.draftNow(inv.id);
        assertThat(llm.lastDraftInput.extraInstructions()).contains("Payment reminder 3 of 3", "final reminder");
        assertThat(d.body).contains(inv.number, "20 days past its due date", "accounts team");
        assertThat(d.subject).contains(inv.number);
    }

    @Test
    void blankSettingTurnsRemindersOffAndOnlySentInvoicesCanBeChased() {
        Invoice inv = lateInvoice(Intent.INVOICE_REQUEST, 30);
        settings.update(Map.of(SettingsService.PAYMENT_REMINDER_DAYS, ""));
        assertThat(settings.paymentReminderDays()).isEmpty();
        assertThat(reminders.dueReminder(inv, settings.today())).isEqualTo(Optional.empty());
        assertThatThrownBy(() -> settings.update(Map.of(SettingsService.PAYMENT_REMINDER_DAYS, "3, soon")))
                .isInstanceOf(IllegalArgumentException.class);

        invoices.voidInvoice(inv.id);
        assertThatThrownBy(() -> reminders.draftNow(inv.id)).isInstanceOf(IllegalStateException.class);
    }
}

package com.creatorcrm.invoices;

import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.InvoiceStatus;
import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.Invoice;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.llm.DraftText;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.InvoiceRepo;
import com.creatorcrm.repo.TaskRepo;
import com.creatorcrm.settings.SettingsService;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Drafts polite payment reminders for unpaid invoices, a set number of days after the due date (Settings,
 * Invoices; 3, 7 and 14 by default). Each one waits in Drafts for the creator's approval, even when automatic
 * follow-ups are on: chasing money is sensitive enough to keep a person in the loop. Reminders stop once the
 * invoice is paid or voided, and pause while she's checking a payment the brand says it made.
 */
@Service
public class PaymentReminders {
    private static final Logger log = LoggerFactory.getLogger(PaymentReminders.class);
    /** Never two reminders closer together than this, even if the previous one was sent late. */
    static final int MIN_DAYS_BETWEEN = 3;

    private final InvoiceRepo invoices;
    private final DraftService drafts;
    private final DraftRepo draftRepo;
    private final TaskRepo tasks;
    private final BrandRepo brands;
    private final SettingsService settings;

    public PaymentReminders(InvoiceRepo invoices, DraftService drafts, DraftRepo draftRepo, TaskRepo tasks,
                            BrandRepo brands, SettingsService settings) {
        this.invoices = invoices;
        this.drafts = drafts;
        this.draftRepo = draftRepo;
        this.tasks = tasks;
        this.brands = brands;
        this.settings = settings;
    }

    /** The reminder number (1-based) due on this day, if one is. */
    public Optional<Integer> dueReminder(Invoice inv, LocalDate today) {
        if (inv.status != InvoiceStatus.SENT || inv.dueDate == null) return Optional.empty();
        List<Integer> days = settings.paymentReminderDays();
        if (inv.remindersSent >= days.size()) return Optional.empty();
        LocalDate at = inv.dueDate.plusDays(days.get(inv.remindersSent));
        if (inv.lastReminderOn != null && inv.lastReminderOn.plusDays(MIN_DAYS_BETWEEN).isAfter(at)) {
            at = inv.lastReminderOn.plusDays(MIN_DAYS_BETWEEN);
        }
        return today.isBefore(at) ? Optional.empty() : Optional.of(inv.remindersSent + 1);
    }

    /** Daily run: a reminder draft for every invoice that has one due. Returns how many were drafted. */
    public int draftDue(LocalDate today) {
        int n = 0;
        for (Invoice inv : invoices.findByStatusOrderByDueDateAsc(InvoiceStatus.SENT)) {
            Optional<Integer> number = dueReminder(inv, today);
            if (number.isEmpty() || hasPendingReminder(inv) || checkingPayment(inv)) continue;
            try {
                draft(inv, number.get(), today);
                n++;
            } catch (RuntimeException e) {
                log.warn("Could not draft a payment reminder for {}: {}", inv.number, e.getMessage());
            }
        }
        return n;
    }

    /** "Write a reminder" on an unpaid invoice: the next reminder now, whatever the schedule says. */
    public Draft draftNow(Long invoiceId) {
        Invoice inv = invoices.findById(invoiceId).orElseThrow(() -> new IllegalArgumentException("Unknown invoice"));
        if (inv.status != InvoiceStatus.SENT) {
            throw new IllegalStateException("Only sent, unpaid invoices get payment reminders");
        }
        return draft(inv, inv.remindersSent + 1, settings.today());
    }

    private Draft draft(Invoice inv, int number, LocalDate today) {
        Brand b = brands.findById(inv.brandId).orElseThrow();
        int total = Math.max(number, settings.paymentReminderDays().size());
        long late = Math.max(0, ChronoUnit.DAYS.between(inv.dueDate, today));
        String amount = InvoicePdf.money(inv.currency, inv.amount);
        String due = InvoicePdf.date(inv.dueDate);
        String tone = number == 1
                ? "Keep it light and friendly: it may simply have slipped through. Ask them to confirm it's on its way."
                : number < total
                        ? "Stay polite but be clearer than last time: ask when you can expect the payment."
                        : "This is the final reminder: courteous and firm. Ask for payment or a firm date this week, and "
                                + "who in their accounts team to contact. No threats or fees.";
        String instructions = "Payment reminder " + number + " of " + total + " for invoice " + inv.number + " (" + amount
                + "), which was due " + due + (late > 0 ? " and is " + late + (late == 1 ? " day" : " days") + " late" : "")
                + ". The invoice PDF is attached again. " + tone;
        String who = b.contactName == null || b.contactName.isBlank() ? "there" : b.contactName.strip();
        String body = number == 1
                ? "Hi " + who + ",\n\nJust a friendly reminder that invoice " + inv.number + " for " + amount + " was due on "
                        + due + ". I've attached it again in case it got lost. Could you let me know when it's on its way?"
                : number < total
                        ? "Hi " + who + ",\n\nFollowing up on invoice " + inv.number + " for " + amount + ", which was due on "
                                + due + ". Could you let me know when I can expect the payment? The invoice is attached."
                        : "Hi " + who + ",\n\nInvoice " + inv.number + " for " + amount + " is now " + late + " days past its due "
                                + "date of " + due + ". Could you arrange payment this week, or let me know who in your "
                                + "accounts team I should contact? The invoice is attached.";
        DraftText fallback = new DraftText("Payment reminder: invoice " + inv.number,
                body + "\n\nThank you!\n\n" + settings.creatorName());
        return drafts.generateInvoiceEmail(inv, DraftType.PAYMENT_REMINDER, instructions, fallback);
    }

    private boolean hasPendingReminder(Invoice inv) {
        return draftRepo.findByOpportunityIdAndStatus(inv.opportunityId, DraftStatus.PENDING).stream()
                .anyMatch(d -> d.type == DraftType.PAYMENT_REMINDER && inv.id.equals(d.invoiceId));
    }

    /** The brand said it paid and she hasn't confirmed yet: don't chase in the meantime. */
    private boolean checkingPayment(Invoice inv) {
        return tasks.findByOpportunityIdAndStatus(inv.opportunityId, TaskStatus.OPEN).stream()
                .anyMatch(t -> t.type == TaskType.CONFIRM_PAYMENT);
    }
}

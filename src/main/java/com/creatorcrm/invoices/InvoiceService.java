package com.creatorcrm.invoices;

import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.InvoiceStatus;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.Enums.TaskType;
import com.creatorcrm.domain.Invoice;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.llm.DraftText;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.InvoiceRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.WorkflowEngine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoices for paid deals: create from a deal, edit while still a draft, email with the PDF attached (through the
 * normal approval queue), and record payment. Nothing here marks an invoice paid by itself: only the creator's
 * click does.
 */
@Service
public class InvoiceService {

    /** Deal statuses where the brand has said yes, so the fee counts as booked. */
    static final Set<OpportunityStatus> BOOKED = EnumSet.of(
            OpportunityStatus.CONTRACT_PENDING, OpportunityStatus.CONTRACT_TO_SIGN, OpportunityStatus.PRODUCT_PENDING,
            OpportunityStatus.PRODUCT_RECEIVED, OpportunityStatus.CONTENT_TO_CREATE, OpportunityStatus.AWAITING_APPROVAL,
            OpportunityStatus.SCHEDULED_TO_POST, OpportunityStatus.POSTED, OpportunityStatus.PAYMENT_PENDING);

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");

    public record InvoiceEdit(String billTo, String billToEmail, String currency, List<LineItem> lineItems,
                              String notes, LocalDate issuedDate, LocalDate dueDate) {}

    public record InvoiceView(Long id, String number, Long opportunityId, String brand, String billTo,
                              String billToEmail, String currency, BigDecimal amount, String amountText,
                              List<LineItem> lineItems, String notes, LocalDate issuedDate, LocalDate dueDate,
                              OffsetDateTime sentAt, LocalDate paidDate, String status, long daysOverdue,
                              int remindersSent, LocalDate lastReminderOn) {}

    /** A deal the brand agreed to pay for that has no invoice yet. */
    public record ReadyToInvoice(Long opportunityId, String brand, String campaign, String currency, BigDecimal amount,
                                 String amountText, String status) {}

    public record MonthGroup(String month, List<InvoiceView> invoices) {}

    /** Totals are per currency, e.g. {"USD": 1200.00}. */
    public record Money(LocalDate today, Map<String, BigDecimal> booked, Map<String, BigDecimal> outstanding,
                        Map<String, BigDecimal> paidThisMonth, Map<String, BigDecimal> overdue,
                        Map<String, BigDecimal> paidThisYear, List<ReadyToInvoice> readyToInvoice,
                        List<MonthGroup> months, List<Integer> years, int year, boolean businessDetailsMissing) {}

    private final InvoiceRepo invoices;
    private final OpportunityRepo opportunities;
    private final BrandRepo brands;
    private final ConversationRepo conversations;
    private final ActivityRepo activity;
    private final WorkflowEngine workflow;
    private final DraftService drafts;
    private final InvoicePdf pdf;
    private final SettingsService settings;

    public InvoiceService(InvoiceRepo invoices, OpportunityRepo opportunities, BrandRepo brands,
                          ConversationRepo conversations, ActivityRepo activity, WorkflowEngine workflow,
                          DraftService drafts, InvoicePdf pdf, SettingsService settings) {
        this.invoices = invoices;
        this.opportunities = opportunities;
        this.brands = brands;
        this.conversations = conversations;
        this.activity = activity;
        this.workflow = workflow;
        this.drafts = drafts;
        this.pdf = pdf;
        this.settings = settings;
    }

    // ---------------------------------------------------------------- lifecycle

    /** A new draft invoice for the deal, filled in from it. Returns the deal's existing draft invoice if it has one. */
    @Transactional
    public synchronized Invoice createForDeal(Long opportunityId, LocalDate issued) {
        Opportunity o = opportunities.findById(opportunityId).orElseThrow(() -> new IllegalArgumentException("Unknown deal"));
        var existing = invoices.findFirstByOpportunityIdAndStatusOrderByIdDesc(o.id, InvoiceStatus.DRAFT);
        if (existing.isPresent()) return existing.get();
        Brand b = brands.findById(o.brandId).orElseThrow();

        Invoice inv = new Invoice();
        inv.opportunityId = o.id;
        inv.brandId = b.id;
        inv.billTo = b.name + (b.contactName == null || b.contactName.isBlank() ? "" : "\nAttn: " + b.contactName);
        inv.billToEmail = billingEmail(o, b);
        inv.currency = o.currency == null || !o.currency.strip().matches("[A-Za-z]{3}") ? "USD" : o.currency.strip().toUpperCase();
        BigDecimal amount = o.budgetAmount == null ? BigDecimal.ZERO : o.budgetAmount.setScale(2, RoundingMode.HALF_UP);
        inv.lineItems = LineItem.toJson(List.of(new LineItem(lineDescription(o), amount)));
        inv.amount = amount;
        inv.issuedDate = issued;
        inv.dueDate = issued.plusDays(settings.invoiceTermsDays());
        inv.status = InvoiceStatus.DRAFT;
        inv.createdAt = OffsetDateTime.now();
        number(inv);
        inv = invoices.save(inv);
        activity.save(Activity.of(o.id, Activity.INVOICE, "Invoice " + inv.number + " created for " + b.name));
        return inv;
    }

    /** Only draft invoices can change; a sent invoice is what the brand has. Void it and make a new one instead. */
    @Transactional
    public synchronized Invoice update(Long id, InvoiceEdit e) {
        Invoice inv = get(id);
        if (inv.status != InvoiceStatus.DRAFT) throw new IllegalStateException("Invoice " + inv.number + " was already sent, so it can't change");
        if (e.billTo() != null) inv.billTo = limit(e.billTo(), 1000, "Bill to");
        if (e.billToEmail() != null) {
            String email = e.billToEmail().strip();
            if (!email.isEmpty() && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new IllegalArgumentException("That email address doesn't look right");
            inv.billToEmail = email.length() > 320 ? email.substring(0, 320) : email;
        }
        if (e.currency() != null) {
            if (!e.currency().strip().matches("[A-Za-z]{3}")) throw new IllegalArgumentException("Currency must be a 3-letter code like USD");
            inv.currency = e.currency().strip().toUpperCase();
        }
        if (e.lineItems() != null) {
            List<LineItem> items = new ArrayList<>();
            for (LineItem li : e.lineItems()) {
                String desc = li.description() == null ? "" : li.description().strip();
                if (desc.isEmpty() && (li.amount() == null || li.amount().signum() == 0)) continue;
                if (desc.length() > 300) throw new IllegalArgumentException("A line description is too long (300 characters max)");
                BigDecimal amt = li.amount() == null ? BigDecimal.ZERO : li.amount().setScale(2, RoundingMode.HALF_UP);
                if (amt.signum() < 0 || amt.compareTo(MAX_AMOUNT) >= 0) throw new IllegalArgumentException("Amounts must be between 0 and 10,000,000");
                items.add(new LineItem(desc, amt));
            }
            if (items.isEmpty()) throw new IllegalArgumentException("Add at least one line");
            if (items.size() > 20) throw new IllegalArgumentException("At most 20 lines per invoice");
            inv.lineItems = LineItem.toJson(items);
            inv.amount = items.stream().map(LineItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        if (e.notes() != null) inv.notes = limit(e.notes(), 2000, "Notes");
        if (e.issuedDate() != null && !e.issuedDate().equals(inv.issuedDate)) {
            boolean newYear = e.issuedDate().getYear() != inv.issuedDate.getYear();
            inv.issuedDate = e.issuedDate();
            if (newYear) number(inv);
        }
        if (e.dueDate() != null) inv.dueDate = e.dueDate();
        if (inv.dueDate.isBefore(inv.issuedDate)) throw new IllegalArgumentException("The due date is before the invoice date");
        return invoices.save(inv);
    }

    /** Puts an email with the PDF attached into Drafts. It is sent only when the creator approves it there. */
    @Transactional
    public Draft emailDraft(Long id) {
        Invoice inv = get(id);
        if (inv.status == InvoiceStatus.VOID || inv.status == InvoiceStatus.PAID) {
            throw new IllegalStateException("Invoice " + inv.number + " is " + inv.status.name().toLowerCase());
        }
        if (inv.amount.signum() == 0) throw new IllegalStateException("The invoice total is 0. Add the amount first.");
        Brand b = brands.findById(inv.brandId).orElseThrow();
        String amount = InvoicePdf.money(inv.currency, inv.amount);
        String instructions = "Send invoice " + inv.number + " for " + amount + ", due " + InvoicePdf.date(inv.dueDate)
                + ". The invoice PDF is attached to this email.";
        String who = b.contactName == null || b.contactName.isBlank() ? "there" : b.contactName.strip();
        DraftText fallback = new DraftText("Invoice " + inv.number,
                "Hi " + who + ",\n\nPlease find attached invoice " + inv.number + " for " + amount + ", due "
                        + InvoicePdf.date(inv.dueDate) + ".\n\nThank you, it was a pleasure working with you!\n\n"
                        + settings.creatorName());
        return drafts.generateInvoiceEmail(inv, DraftType.INVOICE, instructions, fallback);
    }

    /** She sent the invoice outside the app (e.g. downloaded the PDF and emailed it herself). */
    @Transactional
    public Invoice markSent(Long id) {
        Invoice inv = get(id);
        if (inv.status != InvoiceStatus.DRAFT) return inv;
        inv.status = InvoiceStatus.SENT;
        inv.sentAt = OffsetDateTime.now();
        invoices.save(inv);
        Opportunity o = opportunities.findById(inv.opportunityId).orElseThrow();
        workflow.onCreatorMessage(o, Intent.INVOICE_SENT, settings.today());
        activity.save(Activity.of(o.id, Activity.INVOICE, "Invoice " + inv.number + " marked as sent"));
        return inv;
    }

    /** Payment arrived. When nothing else is unpaid on the deal, the deal is done and closes as paid. */
    @Transactional
    public Invoice markPaid(Long id, LocalDate paidOn) {
        Invoice inv = get(id);
        if (inv.status == InvoiceStatus.VOID) throw new IllegalStateException("Invoice " + inv.number + " was voided");
        if (inv.status == InvoiceStatus.PAID) return inv;
        if (inv.sentAt == null) inv.sentAt = OffsetDateTime.now();
        inv.status = InvoiceStatus.PAID;
        inv.paidDate = paidOn;
        invoices.save(inv);
        Opportunity o = opportunities.findById(inv.opportunityId).orElseThrow();
        String brand = workflow.brandName(o);
        activity.save(Activity.of(o.id, Activity.INVOICE, brand + " paid " + InvoicePdf.money(inv.currency, inv.amount)
                + " (" + inv.number + ")"));
        workflow.completeOpenTasks(o.id, Set.of(TaskType.CONFIRM_PAYMENT, TaskType.CHASE_PAYMENT, TaskType.SEND_INVOICE));
        boolean allPaid = invoices.findByOpportunityIdOrderByIdAsc(o.id).stream()
                .noneMatch(i -> i.status == InvoiceStatus.SENT || i.status == InvoiceStatus.DRAFT);
        if (allPaid && o.status == OpportunityStatus.PAYMENT_PENDING) {
            o.closedReason = "Paid";
            workflow.setStatus(o, OpportunityStatus.CLOSED);
            opportunities.save(o);
        }
        return inv;
    }

    /** Cancels an invoice. Its number is kept (never reused), so the numbering has no gaps a tax office would question. */
    @Transactional
    public Invoice voidInvoice(Long id) {
        Invoice inv = get(id);
        if (inv.status == InvoiceStatus.PAID) throw new IllegalStateException("Invoice " + inv.number + " is already paid");
        inv.status = InvoiceStatus.VOID;
        activity.save(Activity.of(inv.opportunityId, Activity.INVOICE, "Invoice " + inv.number + " voided"));
        return invoices.save(inv);
    }

    // ---------------------------------------------------------------- reading

    public Invoice get(Long id) {
        return invoices.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown invoice"));
    }

    public byte[] pdf(Long id) {
        return pdf.render(get(id));
    }

    public InvoiceView view(Invoice i) {
        String brand = brands.findById(i.brandId).map(b -> b.name).orElse("");
        LocalDate today = settings.today();
        return new InvoiceView(i.id, i.number, i.opportunityId, brand, i.billTo, i.billToEmail, i.currency, i.amount,
                InvoicePdf.money(i.currency, i.amount), LineItem.parse(i.lineItems), i.notes, i.issuedDate, i.dueDate,
                i.sentAt, i.paidDate, i.status.name(),
                i.isOverdue(today) ? ChronoUnit.DAYS.between(i.dueDate, today) : 0, i.remindersSent, i.lastReminderOn);
    }

    public List<InvoiceView> forDeal(Long opportunityId) {
        return invoices.findByOpportunityIdOrderByIdAsc(opportunityId).stream().map(this::view).toList();
    }

    /** Sent and not yet paid, soonest due first. */
    public List<InvoiceView> unpaid() {
        return invoices.findByStatusOrderByDueDateAsc(InvoiceStatus.SENT).stream().map(this::view).toList();
    }

    public List<Invoice> overdue(LocalDate today) {
        return invoices.findByStatusOrderByDueDateAsc(InvoiceStatus.SENT).stream().filter(i -> i.isOverdue(today)).toList();
    }

    public Money money(Integer year) {
        LocalDate today = settings.today();
        int y = year == null ? today.getYear() : year;
        List<Invoice> all = invoices.findAllByOrderByIssuedDateDescIdDesc();
        Map<String, BigDecimal> outstanding = new TreeMap<>(), overdue = new TreeMap<>(), paidMonth = new TreeMap<>(),
                paidYear = new TreeMap<>(), booked = new TreeMap<>();
        YearMonth thisMonth = YearMonth.from(today);
        for (Invoice i : all) {
            if (i.status == InvoiceStatus.SENT) {
                outstanding.merge(i.currency, i.amount, BigDecimal::add);
                if (i.isOverdue(today)) overdue.merge(i.currency, i.amount, BigDecimal::add);
            } else if (i.status == InvoiceStatus.PAID && i.paidDate != null) {
                if (YearMonth.from(i.paidDate).equals(thisMonth)) paidMonth.merge(i.currency, i.amount, BigDecimal::add);
                if (i.paidDate.getYear() == y) paidYear.merge(i.currency, i.amount, BigDecimal::add);
            }
        }
        Set<Long> invoiced = new java.util.HashSet<>();
        all.stream().filter(i -> i.status != InvoiceStatus.VOID).forEach(i -> invoiced.add(i.opportunityId));
        List<ReadyToInvoice> ready = new ArrayList<>();
        for (Opportunity o : opportunities.findAll()) {
            if (!BOOKED.contains(o.status) || o.compensation != Compensation.PAID || invoiced.contains(o.id)) continue;
            String currency = o.currency == null || o.currency.isBlank() ? "USD" : o.currency.strip().toUpperCase();
            BigDecimal amount = o.budgetAmount == null ? BigDecimal.ZERO : o.budgetAmount;
            booked.merge(currency, amount, BigDecimal::add);
            ready.add(new ReadyToInvoice(o.id, workflow.brandName(o), o.campaign, currency, amount,
                    amount.signum() == 0 ? "Amount not set" : InvoicePdf.money(currency, amount), o.status.label));
        }
        Map<String, List<InvoiceView>> byMonth = new TreeMap<>(java.util.Comparator.reverseOrder());
        for (Invoice i : all) {
            if (i.issuedDate.getYear() != y) continue;
            byMonth.computeIfAbsent(YearMonth.from(i.issuedDate).toString(), k -> new ArrayList<>()).add(view(i));
        }
        List<MonthGroup> months = byMonth.entrySet().stream().map(e -> new MonthGroup(e.getKey(), e.getValue())).toList();
        java.util.TreeSet<Integer> years = new java.util.TreeSet<>(java.util.Comparator.reverseOrder());
        years.add(today.getYear());
        all.forEach(i -> years.add(i.issuedDate.getYear()));
        boolean missing = settings.invoiceAddress().isBlank() || settings.invoicePaymentDetails().isBlank();
        return new Money(today, booked, outstanding, paidMonth, overdue, paidYear, ready, months, List.copyOf(years), y, missing);
    }

    /** Every invoice issued in the year, for taxes. Spreadsheet-safe: cells can't start a formula. */
    public String csv(int year) {
        StringBuilder sb = new StringBuilder("Invoice,Brand,Issued,Due,Status,Currency,Amount,Sent,Paid\r\n");
        List<Invoice> rows = new ArrayList<>(invoices.findAllByOrderByIssuedDateDescIdDesc());
        java.util.Collections.reverse(rows);
        for (Invoice i : rows) {
            if (i.issuedDate.getYear() != year) continue;
            String brand = brands.findById(i.brandId).map(b -> b.name).orElse("");
            sb.append(String.join(",", cell(i.number), cell(brand), i.issuedDate.toString(), i.dueDate.toString(),
                    i.status.name(), i.currency, i.amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    i.sentAt == null ? "" : i.sentAt.toLocalDate().toString(),
                    i.paidDate == null ? "" : i.paidDate.toString())).append("\r\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- helpers

    /** Next number in the invoice's year: INV-2026-001, INV-2026-002, ... restarting at 001 each year. */
    private void number(Invoice inv) {
        int year = inv.issuedDate.getYear();
        int seq = invoices.maxSeq(year) + 1; // query first: it may flush this invoice, which must not hold the new year yet
        inv.invoiceYear = year;
        inv.seq = seq;
        inv.number = settings.invoicePrefix() + "-" + year + "-" + String.format("%03d", inv.seq);
    }

    private String billingEmail(Opportunity o, Brand b) {
        if (b.contactEmail != null && !b.contactEmail.isBlank()) return b.contactEmail.strip();
        if (o.conversationId == null) return null;
        Conversation c = conversations.findById(o.conversationId).orElse(null);
        return c != null && c.platform == Platform.EMAIL && c.counterparty != null && c.counterparty.contains("@")
                ? c.counterparty.strip() : null;
    }

    private static String lineDescription(Opportunity o) {
        String campaign = o.campaign == null ? "" : o.campaign.strip();
        String deliverables = o.deliverables == null ? "" : o.deliverables.strip();
        String d = campaign.isEmpty() ? deliverables : deliverables.isEmpty() ? campaign : campaign + ": " + deliverables;
        d = d.isEmpty() ? "Content collaboration" : d;
        return d.length() > 300 ? d.substring(0, 300) : d;
    }

    private static String limit(String v, int max, String what) {
        String s = v.strip();
        if (s.length() > max) throw new IllegalArgumentException(what + " is too long (" + max + " characters max)");
        return s;
    }

    private static String cell(String v) {
        String s = v == null ? "" : v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}

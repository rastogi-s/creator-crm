package com.creatorcrm.progress;

import com.creatorcrm.domain.Deadline;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DealStage;
import com.creatorcrm.domain.Enums.DeadlineType;
import com.creatorcrm.domain.Enums.InvoiceStatus;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Invoice;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.StageEntry;
import com.creatorcrm.invoices.InvoicePdf;
import com.creatorcrm.invoices.InvoiceService;
import com.creatorcrm.repo.DeadlineRepo;
import com.creatorcrm.repo.InvoiceRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.StageEntryRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.WorkflowEngine;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The progress diagram on a deal the brand said yes to: its steps in order, which are done, which one it's at and
 * what that asks of her. The stage itself is kept up to date by the workflow engine, mostly from emails.
 */
@Service
public class DealProgress {

    /** One step of the diagram. state: done, now or todo; label and date describe it, e.g. "Signed" 2026-10-02. */
    public record Step(String key, String name, String state, String label, LocalDate date) {}

    /**
     * shown: false for leads. state: active, finished or paused (gone cold or closed before the end). next: the
     * stage the button moves the deal to, with action as its label; null when there's no button.
     */
    public record View(boolean shown, String state, List<Step> steps, int step, String headline, String detail,
                       String action, String next) {
        static View none() { return new View(false, null, List.of(), 0, null, null, null, null); }
    }

    static final String AGREED = "AGREED";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US);

    private final OpportunityRepo opportunities;
    private final StageEntryRepo stages;
    private final DeadlineRepo deadlines;
    private final InvoiceRepo invoices;
    private final InvoiceService invoiceService;
    private final WorkflowEngine workflow;
    private final SettingsService settings;

    public DealProgress(OpportunityRepo opportunities, StageEntryRepo stages, DeadlineRepo deadlines, InvoiceRepo invoices,
                        InvoiceService invoiceService, WorkflowEngine workflow, SettingsService settings) {
        this.opportunities = opportunities;
        this.stages = stages;
        this.deadlines = deadlines;
        this.invoices = invoices;
        this.invoiceService = invoiceService;
        this.workflow = workflow;
        this.settings = settings;
    }

    /** The stages this deal goes through, in order: contract and product only where they apply, payment for paid deals. */
    static List<DealStage> stagesOf(Opportunity o, Set<DealStage> reached) {
        List<DealStage> out = new ArrayList<>();
        boolean pays = WorkflowEngine.paysAfter(o) || o.stage == DealStage.INVOICE || o.stage == DealStage.PAYMENT;
        if (pays || reached.contains(DealStage.CONTRACT)) out.add(DealStage.CONTRACT);
        if (o.compensation == Compensation.GIFTED || o.type == OpportunityType.GIFTED || reached.contains(DealStage.PRODUCT)) {
            out.add(DealStage.PRODUCT);
        }
        out.addAll(List.of(DealStage.CREATE_CONTENT, DealStage.BRAND_APPROVAL, DealStage.POST));
        if (pays) out.addAll(List.of(DealStage.INVOICE, DealStage.PAYMENT));
        else out.add(DealStage.DONE);
        return out;
    }

    public View view(Long opportunityId) {
        Opportunity o = opportunities.findById(opportunityId).orElseThrow();
        if (o.stage == null) return View.none();
        List<StageEntry> log = stages.findByOpportunityIdOrderByReachedAtAscIdAsc(o.id);
        Set<DealStage> reached = EnumSet.of(o.stage);
        log.forEach(e -> reached.add(e.stage));
        List<DealStage> order = stagesOf(o, reached);
        List<Deadline> open = deadlines.findByOpportunityIdOrderByDueDateAsc(o.id).stream().filter(d -> !d.done).toList();
        List<Invoice> invs = invoices.findByOpportunityIdOrderByIdAsc(o.id);
        LocalDate today = settings.today();

        boolean finished = o.stage == DealStage.DONE;
        int at = finished ? order.size() : Math.max(0, order.indexOf(o.stage));
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(AGREED, "Agreed", "done", null, log.isEmpty() ? null : day(log.getFirst().reachedAt)));
        for (int i = 0; i < order.size(); i++) {
            DealStage s = order.get(i);
            String state = i < at ? "done" : i == at ? "now" : "todo";
            if (state.equals("done")) {
                steps.add(new Step(s.name(), s.label, state, doneLabel(s), finishedOn(log, s, order)));
            } else {
                Optional<LocalDate> due = dueFor(s, open, invs);
                LocalDate since = state.equals("now") ? lastReached(log, s) : null;
                steps.add(due.isPresent() ? new Step(s.name(), s.label, state, dueLabel(s), due.get())
                        : new Step(s.name(), s.label, state, since == null ? null : "Since", since));
            }
        }
        int step = Math.min(at + 2, steps.size()); // 1-based, counting Agreed

        String brand = workflow.brandName(o);
        boolean paused = !o.status.isOpen() && !finished || o.status == OpportunityStatus.FOLLOW_UP_NEEDED;
        if (finished) {
            return new View(true, "finished", steps, steps.size(),
                    WorkflowEngine.paysAfter(o) ? "Done: paid and wrapped up" : "Done: collab wrapped up",
                    "Nothing left to do on this deal.", null, null);
        }
        if (paused) {
            String why = o.status == OpportunityStatus.COLD ? brand + " stopped replying."
                    : o.status == OpportunityStatus.FOLLOW_UP_NEEDED ? "Follow up with " + brand + " to get it moving again."
                    : o.closedReason == null || o.closedReason.isBlank() ? "The deal was closed." : o.closedReason + ".";
            return new View(true, "paused", steps, step, "Stopped at " + o.stage.label.toLowerCase(Locale.ROOT), why, null, null);
        }
        DealStage next = at + 1 < order.size() ? order.get(at + 1) : DealStage.DONE;
        String[] now = now(o, brand, open, invs, today);
        return new View(true, "active", steps, step, now[0], now[1], now[2], next.name());
    }

    /** Headline, detail and button label for the stage the deal is at. */
    private String[] now(Opportunity o, String brand, List<Deadline> open, List<Invoice> invs, LocalDate today) {
        String what = o.deliverables == null || o.deliverables.isBlank() ? "the content" : o.deliverables;
        Optional<LocalDate> due = dueFor(o.stage, open, invs);
        String by = due.map(d -> " " + dueLabel(o.stage) + " " + d.format(DAY) + when(d, today) + ".").orElse("");
        return switch (o.stage) {
            case CONTRACT -> o.status == OpportunityStatus.CONTRACT_TO_SIGN
                    ? new String[] {"Now: sign the contract", brand + " sent the contract. Check it below before you sign." + by, "I signed it"}
                    : new String[] {"Now: wait for the contract", brand + " said yes and is sending the contract." + by, "I signed it"};
            case PRODUCT -> o.status == OpportunityStatus.PRODUCT_RECEIVED
                    ? new String[] {"Now: the product arrived", "Let " + brand + " know it arrived, then start creating.", "Start creating"}
                    : new String[] {"Now: wait for the product", brand + " is sending the product.", "It arrived"};
            case CREATE_CONTENT -> new String[] {"Now: create the content", "Make " + what + " for " + brand + "." + by, "I sent it for approval"};
            case BRAND_APPROVAL -> new String[] {"Now: wait for " + brand + " to approve", "You sent " + what + " for their OK." + by, "They approved it"};
            case POST -> new String[] {"Now: post it", brand + " approved it. Post " + what + "." + by, "I posted it"};
            case INVOICE -> new String[] {"Now: send your invoice", "It's posted. Create the invoice below and send it to " + brand + ".", "I sent the invoice"};
            case PAYMENT -> new String[] {"Now: wait for payment", paymentDetail(brand, invs, today), "I got paid"};
            case DONE -> new String[] {"Done", "", null};
        };
    }

    private static String paymentDetail(String brand, List<Invoice> invs, LocalDate today) {
        Optional<Invoice> sent = invs.stream().filter(i -> i.status == InvoiceStatus.SENT).findFirst();
        if (sent.isEmpty()) return "You sent " + brand + " the invoice. Mark it paid when the money arrives.";
        Invoice i = sent.get();
        String due = i.dueDate == null ? "" : ", due " + i.dueDate.format(DAY) + when(i.dueDate, today);
        return "Invoice " + i.number + " for " + InvoicePdf.money(i.currency, i.amount) + due + ".";
    }

    private static String when(LocalDate d, LocalDate today) {
        long days = d.toEpochDay() - today.toEpochDay();
        return days == 0 ? " (today)" : days == 1 ? " (tomorrow)" : days > 1 ? " (" + days + " days)"
                : " (" + -days + (days == -1 ? " day" : " days") + " late)";
    }

    /** The deadline the stage works towards, if an email gave one. */
    private static Optional<LocalDate> dueFor(DealStage s, List<Deadline> open, List<Invoice> invs) {
        if (s == DealStage.PAYMENT) {
            Optional<LocalDate> inv = invs.stream().filter(i -> i.status == InvoiceStatus.SENT && i.dueDate != null)
                    .map(i -> i.dueDate).min(LocalDate::compareTo);
            if (inv.isPresent()) return inv;
        }
        DeadlineType type = switch (s) {
            case CONTRACT -> DeadlineType.CONTRACT;
            case CREATE_CONTENT -> DeadlineType.CONTENT_DUE;
            case BRAND_APPROVAL -> DeadlineType.APPROVAL;
            case POST -> DeadlineType.POSTING;
            case PAYMENT -> DeadlineType.PAYMENT;
            default -> null;
        };
        return open.stream().filter(d -> d.type == type).map(d -> d.dueDate).findFirst();
    }

    private static String dueLabel(DealStage s) {
        return switch (s) {
            case CONTRACT -> "Sign by";
            case POST -> "Post on";
            default -> "Due";
        };
    }

    private static String doneLabel(DealStage s) {
        return switch (s) {
            case CONTRACT -> "Signed";
            case PRODUCT -> "Arrived";
            case CREATE_CONTENT, INVOICE -> "Sent";
            case BRAND_APPROVAL -> "Approved";
            case POST -> "Posted";
            case PAYMENT -> "Paid";
            case DONE -> null;
        };
    }

    private static LocalDate lastReached(List<StageEntry> log, DealStage s) {
        LocalDate d = null;
        for (StageEntry e : log) if (e.stage == s) d = day(e.reachedAt);
        return d;
    }

    /** When the deal first got past this stage: the first later stage reached after it last reached this one. */
    private static LocalDate finishedOn(List<StageEntry> log, DealStage s, List<DealStage> order) {
        int from = 0;
        for (int i = 0; i < log.size(); i++) if (log.get(i).stage == s) from = i;
        for (int i = from; i < log.size(); i++) {
            DealStage e = log.get(i).stage;
            if (e.isAfter(s) && (order.contains(e) || e == DealStage.DONE)) return day(log.get(i).reachedAt);
        }
        return null;
    }

    private static LocalDate day(OffsetDateTime t) {
        return t == null ? null : t.toLocalDate();
    }

    /**
     * The button: she says the deal reached the next step. Only the step the diagram offered is accepted, so a
     * double click or an old page can't skip ahead. Getting paid marks the deal's sent invoices paid.
     */
    @Transactional
    public View advance(Long opportunityId, String to) {
        Opportunity o = opportunities.findById(opportunityId).orElseThrow();
        View v = view(opportunityId);
        if (!"active".equals(v.state()) || v.next() == null || !v.next().equals(to)) {
            throw new IllegalStateException("This deal has moved on since the page was opened. Reload to see where it is now.");
        }
        DealStage target = DealStage.valueOf(to);
        if (target == DealStage.DONE && WorkflowEngine.paysAfter(o)) {
            LocalDate today = settings.today();
            for (Invoice i : invoices.findByOpportunityIdOrderByIdAsc(o.id)) {
                if (i.status == InvoiceStatus.SENT) invoiceService.markPaid(i.id, today);
            }
            o = opportunities.findById(opportunityId).orElseThrow();
            if (o.stage != DealStage.DONE) {
                o.closedReason = "Paid";
                workflow.setStatus(o, OpportunityStatus.CLOSED);
                opportunities.save(o);
            }
        } else {
            workflow.moveTo(o, target, OffsetDateTime.now());
        }
        return view(opportunityId);
    }
}

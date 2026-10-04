package com.creatorcrm.digest;

import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.Deadline;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.InvoiceStatus;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.Enums.Priority;
import com.creatorcrm.domain.Enums.TaskStatus;
import com.creatorcrm.domain.FollowUp;
import com.creatorcrm.domain.Invoice;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Task;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.invoices.InvoicePdf;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.DeadlineRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.InvoiceRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.TaskRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.FollowUpEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Builds "everything you need to do today" and the end-of-day summary from the database. */
@Service
public class DigestService {

    public record Item(String kind, String title, String detail, LocalDate due, long overdueDays, String priority,
                       Long opportunityId, Long refId, String brand, int score) {}

    public record OpportunityCounts(int total, int paid, int gifted, int affiliate, int other, List<String> items) {}

    public record Approval(Long draftId, String brand, String type, String channel, String preview, String blockedReason) {}

    public record Morning(LocalDate date, String headline, List<Item> urgent, List<Item> followUps,
                          OpportunityCounts newOpportunities, List<Item> upcoming, List<Approval> approvals,
                          Map<String, Long> pipeline, BigDecimal openPipelineValue, List<Item> rebook) {}

    public record EndOfDay(LocalDate date, List<String> completed, List<String> stillPending,
                           OpportunityCounts newOpportunities, List<String> tomorrow) {}

    private static final Set<OpportunityStatus> COUNTED = EnumSet.complementOf(EnumSet.of(OpportunityStatus.CLOSED, OpportunityStatus.COLD));

    private final TaskRepo tasks;
    private final OpportunityRepo opportunities;
    private final BrandRepo brands;
    private final DeadlineRepo deadlines;
    private final DraftRepo drafts;
    private final ActivityRepo activity;
    private final FollowUpEngine followUps;
    private final DraftService draftService;
    private final SettingsService settings;
    private final InvoiceRepo invoices;

    public DigestService(TaskRepo tasks, OpportunityRepo opportunities, BrandRepo brands, DeadlineRepo deadlines,
                         DraftRepo drafts, ActivityRepo activity, FollowUpEngine followUps, DraftService draftService,
                         SettingsService settings, InvoiceRepo invoices) {
        this.invoices = invoices;
        this.tasks = tasks;
        this.opportunities = opportunities;
        this.brands = brands;
        this.deadlines = deadlines;
        this.drafts = drafts;
        this.activity = activity;
        this.followUps = followUps;
        this.draftService = draftService;
        this.settings = settings;
    }

    public Morning morning() {
        LocalDate today = settings.today();
        Map<Long, Opportunity> opps = new LinkedHashMap<>();
        opportunities.findAll().forEach(o -> opps.put(o.id, o));
        Map<Long, String> brandNames = new LinkedHashMap<>();
        brands.findAll().forEach(b -> brandNames.put(b.id, b.name));

        List<Item> urgent = new ArrayList<>();
        List<Item> upcoming = new ArrayList<>();
        for (Task t : tasks.findByStatus(TaskStatus.OPEN)) {
            Opportunity o = t.opportunityId == null ? null : opps.get(t.opportunityId);
            if (o != null && !o.status.isOpen()) continue;
            Item item = taskItem(t, o, o == null ? "" : brandNames.getOrDefault(o.brandId, ""), today);
            boolean isUrgent = (t.dueDate != null && !t.dueDate.isAfter(today)) || t.priority == Priority.HIGH || item.score() >= 70;
            if (isUrgent) urgent.add(item);
            else if (t.dueDate == null || !t.dueDate.isAfter(today.plusDays(7))) upcoming.add(item);
        }
        for (Deadline d : deadlines.findByDoneFalseAndDueDateLessThanEqualOrderByDueDateAsc(today.plusDays(14))) {
            Opportunity o = opps.get(d.opportunityId);
            if (o == null || !o.status.isOpen()) continue;
            String brand = brandNames.getOrDefault(o.brandId, "");
            long overdue = Math.max(0, ChronoUnit.DAYS.between(d.dueDate, today));
            Item item = new Item("DEADLINE", brand + " — " + pretty(d.type.name()) + (d.description == null || d.description.isBlank() ? "" : ": " + d.description),
                    when(d.dueDate, today), d.dueDate, overdue, "HIGH", o.id, d.id, brand, 60 + (int) Math.min(overdue * 5, 30));
            if (!d.dueDate.isAfter(today)) urgent.add(item); else upcoming.add(item);
        }
        Set<Long> reminderReady = new java.util.HashSet<>();
        drafts.findByStatusOrderByCreatedAtAsc(DraftStatus.PENDING).stream()
                .filter(d -> d.type == DraftType.PAYMENT_REMINDER && d.invoiceId != null)
                .forEach(d -> reminderReady.add(d.invoiceId));
        for (Invoice inv : invoices.findByStatusOrderByDueDateAsc(InvoiceStatus.SENT)) {
            if (!inv.isOverdue(today)) continue;
            String brand = brandNames.getOrDefault(inv.brandId, "");
            long overdue = ChronoUnit.DAYS.between(inv.dueDate, today);
            urgent.add(new Item("INVOICE", brand + ": payment " + overdue + (overdue == 1 ? " day" : " days") + " late",
                    "Invoice " + inv.number + ", " + InvoicePdf.money(inv.currency, inv.amount) + ", was due "
                            + InvoicePdf.date(inv.dueDate)
                            + (inv.remindersSent > 0 ? " · " + inv.remindersSent + (inv.remindersSent == 1 ? " reminder" : " reminders") + " sent" : "")
                            + (reminderReady.contains(inv.id) ? " · reminder ready in Drafts" : ""),
                    inv.dueDate, overdue, "HIGH", inv.opportunityId, inv.id, brand, 65 + (int) Math.min(overdue, 30)));
        }
        urgent.sort(Comparator.comparingInt(Item::score).reversed());
        upcoming.sort(Comparator.comparing(Item::due, Comparator.nullsLast(Comparator.naturalOrder())));

        List<Item> fus = new ArrayList<>();
        for (FollowUp f : followUps.dueOnOrBefore(today)) {
            Opportunity o = opps.get(f.opportunityId);
            String brand = brandNames.getOrDefault(o.brandId, "");
            long overdue = ChronoUnit.DAYS.between(f.scheduledDate, today);
            boolean last = f.number >= settings.maxFollowups();
            fus.add(new Item("FOLLOW_UP", brand + " — Follow-up #" + f.number + (last ? " / final" : "") + (overdue > 0 ? "" : " due today"),
                    overdue > 0 ? "overdue by " + overdue + (overdue == 1 ? " day" : " days") : "due today",
                    f.scheduledDate, overdue, "MEDIUM", o.id, f.id, brand, 40 + f.number));
        }

        List<Approval> approvals = drafts.findByStatusOrderByCreatedAtAsc(DraftStatus.PENDING).stream()
                .map(d -> approval(d, opps, brandNames)).toList();

        // Win-back re-pitches waiting for her: past brands worth working with again.
        List<Item> rebook = new ArrayList<>();
        for (Draft d : drafts.findByStatusOrderByCreatedAtAsc(DraftStatus.PENDING)) {
            if (d.type != DraftType.REPITCH) continue;
            Opportunity o = opps.get(d.opportunityId);
            if (o == null) continue;
            String brand = brandNames.getOrDefault(o.brandId, "");
            String last = o.campaign != null && !o.campaign.isBlank() ? o.campaign
                    : o.deliverables != null && !o.deliverables.isBlank() ? o.deliverables : pretty(o.type.name());
            rebook.add(new Item("REBOOK", brand + ": work together again?",
                    "Last collab: " + last + (o.compensation == Compensation.GIFTED ? " (gifted)"
                            : o.budgetText != null && !o.budgetText.isBlank() ? " (" + o.budgetText + ")" : "")
                            + " · re-pitch ready in Drafts",
                    null, 0, "MEDIUM", o.id, d.id, brand, 20));
        }

        Map<String, Long> pipeline = new LinkedHashMap<>();
        for (OpportunityStatus s : OpportunityStatus.values()) {
            long n = opps.values().stream().filter(o -> o.status == s).count();
            if (n > 0) pipeline.put(s.label, n);
        }
        BigDecimal value = opps.values().stream().filter(o -> COUNTED.contains(o.status) && o.budgetAmount != null)
                .map(o -> o.budgetAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        OpportunityCounts fresh = counts(opps.values().stream()
                .filter(o -> o.origin == Origin.INBOUND && o.createdAt.isAfter(OffsetDateTime.now().minusHours(24))).toList(), brandNames);
        int total = urgent.size() + fus.size();
        String headline = "Good morning, " + settings.creatorName() + ". You have " + total + (total == 1 ? " thing" : " things")
                + " to do today" + (approvals.isEmpty() ? "" : " and " + approvals.size() + " draft" + (approvals.size() == 1 ? "" : "s") + " to approve") + ".";
        return new Morning(today, headline, urgent, fus, fresh, upcoming, approvals, pipeline, value, rebook);
    }

    public EndOfDay endOfDay() {
        LocalDate today = settings.today();
        OffsetDateTime start = today.atStartOfDay(settings.zone()).toOffsetDateTime();
        List<String> completed = activity.findByAtAfterOrderByAtAsc(start).stream()
                .filter(a -> Set.of(Activity.TASK_DONE, Activity.DRAFT_SENT, Activity.FOLLOWUP_SENT, Activity.BRAND_REPLIED).contains(a.type))
                .map(a -> a.text).distinct().toList();

        Morning m = morning();
        List<String> pending = new ArrayList<>();
        m.urgent().forEach(i -> pending.add(i.title()));
        m.followUps().forEach(i -> pending.add(i.title()));

        Map<Long, String> brandNames = new LinkedHashMap<>();
        brands.findAll().forEach(b -> brandNames.put(b.id, b.name));
        OpportunityCounts fresh = counts(opportunities.findByCreatedAtAfter(start).stream()
                .filter(o -> o.origin == Origin.INBOUND).toList(), brandNames);

        LocalDate tomorrow = today.plusDays(1);
        List<String> next = new ArrayList<>();
        m.upcoming().stream().filter(i -> tomorrow.equals(i.due())).forEach(i -> next.add(i.title()));
        followUps.dueOnOrBefore(tomorrow).stream().filter(f -> f.scheduledDate.equals(tomorrow))
                .forEach(f -> next.add(opportunities.findById(f.opportunityId).map(o -> brandNames.get(o.brandId)).orElse("")
                        + " — Follow-up #" + f.number));
        m.urgent().stream().limit(3).map(Item::title).filter(t -> !next.contains(t)).forEach(next::add);
        return new EndOfDay(today, completed, pending, fresh, next);
    }

    private Item taskItem(Task t, Opportunity o, String brand, LocalDate today) {
        long overdue = t.dueDate == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(t.dueDate, today));
        int score = switch (t.type) {
            case SIGN_CONTRACT -> 50;
            case SEND_RATES, NEGOTIATE -> 45;
            case COMPLETE_APPLICATION -> 40;
            case REPLY, CONFIRM_AVAILABILITY, SEND_MEDIA_KIT, SEND_INVOICE, CHASE_PAYMENT, ASK_MISSING_INFO -> 35;
            case CREATE_CONTENT, REVISE_CONTENT, SUBMIT_CONTENT, POST_CONTENT -> 30;
            case DECIDE -> 10;
            default -> 20;
        };
        score += t.priority == Priority.HIGH ? 25 : t.priority == Priority.MEDIUM ? 10 : 0;
        if (t.dueDate != null) {
            if (overdue > 0) score += 30 + (int) Math.min(overdue * 2, 20);
            else if (t.dueDate.equals(today)) score += 25;
            else if (t.dueDate.equals(today.plusDays(1))) score += 10;
        }
        List<String> detail = new ArrayList<>();
        if (o != null) {
            if (o.compensation == Compensation.PAID) score += 15;
            if (o.compensation == Compensation.AFFILIATE) score += 5;
            if (o.budgetAmount != null && o.budgetAmount.compareTo(BigDecimal.valueOf(500)) >= 0) score += 10;
            if (o.budgetAmount != null && o.budgetAmount.compareTo(BigDecimal.valueOf(2000)) >= 0) score += 10;
            if (o.compensation != Compensation.UNKNOWN) detail.add(o.compensation.name().toLowerCase());
            if (o.budgetText != null && !o.budgetText.isBlank()) detail.add(o.budgetText);
        }
        if (t.dueDate != null) detail.add(when(t.dueDate, today));
        return new Item("TASK", t.description, String.join(" · ", detail), t.dueDate, overdue,
                t.priority == null ? "MEDIUM" : t.priority.name(), o == null ? null : o.id, t.id, brand, score);
    }

    private Approval approval(Draft d, Map<Long, Opportunity> opps, Map<Long, String> brandNames) {
        Opportunity o = opps.get(d.opportunityId);
        String preview = d.body.length() > 160 ? d.body.substring(0, 160) + "…" : d.body;
        return new Approval(d.id, o == null ? "" : brandNames.getOrDefault(o.brandId, ""), d.type.name(), d.channel.name(),
                preview, draftService.sendBlockedReason(d).orElse(null));
    }

    private static OpportunityCounts counts(List<Opportunity> list, Map<Long, String> brandNames) {
        int paid = 0, gifted = 0, affiliate = 0, other = 0;
        List<String> items = new ArrayList<>();
        for (Opportunity o : list) {
            switch (o.compensation) {
                case PAID -> paid++;
                case GIFTED -> gifted++;
                case AFFILIATE -> affiliate++;
                default -> other++;
            }
            items.add(brandNames.getOrDefault(o.brandId, "?") + " — " + pretty(o.type.name())
                    + (o.budgetText == null || o.budgetText.isBlank() ? "" : " (" + o.budgetText + ")"));
        }
        return new OpportunityCounts(list.size(), paid, gifted, affiliate, other, items);
    }

    static String when(LocalDate due, LocalDate today) {
        long d = ChronoUnit.DAYS.between(today, due);
        if (d < 0) return "⚠️ OVERDUE by " + (-d) + (d == -1 ? " day" : " days");
        if (d == 0) return "due today";
        if (d == 1) return "due tomorrow";
        return "due in " + d + " days (" + due + ")";
    }

    static String pretty(String enumName) {
        String s = enumName.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Plain-text rendering (for MCP clients and copy/paste). */
    public String morningText() {
        Morning m = morning();
        StringBuilder sb = new StringBuilder(m.headline()).append("\n\n🔥 TODAY — HIGH PRIORITY\n");
        int i = 1;
        for (Item it : m.urgent()) sb.append(i++).append(". ").append(it.title()).append(it.detail().isBlank() ? "" : " — " + it.detail()).append('\n');
        if (m.urgent().isEmpty()) sb.append("Nothing urgent.\n");
        sb.append("\n📌 FOLLOW-UPS\n");
        m.followUps().forEach(it -> sb.append("• ").append(it.title()).append(it.overdueDays() > 0 ? " (" + it.detail() + ")" : "").append('\n'));
        if (m.followUps().isEmpty()) sb.append("None due.\n");
        OpportunityCounts c = m.newOpportunities();
        sb.append("\n💰 NEW OPPORTUNITIES (24h)\n").append(c.paid()).append(" paid · ").append(c.gifted()).append(" gifted · ")
                .append(c.affiliate()).append(" affiliate · ").append(c.other()).append(" other\n");
        c.items().forEach(s -> sb.append("• ").append(s).append('\n'));
        sb.append("\n📅 UPCOMING\n");
        m.upcoming().forEach(it -> sb.append("• ").append(it.title()).append(it.detail().isBlank() ? "" : " — " + it.detail()).append('\n'));
        if (!m.rebook().isEmpty()) {
            sb.append("\n🔁 REBOOK PAST BRANDS\n");
            m.rebook().forEach(it -> sb.append("• ").append(it.title()).append(" — ").append(it.detail()).append('\n'));
        }
        if (!m.approvals().isEmpty()) {
            sb.append("\n✉️ DRAFTS AWAITING YOUR APPROVAL\n");
            m.approvals().forEach(a -> sb.append("• #").append(a.draftId()).append(' ').append(a.brand()).append(" — ")
                    .append(pretty(a.type())).append(" via ").append(a.channel().toLowerCase()).append('\n'));
        }
        return sb.toString();
    }

    public String endOfDayText() {
        EndOfDay e = endOfDay();
        StringBuilder sb = new StringBuilder("End of day — ").append(e.date()).append("\n\n✅ Completed\n");
        e.completed().forEach(s -> sb.append("• ").append(s).append('\n'));
        if (e.completed().isEmpty()) sb.append("Nothing recorded yet.\n");
        sb.append("\n⏳ Still pending\n");
        e.stillPending().forEach(s -> sb.append("• ").append(s).append('\n'));
        OpportunityCounts c = e.newOpportunities();
        sb.append("\n💰 New opportunities: ").append(c.total()).append(" (").append(c.paid()).append(" paid, ")
                .append(c.gifted()).append(" gifted, ").append(c.affiliate()).append(" affiliate)\n");
        sb.append("\n➡️ Tomorrow's priorities\n");
        e.tomorrow().stream().filter(Objects::nonNull).forEach(s -> sb.append("• ").append(s).append('\n'));
        return sb.toString();
    }
}

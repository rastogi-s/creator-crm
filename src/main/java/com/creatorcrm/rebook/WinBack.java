package com.creatorcrm.rebook;

import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.InvoiceStatus;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Invoice;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.invoices.InvoicePdf;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.InvoiceRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Win back past brands. Brands that paid her and have gone quiet (60 days by default), and gifted collabs posted at
 * least two weeks ago, get a re-pitch draft that mentions the last collab. At most a few a week (5 by default), and
 * never for a brand with an open deal or a recent pitch. Every re-pitch waits in Drafts for her approval; sending
 * one starts a new deal with the brand.
 */
@Service
public class WinBack {
    private static final Logger log = LoggerFactory.getLogger(WinBack.class);
    /** A gifted collab can turn into a paid one sooner: two weeks after it went up. */
    static final int GIFTED_WAIT_DAYS = 14;
    private static final Set<OpportunityStatus> CONTENT_UP = Set.of(OpportunityStatus.POSTED, OpportunityStatus.PAYMENT_PENDING);

    /** A brand worth re-pitching, and the collab the re-pitch will mention. */
    public record Candidate(Long brandId, String brand, Long opportunityId, String lastCollab, boolean gifted,
                            String amount, LocalDate finishedOn, long quietDays) {}

    private record Collab(Opportunity deal, LocalDate finishedOn, BigDecimal paid, String currency) {}

    private final BrandRepo brands;
    private final OpportunityRepo opportunities;
    private final ConversationRepo conversations;
    private final ActivityRepo activity;
    private final InvoiceRepo invoices;
    private final DraftRepo draftRepo;
    private final DraftService drafts;
    private final SettingsService settings;
    private final LlmClient llm;

    public WinBack(BrandRepo brands, OpportunityRepo opportunities, ConversationRepo conversations, ActivityRepo activity,
                   InvoiceRepo invoices, DraftRepo draftRepo, DraftService drafts, SettingsService settings, LlmClient llm) {
        this.brands = brands;
        this.opportunities = opportunities;
        this.conversations = conversations;
        this.activity = activity;
        this.invoices = invoices;
        this.draftRepo = draftRepo;
        this.drafts = drafts;
        this.settings = settings;
        this.llm = llm;
    }

    /** Brands to re-pitch today, best first: paid collabs before gifted ones, bigger before smaller. */
    public List<Candidate> candidates(LocalDate today) {
        int quiet = settings.winBackQuietDays();
        Map<Long, List<Opportunity>> byBrand = opportunities.findAll().stream()
                .filter(o -> o.brandId != null).collect(Collectors.groupingBy(o -> o.brandId));
        Map<Long, List<Invoice>> paidByDeal = invoices.findByStatusOrderByDueDateAsc(InvoiceStatus.PAID).stream()
                .collect(Collectors.groupingBy(i -> i.opportunityId));
        Set<Long> repitchPending = new HashSet<>();
        for (Draft d : draftRepo.findByStatusOrderByCreatedAtAsc(DraftStatus.PENDING)) {
            if (d.type == DraftType.REPITCH) opportunities.findById(d.opportunityId).ifPresent(o -> repitchPending.add(o.brandId));
        }

        List<Candidate> out = new ArrayList<>();
        Map<Candidate, BigDecimal> value = new HashMap<>();
        for (Brand b : brands.findAll()) {
            List<Opportunity> deals = byBrand.getOrDefault(b.id, List.of());
            if (deals.isEmpty() || repitchPending.contains(b.id)) continue;
            if (b.lastRepitchAt != null && b.lastRepitchAt.atZoneSameInstant(settings.zone()).toLocalDate()
                    .isAfter(today.minusDays(quiet))) continue;
            if (!hasContact(b, deals)) continue;

            Collab last = null;
            boolean busy = false;
            LocalDate lastTouch = null;
            for (Opportunity o : deals) {
                lastTouch = later(lastTouch, lastMessageOn(o));
                Collab c = finished(o, paidByDeal.getOrDefault(o.id, List.of()));
                if (c == null) {
                    // An open deal, or a pitch in the quiet period, means they're already talking.
                    if (o.status.isOpen()) busy = true;
                    if (o.pitchedAt != null && o.pitchedAt.isAfter(today.minusDays(quiet))) busy = true;
                    continue;
                }
                // Posted gifted content is done; anything else still open (payment pending) isn't finished yet.
                if (o.status.isOpen() && !(o.status == OpportunityStatus.POSTED && o.compensation == Compensation.GIFTED)) busy = true;
                if (last == null || c.finishedOn().isAfter(last.finishedOn())) last = c;
            }
            if (busy || last == null) continue;

            boolean gifted = last.deal().compensation == Compensation.GIFTED && last.paid() == null;
            int wait = gifted ? GIFTED_WAIT_DAYS : quiet;
            LocalDate since = later(lastTouch, last.finishedOn());
            if (since.isAfter(today.minusDays(wait))) continue;

            BigDecimal amount = last.paid() != null ? last.paid() : gifted ? null : last.deal().budgetAmount;
            String currency = last.currency() != null ? last.currency() : last.deal().currency;
            Candidate cand = new Candidate(b.id, b.name, last.deal().id, describe(last.deal()), gifted,
                    amount == null ? null : InvoicePdf.money(currency == null ? "USD" : currency, amount),
                    last.finishedOn(), ChronoUnit.DAYS.between(since, today));
            out.add(cand);
            value.put(cand, amount == null ? BigDecimal.ZERO : amount);
        }
        out.sort(Comparator.comparing(Candidate::gifted)
                .thenComparing((Candidate c) -> value.get(c), Comparator.reverseOrder())
                .thenComparing(Candidate::finishedOn));
        return out;
    }

    /** Daily run: fills this week's re-pitch allowance from the best candidates. Returns how many were drafted. */
    public int draftDue(LocalDate today) {
        int limit = settings.winBackWeeklyLimit();
        if (limit <= 0 || !llm.isConfigured()) return 0;
        OffsetDateTime weekAgo = OffsetDateTime.now().minusDays(7);
        long thisWeek = brands.findAll().stream().filter(b -> b.lastRepitchAt != null && b.lastRepitchAt.isAfter(weekAgo)).count();
        int n = 0;
        for (Candidate c : candidates(today)) {
            if (thisWeek + n >= limit) break;
            try {
                draft(c, today);
                n++;
            } catch (RuntimeException e) {
                log.warn("Could not draft a re-pitch to {}: {}", c.brand(), e.getMessage());
            }
        }
        return n;
    }

    /** "Pitch them again" on a finished deal: a re-pitch now, whatever the schedule says. */
    @Transactional
    public Draft draftFor(Long opportunityId) {
        Opportunity o = opportunities.findById(opportunityId).orElseThrow(() -> new IllegalArgumentException("Unknown deal"));
        LocalDate today = settings.today();
        Collab c = finished(o, invoices.findByOpportunityIdOrderByIdAsc(o.id).stream()
                .filter(i -> i.status == InvoiceStatus.PAID).toList());
        if (c == null) throw new IllegalStateException("Re-pitches are for finished collabs: this deal's content isn't up yet");
        Brand b = brands.findById(o.brandId).orElseThrow();
        boolean gifted = o.compensation == Compensation.GIFTED && c.paid() == null;
        BigDecimal amount = c.paid() != null ? c.paid() : gifted ? null : o.budgetAmount;
        String currency = c.currency() != null ? c.currency() : o.currency;
        return draft(new Candidate(b.id, b.name, o.id, describe(o), gifted,
                amount == null ? null : InvoicePdf.money(currency == null ? "USD" : currency, amount),
                c.finishedOn(), ChronoUnit.DAYS.between(c.finishedOn(), today)), today);
    }

    private Draft draft(Candidate c, LocalDate today) {
        String when = c.finishedOn().format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH));
        String instructions = "Win-back re-pitch to a brand the creator has worked with before. This is a fresh email, "
                + "not a reply. Last collab: " + c.lastCollab() + (c.gifted() ? " (gifted)" : c.amount() == null ? "" : " (paid, " + c.amount() + ")")
                + ", finished " + when + ". Thank them warmly for that collab and mention something specific about it, "
                + "then propose working together again with one concrete idea"
                + (c.gifted() ? ", as a paid collaboration this time" : "")
                + ". Keep it short, personal and easy to say yes to. Don't quote any rates.";
        Draft d = drafts.generate(c.opportunityId(), DraftType.REPITCH, instructions, null, null);
        Brand b = brands.findById(c.brandId()).orElseThrow();
        b.lastRepitchAt = OffsetDateTime.now();
        brands.save(b);
        return d;
    }

    /**
     * When this collab finished, or null if it never got that far: the day its invoice was paid, else the day it
     * moved to Posted or Payment Pending (from the deal's history), else when a deal in that state, or closed as
     * paid, was last updated.
     */
    private Collab finished(Opportunity o, List<Invoice> paid) {
        LocalDate on = null;
        BigDecimal amount = null;
        String currency = null;
        for (Invoice i : paid) {
            on = later(on, i.paidDate);
            amount = amount == null ? i.amount : amount.add(i.amount);
            currency = i.currency;
        }
        if (on != null) return new Collab(o, on, amount, currency); // paid: that's when it finished
        for (Activity a : activity.findByOpportunityIdOrderByAtDesc(o.id)) {
            if (!Activity.STATUS_CHANGED.equals(a.type) || a.text == null) continue;
            if (CONTENT_UP.stream().anyMatch(s -> a.text.endsWith("→ " + s.label))) {
                on = a.at.atZoneSameInstant(settings.zone()).toLocalDate();
                break;
            }
        }
        boolean closedPaid = o.status == OpportunityStatus.CLOSED && o.closedReason != null && o.closedReason.equalsIgnoreCase("Paid");
        if (on == null && (CONTENT_UP.contains(o.status) || closedPaid)) on = o.updatedAt.atZoneSameInstant(settings.zone()).toLocalDate();
        return on == null ? null : new Collab(o, on, amount, currency);
    }

    private LocalDate lastMessageOn(Opportunity o) {
        if (o.conversationId == null) return null;
        return conversations.findById(o.conversationId).map(c -> c.lastMessageAt)
                .map(t -> t.atZoneSameInstant(settings.zone()).toLocalDate()).orElse(null);
    }

    private boolean hasContact(Brand b, List<Opportunity> deals) {
        if (b.contactEmail != null && !b.contactEmail.isBlank()) return true;
        if (b.instagram != null && !b.instagram.isBlank()) return true;
        return deals.stream().anyMatch(o -> o.conversationId != null
                && conversations.findById(o.conversationId).map((Conversation c) -> c.counterparty != null).orElse(false));
    }

    private static String describe(Opportunity o) {
        for (String s : new String[] {o.campaign, o.deliverables}) {
            if (s != null && !s.isBlank()) return s.length() > 120 ? s.substring(0, 120) + "…" : s.strip();
        }
        return "a " + o.type.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " collab";
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.isAfter(a) ? b : a;
    }
}

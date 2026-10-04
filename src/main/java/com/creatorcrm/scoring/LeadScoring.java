package com.creatorcrm.scoring;

import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.InvoiceStatus;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.repo.InvoiceRepo;
import com.creatorcrm.repo.OpportunityRepo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * How promising an incoming lead is, worked out in code from what the classifier already extracted: paid or gifted,
 * the stated budget against her usual (median) booked deal, the kind of deal, a missing budget, and whether the
 * brand has paid her before. No AI call, nothing stored: the score is recomputed whenever it's shown.
 */
@Service
public class LeadScoring {

    public enum Level { HIGH, MEDIUM, LOW }

    /** {@code points} is 0-100; {@code reasons} explain it in her words, most important first. */
    public record Score(Level level, int points, List<String> reasons) {
        public String summary() {
            return String.join(" · ", reasons);
        }
    }

    static final int HIGH_FROM = 70;
    static final int MEDIUM_FROM = 45;
    /** Booked deals needed before "your usual rate" means anything. */
    static final int MIN_BOOKED = 3;

    /** Leads still being decided on: the ones a score helps triage. */
    static final Set<OpportunityStatus> LEAD = EnumSet.of(OpportunityStatus.NEW_LEAD, OpportunityStatus.AWAITING_MY_REPLY,
            OpportunityStatus.NEGOTIATING);
    /** She said yes and the work went ahead. */
    static final Set<OpportunityStatus> BOOKED = EnumSet.of(OpportunityStatus.CONTRACT_PENDING, OpportunityStatus.CONTRACT_TO_SIGN,
            OpportunityStatus.PRODUCT_PENDING, OpportunityStatus.PRODUCT_RECEIVED, OpportunityStatus.CONTENT_TO_CREATE,
            OpportunityStatus.AWAITING_APPROVAL, OpportunityStatus.SCHEDULED_TO_POST, OpportunityStatus.POSTED,
            OpportunityStatus.PAYMENT_PENDING);

    private final OpportunityRepo opportunities;
    private final InvoiceRepo invoices;

    public LeadScoring(OpportunityRepo opportunities, InvoiceRepo invoices) {
        this.opportunities = opportunities;
        this.invoices = invoices;
    }

    /** What the scores are compared against, loaded once per screen. */
    public record Context(BigDecimal medianDeal, Set<Long> paidBefore) {}

    public Context context() {
        List<Opportunity> all = opportunities.findAll();
        Set<Long> paidDeals = invoices.findByStatusOrderByDueDateAsc(InvoiceStatus.PAID).stream()
                .map(i -> i.opportunityId).collect(Collectors.toSet());
        List<BigDecimal> booked = all.stream()
                .filter(o -> o.compensation == Compensation.PAID && o.budgetAmount != null && o.budgetAmount.signum() > 0)
                .filter(o -> BOOKED.contains(o.status) || paidDeals.contains(o.id) || isClosedPaid(o))
                .map(o -> o.budgetAmount).sorted().toList();
        Set<Long> paidBefore = new HashSet<>();
        for (Opportunity o : all) {
            if (paidDeals.contains(o.id) || isClosedPaid(o)) paidBefore.add(o.brandId);
        }
        return new Context(booked.size() < MIN_BOOKED ? null : median(booked), paidBefore);
    }

    /** Scores for these deals; leads only, so finished or pitched deals are left out. */
    public Map<Long, Score> scores(Collection<Opportunity> deals) {
        Context c = context();
        return deals.stream().filter(LeadScoring::isLead)
                .collect(Collectors.toMap(o -> o.id, o -> score(o, c), (a, b) -> a));
    }

    public static boolean isLead(Opportunity o) {
        return o.origin == Origin.INBOUND && LEAD.contains(o.status);
    }

    public Score score(Opportunity o, Context c) {
        int points = 50;
        List<String> reasons = new ArrayList<>();
        switch (o.compensation) {
            case PAID -> { points += 20; reasons.add("Paid"); }
            case GIFTED -> { points -= 25; reasons.add("Gifted only"); }
            case AFFILIATE -> { points -= 15; reasons.add("Commission only"); }
            default -> { }
        }
        boolean hasBudget = o.budgetAmount != null && o.budgetAmount.signum() > 0;
        if (hasBudget && c.medianDeal() != null) {
            BigDecimal ratio = o.budgetAmount.divide(c.medianDeal(), 2, RoundingMode.HALF_UP);
            if (ratio.compareTo(BigDecimal.ONE) >= 0) {
                points += 20;
                reasons.add("At or above your usual " + plain(c.medianDeal()));
            } else if (ratio.compareTo(new BigDecimal("0.5")) >= 0) {
                points += 5;
                reasons.add("A bit under your usual " + plain(c.medianDeal()));
            } else {
                points -= 30;
                reasons.add("Well under your usual " + plain(c.medianDeal()));
            }
        } else if (!hasBudget && o.compensation != Compensation.GIFTED && o.type != OpportunityType.RATES_REQUEST) {
            points -= 5;
            reasons.add("No budget mentioned");
        }
        switch (o.type) {
            case LONG_TERM, AMBASSADOR, CAMPAIGN -> { points += 10; reasons.add("Ongoing work"); }
            case RATES_REQUEST -> { points += 5; reasons.add("Asked for your rates"); }
            case CREATOR_APPLICATION -> { points -= 10; reasons.add("Application, not an offer"); }
            default -> { }
        }
        if (c.paidBefore().contains(o.brandId)) {
            points += 10;
            reasons.add("Paid you before");
        }
        points = Math.max(0, Math.min(100, points));
        Level level = points >= HIGH_FROM ? Level.HIGH : points >= MEDIUM_FROM ? Level.MEDIUM : Level.LOW;
        return new Score(level, points, reasons);
    }

    private static boolean isClosedPaid(Opportunity o) {
        return o.status == OpportunityStatus.CLOSED && "Paid".equalsIgnoreCase(o.closedReason);
    }

    static BigDecimal median(List<BigDecimal> sorted) {
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2)
                : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    /** Budgets are compared as plain numbers; her deals are almost all in dollars. */
    private static String plain(BigDecimal v) {
        return "$" + v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}

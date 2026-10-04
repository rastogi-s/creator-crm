package com.creatorcrm.rates;

import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.InvoiceStatus;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Invoice;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.rates.Deliverables.Kind;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.InvoiceRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.scoring.LeadScoring;
import com.creatorcrm.settings.SettingsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * What to ask for on a deal that's still being decided. Her price per deliverable comes from the paid deals she has
 * booked (once there are enough of them), otherwise from the rates written in About you. Paid usage and exclusivity
 * add the percentages set in Settings. Worked out in code with no AI call; the number only reaches a brand when she
 * puts it in a counter-offer draft and approves that draft.
 */
@Service
public class RateAdvisor {

    /** Booked paid deals needed before her own history sets the price. */
    static final int MIN_HISTORY = 5;

    /** How much each deliverable is worth next to a Reel, used to split a past fee and to fill rates she hasn't written. */
    static final Map<Kind, Double> WEIGHT = new EnumMap<>(Map.of(
            Kind.REEL, 1.0, Kind.TIKTOK, 1.0, Kind.POST, 0.7, Kind.STORY, 0.25, Kind.UGC, 0.8));

    /** Deals still being decided, where a counter-offer makes sense. */
    public static final Set<OpportunityStatus> NEGOTIABLE = EnumSet.of(OpportunityStatus.NEW_LEAD,
            OpportunityStatus.AWAITING_MY_REPLY, OpportunityStatus.NEGOTIATING);

    public record Line(String text, String amount) {}

    /**
     * {@code available} false means there's nothing to suggest, and {@code why} says what would change that.
     * {@code offerGapPercent} compares the brand's offer to the suggestion: -40 is 40% under.
     */
    public record Advice(boolean available, String why, BigDecimal suggested, String suggestedText, String asks,
                         List<Line> lines, String basis, BigDecimal offer, Integer offerGapPercent) {
        static Advice none(String why) {
            return new Advice(false, why, null, null, null, List.of(), null, null, null);
        }
    }

    /** Her price for one of each deliverable, and where it came from. */
    record Rates(Map<Kind, BigDecimal> perUnit, String basis) {}

    record PastDeal(BigDecimal fee, Deliverables asked) {}

    private final OpportunityRepo opportunities;
    private final InvoiceRepo invoices;
    private final SettingsService settings;
    private final DraftService drafts;
    private final DraftRepo draftRepo;

    public RateAdvisor(OpportunityRepo opportunities, InvoiceRepo invoices, SettingsService settings, DraftService drafts,
                       DraftRepo draftRepo) {
        this.draftRepo = draftRepo;
        this.opportunities = opportunities;
        this.invoices = invoices;
        this.settings = settings;
        this.drafts = drafts;
    }

    public Advice advise(Long opportunityId) {
        return advise(opportunities.findById(opportunityId).orElseThrow());
    }

    public Advice advise(Opportunity o) {
        if (o.compensation == Compensation.GIFTED || o.compensation == Compensation.AFFILIATE) {
            return Advice.none("Gifted and commission-only offers have no fee to suggest.");
        }
        Deliverables asked = Deliverables.parse(o.deliverables, o.usageRights);
        if (asked.isEmpty()) {
            return Advice.none("Add what they're asking for under Deliverables, for example \"1 Reel + 3 Stories\", to get a suggestion.");
        }
        Rates rates = rates(o.id);
        if (rates == null) {
            return Advice.none("Write your rates in Settings, About you (for example \"1 Instagram Reel: $800\"), "
                    + "or book " + MIN_HISTORY + " paid deals, and a suggestion appears here.");
        }
        return compute(asked, rates, settings.rateUsagePercent(), settings.rateExclusivityPercent(), o.budgetAmount);
    }

    /** Writes a counter-offer draft quoting the amount she chose. It waits in Drafts like any other. */
    public Draft draftCounter(Long opportunityId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(new BigDecimal("1000000")) > 0) {
            throw new IllegalArgumentException("Enter the amount to ask for");
        }
        Opportunity o = opportunities.findById(opportunityId).orElseThrow();
        Deliverables asked = Deliverables.parse(o.deliverables, o.usageRights);
        String what = asked.isEmpty() ? (o.deliverables == null || o.deliverables.isBlank() ? "this collab" : o.deliverables) : asked.describe();
        String instructions = "Counter-offer: propose " + money(amount) + " for " + what + ". The creator chose this amount; quote it "
                + "exactly and no other prices. Thank them for the offer, say briefly why this is the right rate for what they're "
                + "asking (for example the deliverables, paid usage or exclusivity), and keep the door open to talk it through.";
        // An untouched reply already drafted for this deal is replaced by the counter, which takes over its task.
        List<Draft> stale = draftRepo.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING).stream()
                .filter(d -> d.type == DraftType.NEGOTIATION && Objects.equals(d.body, d.originalBody)).toList();
        Long taskId = stale.stream().map(d -> d.taskId).filter(Objects::nonNull).findFirst().orElse(null);
        Draft counter = drafts.generate(o.id, DraftType.NEGOTIATION, instructions, taskId, null);
        for (Draft d : stale) {
            d.status = DraftStatus.SUPERSEDED;
            draftRepo.save(d);
        }
        return counter;
    }

    Rates rates(Long excludeId) {
        List<PastDeal> history = history(excludeId);
        Map<Kind, BigDecimal> written = fromProfile(settings.creatorProfile());
        if (history.size() >= MIN_HISTORY || (written.isEmpty() && !history.isEmpty())) {
            BigDecimal perReel = perReelFromHistory(history, settings.rateUsagePercent(), settings.rateExclusivityPercent());
            Map<Kind, BigDecimal> perUnit = new EnumMap<>(Kind.class);
            for (Kind k : Kind.values()) perUnit.put(k, perReel.multiply(BigDecimal.valueOf(WEIGHT.get(k))));
            String basis = "Based on your last " + history.size() + " paid " + (history.size() == 1 ? "deal" : "deals")
                    + (history.size() < MIN_HISTORY ? ". That's only a few, so treat it as a rough guide" : "");
            return new Rates(perUnit, basis);
        }
        if (written.isEmpty()) return null;
        return new Rates(fill(written), "Based on the rates in About you");
    }

    /** Booked or paid deals with a fee and readable deliverables. The fee is the paid invoice when there is one. */
    List<PastDeal> history(Long excludeId) {
        Map<Long, BigDecimal> paid = new HashMap<>();
        for (Invoice i : invoices.findByStatusOrderByDueDateAsc(InvoiceStatus.PAID)) {
            if (i.opportunityId != null && i.amount != null) paid.merge(i.opportunityId, i.amount, BigDecimal::add);
        }
        List<PastDeal> out = new ArrayList<>();
        for (Opportunity o : opportunities.findAll()) {
            if (o.id.equals(excludeId) || o.compensation != Compensation.PAID) continue;
            boolean done = LeadScoring.BOOKED.contains(o.status) || paid.containsKey(o.id) || LeadScoring.isClosedPaid(o);
            BigDecimal fee = paid.getOrDefault(o.id, o.budgetAmount);
            if (!done || fee == null || fee.signum() <= 0) continue;
            Deliverables d = Deliverables.parse(o.deliverables, o.usageRights);
            if (!d.isEmpty()) out.add(new PastDeal(fee, d));
        }
        return out;
    }

    /** The median of what each past deal paid per Reel-equivalent, after taking out usage and exclusivity. */
    static BigDecimal perReelFromHistory(List<PastDeal> history, int usagePct, int exclusivityPct) {
        List<BigDecimal> each = new ArrayList<>();
        for (PastDeal p : history) {
            double units = 0;
            for (Map.Entry<Kind, Integer> e : p.asked().counts().entrySet()) units += e.getValue() * WEIGHT.get(e.getKey());
            double base = p.fee().doubleValue() / uplift(p.asked(), usagePct, exclusivityPct);
            each.add(BigDecimal.valueOf(base / units));
        }
        each.sort(null);
        int n = each.size();
        return n % 2 == 1 ? each.get(n / 2) : each.get(n / 2 - 1).add(each.get(n / 2)).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    static double uplift(Deliverables d, int usagePct, int exclusivityPct) {
        return 1 + d.usageMonths() * usagePct / 100.0 + (d.exclusive() ? exclusivityPct / 100.0 : 0);
    }

    private static final Pattern PRICE = Pattern.compile("(\\+\\s*)?\\$\\s*(\\d[\\d,]*(?:\\.\\d+)?)");
    private static final Pattern LEADING_COUNT = Pattern.compile("^\\s*[-*•]?\\s*(\\d{1,2})\\s*(?:-|\\s|x|×)");

    /**
     * Rates she wrote in About you, one per line: "1 Instagram Reel: $800", "3-frame Story set: $300" (so $100 a
     * Story). Add-ons written "+$" and lines still showing the template's "$___" are skipped.
     */
    static Map<Kind, BigDecimal> fromProfile(String profile) {
        Map<Kind, BigDecimal> out = new EnumMap<>(Kind.class);
        if (profile == null) return out;
        for (String line : profile.split("\\R")) {
            String l = line.toLowerCase(Locale.ROOT);
            Matcher price = PRICE.matcher(l);
            if (!price.find() || price.group(1) != null) continue;
            Kind kind = Deliverables.kindOf(l.substring(0, price.start()));
            if (kind == null || out.containsKey(kind)) continue;
            BigDecimal amount = new BigDecimal(price.group(2).replace(",", ""));
            Matcher count = LEADING_COUNT.matcher(l);
            int n = count.find() ? Math.max(1, Integer.parseInt(count.group(1))) : 1;
            if (amount.signum() > 0) out.put(kind, amount.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP));
        }
        return out;
    }

    /** Rates she didn't write are estimated from the ones she did, using the usual weights. */
    static Map<Kind, BigDecimal> fill(Map<Kind, BigDecimal> written) {
        List<Double> perReel = new ArrayList<>();
        written.forEach((k, v) -> perReel.add(v.doubleValue() / WEIGHT.get(k)));
        perReel.sort(null);
        double ref = perReel.get(perReel.size() / 2);
        Map<Kind, BigDecimal> out = new EnumMap<>(Kind.class);
        for (Kind k : Kind.values()) out.put(k, written.getOrDefault(k, BigDecimal.valueOf(ref * WEIGHT.get(k))));
        return out;
    }

    static Advice compute(Deliverables asked, Rates rates, int usagePct, int exclusivityPct, BigDecimal offer) {
        List<Line> lines = new ArrayList<>();
        BigDecimal base = BigDecimal.ZERO;
        for (Map.Entry<Kind, Integer> e : asked.counts().entrySet()) {
            BigDecimal unit = roundToTen(rates.perUnit().get(e.getKey()));
            BigDecimal sub = unit.multiply(BigDecimal.valueOf(e.getValue()));
            base = base.add(sub);
            lines.add(new Line(e.getKey().label(e.getValue()) + " × " + money(unit), money(sub)));
        }
        if (asked.usageMonths() > 0) {
            int pct = asked.usageMonths() * usagePct;
            lines.add(new Line(asked.usageMonths() + (asked.usageMonths() == 1 ? " month" : " months") + " of paid usage, +" + pct + "%",
                    money(base.multiply(BigDecimal.valueOf(pct)).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP))));
        }
        if (asked.exclusive()) {
            lines.add(new Line("Exclusivity, +" + exclusivityPct + "%",
                    money(base.multiply(BigDecimal.valueOf(exclusivityPct)).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP))));
        }
        BigDecimal total = base.multiply(BigDecimal.valueOf(uplift(asked, usagePct, exclusivityPct)));
        BigDecimal suggested = roundToTen(total);
        Integer gap = null;
        BigDecimal shownOffer = offer != null && offer.signum() > 0 ? offer : null;
        if (shownOffer != null) {
            gap = shownOffer.subtract(suggested).multiply(BigDecimal.valueOf(100)).divide(suggested, 0, RoundingMode.HALF_UP).intValue();
        }
        return new Advice(true, null, suggested, money(suggested), asked.describe(), lines, rates.basis(), shownOffer, gap);
    }

    /** Prices are quoted in round tens. */
    static BigDecimal roundToTen(BigDecimal v) {
        return v.divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP).multiply(BigDecimal.TEN);
    }

    /** Her deals are almost all in dollars, as elsewhere in the app. */
    public static String money(BigDecimal v) {
        NumberFormat f = NumberFormat.getIntegerInstance(Locale.US);
        return "$" + f.format(v.setScale(0, RoundingMode.HALF_UP));
    }
}

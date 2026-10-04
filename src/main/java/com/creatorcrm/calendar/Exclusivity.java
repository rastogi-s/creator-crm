package com.creatorcrm.calendar;

import com.creatorcrm.contracts.ContractService;
import com.creatorcrm.domain.Deadline;
import com.creatorcrm.domain.Enums.DeadlineType;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.llm.ContractTerms;
import com.creatorcrm.rates.Deliverables;
import com.creatorcrm.repo.DeadlineRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.scoring.LeadScoring;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.WorkflowEngine;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Exclusivity windows across deals. A deal's window runs from its posting date (or content due date, or today) for the
 * exclusivity months in its contract, or else in what the brand wrote. When another brand's window or date falls inside
 * it, both deals show a warning: only she knows whether the two brands compete.
 */
@Service
public class Exclusivity {

    /** "No end date" in a contract: counted as a year so the warning still shows. */
    static final int OPEN_ENDED_MONTHS = 12;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /**
     * A deal's dates: {@code exclusive} deals block competitors from {@code from} to {@code to}; others are just busy
     * on those days. {@code booked} = she said yes.
     */
    public record Window(Long opportunityId, Long brandId, String brand, LocalDate from, LocalDate to, boolean exclusive,
                         boolean booked, String terms) {}

    /** Shown on one deal: the other deal it clashes with, and why. */
    public record Overlap(Long otherOpportunityId, String otherBrand, String text) {}

    private final OpportunityRepo opportunities;
    private final DeadlineRepo deadlines;
    private final ContractService contracts;
    private final WorkflowEngine workflow;
    private final SettingsService settings;

    public Exclusivity(OpportunityRepo opportunities, DeadlineRepo deadlines, ContractService contracts,
                       WorkflowEngine workflow, SettingsService settings) {
        this.opportunities = opportunities;
        this.deadlines = deadlines;
        this.contracts = contracts;
        this.workflow = workflow;
        this.settings = settings;
    }

    public List<Overlap> forDeal(Long opportunityId) {
        List<Window> windows = windows();
        return windows.stream().filter(w -> w.opportunityId().equals(opportunityId)).findFirst()
                .map(mine -> windows.stream().filter(w -> clash(mine, w)).map(w -> overlap(mine, w)).toList())
                .orElse(List.of());
    }

    /** Every clash once, for the MCP tool: "Brand A and Brand B: …". */
    public List<String> all() {
        List<Window> windows = windows();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < windows.size(); i++) {
            for (int j = i + 1; j < windows.size(); j++) {
                Window a = windows.get(i), b = windows.get(j);
                if (clash(a, b)) out.add(a.brand() + " and " + b.brand() + ": " + overlap(a, b).text());
            }
        }
        return out;
    }

    List<Window> windows() {
        LocalDate today = settings.today();
        List<Window> out = new ArrayList<>();
        for (Opportunity o : opportunities.findAll()) {
            if (o.status == null || !o.status.isOpen()) continue;
            window(o, today).ifPresent(out::add);
        }
        out.sort(Comparator.comparing(Window::from));
        return out;
    }

    private Optional<Window> window(Opportunity o, LocalDate today) {
        List<Deadline> dates = deadlines.findByOpportunityIdOrderByDueDateAsc(o.id);
        Optional<LocalDate> dated = first(dates, DeadlineType.POSTING).or(() -> first(dates, DeadlineType.CONTENT_DUE))
                .or(() -> first(dates, DeadlineType.LAUNCH));
        int months = 0;
        String terms = "";
        Optional<ContractTerms> contract = contracts.forDeal(o.id).stream().map(ContractService.View::terms)
                .filter(Objects::nonNull).filter(t -> t.exclusivityMonths() > 0).findFirst();
        if (contract.isPresent()) {
            months = contract.get().exclusivityMonths() >= 999 ? OPEN_ENDED_MONTHS : contract.get().exclusivityMonths();
            terms = Optional.ofNullable(contract.get().exclusivityTerms()).orElse("");
        } else {
            Deliverables d = Deliverables.parse(o.deliverables, o.usageRights);
            if (d.exclusive()) months = Math.max(1, d.exclusivityMonths());
        }
        boolean booked = LeadScoring.BOOKED.contains(o.status);
        String brand = workflow.brandName(o);
        if (months > 0) {
            LocalDate from = dated.orElse(today);
            return Optional.of(new Window(o.id, o.brandId, brand, from, from.plusMonths(months), true, booked, terms));
        }
        return dated.map(d -> new Window(o.id, o.brandId, brand, d, d, false, booked, ""));
    }

    private static Optional<LocalDate> first(List<Deadline> dates, DeadlineType type) {
        return dates.stream().filter(d -> d.type == type && d.dueDate != null).map(d -> d.dueDate).min(LocalDate::compareTo);
    }

    /** Two different brands, at least one exclusive, at least one booked, and their dates touch. */
    static boolean clash(Window a, Window b) {
        if (a.opportunityId().equals(b.opportunityId())) return false;
        if (a.brandId() != null && a.brandId().equals(b.brandId())) return false;
        if (!a.exclusive() && !b.exclusive()) return false;
        if (!a.booked() && !b.booked()) return false;
        return !a.from().isAfter(b.to()) && !b.from().isAfter(a.to());
    }

    /** What deal {@code mine} shows about {@code other}. */
    static Overlap overlap(Window mine, Window other) {
        String text;
        if (mine.exclusive() && other.exclusive()) {
            text = "Your exclusivity with " + other.brand() + " (" + span(other) + ") overlaps this deal's (" + span(mine) + ").";
        } else if (other.exclusive()) {
            text = "This deal's date (" + DAY.format(mine.from()) + ") falls inside your exclusivity with " + other.brand()
                    + " (" + span(other) + ").";
        } else {
            text = other.brand() + "'s date (" + DAY.format(other.from()) + ") falls inside this deal's exclusivity ("
                    + span(mine) + ").";
        }
        String terms = other.exclusive() && !other.terms().isBlank() ? " " + other.brand() + "'s contract: " + other.terms().strip()
                : mine.exclusive() && !mine.terms().isBlank() ? " This contract: " + mine.terms().strip() : "";
        if (!terms.isEmpty() && !terms.endsWith(".")) terms += ".";
        return new Overlap(other.opportunityId(), other.brand(), text + terms + " Check the two brands don't compete.");
    }

    private static String span(Window w) {
        return DAY.format(w.from()) + " to " + DAY.format(w.to());
    }
}

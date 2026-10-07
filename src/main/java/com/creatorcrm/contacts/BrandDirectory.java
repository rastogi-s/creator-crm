package com.creatorcrm.contacts;

import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.BrandContact.Verified;
import com.creatorcrm.domain.BrandLead;
import com.creatorcrm.domain.ContactSource;
import com.creatorcrm.repo.BrandContactRepo;
import com.creatorcrm.repo.BrandLeadRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.ContactSourceRepo;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The brand directory she can share or sell: which brands work with creators and the inbox they publish for it
 * (collabs@, pr@), never a named person. A contact only goes in when {@link ContactService#shareable} allows it
 * (no named people, nothing from Hunter, Apollo or another finder service, nothing on the do-not-email list), and
 * on top of that it must be a company address the brand really uses: not Gmail-style personal mail, not an address
 * the app only guessed and never saw work.
 */
@Service
public class BrandDirectory {

    /** One shareable inbox at one brand. Nothing else about the brand or the people there leaves the app. */
    public record Row(String brand, String niche, String website, String instagram, String inbox, String inboxType,
                      boolean answered, LocalDate lastVerified) {}

    /** What the export holds, and how many contacts were left out for each reason. */
    public record Summary(List<Row> rows, int brands, int leftOutPeople, int leftOutPaid, int leftOutDoNotEmail,
                          int leftOutOther) {}

    private final BrandContactRepo contacts;
    private final ContactSourceRepo sources;
    private final BrandRepo brands;
    private final BrandLeadRepo leads;
    private final Suppressions suppressions;

    public BrandDirectory(BrandContactRepo contacts, ContactSourceRepo sources, BrandRepo brands, BrandLeadRepo leads,
                          Suppressions suppressions) {
        this.contacts = contacts;
        this.sources = sources;
        this.brands = brands;
        this.leads = leads;
        this.suppressions = suppressions;
    }

    public Summary build() {
        List<BrandContact> all = contacts.findAllByOrderByScoreDescIdAsc();
        Map<Long, List<ContactSource>> bySource = sources.findByContactIdIn(all.stream().map(c -> c.id).toList()).stream()
                .collect(Collectors.groupingBy(s -> s.contactId));
        Map<Long, Brand> byId = brands.findAllById(all.stream().map(c -> c.brandId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(b -> b.id, b -> b));
        Map<String, String> niches = niches();
        List<Row> rows = new ArrayList<>();
        int people = 0, paid = 0, dne = 0, other = 0;
        for (BrandContact c : all) {
            List<ContactSource> src = bySource.getOrDefault(c.id, List.of());
            Brand b = byId.get(c.brandId);
            if (!c.emailable() || suppressions.blocked(c.email)) dne++;
            else if (src.stream().anyMatch(s -> s.source.licensed())) paid++;
            else if (!ContactService.blank(c.name)) people++;
            else if (b == null || !ContactService.shareable(c, src) || !companyInbox(c) || !seenWorking(c, src)) other++;
            else rows.add(new Row(b.name, niches.get(b.nameKey), b.website, handle(b.instagram), c.email, label(c.role),
                    c.replies > 0, lastVerified(c, src)));
        }
        rows.sort(Comparator.comparing((Row r) -> r.brand().toLowerCase()).thenComparing(r -> !r.answered()));
        int brandCount = (int) rows.stream().map(r -> r.brand().toLowerCase()).distinct().count();
        return new Summary(rows, brandCount, people, paid, dne, other);
    }

    public String csv() {
        StringBuilder sb = new StringBuilder();
        line(sb, List.of("Brand", "Niche", "Website", "Instagram", "Collab inbox", "Inbox type", "Has answered creators",
                "Last verified"));
        for (Row r : build().rows()) {
            line(sb, List.of(r.brand(), n(r.niche()), n(r.website()), r.instagram() == null ? "" : "https://instagram.com/" + r.instagram(),
                    r.inbox(), r.inboxType(), r.answered() ? "yes" : "", r.lastVerified() == null ? "" : r.lastVerified().toString()));
        }
        return sb.toString();
    }

    /** A brand's own domain, not a personal Gmail or Outlook account that happens to be called collabs@. */
    static boolean companyInbox(BrandContact c) {
        String d = Emails.domainOf(c.email);
        return d != null && !Emails.isFreeMail(d);
    }

    /** An address the app made up (collabs@brand.com) only counts once it was verified or someone answered from it. */
    static boolean seenWorking(BrandContact c, List<ContactSource> src) {
        boolean onlyGuessed = src.stream().allMatch(s -> s.source == ContactSource.Kind.GUESS);
        return !onlyGuessed || c.replies > 0 || c.verified == Verified.VALID;
    }

    /** The most recent proof the inbox works: a verification, a reply, or where it was last seen published. */
    static LocalDate lastVerified(BrandContact c, List<ContactSource> src) {
        OffsetDateTime best = null;
        if (c.verified == Verified.VALID) best = c.verifiedAt;
        best = later(best, c.lastRepliedAt);
        for (ContactSource s : src) {
            if (s.source != ContactSource.Kind.GUESS) best = later(best, s.foundAt);
        }
        return best == null ? null : best.toLocalDate();
    }

    /** The search she used when Find brands turned the brand up ("vegan skincare"), newest first. */
    private Map<String, String> niches() {
        Map<String, String> out = new HashMap<>();
        List<BrandLead> list = new ArrayList<>(leads.findAll());
        list.sort(Comparator.comparing((BrandLead l) -> l.id).reversed());
        for (BrandLead l : list) {
            if (l.source != BrandLead.Source.WEB || ContactService.blank(l.searchQuery) || l.nameKey == null) continue;
            out.putIfAbsent(l.nameKey, ContactService.clip(l.searchQuery.strip(), 80));
        }
        return out;
    }

    static String label(BrandContact.Role r) {
        if (r == null) return "";
        return switch (r) {
            case PARTNERSHIPS -> "Partnerships";
            case PR -> "PR";
            case MARKETING -> "Marketing";
            case GENERAL -> "General";
            case SUPPORT -> "Support";
            case FOUNDER, OTHER -> "";
        };
    }

    private static String handle(String ig) {
        return ContactService.blank(ig) ? null : ig.strip().replaceFirst("^@", "");
    }

    private static OffsetDateTime later(OffsetDateTime a, OffsetDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.isAfter(a) ? b : a;
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    private static void line(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ContactCsv.cell(cells.get(i)));
        }
        sb.append("\r\n");
    }
}

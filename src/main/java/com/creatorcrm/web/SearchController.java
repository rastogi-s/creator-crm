package com.creatorcrm.web;

import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.Invoice;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.InvoiceRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The search box in the header: finds deals by brand, contact or campaign, past emails and DMs, and invoices.
 * One creator's data is small, so brands, deals and invoices are matched in memory; messages are searched in the database.
 */
@RestController
public class SearchController {

    /** kind is DEAL, MESSAGE or INVOICE. Message text is third-party: the UI renders it as plain text only. */
    public record Hit(String kind, String title, String detail, Long opportunityId, Long invoiceId) {}

    static final int MAX_PER_KIND = 8;
    private static final int SNIPPET = 140;

    private final BrandRepo brands;
    private final OpportunityRepo opportunities;
    private final MessageRepo messages;
    private final InvoiceRepo invoices;

    public SearchController(BrandRepo brands, OpportunityRepo opportunities, MessageRepo messages, InvoiceRepo invoices) {
        this.brands = brands;
        this.opportunities = opportunities;
        this.messages = messages;
        this.invoices = invoices;
    }

    @GetMapping("/api/search")
    public List<Hit> search(@RequestParam(defaultValue = "") String q) {
        String needle = q.strip().toLowerCase(Locale.ROOT);
        if (needle.length() < 2) return List.of();
        if (needle.length() > 100) needle = needle.substring(0, 100);

        Map<Long, Brand> brandById = new HashMap<>();
        brands.findAll().forEach(b -> brandById.put(b.id, b));
        List<Hit> hits = new ArrayList<>();

        // Deals: the brand's name or contact, or the deal's own text. Newest first, open and closed alike.
        final String n = needle;
        opportunities.findAll().stream()
                .sorted(Comparator.comparing((Opportunity o) -> o.updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .filter(o -> {
                    Brand b = brandById.get(o.brandId);
                    return matches(n, o.campaign, o.deliverables, o.nextStep, o.budgetText)
                            || (b != null && matches(n, b.name, b.contactName, b.contactEmail, b.instagram, b.website));
                })
                .limit(MAX_PER_KIND)
                .forEach(o -> {
                    Brand b = brandById.get(o.brandId);
                    String contact = b == null ? null : join(b.contactName, b.contactEmail, b.instagram == null ? null : "@" + b.instagram);
                    hits.add(new Hit("DEAL", brandName(b), join(o.status.label, o.campaign, contact), o.id, null));
                });

        // Emails and DMs, newest first, one hit per deal.
        Set<Long> seenDeals = new HashSet<>();
        for (Message m : messages.search("%" + escapeLike(needle) + "%", PageRequest.of(0, 50))) {
            if (hits.stream().filter(h -> h.kind().equals("MESSAGE")).count() >= MAX_PER_KIND) break;
            Opportunity o = opportunities.findFirstByConversationIdOrderByIdDesc(m.conversationId).orElse(null);
            if (o == null || !seenDeals.add(o.id)) continue;
            String who = m.senderName == null || m.senderName.isBlank() ? m.sender : m.senderName;
            String date = m.sentAt == null ? null : m.sentAt.toLocalDate().toString();
            hits.add(new Hit("MESSAGE", brandName(brandById.get(o.brandId)) + (m.subject == null || m.subject.isBlank() ? "" : " · " + m.subject),
                    join(date, who) + ": " + snippet(m.content, needle), o.id, null));
        }

        invoices.findAllByOrderByIssuedDateDescIdDesc().stream()
                .filter(i -> matches(n, i.number, i.billTo, i.billToEmail) || matches(n, brandName(brandById.get(i.brandId))))
                .limit(MAX_PER_KIND)
                .forEach(i -> hits.add(new Hit("INVOICE", "Invoice " + i.number,
                        join(brandName(brandById.get(i.brandId)), i.currency + " " + i.amount, i.status.name()), i.opportunityId, i.id)));
        return hits;
    }

    static boolean matches(String needle, String... fields) {
        for (String f : fields) if (f != null && f.toLowerCase(Locale.ROOT).contains(needle)) return true;
        return false;
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** About {@value #SNIPPET} characters of the text around the first match, on one line. */
    static String snippet(String text, String needle) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").strip();
        int at = flat.toLowerCase(Locale.ROOT).indexOf(needle);
        if (flat.length() <= SNIPPET) return flat;
        int start = Math.max(0, at < 0 ? 0 : at - SNIPPET / 3);
        int end = Math.min(flat.length(), start + SNIPPET);
        return (start > 0 ? "…" : "") + flat.substring(start, end) + (end < flat.length() ? "…" : "");
    }

    private static String brandName(Brand b) {
        return b == null ? "Unknown brand" : b.name;
    }

    private static String join(String... parts) {
        return String.join(" · ", Stream.of(parts).filter(Objects::nonNull).filter(p -> !p.isBlank()).toList());
    }
}

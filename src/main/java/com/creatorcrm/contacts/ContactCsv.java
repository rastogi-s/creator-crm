package com.creatorcrm.contacts;

import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.BrandContact.Role;
import com.creatorcrm.domain.ContactSource;
import com.creatorcrm.repo.BrandContactRepo;
import com.creatorcrm.repo.BrandDomainRepo;
import com.creatorcrm.repo.BrandRepo;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spreadsheet import and export for contacts. Import reads the columns Hunter, Apollo and most spreadsheets use,
 * shows what it would do first, then cleans and dedupes every row through {@link ContactService}.
 */
@Service
public class ContactCsv {
    public static final int MAX_ROWS = 20_000;

    /** Where an imported list came from. Lists bought from a finder service stay out of anything shared. */
    public enum Origin {
        OWN(ContactSource.Kind.IMPORT), HUNTER(ContactSource.Kind.HUNTER), APOLLO(ContactSource.Kind.APOLLO),
        OTHER_FINDER(ContactSource.Kind.FINDER);

        final ContactSource.Kind kind;

        Origin(ContactSource.Kind kind) {
            this.kind = kind;
        }
    }

    public record Row(int line, String brand, String website, String email, String name, String title, Role role,
                      String phone, String linkedin, String instagram, String action, String note) {}

    public record Preview(int added, int merged, int newBrands, int skipped, List<Row> rows) {}

    private static final Map<String, String> COLUMNS = new HashMap<>();

    static {
        for (String a : List.of("brand", "company", "company name", "organization", "organisation", "account name", "brand name"))
            COLUMNS.put(a, "brand");
        for (String a : List.of("website", "domain", "company domain", "company website", "url", "site"))
            COLUMNS.put(a, "website");
        for (String a : List.of("email", "email address", "work email", "e-mail", "contact email", "business email"))
            COLUMNS.put(a, "email");
        for (String a : List.of("name", "full name", "contact name", "contact")) COLUMNS.put(a, "name");
        COLUMNS.put("first name", "first");
        COLUMNS.put("firstname", "first");
        COLUMNS.put("last name", "last");
        COLUMNS.put("lastname", "last");
        for (String a : List.of("title", "job title", "position", "job position")) COLUMNS.put(a, "title");
        COLUMNS.put("role", "role");
        for (String a : List.of("phone", "phone number", "mobile", "work phone", "direct phone")) COLUMNS.put(a, "phone");
        for (String a : List.of("linkedin", "linkedin url", "person linkedin url", "linkedin profile")) COLUMNS.put(a, "linkedin");
        for (String a : List.of("instagram", "instagram handle", "ig")) COLUMNS.put(a, "instagram");
    }

    private final ContactService contacts;
    private final BrandContactRepo contactRepo;
    private final BrandRepo brands;
    private final BrandDomainRepo domains;
    private final Suppressions suppressions;

    public ContactCsv(ContactService contacts, BrandContactRepo contactRepo, BrandRepo brands, BrandDomainRepo domains,
                      Suppressions suppressions) {
        this.contacts = contacts;
        this.contactRepo = contactRepo;
        this.brands = brands;
        this.domains = domains;
        this.suppressions = suppressions;
    }

    /** What importing would do, row by row. Nothing is saved. */
    public Preview preview(String csv) {
        return plan(csv);
    }

    @Transactional
    public Preview importCsv(String csv, Origin origin) {
        Preview p = plan(csv);
        Map<String, Long> created = new HashMap<>();
        Set<Long> touched = new HashSet<>();
        for (Row r : p.rows()) {
            if (r.action().equals("SKIP")) continue;
            Long brandId = resolveBrand(r).map(b -> b.id).orElse(null);
            if (brandId == null) {
                String name = brandName(r);
                brandId = created.get(Brand.key(name));
                if (brandId == null) {
                    Brand b = new Brand();
                    b.name = name;
                    b.nameKey = Brand.key(name);
                    b.website = blank(r.website()) ? null : r.website().strip();
                    b.createdAt = OffsetDateTime.now();
                    brandId = brands.save(b).id;
                    created.put(b.nameKey, brandId);
                }
            }
            contacts.claimDomain(brandId, Emails.domainOfUrl(r.website()));
            Long id = brandId;
            contacts.save(id, new ContactService.Found(r.email(), r.name(), r.title(), r.role(), r.phone(), r.linkedin(),
                    r.instagram(), null, origin.kind, null)).ifPresent(c -> touched.add(c.brandId));
        }
        touched.forEach(contacts::refreshPrimary);
        return p;
    }

    private Preview plan(String csv) {
        List<List<String>> table = parse(csv == null ? "" : csv);
        if (table.isEmpty()) throw new IllegalArgumentException("That file is empty");
        if (table.size() - 1 > MAX_ROWS) throw new IllegalArgumentException("That's more than " + MAX_ROWS + " rows. Split it into smaller files.");
        List<String> head = table.get(0);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < head.size(); i++) {
            String k = COLUMNS.get(head.get(i).strip().toLowerCase(Locale.ROOT).replace('_', ' '));
            if (k != null) col.putIfAbsent(k, i);
        }
        if (!col.containsKey("email")) throw new IllegalArgumentException("Couldn't find an Email column. Name one column \"Email\".");

        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> newBrandKeys = new HashSet<>();
        int added = 0, merged = 0, skipped = 0;
        for (int i = 1; i < table.size(); i++) {
            List<String> t = table.get(i);
            if (t.stream().allMatch(String::isBlank)) continue;
            String raw = get(t, col, "email");
            String name = get(t, col, "name");
            if (blank(name)) name = (get(t, col, "first") + " " + get(t, col, "last")).strip();
            String title = get(t, col, "title");
            Role role = parseRole(get(t, col, "role"));
            Row base = new Row(i + 1, get(t, col, "brand"), get(t, col, "website"), raw, name, title, role,
                    get(t, col, "phone"), get(t, col, "linkedin"), get(t, col, "instagram"), "SKIP", null);
            String email = Emails.clean(raw);
            String note;
            String action;
            if (email == null) {
                action = "SKIP";
                note = blank(raw) ? "No email" : "Not a usable email";
            } else if (!seen.add(email)) {
                action = "SKIP";
                note = "Same email as an earlier row";
            } else if (suppressions.blocked(email)) {
                action = "SKIP";
                note = "On your do-not-email list";
            } else if (contactRepo.findByEmail(email).isPresent()) {
                action = "MERGE";
                note = "Already saved; new details fill in the blanks";
            } else {
                action = "ADD";
                Row probe = new Row(base.line(), base.brand(), base.website(), email, name, title, role, base.phone(),
                        base.linkedin(), base.instagram(), action, null);
                Optional<Brand> b = resolveBrand(probe);
                if (b.isPresent()) {
                    note = "Adds to " + b.get().name;
                } else {
                    String key = Brand.key(brandName(probe));
                    if (key.isEmpty()) {
                        action = "SKIP";
                        note = "No brand name, website or company email";
                    } else {
                        newBrandKeys.add(key);
                        note = "New brand: " + brandName(probe);
                    }
                }
            }
            if (action.equals("ADD")) added++;
            else if (action.equals("MERGE")) merged++;
            else skipped++;
            rows.add(new Row(base.line(), base.brand(), base.website(), email == null ? raw : email, name, title, role,
                    base.phone(), base.linkedin(), base.instagram(), action, note));
        }
        return new Preview(added, merged, newBrandKeys.size(), skipped, rows);
    }

    /** The brand a row belongs to: the one that has the email, owns its domain or website, or has its name. */
    private Optional<Brand> resolveBrand(Row r) {
        Optional<Brand> b = contacts.brandFor(r.email());
        if (b.isPresent()) return b;
        String d = Emails.domainOfUrl(r.website());
        if (d != null) {
            b = domains.findByDomain(d).flatMap(x -> brands.findById(x.brandId));
            if (b.isPresent()) return b;
        }
        if (!blank(r.brand())) return brands.findByNameKey(Brand.key(r.brand()));
        return Optional.empty();
    }

    /** The brand name a row gives, else one made from its website or company email ("glowberry.com" → "Glowberry"). */
    static String brandName(Row r) {
        if (!blank(r.brand())) return r.brand().strip();
        String d = Emails.domainOfUrl(r.website());
        if (d == null) d = Emails.brandDomain(Emails.clean(r.email()));
        if (d == null) return "";
        String n = d.substring(0, d.indexOf('.'));
        return n.isEmpty() ? "" : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    static Role parseRole(String s) {
        if (blank(s)) return null;
        try {
            return Role.valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Every contact with its ranking, for her own use (backups, other tools). */
    public String export(List<ContactService.View> views) {
        StringBuilder sb = new StringBuilder();
        line(sb, List.of("Brand", "Email", "Name", "Title", "Role", "Phone", "LinkedIn", "Instagram", "Verified", "Rank score",
                "Why", "Replies", "Emails sent", "Deals", "Last replied", "Last emailed", "Do not email", "Found in"));
        for (ContactService.View v : views) {
            BrandContact c = v.contact();
            line(sb, List.of(n(v.brandName()), c.email, n(c.name), n(c.title), c.role == null ? "" : c.role.name(), n(c.phone),
                    n(c.linkedin), n(c.instagram), c.verified.name(), String.valueOf(c.score), n(c.scoreReason),
                    String.valueOf(c.replies), String.valueOf(c.emailsSent), String.valueOf(c.dealsWon),
                    c.lastRepliedAt == null ? "" : c.lastRepliedAt.toLocalDate().toString(),
                    c.lastContactedAt == null ? "" : c.lastContactedAt.toLocalDate().toString(),
                    c.emailable() && v.doNotEmail() == null ? "" : "yes", String.join(" ", v.sources())));
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells.get(i)));
        }
        sb.append("\r\n");
    }

    /** Quotes a cell, and defuses text a spreadsheet would run as a formula. */
    static String cell(String v) {
        String s = v == null ? "" : v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** RFC 4180 CSV: quoted cells, doubled quotes, line breaks inside quotes. Also reads semicolon-separated files. */
    static List<List<String>> parse(String csv) {
        String text = csv.startsWith("﻿") ? csv.substring(1) : csv;
        int firstBreak = text.indexOf('\n');
        String firstLine = firstBreak < 0 ? text : text.substring(0, firstBreak);
        char sep = firstLine.chars().filter(ch -> ch == ';').count() > firstLine.chars().filter(ch -> ch == ',').count() ? ';' : ',';
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == sep) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                out.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            out.add(row);
        }
        out.removeIf(r -> r.stream().allMatch(String::isBlank));
        return out;
    }

    private static String get(List<String> row, Map<String, Integer> col, String key) {
        Integer i = col.get(key);
        return i == null || i >= row.size() ? "" : row.get(i).strip();
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

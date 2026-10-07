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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spreadsheet import and export for contacts. Import guesses which column is which from the names Hunter, Apollo,
 * Google, Outlook and most spreadsheets use, lets her change that, shows what it would do first, then cleans and dedupes
 * every row through {@link ContactService}. {@link ContactFiles} turns other files (Excel, contact cards, pictures)
 * into the same grid.
 */
@Service
public class ContactCsv {
    public static final int MAX_ROWS = 20_000;

    /**
     * Where an imported list came from. Lists bought from a finder service, or bought or got from someone else, stay
     * out of anything shared.
     */
    public enum Origin {
        OWN(ContactSource.Kind.IMPORT), THIRD_PARTY(ContactSource.Kind.THIRD_PARTY), HUNTER(ContactSource.Kind.HUNTER),
        APOLLO(ContactSource.Kind.APOLLO), OTHER_FINDER(ContactSource.Kind.FINDER);

        final ContactSource.Kind kind;

        Origin(ContactSource.Kind kind) {
            this.kind = kind;
        }
    }

    public record Row(int line, String brand, String website, String email, String name, String title, Role role,
                      String phone, String linkedin, String instagram, String action, String note) {}

    /** {@code mapping}: which column each field was read from (field → column number, from 0). */
    public record Preview(int added, int merged, int newBrands, int skipped, List<Row> rows, Map<String, Integer> mapping) {}

    /**
     * A file read as a grid. {@code firstLine} is the line number of the first row (2 below a header row), so skipped
     * rows can be named the way she sees them in Excel.
     */
    public record Table(List<String> headers, List<List<String>> rows, int firstLine) {}

    /** The fields a column can be read as. "first" and "last" are joined into the name. */
    public static final List<String> FIELDS = List.of("email", "name", "first", "last", "title", "brand", "website", "role",
            "phone", "instagram", "linkedin");

    private static final Map<String, String> COLUMNS = new HashMap<>();

    static {
        for (String a : List.of("brand", "company", "company name", "organization", "organisation", "account name", "brand name",
                "organization 1 - name", "business", "employer"))
            COLUMNS.put(a, "brand");
        for (String a : List.of("website", "domain", "company domain", "company website", "url", "site", "web page", "web site",
                "website 1 - value", "homepage"))
            COLUMNS.put(a, "website");
        for (String a : List.of("email", "email address", "work email", "e-mail", "contact email", "business email",
                "e-mail address", "e-mail 1 - value", "email 1", "e-mail 1", "email 1 - value", "mail", "emails"))
            COLUMNS.put(a, "email");
        for (String a : List.of("name", "full name", "contact name", "contact", "display name", "person")) COLUMNS.put(a, "name");
        for (String a : List.of("first name", "firstname", "given name", "first")) COLUMNS.put(a, "first");
        for (String a : List.of("last name", "lastname", "family name", "surname", "last")) COLUMNS.put(a, "last");
        for (String a : List.of("title", "job title", "position", "job position", "organization 1 - title", "designation", "job"))
            COLUMNS.put(a, "title");
        COLUMNS.put("role", "role");
        for (String a : List.of("phone", "phone number", "mobile", "work phone", "direct phone", "business phone", "mobile phone",
                "phone 1 - value", "telephone", "tel", "cell"))
            COLUMNS.put(a, "phone");
        for (String a : List.of("linkedin", "linkedin url", "person linkedin url", "linkedin profile")) COLUMNS.put(a, "linkedin");
        for (String a : List.of("instagram", "instagram handle", "ig", "instagram url", "insta")) COLUMNS.put(a, "instagram");
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

    /** What importing a CSV would do, row by row, with the columns guessed from their names. Nothing is saved. */
    public Preview preview(String csv) {
        Table t = table(parse(csv == null ? "" : csv));
        return preview(t, guessMapping(t));
    }

    @Transactional
    public Preview importCsv(String csv, Origin origin) {
        Table t = table(parse(csv == null ? "" : csv));
        return importTable(t, guessMapping(t), origin, null);
    }

    /** What importing would do with these columns. Nothing is saved. */
    public Preview preview(Table t, Map<String, Integer> mapping) {
        return plan(t, mapping);
    }

    /** Saves every usable row. {@code fileName} is kept as where the contacts came from. */
    @Transactional
    public Preview importTable(Table t, Map<String, Integer> mapping, Origin origin, String fileName) {
        Preview p = plan(t, mapping);
        String from = blank(fileName) ? null : "file:" + fileName.strip();
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
                    r.instagram(), null, origin.kind, from)).ifPresent(c -> touched.add(c.brandId));
        }
        touched.forEach(contacts::refreshPrimary);
        return p;
    }

    /**
     * A grid with its header row, or with made-up headers ("Column 1") when the first row already holds an email
     * address, as in a list copied without its headings.
     */
    public static Table table(List<List<String>> raw) {
        List<List<String>> rows = new ArrayList<>(raw);
        rows.removeIf(r -> r.stream().allMatch(c -> c == null || c.isBlank()));
        if (rows.isEmpty()) throw new IllegalArgumentException("That file is empty");
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        List<String> first = rows.get(0);
        boolean headerless = first.stream().anyMatch(c -> Emails.IN_TEXT.matcher(c == null ? "" : c).find());
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            String h = headerless || i >= first.size() || first.get(i) == null ? "" : first.get(i).strip();
            headers.add(h.isEmpty() ? "Column " + (i + 1) : h);
        }
        List<List<String>> data = headerless ? rows : rows.subList(1, rows.size());
        if (data.size() > MAX_ROWS) throw new IllegalArgumentException("That's more than " + MAX_ROWS + " rows. Split it into smaller files.");
        return new Table(headers, new ArrayList<>(data), headerless ? 1 : 2);
    }

    /** Which column holds what, from the column names; the email column is found by its contents if its name isn't known. */
    public static Map<String, Integer> guessMapping(Table t) {
        Map<String, Integer> col = new LinkedHashMap<>();
        for (int i = 0; i < t.headers().size(); i++) {
            String k = COLUMNS.get(headerKey(t.headers().get(i)));
            if (k != null) col.putIfAbsent(k, i);
        }
        if (!col.containsKey("email")) {
            int best = -1, bestCount = 0;
            for (int i = 0; i < t.headers().size(); i++) {
                int count = 0;
                for (List<String> r : t.rows().subList(0, Math.min(50, t.rows().size()))) {
                    if (i < r.size() && r.get(i) != null && Emails.clean(r.get(i)) != null) count++;
                }
                if (count > bestCount) {
                    best = i;
                    bestCount = count;
                }
            }
            if (best >= 0) {
                int emailCol = best;
                col.values().removeIf(i -> i == emailCol); // "Contact" read as a name column, but it holds the emails
                col.put("email", emailCol);
            }
        }
        return col;
    }

    static String headerKey(String h) {
        return h == null ? "" : h.strip().toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ").replaceFirst(":$", "");
    }

    private Preview plan(Table t, Map<String, Integer> mapping) {
        Map<String, Integer> col = new LinkedHashMap<>();
        if (mapping != null) {
            mapping.forEach((field, i) -> {
                if (i != null && i >= 0 && i < t.headers().size() && FIELDS.contains(field)) col.put(field, i);
            });
        }
        if (!col.containsKey("email")) throw new IllegalArgumentException("Pick which column has the email addresses.");
        if (t.rows().size() > MAX_ROWS) throw new IllegalArgumentException("That's more than " + MAX_ROWS + " rows. Split it into smaller files.");

        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> newBrandKeys = new HashSet<>();
        int added = 0, merged = 0, skipped = 0;
        for (int i = 0; i < t.rows().size(); i++) {
            List<String> r = t.rows().get(i);
            if (r.stream().allMatch(c -> c == null || c.isBlank())) continue;
            String raw = get(r, col, "email");
            String name = get(r, col, "name");
            if (blank(name)) name = (get(r, col, "first") + " " + get(r, col, "last")).strip();
            String title = get(r, col, "title");
            Role role = parseRole(get(r, col, "role"));
            String instagram = get(r, col, "instagram").replaceFirst("(?i)^.*instagram\\.com/", "").replaceAll("[/?].*$", "");
            Row base = new Row(t.firstLine() + i, get(r, col, "brand"), get(r, col, "website"), raw, name, title, role,
                    get(r, col, "phone"), get(r, col, "linkedin"), instagram, "SKIP", null);
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
        return new Preview(added, merged, newBrandKeys.size(), skipped, rows, col);
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

    /** RFC 4180 CSV: quoted cells, doubled quotes, line breaks inside quotes. Also reads semicolon- and tab-separated files. */
    static List<List<String>> parse(String csv) {
        String text = csv.startsWith("﻿") ? csv.substring(1) : csv;
        int firstBreak = text.indexOf('\n');
        String firstLine = firstBreak < 0 ? text : text.substring(0, firstBreak);
        char sep = ',';
        long most = firstLine.chars().filter(ch -> ch == ',').count();
        for (char c : new char[] {';', '\t'}) {
            long n = firstLine.chars().filter(ch -> ch == c).count();
            if (n > most) {
                sep = c;
                most = n;
            }
        }
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
        return i == null || i >= row.size() || row.get(i) == null ? "" : row.get(i).strip();
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

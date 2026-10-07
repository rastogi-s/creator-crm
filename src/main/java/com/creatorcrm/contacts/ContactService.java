package com.creatorcrm.contacts;

import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.BrandContact.Role;
import com.creatorcrm.domain.BrandDomain;
import com.creatorcrm.domain.ContactSource;
import com.creatorcrm.domain.Suppression;
import com.creatorcrm.repo.BrandContactRepo;
import com.creatorcrm.repo.BrandDomainRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.ContactSourceRepo;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The brand contacts database: many people per brand, one row per email address, merged when the same person turns
 * up again, ranked so the best person to pitch comes first. Every way a contact enters the app (Gmail, a lead, a CSV,
 * the website reader, a finder service) goes through {@link #add}, so the cleaning and dedupe rules live in one place.
 */
@Service
public class ContactService {

    /** A contact as one source saw it. Blank fields are ignored when merging into an existing contact. */
    public record Found(String email, String name, String title, Role role, String phone, String linkedin,
                        String instagram, Integer confidence, ContactSource.Kind source, String sourceUrl) {
        public static Found of(String email, String name, ContactSource.Kind source, String sourceUrl) {
            return new Found(email, name, null, null, null, null, null, null, source, sourceUrl);
        }
    }

    /** A contact with what the screens need beside it. {@code shareable} = may appear in a shared brand directory. */
    public record View(BrandContact contact, String brandName, List<String> sources, boolean shareable, String doNotEmail) {}

    /** Two brands that look like the same company, with why. */
    public record Duplicate(Long brandId, String brandName, Long otherBrandId, String otherBrandName, String why) {}

    private final BrandContactRepo contacts;
    private final ContactSourceRepo sources;
    private final BrandDomainRepo domains;
    private final BrandRepo brands;
    private final Suppressions suppressions;
    private final JdbcTemplate jdbc;

    public ContactService(BrandContactRepo contacts, ContactSourceRepo sources, BrandDomainRepo domains, BrandRepo brands,
                          Suppressions suppressions, JdbcTemplate jdbc) {
        this.contacts = contacts;
        this.sources = sources;
        this.domains = domains;
        this.brands = brands;
        this.suppressions = suppressions;
        this.jdbc = jdbc;
    }

    /**
     * Saves a contact for a brand, or merges it into the one already saved with that address. Returns empty when the
     * address isn't usable or the person asked to be forgotten. Never moves an existing contact to another brand.
     */
    @Transactional
    public Optional<BrandContact> add(Long brandId, Found f) {
        Optional<BrandContact> saved = save(brandId, f);
        saved.ifPresent(c -> refreshPrimary(c.brandId));
        return saved;
    }

    /** {@link #add} without updating the brand's main contact; callers adding many contacts do that once at the end. */
    @Transactional
    public Optional<BrandContact> save(Long brandId, Found f) {
        String email = Emails.clean(f.email());
        if (email == null || brandId == null) return Optional.empty();
        Optional<Suppression> sup = suppressions.find(email);
        if (sup.isPresent() && sup.get().reason == Suppression.Reason.FORGET_ME && sup.get().kind == Suppression.Kind.EMAIL) {
            return Optional.empty();
        }
        OffsetDateTime now = OffsetDateTime.now();
        BrandContact c = contacts.findByEmail(email).orElseGet(() -> {
            BrandContact n = new BrandContact();
            n.brandId = brandId;
            n.email = email;
            n.createdAt = now;
            return n;
        });
        if (blank(c.name) && !blank(f.name()) && !f.name().contains("@")) c.name = clip(f.name().strip(), 200);
        if (blank(c.title) && !blank(f.title())) c.title = clip(f.title().strip(), 200);
        if (blank(c.phone) && !blank(f.phone())) c.phone = clip(f.phone().strip(), 50);
        if (blank(c.linkedin) && !blank(f.linkedin())) c.linkedin = clip(f.linkedin().strip(), 300);
        if (blank(c.instagram) && !blank(f.instagram())) c.instagram = clip(f.instagram().strip().replaceFirst("^@", ""), 100);
        Role found = f.role() != null ? f.role() : ContactRoles.guess(email, c.title);
        c.role = ContactRoles.better(c.role, found);
        if (f.confidence() != null) c.confidence = c.confidence == null ? f.confidence() : Math.max(c.confidence, f.confidence());
        if (sup.isPresent()) {
            if (sup.get().reason == Suppression.Reason.BOUNCED) c.bounced = true;
            else c.optedOut = true;
        }
        c.updatedAt = now;
        rescore(c, now);
        c = contacts.save(c);
        if (f.source() != null) addSource(c.id, f.source(), f.sourceUrl(), now);
        claimDomain(c.brandId, Emails.brandDomain(email));
        return Optional.of(c);
    }

    private void addSource(Long contactId, ContactSource.Kind kind, String url, OffsetDateTime now) {
        String u = blank(url) ? null : clip(url.strip(), 1000);
        boolean seen = sources.findByContactIdOrderByFoundAtAsc(contactId).stream()
                .anyMatch(s -> s.source == kind && Objects.equals(s.sourceUrl, u));
        if (seen) return;
        ContactSource s = new ContactSource();
        s.contactId = contactId;
        s.source = kind;
        s.sourceUrl = u;
        s.foundAt = now;
        sources.save(s);
    }

    /** Records that a brand owns a web domain. A domain another brand already owns stays with it (see {@link #duplicates}). */
    @Transactional
    public void claimDomain(Long brandId, String domain) {
        if (brandId == null || domain == null || Emails.isFreeMail(domain)) return;
        if (domains.findByDomain(domain).isPresent()) return;
        BrandDomain d = new BrandDomain();
        d.brandId = brandId;
        d.domain = domain;
        domains.save(d);
    }

    /** The brand an address belongs to: the brand that already has it, else the brand owning its domain. */
    public Optional<Brand> brandFor(String email) {
        String e = Emails.clean(email);
        if (e == null) return Optional.empty();
        Optional<Long> id = contacts.findByEmail(e).map(c -> c.brandId);
        if (id.isEmpty()) {
            String d = Emails.brandDomain(e);
            if (d != null) id = domains.findByDomain(d).map(x -> x.brandId);
        }
        return id.flatMap(brands::findById);
    }

    public List<View> forBrand(Long brandId) {
        return views(contacts.findByBrandIdOrderByScoreDescIdAsc(brandId));
    }

    public List<View> all() {
        return views(contacts.findAllByOrderByScoreDescIdAsc());
    }

    private List<View> views(List<BrandContact> list) {
        Map<Long, String> names = brands.findAllById(list.stream().map(c -> c.brandId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(b -> b.id, b -> b.name));
        Map<Long, List<ContactSource>> bySource = sources.findByContactIdIn(list.stream().map(c -> c.id).toList()).stream()
                .collect(Collectors.groupingBy(s -> s.contactId));
        List<View> out = new ArrayList<>();
        for (BrandContact c : list) {
            List<ContactSource> src = bySource.getOrDefault(c.id, List.of());
            List<String> kinds = src.stream().map(s -> s.source.name()).distinct().toList();
            out.add(new View(c, names.get(c.brandId), kinds, shareable(c, src),
                    suppressions.find(c.email).map(Suppressions::explain).orElse(null)));
        }
        return out;
    }

    /**
     * Whether a contact may appear in a directory shared with other people: only role inboxes (collabs@, pr@), never
     * a named person, never anything a finder service supplied (their terms forbid resale), never a do-not-email one.
     */
    public static boolean shareable(BrandContact c, List<ContactSource> src) {
        if (!c.emailable() || src.isEmpty()) return false;
        if (src.stream().anyMatch(s -> s.source.licensed())) return false;
        if (!blank(c.name)) return false;
        Role r = c.role == null ? Role.OTHER : c.role;
        return r != Role.OTHER && r != Role.FOUNDER;
    }

    @Transactional
    public BrandContact update(Long id, String name, String title, Role role, String phone, String notes) {
        BrandContact c = contacts.findById(id).orElseThrow(() -> new IllegalArgumentException("That contact no longer exists"));
        c.name = blank(name) ? null : clip(name.strip(), 200);
        c.title = blank(title) ? null : clip(title.strip(), 200);
        if (role != null) c.role = role;
        c.phone = blank(phone) ? null : clip(phone.strip(), 50);
        c.notes = blank(notes) ? null : clip(notes.strip(), 2000);
        c.updatedAt = OffsetDateTime.now();
        rescore(c, c.updatedAt);
        c = contacts.save(c);
        refreshPrimary(c.brandId);
        return c;
    }

    /** Never email this person again. Kept in the list so it can't creep back in from Gmail or a new import. */
    @Transactional
    public BrandContact doNotEmail(Long id, Suppression.Reason reason) {
        BrandContact c = contacts.findById(id).orElseThrow(() -> new IllegalArgumentException("That contact no longer exists"));
        suppressions.add(c.email, reason);
        if (reason == Suppression.Reason.BOUNCED) c.bounced = true;
        else c.optedOut = true;
        c.updatedAt = OffsetDateTime.now();
        rescore(c, c.updatedAt);
        c = contacts.save(c);
        refreshPrimary(c.brandId);
        return c;
    }

    /** Marks an address do-not-email wherever it is, e.g. when someone replies "please remove me". */
    @Transactional
    public void doNotEmail(String email, Suppression.Reason reason) {
        String e = Emails.clean(email);
        if (e == null) return;
        Optional<BrandContact> c = contacts.findByEmail(e);
        if (c.isPresent()) doNotEmail(c.get().id, reason);
        else suppressions.add(e, reason);
    }

    /**
     * "Forget me" (GDPR erasure): deletes the person and where they came from, and keeps only their address on the
     * do-not-email list so they are never collected or emailed again.
     */
    @Transactional
    public void forget(Long id) {
        BrandContact c = contacts.findById(id).orElseThrow(() -> new IllegalArgumentException("That contact no longer exists"));
        suppressions.add(c.email, Suppression.Reason.FORGET_ME);
        sources.deleteByContactId(c.id);
        contacts.delete(c);
        contacts.flush();
        brands.findById(c.brandId).ifPresent(b -> {
            if (c.email.equalsIgnoreCase(b.contactEmail == null ? "" : b.contactEmail.strip())) {
                b.contactEmail = null;
                if (c.name != null && c.name.equals(b.contactName)) b.contactName = null;
                brands.save(b);
            }
        });
        refreshPrimary(c.brandId);
    }

    /** Recomputes one contact's score. */
    public static void rescore(BrandContact c, OffsetDateTime now) {
        ContactRanking.Score s = ContactRanking.score(c, now);
        c.score = s.score();
        c.scoreReason = clip(s.reason(), 300);
    }

    /**
     * Keeps the brand's main contact (used as "To" for new pitches) pointing at its best-ranked address that may be
     * emailed. A main contact she typed in herself stays unless a better-ranked one exists or it can't be emailed.
     */
    @Transactional
    public void refreshPrimary(Long brandId) {
        Brand b = brands.findById(brandId).orElse(null);
        if (b == null) return;
        List<BrandContact> list = contacts.findByBrandIdOrderByScoreDescIdAsc(brandId);
        BrandContact best = list.stream().filter(BrandContact::emailable)
                .filter(c -> !suppressions.blocked(c.email)).findFirst().orElse(null);
        String current = Emails.clean(b.contactEmail);
        BrandContact currentRow = current == null ? null : list.stream().filter(c -> c.email.equals(current)).findFirst().orElse(null);
        boolean currentBlocked = current != null && (suppressions.blocked(current) || (currentRow != null && !currentRow.emailable()));
        boolean replace = current == null || currentBlocked || (best != null && currentRow != null && best.score > currentRow.score);
        if (!replace) return;
        if (best == null) {
            if (currentBlocked) {
                b.contactEmail = null;
                brands.save(b);
            }
            return;
        }
        if (best.email.equals(current)) return;
        b.contactEmail = best.email;
        if (!blank(best.name)) b.contactName = best.name;
        brands.save(b);
    }

    /** Brands that look like the same company: one brand's contact uses a domain another brand owns. */
    public List<Duplicate> duplicates() {
        Map<String, Duplicate> out = new LinkedHashMap<>();
        Map<Long, Brand> byId = brands.findAll().stream().collect(Collectors.toMap(b -> b.id, b -> b));
        for (BrandContact c : contacts.findAll()) {
            String d = Emails.brandDomain(c.email);
            if (d == null) continue;
            domains.findByDomain(d).filter(x -> !x.brandId.equals(c.brandId)).ifPresent(x -> {
                Brand a = byId.get(c.brandId), o = byId.get(x.brandId);
                if (a == null || o == null) return;
                String key = Math.min(a.id, o.id) + ":" + Math.max(a.id, o.id);
                out.putIfAbsent(key, new Duplicate(a.id, a.name, o.id, o.name, c.email + " at " + a.name + " uses " + o.name + "'s website " + d));
            });
        }
        return new ArrayList<>(out.values());
    }

    /** Folds one brand into another: its deals, emails, invoices, contacts and domains move over, then it is removed. */
    @Transactional
    public Brand merge(Long keepId, Long dropId) {
        if (Objects.equals(keepId, dropId)) throw new IllegalArgumentException("Pick two different brands");
        Brand keep = brands.findById(keepId).orElseThrow(() -> new IllegalArgumentException("That brand no longer exists"));
        Brand drop = brands.findById(dropId).orElseThrow(() -> new IllegalArgumentException("That brand no longer exists"));
        contacts.flush();
        for (String table : List.of("conversations", "opportunities", "invoices", "brand_contacts", "brand_domains")) {
            jdbc.update("UPDATE " + table + " SET brand_id = ? WHERE brand_id = ?", keepId, dropId);
        }
        if (blank(keep.website)) keep.website = drop.website;
        if (blank(keep.instagram)) keep.instagram = drop.instagram;
        if (blank(keep.contactName)) keep.contactName = drop.contactName;
        if (blank(keep.contactEmail)) keep.contactEmail = drop.contactEmail;
        if (!blank(drop.notes)) keep.notes = blank(keep.notes) ? drop.notes : keep.notes + "\n" + drop.notes;
        if (keep.lastRepitchAt == null || (drop.lastRepitchAt != null && drop.lastRepitchAt.isAfter(keep.lastRepitchAt))) {
            keep.lastRepitchAt = drop.lastRepitchAt;
        }
        brands.save(keep);
        brands.delete(drop);
        brands.flush();
        refreshPrimary(keepId);
        return keep;
    }

    /** Re-ranks everyone, e.g. after the reply counts were refreshed. Returns how many contacts there are. */
    @Transactional
    public int rescoreAll() {
        OffsetDateTime now = OffsetDateTime.now();
        List<BrandContact> list = contacts.findAll();
        for (BrandContact c : list) rescore(c, now);
        contacts.saveAll(list);
        list.stream().map(c -> c.brandId).distinct().forEach(this::refreshPrimary);
        return list.size();
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}

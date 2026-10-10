package com.creatorcrm.contacts;

import com.creatorcrm.contacts.FinderCredits.Provider;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.BrandContact.Verified;
import com.creatorcrm.domain.BrandDomain;
import com.creatorcrm.domain.BrandLead;
import com.creatorcrm.domain.ContactSource;
import com.creatorcrm.repo.BrandContactRepo;
import com.creatorcrm.repo.BrandDomainRepo;
import com.creatorcrm.repo.BrandLeadRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.security.SecretName;
import com.creatorcrm.security.SecretStore;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Finds more people at a brand with Hunter (and Apollo, if she added a key), and checks that addresses exist before
 * she emails them. Plain API calls: no Claude tokens. Every person found goes through {@link ContactService#save},
 * so the usual cleaning, dedupe, do-not-email and ranking rules apply, and is tagged with where it came from:
 * HUNTER and APOLLO contacts are licensed for her own outreach and are never shared.
 *
 * <p>Checking: someone who has replied is proven; a domain with no mail server is INVALID for free; otherwise Hunter's
 * verifier says VALID, RISKY (the domain takes everything, so it can't tell) or INVALID. INVALID is never emailed.
 */
@Service
public class ContactFinder {
    private static final Logger log = LoggerFactory.getLogger(ContactFinder.class);

    /** Free tier answers at most 10 people per search. */
    static final int HUNTER_LIMIT = 10;
    /** Apollo reveals cost a credit each; at most this many per brand per run. */
    static final int APOLLO_REVEALS = 3;
    /** A domain Hunter was asked about this recently isn't asked again unless she says so. */
    static final int RESEARCH_DAYS = 30;
    /** Check addresses again after this long without being emailed. */
    public static final int STALE_DAYS = 180;

    /**
     * What one "Find more people" did. {@code askFirst} is set (and nothing was spent) when the brand was searched
     * recently: the screen asks before paying again.
     */
    public record FindResult(int found, int added, int alreadyKnown, int skipped, double hunterCredits,
                             double apolloCredits, String domain, List<String> notes, String askFirst,
                             List<ContactService.View> contacts) {}

    /** One address checked. */
    public record CheckResult(BrandContact contact, String message, double credits) {}

    public record BrandCheck(int checked, int valid, int risky, int invalid, double credits, List<String> notes,
                             List<ContactService.View> contacts) {}

    private final ContactService contacts;
    private final BrandContactRepo contactRepo;
    private final BrandRepo brands;
    private final BrandDomainRepo domains;
    private final SecretStore secrets;
    private final HunterApi hunter;
    private final ApolloApi apollo;
    private final MailServers mail;
    private final FinderCredits credits;
    private final BrandLeadRepo leads;
    private final Suppressions suppressions;

    public ContactFinder(ContactService contacts, BrandContactRepo contactRepo, BrandRepo brands, BrandDomainRepo domains,
                         SecretStore secrets, HunterApi hunter, ApolloApi apollo, MailServers mail, FinderCredits credits,
                         BrandLeadRepo leads, Suppressions suppressions) {
        this.leads = leads;
        this.suppressions = suppressions;
        this.contacts = contacts;
        this.contactRepo = contactRepo;
        this.brands = brands;
        this.domains = domains;
        this.secrets = secrets;
        this.hunter = hunter;
        this.apollo = apollo;
        this.mail = mail;
        this.credits = credits;
    }

    public boolean hunterConnected() {
        return secrets.has(SecretName.HUNTER_API_KEY);
    }

    public boolean apolloConnected() {
        return secrets.has(SecretName.APOLLO_API_KEY);
    }

    /** The brand's web domain: one it already owns, else its website, else its main contact's work address. */
    public Optional<String> domainFor(Brand b) {
        String site = Emails.domainOfUrl(b.website);
        List<BrandDomain> owned = domains.findByBrandId(b.id);
        if (site != null && owned.stream().anyMatch(d -> d.domain.equals(site))) return Optional.of(site);
        if (!owned.isEmpty()) return Optional.of(owned.get(0).domain);
        if (site != null) return Optional.of(site);
        return Optional.ofNullable(Emails.brandDomain(b.contactEmail));
    }

    /** "Find more people at this brand". {@code again}: search even though it was searched recently. */
    public FindResult findMore(Long brandId, boolean again) {
        Brand b = brands.findById(brandId).orElseThrow(() -> new IllegalArgumentException("That brand no longer exists"));
        boolean useHunter = hunterConnected(), useApollo = apolloConnected();
        if (!useHunter && !useApollo) {
            throw new IllegalStateException("Add a Hunter key first, in Settings, Accounts, Contact finders. Hunter's free plan finds people at 50 brands a month.");
        }
        String domain = domainFor(b).orElseThrow(() -> new IllegalArgumentException(
                "Add " + b.name + "'s website first (on the brand), so the finders know where to look."));
        if (!again) {
            Optional<LocalDate> last = credits.searchedOn(useHunter ? Provider.HUNTER : Provider.APOLLO, domain);
            if (last.isPresent() && ChronoUnit.DAYS.between(last.get(), LocalDate.now()) < RESEARCH_DAYS) {
                return new FindResult(0, 0, 0, 0, 0, 0, domain, List.of(),
                        "You already looked up " + domain + " on " + last.get() + ". Looking again costs another credit and usually finds the same people. Look again anyway?",
                        contacts.forBrand(b.id));
            }
        }

        Tally t = new Tally();
        List<String> notes = new ArrayList<>();
        Set<Long> touched = new LinkedHashSet<>();
        double hunterCost = 0, apolloCost = 0;

        if (useHunter) {
            try {
                hunterCost = viaHunter(b, domain, t, touched, notes);
            } catch (IllegalStateException e) {
                if (!useApollo) throw e; // nothing else to try: say so plainly
                notes.add(e.getMessage());
            }
        }
        if (useApollo && t.added < 3) {
            try {
                apolloCost = viaApollo(b, domain, t, touched, notes);
            } catch (IllegalStateException e) {
                notes.add(e.getMessage());
            }
        }
        // Free check on everyone new that Hunter or Apollo didn't already prove
        for (Long id : touched) contactRepo.findById(id).filter(c -> c.verified == Verified.UNKNOWN).ifPresent(this::mailServerOnly);
        contacts.refreshPrimary(b.id);
        if (t.found == 0 && notes.isEmpty()) notes.add("Nobody new turned up for " + domain + ". The brand's own contact page may list a collabs@ inbox.");
        log.info("Contact finder for brand {} ({}): {} found, {} new, {} known, {} skipped", b.id, domain, t.found, t.added, t.known, t.skipped);
        return new FindResult(t.found, t.added, t.known, t.skipped, hunterCost, apolloCost, domain, notes, null,
                contacts.forBrand(b.id));
    }

    /** What "Find email" did for a brand lead. {@code email}: the address the pitch will go to, or null. */
    public record LeadResult(int found, String email, double hunterCredits, double apolloCredits, List<String> notes,
                             String askFirst) {}

    /**
     * "Find email" on a brand lead with no published address. Leads aren't brands yet, so the best person found
     * becomes the lead's contact, and everyone found is kept on the lead until she pitches it ({@link #importLeadPeople}).
     */
    public LeadResult findForLead(Long leadId, boolean again) {
        BrandLead lead = leads.findById(leadId).orElseThrow(() -> new IllegalArgumentException("That suggestion no longer exists"));
        boolean useHunter = hunterConnected(), useApollo = apolloConnected();
        if (!useHunter && !useApollo) {
            throw new IllegalStateException("Add a Hunter key first, in Settings, Accounts, Contact finders. Hunter's free plan finds people at 50 brands a month.");
        }
        String domain = Optional.ofNullable(Emails.domainOfUrl(lead.website)).orElseGet(() -> Emails.brandDomain(lead.contactEmail));
        if (domain == null) throw new IllegalArgumentException("Add " + lead.name + "'s website first (Edit contact), so the finders know where to look.");
        if (!again) {
            Optional<LocalDate> last = credits.searchedOn(useHunter ? Provider.HUNTER : Provider.APOLLO, domain);
            if (last.isPresent() && ChronoUnit.DAYS.between(last.get(), LocalDate.now()) < RESEARCH_DAYS) {
                return new LeadResult(0, lead.contactEmail, 0, 0, List.of(), "You already looked up " + domain + " on " + last.get()
                        + ". Looking again costs another credit and usually finds the same people. Look again anyway?");
            }
        }
        List<Person> found = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        double hunterCost = 0, apolloCost = 0;
        if (useHunter) {
            try {
                Search s = hunterSearch(domain);
                found.addAll(s.people());
                notes.addAll(s.notes());
                hunterCost = s.cost();
            } catch (IllegalStateException e) {
                if (!useApollo) throw e;
                notes.add(e.getMessage());
            }
        }
        if (useApollo && usable(found).size() < 3) {
            try {
                Search s = apolloSearch(domain);
                found.addAll(s.people());
                notes.addAll(s.notes());
                apolloCost = s.cost();
            } catch (IllegalStateException e) {
                notes.add(e.getMessage());
            }
        }
        if (!found.isEmpty() && mail.check(domain) == MailServers.Result.NO_MAIL) {
            found = found.stream().map(p -> withVerified(p, Verified.INVALID)).toList();
            notes.add(domain + " can't receive email, so none of these addresses will be emailed.");
        }
        Map<String, Person> kept = new LinkedHashMap<>();
        for (Person p : readPeople(lead.finderContacts)) kept.put(p.email(), p);
        for (Person p : found) {
            String e = Emails.clean(p.email());
            if (e != null) kept.put(e, new Person(e, p.name(), p.title(), p.phone(), p.linkedin(), p.confidence(), p.source(),
                    p.sourceUrl(), p.verified(), p.verifiedAt()));
        }
        lead.finderContacts = kept.isEmpty() ? null : writePeople(new ArrayList<>(kept.values()));
        List<Person> best = usable(new ArrayList<>(kept.values()));
        boolean filled = false;
        if ((lead.contactEmail == null || lead.contactEmail.isBlank()) && !best.isEmpty()) {
            lead.contactEmail = best.get(0).email();
            lead.contactSourceUrl = best.get(0).sourceUrl();
            filled = true;
        }
        leads.save(lead);
        int n = usable(found).size();
        String head = n == 0 ? "Nobody with a usable email turned up at " + domain + "."
                : "Found " + n + (n == 1 ? " person" : " people") + " at " + domain + "."
                + (filled ? " Using " + lead.contactEmail + " for this pitch." : "");
        List<String> all = new ArrayList<>();
        all.add(head);
        all.addAll(notes);
        log.info("Contact finder for lead {} ({}): {} found", lead.id, domain, found.size());
        return new LeadResult(n, lead.contactEmail, hunterCost, apolloCost, all, null);
    }

    /** People who may be emailed, best to pitch first: partnerships, then PR, marketing; proven addresses first. */
    List<Person> usable(List<Person> people) {
        return people.stream()
                .filter(p -> Emails.clean(p.email()) != null && p.verified() != Verified.INVALID && !suppressions.blocked(p.email()))
                .sorted(Comparator.comparingInt((Person p) -> WebsiteContacts.rank(ContactRoles.guess(Emails.clean(p.email()), p.title())))
                        .thenComparingInt(p -> p.verified() == Verified.VALID ? 0 : p.verified() == Verified.RISKY ? 2 : 1)
                        .thenComparingInt(p -> -(p.confidence() == null ? 0 : p.confidence())))
                .toList();
    }

    /** When she pitches a lead, everyone the finders found for it joins the new brand's contacts, tagged where they came from. */
    public void importLeadPeople(Long brandId, BrandLead lead) {
        List<Person> people = readPeople(lead.finderContacts);
        if (people.isEmpty()) return;
        List<String> notes = saveAll(brandId, people, new Tally(), new LinkedHashSet<>());
        notes.forEach(n -> log.info("Lead {}: {}", lead.id, n));
        contacts.refreshPrimary(brandId);
    }

    private static Person withVerified(Person p, Verified v) {
        return new Person(p.email(), p.name(), p.title(), p.phone(), p.linkedin(), p.confidence(), p.source(), p.sourceUrl(), v, null);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    static String writePeople(List<Person> people) {
        ArrayNode arr = JSON.createArrayNode();
        for (Person p : people) {
            ObjectNode o = arr.addObject();
            o.put("email", p.email());
            o.put("name", p.name());
            o.put("title", p.title());
            o.put("phone", p.phone());
            o.put("linkedin", p.linkedin());
            if (p.confidence() != null) o.put("confidence", p.confidence());
            o.put("source", p.source().name());
            o.put("sourceUrl", p.sourceUrl());
            if (p.verified() != null) o.put("verified", p.verified().name());
            if (p.verifiedAt() != null) o.put("verifiedAt", p.verifiedAt().toString());
        }
        return arr.toString();
    }

    static List<Person> readPeople(String json) {
        List<Person> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            for (JsonNode o : JSON.readTree(json)) {
                out.add(new Person(text(o, "email"), text(o, "name"), text(o, "title"), text(o, "phone"), text(o, "linkedin"),
                        o.hasNonNull("confidence") ? o.get("confidence").asInt() : null,
                        ContactSource.Kind.valueOf(o.path("source").asText("HUNTER")), text(o, "sourceUrl"),
                        o.hasNonNull("verified") ? Verified.valueOf(o.get("verified").asText()) : null,
                        o.hasNonNull("verifiedAt") ? OffsetDateTime.parse(o.get("verifiedAt").asText()) : null));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Couldn't read the people saved on a lead: {}", e.getMessage());
        }
        return out;
    }

    private static String text(JsonNode o, String f) {
        return o.hasNonNull(f) ? o.get(f).asText() : null;
    }

    private static final class Tally {
        int found, added, known, skipped;
    }

    /**
     * One person a finder returned, before it is saved to a brand. {@code verified}: what the finder already knows
     * about the address (null = nothing). Leads keep these as JSON until she pitches them.
     */
    public record Person(String email, String name, String title, String phone, String linkedin, Integer confidence,
                         ContactSource.Kind source, String sourceUrl, Verified verified, OffsetDateTime verifiedAt) {}

    /** What one finder returned for a domain, and what it cost. */
    record Search(List<Person> people, double cost, List<String> notes) {}

    private double viaHunter(Brand b, String domain, Tally t, Set<Long> touched, List<String> notes) {
        Search s = hunterSearch(domain);
        notes.addAll(saveAll(b.id, s.people(), t, touched));
        notes.addAll(s.notes());
        return s.cost();
    }

    private double viaApollo(Brand b, String domain, Tally t, Set<Long> touched, List<String> notes) {
        Search s = apolloSearch(domain);
        notes.addAll(saveAll(b.id, s.people(), t, touched));
        notes.addAll(s.notes());
        return s.cost();
    }

    /** Asks Hunter who works at the domain. Spends credits and remembers the domain was searched. */
    Search hunterSearch(String domain) {
        String key = secrets.require(SecretName.HUNTER_API_KEY);
        credits.require(Provider.HUNTER, FinderCredits.SEARCH_PER_10);
        // People in marketing and partnerships first; everyone when that finds nobody (an empty search is free).
        HunterApi.DomainResult r = hunter.domainSearch(key, domain, HunterApi.PARTNERSHIP_DEPARTMENTS, HUNTER_LIMIT);
        double cost = searchCost(r.people().size());
        if (r.people().isEmpty()) {
            r = hunter.domainSearch(key, domain, null, HUNTER_LIMIT);
            cost = searchCost(r.people().size());
        }
        credits.spend(Provider.HUNTER, cost);
        credits.markSearched(Provider.HUNTER, domain);
        syncHunterAccount(key);
        String url = "https://hunter.io/domain-search/" + domain;
        List<Person> out = new ArrayList<>();
        for (HunterApi.Person p : r.people()) {
            Verified v = r.acceptAll() && !"invalid".equals(p.verification()) ? Verified.RISKY : fromHunterStatus(p.verification());
            out.add(new Person(p.email(), p.name(), p.position(), p.phone(), p.linkedin(), p.confidence(),
                    ContactSource.Kind.HUNTER, p.sourceUrl() != null ? p.sourceUrl() : url, v, parseDate(p.verifiedOn())));
        }
        List<String> notes = new ArrayList<>();
        if (r.acceptAll()) notes.add(domain + " accepts every address, so Hunter can't prove any one of them exists. They're marked \"may not get through\".");
        if (cost > 0) notes.add("Used " + FinderCredits.fmt(cost) + " Hunter credit" + (cost == 1 ? "" : "s") + ".");
        return new Search(out, cost, notes);
    }

    /** Asks Apollo who works at the domain; reveals a few emails, a credit each. */
    Search apolloSearch(String domain) {
        String key = secrets.require(SecretName.APOLLO_API_KEY);
        List<ApolloApi.Person> people = apollo.search(key, domain, 10);
        credits.markSearched(Provider.APOLLO, domain);
        double cost = 0;
        int revealed = 0;
        List<Person> out = new ArrayList<>();
        for (ApolloApi.Person p : people) {
            if (revealed >= APOLLO_REVEALS) break;
            ApolloApi.Person full = p;
            if (full.email() == null) {
                if (p.id() == null || credits.remaining(Provider.APOLLO) < 1) continue;
                Optional<ApolloApi.Person> got = apollo.reveal(key, p.id());
                credits.spend(Provider.APOLLO, 1);
                cost += 1;
                revealed++;
                if (got.isEmpty() || got.get().email() == null) continue;
                full = got.get();
            }
            if (!domain.equals(Emails.brandDomain(full.email()))) continue; // a personal or old-employer address: skip
            out.add(new Person(full.email(), full.name() != null ? full.name() : p.name(),
                    full.title() != null ? full.title() : p.title(), null,
                    full.linkedin() != null ? full.linkedin() : p.linkedin(), null, ContactSource.Kind.APOLLO,
                    "https://app.apollo.io/", "verified".equalsIgnoreCase(full.emailStatus()) ? Verified.VALID : null, null));
        }
        List<String> notes = new ArrayList<>();
        if (cost > 0) notes.add("Used " + FinderCredits.fmt(cost) + " Apollo credit" + (cost == 1 ? "" : "s") + ".");
        return new Search(out, cost, notes);
    }

    /** Saves found people to a brand through {@link ContactService#save}, with what the finder knew about each address. */
    private List<String> saveAll(Long brandId, List<Person> people, Tally t, Set<Long> touched) {
        List<String> notes = new ArrayList<>();
        for (Person p : people) {
            t.found++;
            String clean = Emails.clean(p.email());
            boolean known = clean != null && contactRepo.findByEmail(clean).isPresent();
            Optional<BrandContact> saved = contacts.save(brandId, new ContactService.Found(p.email(), p.name(), p.title(),
                    null, p.phone(), p.linkedin(), null, p.confidence(), p.source(), p.sourceUrl()));
            if (saved.isEmpty()) {
                t.skipped++;
                continue;
            }
            if (known) t.known++;
            else t.added++;
            BrandContact c = saved.get();
            touched.add(c.id);
            if (!c.brandId.equals(brandId)) notes.add(c.email + " is already saved under another brand, so it stayed there.");
            // A finder's "verified" only fills in an unchecked address; its "invalid" or "risky" always counts.
            boolean apply = p.verified() != null && c.replies == 0
                    && (p.verified() != Verified.VALID || c.verified == Verified.UNKNOWN);
            if (apply) mark(c, p.verified(), p.verifiedAt());
        }
        return notes;
    }

    static double searchCost(int results) {
        return results == 0 ? 0 : Math.ceil(results / 10.0) * FinderCredits.SEARCH_PER_10;
    }

    /** Hunter's own last check of an address, when it is a clear answer. */
    static Verified fromHunterStatus(String status) {
        if (status == null) return null;
        return switch (status) {
            case "valid" -> Verified.VALID;
            case "invalid" -> Verified.INVALID;
            case "accept_all" -> Verified.RISKY;
            default -> null;
        };
    }

    /** What Hunter's verifier means for sending: deliverable, undeliverable or risky. */
    static Verified fromHunterCheck(HunterApi.Verification v) {
        if (v.pending()) return null;
        if ("invalid".equals(v.status()) || "undeliverable".equals(v.result()) || "disposable".equals(v.status())) return Verified.INVALID;
        if ("valid".equals(v.status()) || "deliverable".equals(v.result())) return Verified.VALID;
        if ("accept_all".equals(v.status()) || "risky".equals(v.result())) return Verified.RISKY;
        return Verified.UNKNOWN;
    }

    /** Checks one address. Costs half a Hunter credit only when it isn't already proven and the domain takes email. */
    public CheckResult check(Long contactId) {
        BrandContact c = contactRepo.findById(contactId).orElseThrow(() -> new IllegalArgumentException("That contact no longer exists"));
        CheckResult r = checkOne(c);
        contacts.refreshPrimary(c.brandId);
        return r;
    }

    /** Checks everyone at a brand not checked in the last six months, while credits last. */
    public BrandCheck checkBrand(Long brandId) {
        Brand b = brands.findById(brandId).orElseThrow(() -> new IllegalArgumentException("That brand no longer exists"));
        int checked = 0, valid = 0, risky = 0, invalid = 0;
        double spent = 0;
        List<String> notes = new ArrayList<>();
        for (BrandContact c : contactRepo.findByBrandIdOrderByScoreDescIdAsc(b.id)) {
            if (!needsCheck(c, OffsetDateTime.now())) continue;
            try {
                CheckResult r = checkOne(c);
                spent += r.credits();
                checked++;
                switch (r.contact().verified) {
                    case VALID -> valid++;
                    case RISKY -> risky++;
                    case INVALID -> invalid++;
                    default -> { }
                }
            } catch (IllegalStateException e) {
                notes.add(e.getMessage()); // out of credits or Hunter said no: stop here, keep what was checked
                break;
            }
        }
        contacts.refreshPrimary(b.id);
        if (checked == 0 && notes.isEmpty()) notes.add("Everyone at " + b.name + " was checked recently.");
        return new BrandCheck(checked, valid, risky, invalid, spent, notes, contacts.forBrand(b.id));
    }

    /** Not proven by a reply, not already known bad, not checked in six months. */
    static boolean needsCheck(BrandContact c, OffsetDateTime now) {
        if (c.optedOut || c.bounced || c.verified == Verified.INVALID) return false;
        if (c.replies > 0) return c.verified != Verified.VALID;
        return c.verified == Verified.UNKNOWN || c.verifiedAt == null || c.verifiedAt.isBefore(now.minusDays(STALE_DAYS));
    }

    private CheckResult checkOne(BrandContact c) {
        if (c.replies > 0) {
            mark(c, Verified.VALID, null);
            return new CheckResult(c, "They've replied to you before, so the address works. No credit used.", 0);
        }
        MailServers.Result mx = mail.check(Emails.domainOf(c.email));
        if (mx == MailServers.Result.NO_MAIL) {
            mark(c, Verified.INVALID, null);
            return new CheckResult(c, Emails.domainOf(c.email) + " can't receive email, so this address won't be emailed. No credit used.", 0);
        }
        if (!hunterConnected()) {
            return new CheckResult(c, mx == MailServers.Result.ACCEPTS_MAIL
                    ? "The brand's email works. Add a Hunter key in Settings to check this exact address."
                    : "Couldn't check right now. Try again when you're online.", 0);
        }
        String key = secrets.require(SecretName.HUNTER_API_KEY);
        credits.require(Provider.HUNTER, FinderCredits.VERIFY);
        HunterApi.Verification v;
        try {
            v = hunter.verify(key, c.email);
        } catch (HunterApi.HunterException e) {
            if (e.status == 451) return new CheckResult(c, e.getMessage(), 0);
            throw e;
        }
        if (v.pending()) return new CheckResult(c, "Hunter is still checking this one. Try again in a minute.", 0);
        credits.spend(Provider.HUNTER, FinderCredits.VERIFY);
        syncHunterAccount(key);
        Verified result = fromHunterCheck(v);
        mark(c, result, null);
        String msg = switch (result) {
            case VALID -> "The address exists.";
            case INVALID -> "The address doesn't exist, so it won't be emailed. Try someone else at the brand.";
            case RISKY -> "The brand accepts every address, so this one can't be proven. You can still send it yourself.";
            case UNKNOWN -> "Hunter couldn't tell this time.";
        };
        return new CheckResult(c, msg + " Used half a Hunter credit.", FinderCredits.VERIFY);
    }

    /**
     * Free nightly check: addresses at domains that can't receive email are marked INVALID before anything is sent.
     * Never spends credits. Returns how many were marked.
     */
    public int checkMailServers(int max) {
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, MailServers.Result> seen = new HashMap<>();
        int marked = 0, looked = 0;
        for (BrandContact c : contactRepo.findAll()) {
            if (looked >= max) break;
            if (c.replies > 0 || !needsCheck(c, now) || c.verified != Verified.UNKNOWN) continue;
            looked++;
            String d = Emails.domainOf(c.email);
            MailServers.Result r = seen.computeIfAbsent(d, mail::check);
            if (r == MailServers.Result.NO_MAIL) {
                mark(c, Verified.INVALID, null);
                contacts.refreshPrimary(c.brandId);
                marked++;
            }
        }
        return marked;
    }

    /** Whether any recipient is a contact marked RISKY: those go out only when she sends them herself. */
    public boolean risky(String toAddress) {
        return Emails.findAll(toAddress).stream().map(contactRepo::findByEmail).flatMap(Optional::stream)
                .anyMatch(c -> c.verified == Verified.RISKY && c.replies == 0);
    }

    private void mailServerOnly(BrandContact c) {
        if (mail.check(Emails.domainOf(c.email)) == MailServers.Result.NO_MAIL) mark(c, Verified.INVALID, null);
    }

    private void mark(BrandContact c, Verified v, OffsetDateTime when) {
        OffsetDateTime now = OffsetDateTime.now();
        c.verified = v;
        c.verifiedAt = when != null ? when : now;
        c.updatedAt = now;
        ContactService.rescore(c, now);
        contactRepo.save(c);
    }

    private void syncHunterAccount(String key) {
        try {
            credits.recordAccount(hunter.account(key));
        } catch (RuntimeException e) {
            log.debug("Couldn't read the Hunter account: {}", e.getMessage());
        }
    }

    /** Re-reads her Hunter plan and credits (free). */
    public void refreshHunterAccount() {
        credits.recordAccount(hunter.account(secrets.require(SecretName.HUNTER_API_KEY)));
    }

    private static OffsetDateTime parseDate(String d) {
        if (d == null) return null;
        try {
            return LocalDate.parse(d.substring(0, Math.min(10, d.length()))).atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

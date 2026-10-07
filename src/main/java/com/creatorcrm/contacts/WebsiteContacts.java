package com.creatorcrm.contacts;

import com.creatorcrm.domain.AppState;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.BrandContact.Role;
import com.creatorcrm.domain.BrandDomain;
import com.creatorcrm.domain.BrandLead;
import com.creatorcrm.domain.ContactSource;
import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.repo.BrandContactRepo;
import com.creatorcrm.repo.BrandDomainRepo;
import com.creatorcrm.repo.BrandLeadRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.security.SetupService;
import com.creatorcrm.settings.SettingsService;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Finds contacts on brands' own websites (see {@link WebsiteReader} for the rules it reads by) and saves them
 * through {@link ContactService}, marked as found on the website with the page they came from. Runs when she
 * presses "Find contacts on website", and once a night for brands and leads that have no contact yet.
 * Plain code: no Claude, no paid service.
 */
@Service
public class WebsiteContacts {
    private static final Logger log = LoggerFactory.getLogger(WebsiteContacts.class);

    static final String LAST_RUN = "websiteContacts.lastRun";
    /** Nightly: start after this time, once a day, whenever the app is open. */
    static final LocalTime RUN_AFTER = LocalTime.of(1, 0);
    /** A site is read again at most this often, by the nightly run. */
    static final Duration RECHECK_AFTER = Duration.ofDays(60);
    /** Sites read per nightly run, so a long list is worked through over several nights. */
    static final int SITES_PER_NIGHT = 15;

    /** One address found, and whether it was new to her contacts. */
    public record Address(String email, String name, Role role, String pageUrl, boolean newContact) {}

    /** What a visit found, in a sentence for the screen. */
    public record Result(String site, int pagesRead, List<Address> addresses, int added, String message) {}

    private final WebsiteReader reader;
    private final ContactService contacts;
    private final BrandContactRepo contactRepo;
    private final BrandDomainRepo domains;
    private final BrandRepo brands;
    private final BrandLeadRepo leads;
    private final AppStateRepo state;
    private final SettingsService settings;
    private final SetupService setup;
    private final JdbcTemplate jdbc;
    private final TaskExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Websites she asked for (a category search) are read one at a time, in the order asked. */
    private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "website-contacts");
        t.setDaemon(true);
        return t;
    });

    public WebsiteContacts(WebsiteReader reader, ContactService contacts, BrandContactRepo contactRepo, BrandDomainRepo domains,
                           BrandRepo brands, BrandLeadRepo leads, AppStateRepo state, SettingsService settings,
                           SetupService setup, JdbcTemplate jdbc, @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.reader = reader;
        this.contacts = contacts;
        this.contactRepo = contactRepo;
        this.domains = domains;
        this.brands = brands;
        this.leads = leads;
        this.state = state;
        this.settings = settings;
        this.setup = setup;
        this.jdbc = jdbc;
        this.executor = executor;
    }

    // ---------------------------------------------------------------- brands

    /** Reads a brand's website and adds every address it publishes to the brand's contacts. */
    public Result forBrand(Long brandId) {
        Brand b = brands.findById(brandId).orElseThrow(() -> new IllegalArgumentException("That brand no longer exists"));
        String site = websiteOf(b).orElseThrow(() -> new IllegalArgumentException(
                "Add " + b.name + "'s website first, then try again."));
        WebsiteReader.Result r = reader.read(site);
        if (r.problem() != null && r.pagesRead().isEmpty()) {
            markCrawled(b.id, r.site());
            return new Result(r.site(), 0, List.of(), 0, r.problem());
        }
        List<Address> out = new ArrayList<>();
        int added = 0;
        for (WebsiteReader.Hit h : r.hits()) {
            boolean existed = contactRepo.findByEmail(h.email()).isPresent();
            Optional<BrandContact> saved = contacts.save(b.id, new ContactService.Found(h.email(), h.name(), h.title(), null,
                    null, null, null, null, ContactSource.Kind.WEBSITE, h.pageUrl()));
            if (saved.isEmpty()) continue; // asked to be forgotten
            if (!existed) added++;
            out.add(new Address(saved.get().email, saved.get().name, saved.get().role, h.pageUrl(), !existed));
        }
        contacts.refreshPrimary(b.id);
        markCrawled(b.id, r.site());
        return new Result(r.site(), r.pagesRead().size(), out, added, message(r, out.size(), added));
    }

    /** The website to read: the one saved on the brand, else a domain its contacts use. */
    Optional<String> websiteOf(Brand b) {
        if (b.website != null && !b.website.isBlank()) return Optional.of(b.website.strip());
        return domains.findByBrandId(b.id).stream().map(d -> d.domain).findFirst();
    }

    private void markCrawled(Long brandId, String site) {
        String d = Emails.registrable(site);
        if (d == null) return;
        contacts.claimDomain(brandId, d);
        domains.findByDomain(d).filter(x -> x.brandId.equals(brandId)).ifPresent(x -> {
            x.crawledAt = OffsetDateTime.now();
            domains.save(x);
        });
    }

    // ---------------------------------------------------------------- leads

    /**
     * Reads a lead's website. Leads aren't brands yet, so the best address found becomes the lead's contact (when it
     * has none); it joins her contacts, with the page it came from, when she pitches the lead.
     */
    public Result forLead(Long leadId) {
        BrandLead lead = leads.findById(leadId).orElseThrow(() -> new IllegalArgumentException("That suggestion no longer exists"));
        if (lead.website == null || lead.website.isBlank()) {
            throw new IllegalArgumentException("Add " + lead.name + "'s website first, then try again.");
        }
        WebsiteReader.Result r = reader.read(lead.website);
        lead.websiteCheckedAt = OffsetDateTime.now();
        List<Address> out = new ArrayList<>();
        for (WebsiteReader.Hit h : r.hits()) out.add(new Address(h.email(), h.name(), ContactRoles.guess(h.email(), h.title()), h.pageUrl(), true));
        out.sort(Comparator.comparingInt(a -> rank(a.role())));
        boolean filled = false;
        if ((lead.contactEmail == null || lead.contactEmail.isBlank()) && !out.isEmpty()) {
            lead.contactEmail = out.get(0).email();
            lead.contactSourceUrl = out.get(0).pageUrl();
            filled = true;
        }
        leads.save(lead);
        if (r.problem() != null && r.pagesRead().isEmpty()) return new Result(r.site(), 0, List.of(), 0, r.problem());
        String msg = message(r, out.size(), out.size());
        if (filled) msg += " Using " + lead.contactEmail + " for this pitch.";
        return new Result(r.site(), r.pagesRead().size(), out, filled ? 1 : 0, msg);
    }

    /** Reads these leads' websites in the background, one site after another, e.g. after a category search. */
    public void readLeadsSoon(List<Long> leadIds) {
        for (Long id : leadIds) {
            queue.execute(() -> {
                try {
                    forLead(id);
                } catch (RuntimeException e) {
                    log.info("Website contacts: lead {}: {}", id, e.getMessage());
                }
            });
        }
    }

    /** Who to pitch first when a lead has several addresses: partnerships, then PR, marketing, a general inbox. */
    static int rank(Role r) {
        return switch (r == null ? Role.OTHER : r) {
            case PARTNERSHIPS -> 0;
            case PR -> 1;
            case MARKETING -> 2;
            case GENERAL -> 3;
            case FOUNDER -> 4;
            case OTHER -> 5;
            case SUPPORT -> 6;
        };
    }

    private static String message(WebsiteReader.Result r, int found, int added) {
        String pages = r.pagesRead().size() == 1 ? "1 page" : r.pagesRead().size() + " pages";
        if (found == 0) return "Read " + pages + " on " + r.site() + " and found no published email address.";
        String addresses = found == 1 ? "1 email address" : found + " email addresses";
        String isNew = added == found ? "" : added == 0 ? " You already had them all." : " " + added + " of them are new.";
        return "Found " + addresses + " on " + r.site() + " (" + pages + ")." + isNew;
    }

    // ---------------------------------------------------------------- nightly

    /** Checks every 20 minutes whether tonight's run is due, and starts it in the background. */
    @Scheduled(cron = "${crm.schedule.website-check-cron:0 */20 * * * *}")
    public void check() {
        if (!setup.isSetupComplete()) return;
        ZonedDateTime now = ZonedDateTime.now(settings.zone());
        if (!due(now) || !running.compareAndSet(false, true)) return;
        AppState s = new AppState();
        s.stateKey = LAST_RUN;
        s.stateValue = now.toLocalDate().toString();
        state.save(s);
        executor.execute(() -> {
            try {
                nightly();
            } catch (RuntimeException e) {
                log.warn("Website contacts run stopped: {}", e.getMessage());
            } finally {
                running.set(false);
            }
        });
    }

    boolean due(ZonedDateTime now) {
        if (now.toLocalTime().isBefore(RUN_AFTER)) return false;
        String last = state.findById(LAST_RUN).map(x -> x.stateValue).orElse("");
        return !now.toLocalDate().toString().equals(last);
    }

    /** Reads up to {@value #SITES_PER_NIGHT} websites of brands and leads with no contact. Returns how many were read. */
    public int nightly() {
        OffsetDateTime stale = OffsetDateTime.now().minus(RECHECK_AFTER);
        int read = 0, added = 0;
        for (Long id : brandsToRead(stale)) {
            if (read >= SITES_PER_NIGHT) break;
            try {
                added += forBrand(id).added();
            } catch (RuntimeException e) {
                log.info("Website contacts: brand {}: {}", id, e.getMessage());
            }
            read++;
        }
        for (BrandLead l : leadsToRead(stale)) {
            if (read >= SITES_PER_NIGHT) break;
            try {
                if (forLead(l.id).added() > 0) added++;
            } catch (RuntimeException e) {
                log.info("Website contacts: lead {}: {}", l.id, e.getMessage());
            }
            read++;
        }
        log.info("Website contacts: read {} websites, {} new contacts", read, added);
        return read;
    }

    /** Brands with a website or domain, no contacts, and a website not read lately (or never). */
    List<Long> brandsToRead(OffsetDateTime stale) {
        List<Long> ids = jdbc.queryForList("SELECT b.id FROM brands b WHERE NOT EXISTS "
                + "(SELECT 1 FROM brand_contacts c WHERE c.brand_id = b.id) ORDER BY b.id DESC", Long.class);
        List<Long> out = new ArrayList<>();
        for (Brand b : brands.findAllById(ids)) {
            Optional<String> site = websiteOf(b);
            if (site.isEmpty()) continue;
            String d = Emails.domainOfUrl(site.get());
            if (d == null) continue;
            Optional<BrandDomain> row = domains.findByDomain(d);
            if (row.isPresent() && !row.get().brandId.equals(b.id)) continue; // another brand's website: a duplicate to merge
            if (row.isPresent() && row.get().crawledAt != null && row.get().crawledAt.isAfter(stale)) continue;
            out.add(b.id);
        }
        out.sort(Comparator.reverseOrder()); // newest brands first
        return out;
    }

    List<BrandLead> leadsToRead(OffsetDateTime stale) {
        return leads.findByStatusOrderByIdDesc(BrandLead.Status.NEW).stream()
                .filter(l -> l.contactEmail == null || l.contactEmail.isBlank())
                .filter(l -> l.website != null && !l.website.isBlank())
                .filter(l -> l.websiteCheckedAt == null || l.websiteCheckedAt.isBefore(stale))
                .toList();
    }
}

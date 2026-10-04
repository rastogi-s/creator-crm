package com.creatorcrm.outreach;

import com.creatorcrm.channels.instagram.BrandLookupService;
import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandLead;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.InstagramEngagement;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.links.LinkService;
import com.creatorcrm.llm.BrandLeads;
import com.creatorcrm.llm.BrandSearchInput;
import com.creatorcrm.llm.SearchDepth;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.BrandLeadRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.OpportunityRepo;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outreach autopilot: Claude researches brands on the web that fit the creator, finds their published
 * contact details, and each lead the creator picks becomes a pitch draft in the approval queue. Leads also come
 * from brands engaging with her on Instagram and from handles she looks up; with the Facebook connection, each
 * lead's Instagram account is read for followers, bio and the creators it works with.
 * Nothing is ever sent from here.
 */
@Service
public class BrandDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(BrandDiscoveryService.class);
    static final String ENGAGED_QUERY = "Engaged with you on Instagram";
    static final String LOOKUP_QUERY = "Looked up on Instagram";

    public static final int MAX_PER_SEARCH = 10;
    private static final int EXCLUDE_LIMIT = 200;

    private final LlmClient llm;
    private final BrandLeadRepo leads;
    private final BrandRepo brands;
    private final OpportunityRepo opportunities;
    private final ActivityRepo activity;
    private final DraftService drafts;
    private final BrandLookupService lookup;

    public BrandDiscoveryService(LlmClient llm, BrandLeadRepo leads, BrandRepo brands, OpportunityRepo opportunities,
                                 ActivityRepo activity, DraftService drafts, BrandLookupService lookup) {
        this.lookup = lookup;
        this.llm = llm;
        this.leads = leads;
        this.brands = brands;
        this.opportunities = opportunities;
        this.activity = activity;
        this.drafts = drafts;
    }

    public List<BrandLead> open() {
        return leads.findByStatusOrderByIdDesc(BrandLead.Status.NEW);
    }

    /** Research brands for the query and keep the new ones as leads. Returns the leads added. */
    public List<BrandLead> discover(String query, int count) {
        return discover(query, count, SearchDepth.STANDARD);
    }

    /** As above, with a cap on web searches: fewer searches cost less but may find fewer brands. */
    public List<BrandLead> discover(String query, int count, SearchDepth depth) {
        String q = query == null ? "" : query.strip();
        if (q.length() < 3) throw new IllegalArgumentException("Describe the kind of brands to look for");
        if (q.length() > 300) throw new IllegalArgumentException("Keep the search under 300 characters");
        int n = Math.max(1, Math.min(MAX_PER_SEARCH, count));

        List<String> known = new ArrayList<>();
        brands.findAll().stream().limit(EXCLUDE_LIMIT).forEach(b -> known.add(b.name));
        BrandLeads found = llm.findBrands(new BrandSearchInput(q, n, known, depth));

        List<BrandLead> added = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BrandLeads.Lead l : found.leads() == null ? List.<BrandLeads.Lead>of() : found.leads()) {
            String name = clean(l.name(), 200);
            String key = Brand.key(name);
            if (key.isEmpty() || !seen.add(key)) continue;
            if (brands.findByNameKey(key).isPresent() || leads.existsByNameKey(key)) continue; // already known
            BrandLead lead = new BrandLead();
            lead.name = name;
            lead.nameKey = key;
            lead.website = url(l.website());
            lead.instagram = handle(l.instagram());
            lead.contactEmail = email(l.contactEmail());
            lead.contactSourceUrl = lead.contactEmail == null ? null : url(l.contactSourceUrl());
            lead.fitReason = clean(l.fitReason(), 2000);
            lead.pitchAngle = clean(l.pitchAngle(), 2000);
            lead.searchQuery = q;
            lead.status = BrandLead.Status.NEW;
            lead.createdAt = OffsetDateTime.now();
            added.add(leads.save(lead));
            if (added.size() >= n) break;
        }
        if (lookup.available()) added.replaceAll(this::enrichQuietly);
        return added;
    }

    /** She types a brand's handle; its Instagram account becomes a lead. Needs the Facebook connection. */
    @Transactional
    public BrandLead lookupHandle(String handle) throws IOException, InterruptedException {
        if (!lookup.available()) throw new IllegalStateException("Connect Facebook on the Settings page to look up brands on Instagram");
        String h = handle(handle);
        if (h == null) throw new IllegalArgumentException("That doesn't look like an Instagram handle");
        BrandLookupService.Profile p = lookup.lookup(h).orElseThrow(() -> new IllegalArgumentException(
                "@" + h + " isn't a business or creator account on Instagram, or doesn't exist"));
        String name = clean(p.name(), 200) != null ? clean(p.name(), 200) : p.username();
        String key = Brand.key(name);
        if (key.isEmpty()) key = Brand.key(p.username());
        Optional<BrandLead> existing = leads.findFirstByNameKeyOrderByIdDesc(key)
                .filter(l -> l.status == BrandLead.Status.NEW);
        if (existing.isPresent()) return leads.save(apply(existing.get(), p));
        if (brands.findByNameKey(key).isPresent()) throw new IllegalStateException(name + " is already one of your brands");
        BrandLead lead = newLead(name, key, BrandLead.Source.LOOKUP, LOOKUP_QUERY);
        lead.instagram = p.username();
        return leads.save(apply(lead, p));
    }

    /** An account that tagged, mentioned or commented on her becomes a lead (or the existing lead for it). */
    @Transactional
    public BrandLead fromEngagement(InstagramEngagement e) {
        Optional<BrandLookupService.Profile> p = Optional.empty();
        if (lookup.available()) {
            try {
                p = lookup.lookup(e.username);
            } catch (Exception ex) {
                log.warn("Could not look up @{} on Instagram: {}", e.username, ex.getMessage());
            }
        }
        String name = p.map(BrandLookupService.Profile::name).map(v -> clean(v, 200)).orElse(null);
        if (name == null) name = e.username;
        String key = Brand.key(name);
        if (key.isEmpty()) key = Brand.key(e.username);
        Optional<BrandLead> existing = leads.findFirstByNameKeyOrderByIdDesc(key)
                .filter(l -> l.status == BrandLead.Status.NEW);
        if (existing.isPresent()) return existing.get();
        BrandLead lead = newLead(name, key, BrandLead.Source.INSTAGRAM, ENGAGED_QUERY);
        lead.instagram = handle(e.username);
        lead.fitReason = clean(engagementReason(e), 2000);
        if (p.isPresent()) apply(lead, p.get());
        return leads.save(lead);
    }

    /** Re-read a lead's Instagram account (button on the lead). */
    @Transactional
    public BrandLead refreshInstagram(Long id) throws IOException, InterruptedException {
        BrandLead lead = leads.findById(id).orElseThrow();
        if (!lookup.available()) throw new IllegalStateException("Connect Facebook on the Settings page to look up brands on Instagram");
        if (lead.instagram == null) throw new IllegalStateException("Add " + lead.name + "'s Instagram handle first");
        BrandLookupService.Profile p = lookup.lookup(lead.instagram).orElseThrow(() -> new IllegalArgumentException(
                "@" + lead.instagram + " isn't a business or creator account on Instagram, or doesn't exist"));
        return leads.save(apply(lead, p));
    }

    static String engagementReason(InstagramEngagement e) {
        List<String> parts = new ArrayList<>();
        if (e.mentions > 0) parts.add("Tagged or mentioned you " + times(e.mentions));
        if (e.comments > 0) parts.add("commented on your posts " + times(e.comments));
        String what = parts.isEmpty() ? "Engaged with you on Instagram" : String.join(" and ", parts);
        what = Character.toUpperCase(what.charAt(0)) + what.substring(1) + ".";
        return e.lastText == null || e.lastText.isBlank() ? what : what + " Latest: \"" + e.lastText + "\"";
    }

    private static String times(int n) {
        return n == 1 ? "once" : n + " times";
    }

    private BrandLead enrichQuietly(BrandLead lead) {
        if (lead.instagram == null) return lead;
        try {
            return lookup.lookup(lead.instagram).map(p -> leads.save(apply(lead, p))).orElse(lead);
        } catch (Exception e) {
            log.warn("Could not look up @{} on Instagram: {}", lead.instagram, e.getMessage());
            return lead;
        }
    }

    /** Copy what the brand's Instagram account shows onto the lead, without overwriting what she entered. */
    static BrandLead apply(BrandLead lead, BrandLookupService.Profile p) {
        lead.igFollowers = p.followers();
        lead.igBio = clean(p.biography(), 1000);
        lead.igPartners = p.partners().isEmpty() ? null : clean(String.join(",", p.partners()), 1000);
        lead.igCheckedAt = OffsetDateTime.now();
        if (lead.instagram == null) lead.instagram = handle(p.username());
        if (lead.website == null) lead.website = url(p.website());
        if (lead.contactEmail == null && email(p.bioEmail()) != null) {
            lead.contactEmail = email(p.bioEmail());
            lead.contactSourceUrl = "https://www.instagram.com/" + p.username() + "/";
        }
        return lead;
    }

    private static BrandLead newLead(String name, String key, BrandLead.Source source, String query) {
        BrandLead lead = new BrandLead();
        lead.name = name;
        lead.nameKey = key;
        lead.source = source;
        lead.searchQuery = query;
        lead.status = BrandLead.Status.NEW;
        lead.createdAt = OffsetDateTime.now();
        return lead;
    }

    /** The creator corrects or adds contact details before pitching. */
    @Transactional
    public BrandLead updateContact(Long id, String email, String instagram) {
        BrandLead lead = leads.findById(id).orElseThrow();
        if (email != null) {
            lead.contactEmail = email.isBlank() ? null : email(email);
            if (!email.isBlank() && lead.contactEmail == null) throw new IllegalArgumentException("That email address doesn't look right");
            lead.contactSourceUrl = null; // entered by the creator
        }
        if (instagram != null) lead.instagram = instagram.isBlank() ? null : handle(instagram);
        return leads.save(lead);
    }

    @Transactional
    public BrandLead dismiss(Long id) {
        BrandLead lead = leads.findById(id).orElseThrow();
        lead.status = BrandLead.Status.DISMISSED;
        return leads.save(lead);
    }

    /** Turn a lead into a brand + new-lead deal and queue a pitch draft for approval. */
    @Transactional
    public Draft draftPitch(Long id) {
        BrandLead lead = leads.findById(id).orElseThrow();
        if (lead.status != BrandLead.Status.NEW) throw new IllegalStateException(lead.name + " was already handled");
        if (lead.contactEmail == null && lead.instagram == null) {
            throw new IllegalStateException("No email or Instagram for " + lead.name + ". Add one first.");
        }
        Brand b = brands.findByNameKey(lead.nameKey).orElseGet(() -> {
            Brand n = new Brand();
            n.name = lead.name;
            n.nameKey = lead.nameKey;
            n.createdAt = OffsetDateTime.now();
            return n;
        });
        if (b.website == null) b.website = lead.website;
        if (b.contactEmail == null) b.contactEmail = lead.contactEmail;
        if (b.instagram == null) b.instagram = lead.instagram;
        b = brands.save(b);

        Opportunity o = new Opportunity();
        o.brandId = b.id;
        o.origin = Origin.PITCH;
        o.type = OpportunityType.OTHER;
        o.compensation = Compensation.UNKNOWN;
        o.status = OpportunityStatus.NEW_LEAD;
        o.campaign = lead.pitchAngle;
        o.pitchPlatform = lead.contactEmail != null ? "EMAIL" : "INSTAGRAM";
        o.createdAt = OffsetDateTime.now();
        o.updatedAt = o.createdAt;
        o = opportunities.save(o);
        activity.save(Activity.of(o.id, Activity.NEW_OPPORTUNITY, "Pitch drafted for " + b.name));

        Draft d = drafts.generate(o.id, DraftType.PITCH, pitchInstructions(lead), null, null);
        lead.status = BrandLead.Status.DRAFTED;
        lead.opportunityId = o.id;
        leads.save(lead);
        return d;
    }

    static String pitchInstructions(BrandLead lead) {
        return "First cold pitch to a brand the creator has never worked with. Introduce the creator briefly, show "
                + "why they fit this brand, propose one specific collaboration idea, mention relevant numbers and links "
                + "from the profile, and end with a light call to action. Keep it short and personal, not a template."
                + (lead.fitReason == null ? "" : " Research notes on why they fit: " + lead.fitReason)
                + (lead.pitchAngle == null ? "" : " Suggested idea: " + lead.pitchAngle)
                + (lead.source == BrandLead.Source.INSTAGRAM ? " This brand already engaged with the creator on Instagram;"
                        + " open by referring to that warmly." : "")
                + (lead.igBio == null ? "" : " The brand's Instagram bio: " + lead.igBio)
                + (lead.igPartners == null ? "" : " Creators the brand has tagged in sponsored posts: @"
                        + lead.igPartners.replace(",", ", @") + ". Use this only to understand what they look for;"
                        + " don't name other creators in the pitch.");
    }

    // ---------------------------------------------------------------- sanitizing model output

    private static String clean(String s, int max) {
        if (s == null) return null;
        String v = s.strip().replaceAll("[\\r\\n\\t]+", " ");
        if (v.isEmpty()) return null;
        return v.length() > max ? v.substring(0, max) : v;
    }

    static String url(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LinkService.normalizeUrl(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String email(String s) {
        if (s == null) return null;
        String v = s.strip().toLowerCase(Locale.ROOT).replaceFirst("^mailto:", "");
        return v.length() <= 320 && v.matches("[a-z0-9._%+'-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}") ? v : null;
    }

    static String handle(String s) {
        if (s == null) return null;
        String v = s.strip().replaceFirst("(?i)^https?://(www\\.)?instagram\\.com/", "").replaceAll("[/?].*$", "")
                .replaceFirst("^@", "");
        return v.matches("[A-Za-z0-9._]{1,30}") ? v : null;
    }
}

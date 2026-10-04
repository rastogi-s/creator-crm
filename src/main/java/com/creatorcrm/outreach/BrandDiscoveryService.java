package com.creatorcrm.outreach;

import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandLead;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.links.LinkService;
import com.creatorcrm.llm.BrandLeads;
import com.creatorcrm.llm.BrandSearchInput;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.BrandLeadRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.OpportunityRepo;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outreach autopilot: Claude researches brands on the web that fit the creator, finds their published
 * contact details, and each lead the creator picks becomes a pitch draft in the approval queue.
 * Nothing is ever sent from here.
 */
@Service
public class BrandDiscoveryService {

    public static final int MAX_PER_SEARCH = 10;
    private static final int EXCLUDE_LIMIT = 200;

    private final LlmClient llm;
    private final BrandLeadRepo leads;
    private final BrandRepo brands;
    private final OpportunityRepo opportunities;
    private final ActivityRepo activity;
    private final DraftService drafts;

    public BrandDiscoveryService(LlmClient llm, BrandLeadRepo leads, BrandRepo brands, OpportunityRepo opportunities,
                                 ActivityRepo activity, DraftService drafts) {
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
        String q = query == null ? "" : query.strip();
        if (q.length() < 3) throw new IllegalArgumentException("Describe the kind of brands to look for");
        if (q.length() > 300) throw new IllegalArgumentException("Keep the search under 300 characters");
        int n = Math.max(1, Math.min(MAX_PER_SEARCH, count));

        List<String> known = new ArrayList<>();
        brands.findAll().stream().limit(EXCLUDE_LIMIT).forEach(b -> known.add(b.name));
        BrandLeads found = llm.findBrands(new BrandSearchInput(q, n, known));

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
        return added;
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
                + (lead.pitchAngle == null ? "" : " Suggested idea: " + lead.pitchAngle);
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

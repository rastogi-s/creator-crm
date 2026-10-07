package com.creatorcrm.campaigns;

import com.creatorcrm.contacts.Suppressions;
import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandContact;
import com.creatorcrm.domain.BrandLead;
import com.creatorcrm.domain.Campaign;
import com.creatorcrm.domain.CampaignTarget;
import com.creatorcrm.domain.CampaignTarget.State;
import com.creatorcrm.domain.ContactList;
import com.creatorcrm.domain.CreatorLink;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Origin;
import com.creatorcrm.domain.FollowUp;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.PitchTemplate;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.drafts.Placeholders;
import com.creatorcrm.links.LinkService;
import com.creatorcrm.llm.DraftText;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.BrandContactRepo;
import com.creatorcrm.repo.BrandLeadRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.CampaignRepo;
import com.creatorcrm.repo.CampaignTargetRepo;
import com.creatorcrm.repo.ContactListRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.repo.PitchTemplateRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.FollowUpEngine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pitch campaigns: saved contact lists, templates, and one pitch per brand to its best-ranked person who may be
 * emailed. Every pitch is a draft she reads and approves in Drafts; approved ones go out slowly from her Gmail
 * ({@link CampaignSender}). A reply from anyone at a brand stops that brand ({@link CampaignWatcher}); a brand that
 * never answered is tried once more, with the next-ranked person, after its follow-ups run out.
 */
@Service
public class CampaignService {
    private static final Logger log = LoggerFactory.getLogger(CampaignService.class);

    public record Pick(Brand brand, BrandContact contact) {}

    public record PickRow(Long brandId, String brand, String name, String email, String role, int score, String why) {}

    public record ListView(ContactList list, ContactFilter filter, int brands) {}

    public record Preview(int brands, List<PickRow> sample) {}

    public record CampaignView(Campaign campaign, String listName, String templateName, Map<State, Integer> counts) {}

    public record TargetRow(CampaignTarget target, String brand) {}

    static final Set<State> ACTIVE_OR_SENT = Set.of(State.WAITING_LINE, State.DRAFTED, State.APPROVED, State.SENT);
    /** A brand that answered a campaign is a conversation now, never pitched cold again by a campaign. */
    static final Set<State> ANSWERED = Set.of(State.REPLIED, State.OPTED_OUT);

    private static final Pattern MEDIA_KIT = Pattern.compile("(?i)media ?kit|press ?kit");
    private static final Pattern RATES = Pattern.compile("(?i)rate|pricing|price|packages");
    private static final Pattern INSTAGRAM = Pattern.compile("(?i)instagram");

    private final ContactListRepo lists;
    private final PitchTemplateRepo templates;
    private final CampaignRepo campaigns;
    private final CampaignTargetRepo targets;
    private final BrandContactRepo contacts;
    private final BrandRepo brands;
    private final BrandLeadRepo leads;
    private final OpportunityRepo opportunities;
    private final DraftRepo drafts;
    private final DraftService draftService;
    private final ActivityRepo activity;
    private final LinkService links;
    private final SettingsService settings;
    private final Suppressions suppressions;
    private final FollowUpEngine followUps;
    private final CampaignState state;
    private final ObjectMapper json;

    public CampaignService(ContactListRepo lists, PitchTemplateRepo templates, CampaignRepo campaigns,
                           CampaignTargetRepo targets, BrandContactRepo contacts, BrandRepo brands, BrandLeadRepo leads,
                           OpportunityRepo opportunities, DraftRepo drafts, DraftService draftService,
                           ActivityRepo activity, LinkService links, SettingsService settings, Suppressions suppressions,
                           FollowUpEngine followUps, CampaignState state, ObjectMapper json) {
        this.lists = lists;
        this.templates = templates;
        this.campaigns = campaigns;
        this.targets = targets;
        this.contacts = contacts;
        this.brands = brands;
        this.leads = leads;
        this.opportunities = opportunities;
        this.drafts = drafts;
        this.draftService = draftService;
        this.activity = activity;
        this.links = links;
        this.settings = settings;
        this.suppressions = suppressions;
        this.followUps = followUps;
        this.state = state;
        this.json = json;
    }

    // ---------- saved lists ----------

    public List<ListView> lists() {
        List<ListView> out = new ArrayList<>();
        for (ContactList l : lists.findAllByOrderByNameAsc()) {
            ContactFilter f = filterOf(l);
            out.add(new ListView(l, f, pick(f).size()));
        }
        return out;
    }

    @Transactional
    public ContactList saveList(Long id, String name, ContactFilter filter) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Give the list a name");
        ContactList l = id == null ? new ContactList() : lists.findById(id).orElseThrow(() -> new IllegalArgumentException("That list no longer exists"));
        l.name = clip(name.strip(), 120);
        try {
            l.filterJson = json.writeValueAsString(filter == null ? new ContactFilter(null, false, false, null, null, null, true) : filter);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Those list rules can't be saved");
        }
        if (l.createdAt == null) l.createdAt = OffsetDateTime.now();
        return lists.save(l);
    }

    @Transactional
    public void deleteList(Long id) {
        if (campaigns.existsByListId(id)) throw new IllegalStateException("A campaign uses this list, so it can't be deleted");
        lists.deleteById(id);
    }

    ContactFilter filterOf(ContactList l) {
        try {
            return json.readValue(l.filterJson, ContactFilter.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The list " + l.name + " can't be read", e);
        }
    }

    /** Who this list would pitch: per brand, its best-ranked person who matches and may be emailed. Best first. */
    public List<Pick> pick(ContactFilter f) {
        Map<Long, Brand> byId = brands.findAll().stream().collect(Collectors.toMap(b -> b.id, b -> b));
        Set<Long> inDeals = f.skipsBrandsInDeals() ? brandsInDeals() : Set.of();
        Map<Long, Pick> best = new LinkedHashMap<>();
        for (BrandContact c : contacts.findAllByOrderByScoreDescIdAsc()) {
            Brand b = byId.get(c.brandId);
            if (b == null || best.containsKey(b.id) || inDeals.contains(b.id)) continue;
            if (!c.emailable() || suppressions.blocked(c.email) || !f.matches(c, b.name)) continue;
            best.put(b.id, new Pick(b, c));
        }
        return new ArrayList<>(best.values());
    }

    public Preview preview(ContactFilter f) {
        List<Pick> picks = pick(f);
        return new Preview(picks.size(), picks.stream().limit(25).map(CampaignService::row).toList());
    }

    static PickRow row(Pick p) {
        BrandContact c = p.contact();
        return new PickRow(p.brand().id, p.brand().name, c.name, c.email, c.role == null ? "OTHER" : c.role.name(), c.score, c.scoreReason);
    }

    /** Brands with an open deal that a campaign didn't start: she's already talking to them. */
    private Set<Long> brandsInDeals() {
        Set<Long> campaignDeals = targets.findAll().stream().map(t -> t.opportunityId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return opportunities.findAll().stream()
                .filter(o -> o.status.isOpen() && !campaignDeals.contains(o.id))
                .map(o -> o.brandId).collect(Collectors.toSet());
    }

    // ---------- templates ----------

    public List<PitchTemplate> templates() {
        return templates.findAllByOrderByIdAsc();
    }

    @Transactional
    public PitchTemplate saveTemplate(Long id, String name, PitchTemplate.Kind kind, String subject, String body, String followUpBody) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Give the template a name");
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("Add a subject line");
        if (body == null || body.isBlank()) throw new IllegalArgumentException("Write the email");
        PitchTemplate t = id == null ? new PitchTemplate()
                : templates.findById(id).orElseThrow(() -> new IllegalArgumentException("That template no longer exists"));
        t.name = clip(name.strip(), 120);
        t.kind = kind == null ? PitchTemplate.Kind.OTHER : kind;
        t.subject = clip(subject.strip(), 300);
        t.body = clip(body.strip(), 8000);
        t.followUpBody = followUpBody == null || followUpBody.isBlank() ? null : clip(followUpBody.strip(), 4000);
        OffsetDateTime now = OffsetDateTime.now();
        if (t.createdAt == null) t.createdAt = now;
        t.updatedAt = now;
        return templates.save(t);
    }

    @Transactional
    public void deleteTemplate(Long id) {
        if (campaigns.existsByTemplateId(id)) throw new IllegalStateException("A campaign uses this template, so it can't be deleted");
        templates.deleteById(id);
    }

    /** The template filled in for one brand, footer included, exactly as the draft would read. */
    public DraftText sample(PitchTemplate t, Long brandId) {
        Pick p = null;
        if (brandId != null) {
            Brand b = brands.findById(brandId).orElseThrow(() -> new IllegalArgumentException("That brand no longer exists"));
            BrandContact c = contacts.findByBrandIdOrderByScoreDescIdAsc(b.id).stream().filter(BrandContact::emailable)
                    .findFirst().orElse(null);
            p = new Pick(b, c);
        } else {
            List<BrandContact> all = contacts.findAllByOrderByScoreDescIdAsc();
            if (!all.isEmpty()) {
                BrandContact c = all.get(0);
                p = new Pick(brands.findById(c.brandId).orElseThrow(), c);
            }
        }
        if (p == null) {
            Brand demo = new Brand();
            demo.name = "Glowberry";
            demo.nameKey = "glowberry";
            p = new Pick(demo, null);
        }
        return fill(t, t.body, values(p.brand(), p.contact(), null), false);
    }

    // ---------- campaigns ----------

    public List<CampaignView> campaigns() {
        Map<Long, String> listNames = lists.findAll().stream().collect(Collectors.toMap(l -> l.id, l -> l.name));
        Map<Long, String> templateNames = templates.findAll().stream().collect(Collectors.toMap(t -> t.id, t -> t.name));
        List<CampaignView> out = new ArrayList<>();
        for (Campaign c : campaigns.findAllByOrderByIdDesc()) {
            Map<State, Integer> counts = new EnumMap<>(State.class);
            for (CampaignTarget t : targets.findByCampaignIdOrderByIdAsc(c.id)) counts.merge(t.state, 1, Integer::sum);
            out.add(new CampaignView(c, listNames.getOrDefault(c.listId, "?"), templateNames.getOrDefault(c.templateId, "?"), counts));
        }
        return out;
    }

    public List<TargetRow> targets(Long campaignId) {
        Map<Long, String> names = brands.findAll().stream().collect(Collectors.toMap(b -> b.id, b -> b.name));
        return targets.findByCampaignIdOrderByIdAsc(campaignId).stream()
                .map(t -> new TargetRow(t, names.getOrDefault(t.brandId, "?"))).toList();
    }

    /** Before any campaign email is drafted or sent: the footer needs her postal address. */
    void requireAddress() {
        if (settings.campaignAddress().isBlank()) {
            throw new IllegalStateException("Add your business postal address under Sending rules first. US law (CAN-SPAM) asks "
                    + "for one in pitch emails; a PO box works.");
        }
    }

    @Transactional
    public Campaign start(String name, Long listId, Long templateId, boolean personalise, boolean claudeReady) {
        requireAddress();
        ContactList l = lists.findById(listId).orElseThrow(() -> new IllegalArgumentException("Pick a saved list"));
        PitchTemplate t = templates.findById(templateId).orElseThrow(() -> new IllegalArgumentException("Pick a template"));
        if (personalise && !claudeReady) {
            throw new IllegalStateException("Personalise needs Claude connected in Settings. Turn it off to use the template on its own.");
        }
        Campaign c = new Campaign();
        c.name = clip(name == null || name.isBlank() ? t.name + " · " + l.name : name.strip(), 120);
        c.listId = l.id;
        c.templateId = t.id;
        c.personalise = personalise;
        c.status = Campaign.Status.ACTIVE;
        c.createdAt = OffsetDateTime.now();
        return campaigns.save(c);
    }

    @Transactional
    public Campaign setStatus(Long id, Campaign.Status status) {
        Campaign c = campaigns.findById(id).orElseThrow(() -> new IllegalArgumentException("That campaign no longer exists"));
        c.status = status;
        if (status == Campaign.Status.DONE) {
            for (CampaignTarget t : targets.findByCampaignIdOrderByIdAsc(c.id)) {
                if (State.WAITING.contains(t.state)) end(t, State.STOPPED, "Campaign ended before this was sent");
            }
        }
        return campaigns.save(c);
    }

    /**
     * Adds brands from the campaign's list until the pitches waiting for her (or for the queue) would fill about two
     * days of sending. Returns how many new targets were added.
     */
    @Transactional
    public int draftMore(Long campaignId) {
        Campaign c = campaigns.findById(campaignId).orElseThrow(() -> new IllegalArgumentException("That campaign no longer exists"));
        if (c.status != Campaign.Status.ACTIVE) return 0;
        requireAddress();
        int room = 2 * state.dailyAllowance() - (int) targets.findByStateIn(State.WAITING).size();
        if (room <= 0) return 0;
        ContactList l = lists.findById(c.listId).orElseThrow();
        PitchTemplate t = templates.findById(c.templateId).orElseThrow();
        Set<Long> skip = brandsNotToPitch(c.id);
        int added = 0;
        for (Pick p : pick(filterOf(l))) {
            if (added >= room) break;
            if (skip.contains(p.brand().id)) continue;
            CampaignTarget target = newTarget(c, p, 1);
            if (c.personalise) {
                target.state = State.WAITING_LINE;
                targets.save(target);
            } else {
                draft(target, c, t);
            }
            skip.add(p.brand().id);
            added++;
        }
        if (added > 0) log.info("Campaign {}: {} new pitches", c.name, added);
        return added;
    }

    /** Brands this campaign already has, and brands any campaign is pitching right now or that answered one. */
    private Set<Long> brandsNotToPitch(Long campaignId) {
        Set<Long> out = new HashSet<>();
        for (CampaignTarget t : targets.findAll()) {
            if (t.campaignId.equals(campaignId) || ACTIVE_OR_SENT.contains(t.state) || ANSWERED.contains(t.state)) out.add(t.brandId);
        }
        return out;
    }

    private CampaignTarget newTarget(Campaign c, Pick p, int attempt) {
        CampaignTarget t = new CampaignTarget();
        t.campaignId = c.id;
        t.brandId = p.brand().id;
        t.contactId = p.contact().id;
        t.email = p.contact().email;
        t.attempt = attempt;
        t.state = State.DRAFTED;
        t.createdAt = OffsetDateTime.now();
        return targets.save(t);
    }

    /** Writes the pitch for one brand from the template (and its opening line, if any) and puts it in Drafts. */
    @Transactional
    public void draft(CampaignTarget target, Campaign c, PitchTemplate t) {
        Brand b = brands.findById(target.brandId).orElseThrow();
        BrandContact contact = target.contactId == null ? null : contacts.findById(target.contactId).orElse(null);
        Opportunity o = new Opportunity();
        o.brandId = b.id;
        o.origin = Origin.PITCH;
        o.type = t.kind == PitchTemplate.Kind.UGC ? OpportunityType.UGC
                : t.kind == PitchTemplate.Kind.GIFTING ? OpportunityType.GIFTED : OpportunityType.OTHER;
        o.compensation = t.kind == PitchTemplate.Kind.GIFTING ? Compensation.GIFTED : Compensation.UNKNOWN;
        o.status = OpportunityStatus.NEW_LEAD;
        o.campaign = clip(t.name + " (campaign: " + c.name + ")", 200);
        o.pitchPlatform = "EMAIL";
        o.createdAt = OffsetDateTime.now();
        o.updatedAt = o.createdAt;
        o = opportunities.save(o);
        activity.save(Activity.of(o.id, Activity.NEW_OPPORTUNITY, "Campaign pitch drafted for " + b.name));

        DraftText text = fill(t, withOpeningLine(t.body, c.personalise), values(b, contact, target.openingLine), true);
        Draft d = draftService.createWritten(o.id, DraftType.PITCH, null, target.email, text, target.id);
        target.opportunityId = o.id;
        target.draftId = d.id;
        target.state = State.DRAFTED;
        targets.save(target);
    }

    /** With Personalise on, a template without {opening_line} gets the line right after its greeting. */
    static String withOpeningLine(String body, boolean personalise) {
        if (!personalise || body.contains("{opening_line")) return body;
        int nl = body.indexOf('\n');
        return nl < 0 ? "{opening_line|} " + body : body.substring(0, nl) + "\n\n{opening_line|}" + body.substring(nl);
    }

    DraftText fill(PitchTemplate t, String body, Map<String, String> values, boolean footer) {
        String text = MergeFields.fill(body, values).replaceAll("\n{3,}", "\n\n").strip();
        if (footer) text = CampaignFooter.ensure(text, settings.creatorName(), settings.campaignAddress());
        else text = text + "\n\n" + CampaignFooter.footer(settings.creatorName(),
                settings.campaignAddress().isBlank() ? "[YOUR POSTAL ADDRESS]" : settings.campaignAddress());
        return new DraftText(MergeFields.fill(t.subject, values).strip(), text);
    }

    /** What the merge fields stand for, for this brand and person. */
    Map<String, String> values(Brand b, BrandContact c, String openingLine) {
        Map<String, String> v = new HashMap<>();
        v.put("brand", b.name);
        String first = c == null || c.name == null ? null : c.name.strip().split("\\s+")[0];
        v.put("first_name", first != null && first.length() > 1 && Character.isLetter(first.charAt(0)) ? first : b.name + " team");
        Optional<BrandLead> lead = b.nameKey == null ? Optional.empty() : leads.findFirstByNameKeyOrderByIdDesc(b.nameKey);
        lead.ifPresent(l -> {
            v.put("why_you", sentence(l.fitReason));
            v.put("pitch_idea", sentence(l.pitchAngle));
        });
        v.put("opening_line", openingLine);
        List<CreatorLink> mine = links.list();
        v.put("media_kit", link(mine, MEDIA_KIT));
        v.put("rates", link(mine, RATES));
        v.put("instagram", link(mine, INSTAGRAM));
        v.put("my_name", settings.creatorName());
        return v;
    }

    private static String link(List<CreatorLink> mine, Pattern label) {
        return mine.stream().filter(l -> l.label != null && label.matcher(l.label).find()).map(l -> l.url).findFirst().orElse(null);
    }

    /** Research notes read as her own words: a whole sentence with a full stop. */
    private static String sentence(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip();
        t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        return t.matches(".*[.!?]$") ? t : t + ".";
    }

    // ---------- approving ----------

    /**
     * She read the campaign email and approved it: her edits are saved and it waits in the slow send queue. Refused
     * when it couldn't be sent (blanks left, do-not-email, Gmail not connected), so problems show now, not later.
     */
    @Transactional
    public Draft approve(Long draftId, String subject, String body) {
        requireAddress();
        Draft d = drafts.findById(draftId).orElseThrow(() -> new IllegalArgumentException("Unknown draft"));
        if (d.campaignTargetId == null) throw new IllegalStateException("Only campaign emails are approved for the send queue");
        if (d.status != DraftStatus.PENDING) throw new IllegalStateException("This email was already " + d.status.name().toLowerCase());
        d = draftService.edit(draftId, subject, body);
        d.body = CampaignFooter.ensure(d.body, settings.creatorName(), settings.campaignAddress());
        Draft check = d;
        draftService.sendBlockedReason(check).ifPresent(reason -> { throw new IllegalStateException(reason); });
        String blanks = Placeholders.message(d.body);
        if (blanks != null) throw new IllegalStateException(blanks);
        if (d.type == DraftType.PITCH && (d.subject == null || d.subject.isBlank())) throw new IllegalStateException("Add a subject line");
        d.approvedAt = OffsetDateTime.now();
        d.error = null;
        targets.findById(d.campaignTargetId).ifPresent(t -> {
            if (check.id.equals(t.draftId) && t.state == State.DRAFTED) {
                t.state = State.APPROVED;
                t.approvedAt = OffsetDateTime.now();
                targets.save(t);
            }
        });
        return drafts.save(d);
    }

    /** Approves every campaign pitch in Drafts that is ready; the rest stay for her with the reason. */
    @Transactional
    public Map<String, Object> approveAll(Long campaignId) {
        int ok = 0;
        List<String> skipped = new ArrayList<>();
        for (CampaignTarget t : targets.findByCampaignIdOrderByIdAsc(campaignId)) {
            if (t.state != State.DRAFTED || t.draftId == null) continue;
            try {
                approve(t.draftId, null, null);
                ok++;
            } catch (RuntimeException e) {
                skipped.add(brands.findById(t.brandId).map(b -> b.name).orElse("?") + ": " + e.getMessage());
            }
        }
        return Map.of("approved", ok, "skipped", skipped);
    }

    /** Takes an approved email out of the queue and back to Drafts. */
    @Transactional
    public Draft unapprove(Long draftId) {
        Draft d = drafts.findById(draftId).orElseThrow(() -> new IllegalArgumentException("Unknown draft"));
        if (d.status != DraftStatus.PENDING || d.approvedAt == null) throw new IllegalStateException("This email isn't waiting to be sent");
        d.approvedAt = null;
        targets.findById(d.campaignTargetId).ifPresent(t -> {
            if (d.id.equals(t.draftId) && t.state == State.APPROVED) {
                t.state = State.DRAFTED;
                t.approvedAt = null;
                targets.save(t);
            }
        });
        return drafts.save(d);
    }

    /** The queue couldn't send it: back to Drafts with the reason, for her to fix or discard. */
    @Transactional
    public void returnToDrafts(Long draftId, String error) {
        drafts.findById(draftId).ifPresent(d -> {
            d.approvedAt = null;
            d.error = clip(error, 1000);
            drafts.save(d);
            targets.findById(d.campaignTargetId).ifPresent(t -> {
                if (d.id.equals(t.draftId) && t.state == State.APPROVED) {
                    t.state = State.DRAFTED;
                    t.approvedAt = null;
                    targets.save(t);
                }
            });
        });
    }

    @Transactional
    public void markSent(Long targetId) {
        targets.findById(targetId).ifPresent(t -> {
            t.state = State.SENT;
            t.sentAt = OffsetDateTime.now();
            targets.save(t);
        });
    }

    // ---------- follow-ups ----------

    /**
     * A follow-up for a campaign pitch, from the template's follow-up text: no Claude. False when this isn't a
     * campaign's deal or its template has no follow-up text, so the usual Claude follow-up is written instead.
     * With automatic follow-ups on, it goes straight to the send queue; otherwise it waits in Drafts.
     */
    @Transactional
    public boolean draftFollowUp(FollowUp fu) {
        Optional<CampaignTarget> target = targets.findFirstByOpportunityIdOrderByIdDesc(fu.opportunityId);
        if (target.isEmpty()) return false;
        CampaignTarget t = target.get();
        Campaign c = campaigns.findById(t.campaignId).orElse(null);
        PitchTemplate tpl = c == null ? null : templates.findById(c.templateId).orElse(null);
        if (tpl == null || tpl.followUpBody == null) return false;
        if (t.state != State.SENT || c.status == Campaign.Status.DONE) return true; // nothing to chase
        if (drafts.existsByFollowupIdAndStatus(fu.id, DraftStatus.PENDING)) return true;
        Brand b = brands.findById(t.brandId).orElseThrow();
        BrandContact contact = t.contactId == null ? null : contacts.findById(t.contactId).orElse(null);
        Map<String, String> v = values(b, contact, t.openingLine);
        DraftText text = new DraftText("", CampaignFooter.ensure(MergeFields.fill(tpl.followUpBody, v).strip(),
                settings.creatorName(), settings.campaignAddress()));
        Draft d = draftService.createWritten(fu.opportunityId, DraftType.FOLLOW_UP, fu.id, t.email, text, t.id);
        if (settings.followupAutoSend() && !settings.campaignAddress().isBlank() && Placeholders.message(d.body) == null
                && draftService.sendBlockedReason(d).isEmpty()) {
            d.approvedAt = OffsetDateTime.now();
            drafts.save(d);
        }
        return true;
    }

    // ---------- ending, stopping, moving on ----------

    /** Ends a waiting or sent target; a pitch never sent is taken out of Drafts and its new deal closed. */
    @Transactional
    public void end(CampaignTarget t, State to, String reason) {
        boolean neverSent = t.sentAt == null;
        t.state = to;
        t.endedReason = clip(reason, 300);
        targets.save(t);
        for (Draft d : drafts.findByCampaignTargetIdAndStatus(t.id, DraftStatus.PENDING)) {
            d.status = DraftStatus.SUPERSEDED;
            d.approvedAt = null;
            drafts.save(d);
        }
        if (t.opportunityId == null) return;
        opportunities.findById(t.opportunityId).ifPresent(o -> {
            if (neverSent && o.status == OpportunityStatus.NEW_LEAD) {
                o.status = OpportunityStatus.CLOSED;
                o.closedReason = clip("Campaign pitch not sent: " + reason, 200);
                o.updatedAt = OffsetDateTime.now();
                opportunities.save(o);
            } else if (!neverSent) {
                followUps.stop(o);
            }
        });
    }

    /**
     * Housekeeping, run with the send queue: pitches she discarded in Drafts are skipped; sent pitches whose deal
     * went cold (no reply after every follow-up) are marked cold, and each brand that went cold or bounced is tried
     * once more with its next-ranked person.
     */
    @Transactional
    public int reconcile() {
        int moved = 0;
        for (CampaignTarget t : targets.findByStateIn(Set.of(State.DRAFTED, State.APPROVED, State.SENT, State.COLD, State.BOUNCED))) {
            if ((t.state == State.DRAFTED || t.state == State.APPROVED) && t.draftId != null) {
                Draft d = drafts.findById(t.draftId).orElse(null);
                if (d == null || d.status == DraftStatus.DISCARDED) end(t, State.SKIPPED, "You discarded the pitch");
                continue;
            }
            if (t.state == State.SENT) {
                Opportunity o = t.opportunityId == null ? null : opportunities.findById(t.opportunityId).orElse(null);
                if (o != null && o.status == OpportunityStatus.COLD) {
                    t.state = State.COLD;
                    t.endedReason = "No reply after the follow-ups";
                    targets.save(t);
                }
            }
            if ((t.state == State.COLD || t.state == State.BOUNCED) && t.attempt == 1 && moveOn(t)) moved++;
        }
        return moved;
    }

    /** One more try at a brand that went cold or bounced: its next-ranked person nobody has pitched yet. */
    private boolean moveOn(CampaignTarget t) {
        Campaign c = campaigns.findById(t.campaignId).orElse(null);
        if (c == null || c.status != Campaign.Status.ACTIVE) return false;
        List<CampaignTarget> forBrand = targets.findByBrandId(t.brandId);
        if (forBrand.stream().anyMatch(x -> ANSWERED.contains(x.state) || (!x.id.equals(t.id) && ACTIVE_OR_SENT.contains(x.state)))) return false;
        if (forBrand.stream().anyMatch(x -> x.campaignId.equals(c.id) && x.attempt == 2)) return false;
        Set<String> tried = forBrand.stream().map(x -> x.email).collect(Collectors.toSet());
        Optional<BrandContact> next = contacts.findByBrandIdOrderByScoreDescIdAsc(t.brandId).stream()
                .filter(BrandContact::emailable).filter(x -> !tried.contains(x.email)).filter(x -> !suppressions.blocked(x.email))
                .findFirst();
        String why = t.state == State.BOUNCED ? "Bounced" : "No reply";
        t.state = State.MOVED_ON;
        t.endedReason = next.map(n -> why + "; trying " + n.email + " next").orElse(why + "; nobody else at this brand to try");
        targets.save(t);
        if (next.isEmpty()) return false;
        Brand b = brands.findById(t.brandId).orElseThrow();
        CampaignTarget again = newTarget(c, new Pick(b, next.get()), 2);
        if (c.personalise) {
            again.state = State.WAITING_LINE;
            again.openingLine = t.openingLine;
            if (again.openingLine != null) draft(again, c, templates.findById(c.templateId).orElseThrow());
            else targets.save(again);
        } else {
            draft(again, c, templates.findById(c.templateId).orElseThrow());
        }
        return true;
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}

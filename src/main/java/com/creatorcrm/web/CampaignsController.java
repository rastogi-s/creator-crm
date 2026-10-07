package com.creatorcrm.web;

import com.creatorcrm.campaigns.CampaignService;
import com.creatorcrm.campaigns.CampaignState;
import com.creatorcrm.campaigns.ContactFilter;
import com.creatorcrm.campaigns.MergeFields;
import com.creatorcrm.campaigns.OpeningLines;
import com.creatorcrm.domain.Campaign;
import com.creatorcrm.domain.ContactList;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.PitchTemplate;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.llm.DraftText;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.WorkflowEngine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Campaigns page API: saved lists, templates, campaigns, approving pitches, and the send queue's state. */
@RestController
@RequestMapping("/api/campaigns")
public class CampaignsController {

    public record ListInput(@Size(max = 120) String name, @Valid ContactFilter filter) {}

    public record TemplateInput(@Size(max = 120) String name, PitchTemplate.Kind kind, @Size(max = 300) String subject,
                                @Size(max = 8000) String body, @Size(max = 4000) String followUpBody, Long brandId) {}

    public record StartInput(@Size(max = 120) String name, @NotNull Long listId, @NotNull Long templateId, boolean personalise) {}

    public record DraftEdit(@Size(max = 1000) String subject, @Size(max = 20000) String body) {}

    public record QueuedEmail(Long draftId, Long opportunityId, String brand, String to, String type, String subject,
                              OffsetDateTime approvedAt) {}

    /** Everything the Campaigns page shows at the top: is it sending, how much today, and what's next. */
    public record Overview(String pausedReason, String pausedAt, long sentToday, int allowance, int cap, boolean warmup,
                           OffsetDateTime nextSendAt, String heldBecause, boolean addressSet, String address, boolean claudeReady,
                           List<QueuedEmail> queue, List<CampaignService.CampaignView> campaigns,
                           List<CampaignService.ListView> lists, List<PitchTemplate> templates, Map<String, String> fields) {}

    private final CampaignService campaigns;
    private final CampaignState state;
    private final OpeningLines openingLines;
    private final SettingsService settings;
    private final DraftRepo drafts;
    private final OpportunityRepo opportunities;
    private final WorkflowEngine workflow;
    private final LlmClient llm;
    private final DraftService draftService;

    public CampaignsController(CampaignService campaigns, CampaignState state, OpeningLines openingLines,
                               SettingsService settings, DraftRepo drafts, OpportunityRepo opportunities,
                               WorkflowEngine workflow, LlmClient llm, DraftService draftService) {
        this.campaigns = campaigns;
        this.state = state;
        this.openingLines = openingLines;
        this.settings = settings;
        this.drafts = drafts;
        this.opportunities = opportunities;
        this.workflow = workflow;
        this.llm = llm;
        this.draftService = draftService;
    }

    @GetMapping
    public Overview overview() {
        List<Draft> queued = drafts.findByStatusAndApprovedAtNotNullOrderByApprovedAtAscIdAsc(DraftStatus.PENDING);
        // e.g. Gmail isn't connected: approved emails wait until it is
        String held = queued.isEmpty() ? null : draftService.sendBlockedReason(queued.get(0)).orElse(null);
        List<QueuedEmail> queue = queued.stream()
                .map(d -> new QueuedEmail(d.id, d.opportunityId, opportunities.findById(d.opportunityId).map(workflow::brandName).orElse(""),
                        d.toAddress, d.type.name(), d.subject, d.approvedAt))
                .toList();
        String address = settings.campaignAddress();
        return new Overview(state.pausedReason().orElse(null), state.pausedAt().orElse(null), state.sentToday(),
                state.dailyAllowance(), settings.campaignDailyCap(), settings.campaignWarmup(),
                state.nextSendAt().filter(t -> t.isAfter(OffsetDateTime.now())).orElse(null), held, !address.isBlank(), address,
                llm.isConfigured(), queue, campaigns.campaigns(), campaigns.lists(), campaigns.templates(), MergeFields.FIELDS);
    }

    // ---------- campaigns ----------

    @PostMapping
    public Campaign start(@Valid @RequestBody StartInput in) {
        Campaign c = campaigns.start(in.name(), in.listId(), in.templateId(), in.personalise(), llm.isConfigured());
        campaigns.draftMore(c.id);
        if (c.personalise) openingLines.submitWaiting();
        return c;
    }

    @GetMapping("/{id}/targets")
    public List<CampaignService.TargetRow> targets(@PathVariable Long id) {
        return campaigns.targets(id);
    }

    @PostMapping("/{id}/pause")
    public Campaign pause(@PathVariable Long id) {
        return campaigns.setStatus(id, Campaign.Status.PAUSED);
    }

    @PostMapping("/{id}/resume")
    public Campaign resume(@PathVariable Long id) {
        return campaigns.setStatus(id, Campaign.Status.ACTIVE);
    }

    @PostMapping("/{id}/end")
    public Campaign end(@PathVariable Long id) {
        return campaigns.setStatus(id, Campaign.Status.DONE);
    }

    @PostMapping("/{id}/draft-more")
    public Map<String, Integer> draftMore(@PathVariable Long id) {
        int n = campaigns.draftMore(id);
        openingLines.submitWaiting();
        return Map.of("added", n);
    }

    @PostMapping("/{id}/approve-all")
    public Map<String, Object> approveAll(@PathVariable Long id) {
        return campaigns.approveAll(id);
    }

    // ---------- sending on/off ----------

    /** Resume after an automatic or manual pause. */
    @PostMapping("/sending/resume")
    public Overview resumeSending() {
        state.resume();
        return overview();
    }

    @PostMapping("/sending/pause")
    public Overview pauseSending() {
        state.pause("You paused sending.");
        return overview();
    }

    // ---------- approving ----------

    /** She read the campaign email and approved it: it waits in the slow send queue. */
    @PostMapping("/drafts/{draftId}/approve")
    public Draft approve(@PathVariable Long draftId, @Valid @RequestBody(required = false) DraftEdit e) {
        return campaigns.approve(draftId, e == null ? null : e.subject(), e == null ? null : e.body());
    }

    @PostMapping("/drafts/{draftId}/unapprove")
    public Draft unapprove(@PathVariable Long draftId) {
        return campaigns.unapprove(draftId);
    }

    // ---------- lists ----------

    @PostMapping("/lists")
    public ContactList addList(@Valid @RequestBody ListInput in) {
        return campaigns.saveList(null, in.name(), in.filter());
    }

    @PutMapping("/lists/{id}")
    public ContactList saveList(@PathVariable Long id, @Valid @RequestBody ListInput in) {
        return campaigns.saveList(id, in.name(), in.filter());
    }

    @DeleteMapping("/lists/{id}")
    public ResponseEntity<Void> deleteList(@PathVariable Long id) {
        campaigns.deleteList(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/lists/preview")
    public CampaignService.Preview previewList(@Valid @RequestBody ListInput in) {
        return campaigns.preview(in.filter() == null ? new ContactFilter(null, false, false, null, null, null, true) : in.filter());
    }

    // ---------- templates ----------

    @PostMapping("/templates")
    public PitchTemplate addTemplate(@Valid @RequestBody TemplateInput in) {
        return campaigns.saveTemplate(null, in.name(), in.kind(), in.subject(), in.body(), in.followUpBody());
    }

    @PutMapping("/templates/{id}")
    public PitchTemplate saveTemplate(@PathVariable Long id, @Valid @RequestBody TemplateInput in) {
        return campaigns.saveTemplate(id, in.name(), in.kind(), in.subject(), in.body(), in.followUpBody());
    }

    @DeleteMapping("/templates/{id}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable Long id) {
        campaigns.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }

    /** The template (as typed, unsaved) filled in for one of her brands, footer included. */
    @PostMapping("/templates/preview")
    public DraftText previewTemplate(@Valid @RequestBody TemplateInput in) {
        PitchTemplate t = new PitchTemplate();
        t.subject = in.subject() == null ? "" : in.subject();
        t.body = in.body() == null ? "" : in.body();
        return campaigns.sample(t, in.brandId());
    }
}

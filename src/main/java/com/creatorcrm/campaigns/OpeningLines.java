package com.creatorcrm.campaigns;

import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.BrandLead;
import com.creatorcrm.domain.Campaign;
import com.creatorcrm.domain.CampaignTarget;
import com.creatorcrm.domain.CampaignTarget.State;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.llm.OpeningLineInput;
import com.creatorcrm.repo.BrandLeadRepo;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.CampaignRepo;
import com.creatorcrm.repo.CampaignTargetRepo;
import com.creatorcrm.repo.PitchTemplateRepo;
import com.creatorcrm.security.SetupService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Personalise: one opening line per brand from Claude Haiku, asked for in one half-price batch (results usually
 * within the hour, at most a day). When the batch fails or takes too long, the pitch is drafted from the template
 * alone, so a campaign never waits on Claude. Roughly a tenth of a cent per brand.
 */
@Service
public class OpeningLines {
    private static final Logger log = LoggerFactory.getLogger(OpeningLines.class);
    /** Batches can take up to a day; after that the pitch goes ahead without its line. */
    static final Duration GIVE_UP = Duration.ofHours(26);

    private final CampaignTargetRepo targets;
    private final CampaignRepo campaigns;
    private final PitchTemplateRepo templates;
    private final BrandRepo brands;
    private final BrandLeadRepo leads;
    private final LlmClient llm;
    private final CampaignService service;
    private final SetupService setup;

    public OpeningLines(CampaignTargetRepo targets, CampaignRepo campaigns, PitchTemplateRepo templates, BrandRepo brands,
                        BrandLeadRepo leads, LlmClient llm, CampaignService service, SetupService setup) {
        this.targets = targets;
        this.campaigns = campaigns;
        this.templates = templates;
        this.brands = brands;
        this.leads = leads;
        this.llm = llm;
        this.service = service;
        this.setup = setup;
    }

    /** Sends every brand still waiting for a line, and not already asked, to Claude in one batch. */
    public int submitWaiting() {
        List<CampaignTarget> waiting = targets.findByStateIn(Set.of(State.WAITING_LINE)).stream()
                .filter(t -> t.batchId == null).toList();
        if (waiting.isEmpty()) return 0;
        if (!llm.isConfigured()) {
            waiting.forEach(t -> draftWithout(t, "Claude isn't connected"));
            return 0;
        }
        Map<String, OpeningLineInput> inputs = new LinkedHashMap<>();
        for (CampaignTarget t : waiting) {
            Brand b = brands.findById(t.brandId).orElseThrow();
            BrandLead lead = b.nameKey == null ? null : leads.findFirstByNameKeyOrderByIdDesc(b.nameKey).orElse(null);
            inputs.put("t" + t.id, new OpeningLineInput(b.name, lead == null ? null : lead.fitReason,
                    lead == null ? null : lead.pitchAngle, lead == null ? null : lead.igBio));
        }
        try {
            String batch = llm.submitOpeningLines(inputs);
            for (CampaignTarget t : waiting) {
                t.batchId = batch;
                targets.save(t);
            }
            log.info("Asked Claude for {} opening lines (batch {})", waiting.size(), batch);
            return waiting.size();
        } catch (RuntimeException e) {
            log.warn("Could not ask Claude for opening lines, using the template on its own: {}", e.getMessage());
            waiting.forEach(t -> draftWithout(t, e.getMessage()));
            return 0;
        }
    }

    /** Every few minutes: collect finished batches and draft those pitches. */
    @Scheduled(cron = "${crm.schedule.opening-lines-cron:0 */5 * * * *}")
    public void collect() {
        if (!setup.isSetupComplete()) return;
        submitWaiting();
        Map<String, List<CampaignTarget>> byBatch = targets.findByStateIn(Set.of(State.WAITING_LINE)).stream()
                .filter(t -> t.batchId != null).collect(Collectors.groupingBy(t -> t.batchId));
        byBatch.forEach((batch, list) -> {
            Map<String, String> lines;
            try {
                lines = llm.pollOpeningLines(batch);
            } catch (RuntimeException e) {
                log.warn("Could not read opening lines batch {}: {}", batch, e.getMessage());
                lines = null;
                if (list.stream().allMatch(t -> Duration.between(t.createdAt, OffsetDateTime.now()).compareTo(GIVE_UP) > 0)) {
                    list.forEach(t -> draftWithout(t, "the batch didn't finish"));
                }
                return;
            }
            if (lines == null) {
                if (list.stream().allMatch(t -> Duration.between(t.createdAt, OffsetDateTime.now()).compareTo(GIVE_UP) > 0)) {
                    list.forEach(t -> draftWithout(t, "the batch took too long"));
                }
                return;
            }
            for (CampaignTarget t : list) {
                t.openingLine = lines.get("t" + t.id);
                draft(t);
            }
        });
    }

    private void draftWithout(CampaignTarget t, String why) {
        log.info("Drafting campaign pitch {} without a personal line: {}", t.id, why);
        t.openingLine = null;
        draft(t);
    }

    private void draft(CampaignTarget t) {
        Campaign c = campaigns.findById(t.campaignId).orElse(null);
        if (c == null || c.status == Campaign.Status.DONE) {
            service.end(t, State.STOPPED, "Campaign ended before the pitch was written");
            return;
        }
        try {
            service.draft(t, c, templates.findById(c.templateId).orElseThrow());
        } catch (RuntimeException e) {
            log.warn("Could not draft campaign pitch {}: {}", t.id, e.getMessage());
            service.end(t, State.SKIPPED, "Couldn't be drafted: " + e.getMessage());
        }
    }
}

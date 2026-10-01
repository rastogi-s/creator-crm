package com.creatorcrm.jobs;

import com.creatorcrm.domain.FollowUp;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.security.SetupService;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.FollowUpEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledJobs {
    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final IngestionService ingestion;
    private final FollowUpEngine followUps;
    private final DraftService drafts;
    private final LlmClient llm;
    private final SettingsService settings;
    private final SetupService setup;

    public ScheduledJobs(IngestionService ingestion, FollowUpEngine followUps, DraftService drafts, LlmClient llm,
                         SettingsService settings, SetupService setup) {
        this.ingestion = ingestion;
        this.followUps = followUps;
        this.drafts = drafts;
        this.llm = llm;
        this.settings = settings;
        this.setup = setup;
    }

    @Scheduled(cron = "${crm.schedule.sync-cron}")
    public void sync() {
        if (!setup.isSetupComplete()) return;
        log.info("Sync: {}", ingestion.syncAll());
    }

    /** Morning prep: fresh sync, mark cold deals, and pre-draft today's follow-ups for one-click approval. */
    @Scheduled(cron = "${crm.schedule.morning-digest-cron}")
    public void morning() {
        if (!setup.isSetupComplete()) return;
        sync();
        prepareMorning();
    }

    public int prepareMorning() {
        int cold = followUps.markColdDeals(settings.today());
        int drafted = 0;
        if (llm.isConfigured()) {
            for (FollowUp f : followUps.dueOnOrBefore(settings.today())) {
                try {
                    if (drafts.draftForFollowUp(f).isPresent()) drafted++;
                } catch (RuntimeException e) {
                    log.warn("Could not draft follow-up {}: {}", f.id, e.getMessage());
                }
            }
        }
        log.info("Morning prep: {} follow-up drafts, {} deals marked cold", drafted, cold);
        return drafted;
    }
}

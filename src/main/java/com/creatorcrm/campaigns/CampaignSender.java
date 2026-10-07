package com.creatorcrm.campaigns;

import com.creatorcrm.contacts.Suppressions;
import com.creatorcrm.domain.Campaign;
import com.creatorcrm.domain.CampaignTarget;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.repo.CampaignRepo;
import com.creatorcrm.repo.CampaignTargetRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.security.SetupService;
import com.creatorcrm.settings.SettingsService;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The slow send queue for campaign emails she approved. At most one email every few minutes (a random 2 to 6), only
 * on weekdays between 9 and 5 where the recipient is, never more new pitches a day than the cap (and the warm-up)
 * allow, never two people at one brand on the same day, and nothing at all while sending is paused. Each email is
 * checked against the do-not-email list again right before it goes. The queue lives in the database, so closing
 * the app only delays it.
 */
@Service
public class CampaignSender {
    private static final Logger log = LoggerFactory.getLogger(CampaignSender.class);

    private final DraftRepo drafts;
    private final DraftService draftService;
    private final CampaignTargetRepo targets;
    private final CampaignRepo campaigns;
    private final CampaignService service;
    private final CampaignWatcher watcher;
    private final OpeningLines openingLines;
    private final CampaignState state;
    private final SettingsService settings;
    private final Suppressions suppressions;
    private final SetupService setup;
    private final int minGapSeconds;
    private final int maxGapSeconds;
    private final boolean workingHoursOnly;

    public CampaignSender(DraftRepo drafts, DraftService draftService, CampaignTargetRepo targets, CampaignRepo campaigns,
                          CampaignService service, CampaignWatcher watcher, OpeningLines openingLines, CampaignState state,
                          SettingsService settings, Suppressions suppressions, SetupService setup,
                          @Value("${crm.campaigns.min-gap-seconds:120}") int minGapSeconds,
                          @Value("${crm.campaigns.max-gap-seconds:360}") int maxGapSeconds,
                          @Value("${crm.campaigns.working-hours-only:true}") boolean workingHoursOnly) {
        this.workingHoursOnly = workingHoursOnly;
        this.drafts = drafts;
        this.draftService = draftService;
        this.targets = targets;
        this.campaigns = campaigns;
        this.service = service;
        this.watcher = watcher;
        this.openingLines = openingLines;
        this.state = state;
        this.settings = settings;
        this.suppressions = suppressions;
        this.setup = setup;
        this.minGapSeconds = minGapSeconds;
        this.maxGapSeconds = Math.max(minGapSeconds, maxGapSeconds);
    }

    /** Every minute: read new replies and bounces first, tidy up, then send at most one email if one is due. */
    @Scheduled(cron = "${crm.schedule.campaign-cron:30 * * * * *}")
    public void tick() {
        if (!setup.isSetupComplete()) return;
        try {
            watcher.scan();
            service.reconcile();
            sendNext();
        } catch (RuntimeException e) {
            log.warn("Campaign send queue: {}", e.getMessage());
        }
    }

    /** The daily run: top up each running campaign's pitches and ask for any opening lines they need. */
    public void morning() {
        for (Campaign c : campaigns.findByStatus(Campaign.Status.ACTIVE)) {
            try {
                service.draftMore(c.id);
            } catch (RuntimeException e) {
                log.warn("Could not draft more pitches for campaign {}: {}", c.name, e.getMessage());
            }
        }
        openingLines.submitWaiting();
    }

    /** Sends the next approved email that may go now, if any. */
    public synchronized Optional<Draft> sendNext() {
        if (state.pausedReason().isPresent()) return Optional.empty();
        ZonedDateTime now = state.now();
        Optional<OffsetDateTime> next = state.nextSendAt();
        if (next.isPresent() && now.toOffsetDateTime().isBefore(next.get())) return Optional.empty();
        List<Draft> queue = drafts.findByStatusAndApprovedAtNotNullOrderByApprovedAtAscIdAsc(DraftStatus.PENDING);
        if (queue.isEmpty()) return Optional.empty();
        int allowance = state.dailyAllowance();
        long sentToday = state.sentToday();
        OffsetDateTime today = state.startOfToday();
        for (Draft d : queue) {
            CampaignTarget t = d.campaignTargetId == null ? null : targets.findById(d.campaignTargetId).orElse(null);
            if (t == null) continue;
            Campaign c = campaigns.findById(t.campaignId).orElse(null);
            if (c == null || c.status != Campaign.Status.ACTIVE) continue;
            boolean pitch = d.type == DraftType.PITCH;
            if (pitch && (sentToday >= allowance || targets.existsByBrandIdAndSentAtGreaterThanEqual(t.brandId, today))) continue;
            if (workingHoursOnly && !RecipientHours.isWorkingTime(d.toAddress, now, settings.zone())) continue;
            if (suppressions.blocked(d.toAddress)) {
                service.returnToDrafts(d.id, suppressions.find(d.toAddress).map(Suppressions::explain).orElse("On your do-not-email list"));
                continue;
            }
            Optional<String> blocked = draftService.sendBlockedReason(d);
            if (blocked.isPresent()) {
                // Gmail not connected, for example: everything waits until it's back
                log.info("Campaign queue waiting: {}", blocked.get());
                return Optional.empty();
            }
            try {
                Draft sent = draftService.sendApproved(d.id);
                if (pitch) service.markSent(t.id);
                state.nextSendAt(OffsetDateTime.now().plusSeconds(ThreadLocalRandom.current().nextInt(minGapSeconds, maxGapSeconds + 1)));
                log.info("Campaign email sent to {} ({} of {} new pitches today)", d.toAddress, sentToday + (pitch ? 1 : 0), allowance);
                return Optional.of(sent);
            } catch (RuntimeException e) {
                service.returnToDrafts(d.id, e.getMessage() == null ? "Sending failed" : e.getMessage());
                state.nextSendAt(OffsetDateTime.now().plusSeconds(minGapSeconds));
                log.warn("Campaign email to {} not sent: {}", d.toAddress, e.getMessage());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}

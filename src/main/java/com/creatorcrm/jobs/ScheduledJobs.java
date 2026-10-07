package com.creatorcrm.jobs;

import com.creatorcrm.calendar.CalendarSync;
import com.creatorcrm.results.CampaignResults;
import com.creatorcrm.channels.instagram.InstagramStatsService;
import com.creatorcrm.contacts.GmailContacts;
import com.creatorcrm.domain.AppState;
import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Enums.FollowUpStatus;
import com.creatorcrm.domain.Enums.Platform;
import com.creatorcrm.domain.FollowUp;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.drafts.SendQueue;
import com.creatorcrm.drafts.Placeholders;
import com.creatorcrm.ingest.IngestionService;
import com.creatorcrm.invoices.PaymentReminders;
import com.creatorcrm.rebook.WinBack;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.outreach.InstagramEngagementService;
import com.creatorcrm.repo.AppStateRepo;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.FollowUpRepo;
import com.creatorcrm.security.SetupService;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.FollowUpEngine;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledJobs {
    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);
    static final String LAST_MORNING_RUN = "morning.lastRun";

    private final IngestionService ingestion;
    private final FollowUpEngine followUps;
    private final FollowUpRepo followUpRepo;
    private final DraftService drafts;
    private final DraftRepo draftRepo;
    private final AppStateRepo state;
    private final LlmClient llm;
    private final SettingsService settings;
    private final SetupService setup;
    private final InstagramStatsService instagramStats;
    private final PaymentReminders paymentReminders;
    private final WinBack winBack;
    private final InstagramEngagementService instagramEngagement;
    private final CalendarSync calendar;
    private final CampaignResults results;
    private final SendQueue sendQueue;
    private final GmailContacts gmailContacts;

    public ScheduledJobs(IngestionService ingestion, FollowUpEngine followUps, FollowUpRepo followUpRepo,
                         DraftService drafts, DraftRepo draftRepo, AppStateRepo state, LlmClient llm,
                         SettingsService settings, SetupService setup, InstagramStatsService instagramStats,
                         PaymentReminders paymentReminders, WinBack winBack,
                         InstagramEngagementService instagramEngagement, CalendarSync calendar,
                         CampaignResults results, SendQueue sendQueue, GmailContacts gmailContacts) {
        this.gmailContacts = gmailContacts;
        this.results = results;
        this.sendQueue = sendQueue;
        this.instagramEngagement = instagramEngagement;
        this.calendar = calendar;
        this.paymentReminders = paymentReminders;
        this.winBack = winBack;
        this.ingestion = ingestion;
        this.followUps = followUps;
        this.followUpRepo = followUpRepo;
        this.drafts = drafts;
        this.draftRepo = draftRepo;
        this.state = state;
        this.llm = llm;
        this.settings = settings;
        this.setup = setup;
        this.instagramStats = instagramStats;
    }

    @Scheduled(cron = "${crm.schedule.sync-cron}")
    public void sync() {
        if (!setup.isSetupComplete()) return;
        log.info("Sync: {}", ingestion.syncAll());
        int engaged = instagramEngagement.poll();
        if (engaged > 0) log.info("Instagram: {} new comments, tags or mentions", engaged);
        calendar.sync(); // after new mail, so fresh deadlines show up on her calendar
    }

    /** While older email is being analyzed in a half-price batch, check for its results every few minutes. */
    @Scheduled(cron = "${crm.schedule.batch-check-cron:0 */5 * * * *}")
    public void checkBatch() {
        if (!setup.isSetupComplete() || !ingestion.batchInFlight()) return;
        ingestion.processPending();
    }

    /**
     * Checks every minute whether the daily follow-up run is due: once a day, at or after the follow-up time
     * set on the Settings page (creator's time zone). If the computer was off at that time, it runs as soon as
     * the app is back.
     */
    @Scheduled(cron = "${crm.schedule.morning-check-cron}")
    public void morningCheck() {
        if (!setup.isSetupComplete()) return;
        ZonedDateTime now = ZonedDateTime.now(settings.zone());
        if (!morningDue(now)) return;
        markMorningRun(now.toLocalDate());
        sync();
        instagramStats.refreshIfStale();
        try {
            gmailContacts.refresh(); // after the sync, so today's replies count towards who ranks first
        } catch (RuntimeException e) {
            log.warn("Could not refresh brand contacts: {}", e.getMessage());
        }
        prepareMorning();
        if (settings.followupAutoSend()) autoSendFollowUps();
    }

    boolean morningDue(ZonedDateTime now) {
        if (now.toLocalTime().isBefore(settings.followupTime())) return false;
        String last = state.findById(LAST_MORNING_RUN).map(s -> s.stateValue).orElse("");
        return !now.toLocalDate().toString().equals(last);
    }

    private void markMorningRun(LocalDate date) {
        AppState s = new AppState();
        s.stateKey = LAST_MORNING_RUN;
        s.stateValue = date.toString();
        state.save(s);
    }

    /**
     * Morning prep: mark cold deals, pre-draft today's follow-ups, payment reminders and this week's win-back
     * re-pitches for one-click approval. Payment reminders and re-pitches never go out automatically (see {@link #autoSendFollowUps}).
     */
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
        int reminders = paymentReminders.draftDue(settings.today());
        int repitches = winBack.draftDue(settings.today());
        int recaps = results.daily();
        log.info("Morning prep: {} follow-up drafts, {} payment reminders, {} re-pitches, {} results recaps, {} deals marked cold",
                drafted, reminders, repitches, recaps, cold);
        return drafted;
    }

    /**
     * Opt-in: send pending email follow-up drafts whose follow-up is still due today. Instagram follow-ups,
     * follow-ups the brand already answered, and every other kind of draft stay in the approval queue.
     */
    public int autoSendFollowUps() {
        LocalDate today = settings.today();
        int sent = 0;
        for (Draft d : draftRepo.findByStatusOrderByCreatedAtAsc(DraftStatus.PENDING)) {
            if (d.type != DraftType.FOLLOW_UP || d.channel != Platform.EMAIL || d.followupId == null) continue;
            boolean stillDue = followUpRepo.findById(d.followupId)
                    .map(f -> f.status == FollowUpStatus.SCHEDULED && !f.scheduledDate.isAfter(today))
                    .orElse(false);
            // One she just pressed Send on is already on its way, after its undo window
            if (!stillDue || sendQueue.isWaiting(d.id) || drafts.sendBlockedReason(d).isPresent()) continue;
            if (Placeholders.message(d.body) != null) {
                log.info("Follow-up draft {} has blanks to fill in; leaving it in Drafts", d.id);
                continue;
            }
            try {
                drafts.sendFollowUpAutomatically(d.id);
                sent++;
            } catch (RuntimeException e) {
                log.warn("Could not auto-send follow-up draft {}: {}", d.id, e.getMessage());
            }
        }
        log.info("Auto-sent {} follow-up emails", sent);
        return sent;
    }
}

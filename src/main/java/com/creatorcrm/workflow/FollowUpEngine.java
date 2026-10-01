package com.creatorcrm.workflow;

import com.creatorcrm.domain.Activity;
import com.creatorcrm.domain.Enums.FollowUpStatus;
import com.creatorcrm.domain.Enums.OpportunityStatus;
import com.creatorcrm.domain.FollowUp;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.repo.ActivityRepo;
import com.creatorcrm.repo.FollowUpRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic follow-up schedule. The database owns this state, not the LLM.
 *
 * <pre>
 * creator sends a message (pitch / reply)  -> schedule #1 at +cadence[1]
 * creator sends #n                         -> mark #n done, schedule #n+1 at +cadence[n+1]
 * brand replies                            -> cancel the scheduled follow-up
 * final follow-up done and still no reply  -> after cadence[last] days, mark the deal Cold
 * </pre>
 */
@Service
public class FollowUpEngine {

    /** A creator message this close to the due date counts as that follow-up. */
    private static final int EARLY_TOLERANCE_DAYS = 1;

    private final FollowUpRepo followUps;
    private final OpportunityRepo opportunities;
    private final ActivityRepo activity;
    private final SettingsService settings;

    public FollowUpEngine(FollowUpRepo followUps, OpportunityRepo opportunities, ActivityRepo activity,
                          SettingsService settings) {
        this.followUps = followUps;
        this.opportunities = opportunities;
        this.activity = activity;
        this.settings = settings;
    }

    public Optional<FollowUp> scheduled(Long opportunityId) {
        return followUps.findFirstByOpportunityIdAndStatus(opportunityId, FollowUpStatus.SCHEDULED);
    }

    /** The creator wrote to the brand; the ball is now in the brand's court. */
    @Transactional
    public void onCreatorMessage(Opportunity o, LocalDate date, boolean explicitFollowUp) {
        Optional<FollowUp> current = scheduled(o.id);
        if (current.isEmpty()) {
            int lastDone = followUps.findByOpportunityIdOrderByNumberAsc(o.id).stream()
                    .filter(f -> f.status == FollowUpStatus.DONE).mapToInt(f -> f.number).max().orElse(0);
            if (lastDone >= settings.maxFollowups()) return; // sequence already exhausted
            // Fresh wait (new pitch, or a reply after the brand answered): restart the sequence at #1.
            schedule(o.id, 1, date.plusDays(settings.cadenceBefore(1)));
            return;
        }
        FollowUp fu = current.get();
        boolean countsAsFollowUp = explicitFollowUp || !date.isBefore(fu.scheduledDate.minusDays(EARLY_TOLERANCE_DAYS));
        if (!countsAsFollowUp) {
            // Wrote again well before the follow-up was due: restart the clock for this follow-up.
            fu.scheduledDate = date.plusDays(settings.cadenceBefore(fu.number));
            followUps.save(fu);
            return;
        }
        fu.status = FollowUpStatus.DONE;
        fu.completedDate = date;
        followUps.save(fu);
        activity.save(Activity.of(o.id, Activity.FOLLOWUP_SENT, "Follow-up #" + fu.number + " sent"));
        if (fu.number < settings.maxFollowups()) {
            schedule(o.id, fu.number + 1, date.plusDays(settings.cadenceBefore(fu.number + 1)));
        }
    }

    /** The brand answered: stop chasing. Returns true if a scheduled follow-up was cancelled. */
    @Transactional
    public boolean onBrandReply(Opportunity o) {
        Optional<FollowUp> current = scheduled(o.id);
        current.ifPresent(fu -> {
            fu.status = FollowUpStatus.CANCELLED;
            followUps.save(fu);
        });
        return current.isPresent();
    }

    @Transactional
    public void stop(Opportunity o) {
        onBrandReply(o);
    }

    /** Follow-ups due on or before the date, for open deals. */
    public List<FollowUp> dueOnOrBefore(LocalDate date) {
        return followUps.findByStatusAndScheduledDateLessThanEqualOrderByScheduledDateAsc(FollowUpStatus.SCHEDULED, date)
                .stream()
                .filter(f -> opportunities.findById(f.opportunityId).map(o -> o.status.isOpen()).orElse(false))
                .toList();
    }

    /** Deals with no reply after the final follow-up (plus one more wait) become Cold. */
    @Transactional
    public int markColdDeals(LocalDate today) {
        int max = settings.maxFollowups();
        int n = 0;
        for (FollowUp last : followUps.findByNumberAndStatusAndCompletedDateLessThanEqual(
                max, FollowUpStatus.DONE, today.minusDays(settings.cadenceBefore(max)))) {
            Opportunity o = opportunities.findById(last.opportunityId).orElse(null);
            if (o == null || !o.status.isOpen() || scheduled(o.id).isPresent()) continue;
            o.status = OpportunityStatus.COLD;
            o.closedReason = "No reply after " + max + " follow-ups";
            o.updatedAt = OffsetDateTime.now();
            opportunities.save(o);
            activity.save(Activity.of(o.id, Activity.STATUS_CHANGED, "Marked Cold: no reply after " + max + " follow-ups"));
            n++;
        }
        return n;
    }

    private void schedule(Long opportunityId, int number, LocalDate date) {
        FollowUp f = new FollowUp();
        f.opportunityId = opportunityId;
        f.number = number;
        f.scheduledDate = date;
        f.status = FollowUpStatus.SCHEDULED;
        followUps.save(f);
    }
}

package com.creatorcrm.drafts;

import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.security.SetupService;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the emails she scheduled. The schedule lives in the database and is checked every minute while the app
 * runs, so an email whose time passed while the laptop was off or asleep goes out as soon as the app is back.
 */
@Component
public class ScheduledSends {
    private static final Logger log = LoggerFactory.getLogger(ScheduledSends.class);

    private final DraftRepo drafts;
    private final DraftService draftService;
    private final SendQueue sendQueue;
    private final SetupService setup;

    public ScheduledSends(DraftRepo drafts, DraftService draftService, SendQueue sendQueue, SetupService setup) {
        this.drafts = drafts;
        this.draftService = draftService;
        this.sendQueue = sendQueue;
        this.setup = setup;
    }

    @Scheduled(cron = "${crm.schedule.scheduled-sends-cron:20 * * * * *}")
    public void tick() {
        if (setup.isSetupComplete()) sendDue();
    }

    /** Sends every scheduled email whose time has passed, earliest first. */
    public void sendDue() {
        for (Draft d : drafts.findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(DraftStatus.PENDING, OffsetDateTime.now())) {
            sendQueue.sendIfIdle(d.id, () -> send(d.id));
        }
    }

    private void send(Long draftId) {
        try {
            draftService.sendScheduled(draftId);
            log.info("Sent scheduled draft {}", draftId);
        } catch (RuntimeException e) {
            log.warn("Could not send scheduled draft {}: {}", draftId, e.getMessage());
            draftService.scheduledSendFailed(draftId, e.getMessage());
        }
    }
}

package com.creatorcrm.scoring;

import com.creatorcrm.domain.Draft;
import com.creatorcrm.domain.Enums.DraftStatus;
import com.creatorcrm.domain.Enums.DraftType;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.drafts.SendQueue;
import com.creatorcrm.repo.DraftRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.workflow.WorkflowEngine;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Saying no to several leads at once: "Decline selected" writes a polite decline draft for each, and "Approve all
 * declines" sends the decline drafts waiting in Drafts in one go. Nothing is sent without that click, and sending a
 * decline closes the deal as it always has.
 */
@Service
public class BatchDecline {
    private static final Logger log = LoggerFactory.getLogger(BatchDecline.class);
    static final int MAX_AT_ONCE = 50;
    static final String INSTRUCTIONS = "Politely decline this offer. Thank them for thinking of the creator, say it isn't "
            + "the right fit right now, and leave the door open for future collaborations. Keep it to two or three warm "
            + "sentences. Don't give reasons that invite a counter-offer, and don't mention rates.";

    /** What happened to each deal or draft that was skipped, in her words. */
    public record Result(int done, List<String> skipped) {}

    private final OpportunityRepo opportunities;
    private final DraftRepo draftRepo;
    private final DraftService drafts;
    private final WorkflowEngine workflow;
    private final SendQueue sendQueue;

    public BatchDecline(OpportunityRepo opportunities, DraftRepo draftRepo, DraftService drafts, WorkflowEngine workflow,
                        SendQueue sendQueue) {
        this.sendQueue = sendQueue;
        this.opportunities = opportunities;
        this.draftRepo = draftRepo;
        this.drafts = drafts;
        this.workflow = workflow;
    }

    public Result draftDeclines(List<Long> opportunityIds) {
        if (opportunityIds == null || opportunityIds.isEmpty()) throw new IllegalArgumentException("Pick at least one deal");
        if (opportunityIds.size() > MAX_AT_ONCE) throw new IllegalArgumentException("Pick at most " + MAX_AT_ONCE + " deals at a time");
        int done = 0;
        List<String> skipped = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(opportunityIds)) {
            Optional<Opportunity> found = opportunities.findById(id);
            if (found.isEmpty()) continue;
            Opportunity o = found.get();
            String brand = workflow.brandName(o);
            if (!o.status.isOpen()) {
                skipped.add(brand + ": already closed");
                continue;
            }
            if (draftRepo.findByOpportunityIdAndStatus(o.id, DraftStatus.PENDING).stream().anyMatch(d -> d.type == DraftType.DECLINE)) {
                skipped.add(brand + ": a decline is already in Drafts");
                continue;
            }
            try {
                drafts.generate(o.id, DraftType.DECLINE, INSTRUCTIONS, null, null);
                done++;
            } catch (RuntimeException e) {
                log.warn("Could not draft a decline for deal {}: {}", o.id, e.getMessage());
                skipped.add(brand + ": " + e.getMessage());
            }
        }
        return new Result(done, skipped);
    }

    /** Sends every decline waiting in Drafts. Ones that can't go out right now stay there, with the reason. */
    public Result sendDeclines() {
        int sent = 0;
        List<String> skipped = new ArrayList<>();
        for (Draft d : draftRepo.findByStatusOrderByCreatedAtAsc(DraftStatus.PENDING)) {
            if (d.type != DraftType.DECLINE || sendQueue.isWaiting(d.id)) continue; // already on its way
            String brand = opportunities.findById(d.opportunityId).map(workflow::brandName).orElse("?");
            Optional<String> blocked = drafts.sendBlockedReason(d);
            if (blocked.isPresent()) {
                skipped.add(brand + ": " + blocked.get());
                continue;
            }
            try {
                drafts.send(d.id, null, null);
                sent++;
            } catch (RuntimeException e) {
                log.warn("Could not send decline draft {}: {}", d.id, e.getMessage());
                skipped.add(brand + ": " + e.getMessage());
            }
        }
        return new Result(sent, skipped);
    }
}

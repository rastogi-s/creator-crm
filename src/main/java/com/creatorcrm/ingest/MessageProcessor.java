package com.creatorcrm.ingest;

import com.creatorcrm.contracts.ContractService;
import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Enums.Direction;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Task;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.llm.ClassificationInput;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.llm.MessageAnalysis;
import com.creatorcrm.llm.Untrusted;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.WorkflowEngine;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** One message through the pipeline: AI classification, then the deterministic workflow, then drafts. */
@Service
public class MessageProcessor {
    private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);
    private static final int CONTEXT_MESSAGES = 4;
    /** Older than this, a message is history (e.g. an import): update the deal, but draft no replies to it. */
    private static final Duration DRAFT_CUTOFF = Duration.ofDays(60);

    private final LlmClient llm;
    private final WorkflowEngine workflow;
    private final DraftService drafts;
    private final ConversationRepo conversations;
    private final MessageRepo messages;
    private final OpportunityRepo opportunities;
    private final BrandRepo brands;
    private final SettingsService settings;
    private final ContractService contracts;

    public MessageProcessor(LlmClient llm, WorkflowEngine workflow, DraftService drafts, ConversationRepo conversations,
                            MessageRepo messages, OpportunityRepo opportunities, BrandRepo brands,
                            SettingsService settings, ContractService contracts) {
        this.contracts = contracts;
        this.llm = llm;
        this.workflow = workflow;
        this.drafts = drafts;
        this.conversations = conversations;
        this.messages = messages;
        this.opportunities = opportunities;
        this.brands = brands;
        this.settings = settings;
    }

    public void process(Message m) {
        apply(m, llm.classify(input(m)));
    }

    /** What Claude is asked about a message, given everything already known about its conversation. */
    public ClassificationInput input(Message m) {
        Conversation conv = conversations.findById(m.conversationId).orElseThrow();
        Opportunity opp = opportunities.findFirstByConversationIdOrderByIdDesc(conv.id).orElse(null);

        List<Message> earlier = messages.findByConversationIdOrderBySentAtAsc(conv.id).stream()
                .filter(x -> !x.id.equals(m.id) && !x.sentAt.isAfter(m.sentAt))
                .toList();
        List<String> recent = earlier.subList(Math.max(0, earlier.size() - CONTEXT_MESSAGES), earlier.size())
                .stream().map(Untrusted::wrap).toList();

        return new ClassificationInput(
                settings.today(), conv.platform.name(), m.direction.name(), describe(opp),
                conv.summary == null ? "" : conv.summary, recent, Untrusted.wrap(m));
    }

    /** Apply Claude's analysis: the deterministic workflow, then drafts for recent messages. */
    public void apply(Message m, MessageAnalysis analysis) {
        Conversation conv = conversations.findById(m.conversationId).orElseThrow();
        WorkflowEngine.Outcome outcome = workflow.apply(m, conv, analysis);
        boolean recent = !m.sentAt.isBefore(OffsetDateTime.now().minus(DRAFT_CUTOFF));
        if (recent && m.direction == Direction.INBOUND && analysis.intent() == Intent.CONTRACT_SENT) {
            // Read the contract's terms; old contracts in an import aren't worth a Claude call.
            opportunities.findFirstByConversationIdOrderByIdDesc(conv.id).ifPresent(o -> contracts.onContractSent(m, o));
        }
        // A newer email in the thread would supersede the draft at once (common when importing history).
        if (m.sentAt.isBefore(OffsetDateTime.now().minus(DRAFT_CUTOFF))
                || messages.existsByConversationIdAndSentAtAfter(conv.id, m.sentAt)) return;
        for (Task t : outcome.tasksToDraft()) {
            try {
                drafts.draftForTask(t);
            } catch (RuntimeException e) {
                log.warn("Could not draft a reply for task {}: {}", t.id, e.getMessage());
            }
        }
    }

    /** Compact, trusted description of what we already know (from our own database). */
    String describe(Opportunity o) {
        if (o == null) return "";
        String brand = brands.findById(o.brandId).map((Brand b) -> b.name).orElse("?");
        StringBuilder sb = new StringBuilder()
                .append("brand=").append(brand)
                .append("; status=").append(o.status)
                .append("; stage=").append(o.stage == null ? "NOT_AGREED_YET" : o.stage)
                .append("; type=").append(o.type)
                .append("; compensation=").append(o.compensation);
        if (o.budgetText != null && !o.budgetText.isBlank()) sb.append("; budget=").append(o.budgetText);
        if (o.deliverables != null && !o.deliverables.isBlank()) sb.append("; deliverables=").append(o.deliverables);
        if (o.usageRights != null && !o.usageRights.isBlank()) sb.append("; usage=").append(o.usageRights);
        return sb.toString();
    }
}

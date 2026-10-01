package com.creatorcrm.ingest;

import com.creatorcrm.domain.Brand;
import com.creatorcrm.domain.Conversation;
import com.creatorcrm.domain.Message;
import com.creatorcrm.domain.Opportunity;
import com.creatorcrm.domain.Task;
import com.creatorcrm.drafts.DraftService;
import com.creatorcrm.llm.ClassificationInput;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.llm.MessageAnalysis;
import com.creatorcrm.llm.Untrusted;
import com.creatorcrm.repo.BrandRepo;
import com.creatorcrm.repo.ConversationRepo;
import com.creatorcrm.repo.MessageRepo;
import com.creatorcrm.repo.OpportunityRepo;
import com.creatorcrm.settings.SettingsService;
import com.creatorcrm.workflow.WorkflowEngine;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** One message through the pipeline: AI classification, then the deterministic workflow, then drafts. */
@Service
public class MessageProcessor {
    private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);
    private static final int CONTEXT_MESSAGES = 4;

    private final LlmClient llm;
    private final WorkflowEngine workflow;
    private final DraftService drafts;
    private final ConversationRepo conversations;
    private final MessageRepo messages;
    private final OpportunityRepo opportunities;
    private final BrandRepo brands;
    private final SettingsService settings;

    public MessageProcessor(LlmClient llm, WorkflowEngine workflow, DraftService drafts, ConversationRepo conversations,
                            MessageRepo messages, OpportunityRepo opportunities, BrandRepo brands,
                            SettingsService settings) {
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
        Conversation conv = conversations.findById(m.conversationId).orElseThrow();
        Opportunity opp = opportunities.findFirstByConversationIdOrderByIdDesc(conv.id).orElse(null);

        List<Message> earlier = messages.findByConversationIdOrderBySentAtAsc(conv.id).stream()
                .filter(x -> !x.id.equals(m.id) && !x.sentAt.isAfter(m.sentAt))
                .toList();
        List<String> recent = earlier.subList(Math.max(0, earlier.size() - CONTEXT_MESSAGES), earlier.size())
                .stream().map(Untrusted::wrap).toList();

        MessageAnalysis analysis = llm.classify(new ClassificationInput(
                settings.today(), conv.platform.name(), m.direction.name(), describe(opp),
                conv.summary == null ? "" : conv.summary, recent, Untrusted.wrap(m)));

        WorkflowEngine.Outcome outcome = workflow.apply(m, conv, analysis);
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
                .append("; type=").append(o.type)
                .append("; compensation=").append(o.compensation);
        if (o.budgetText != null && !o.budgetText.isBlank()) sb.append("; budget=").append(o.budgetText);
        if (o.deliverables != null && !o.deliverables.isBlank()) sb.append("; deliverables=").append(o.deliverables);
        if (o.usageRights != null && !o.usageRights.isBlank()) sb.append("; usage=").append(o.usageRights);
        return sb.toString();
    }
}

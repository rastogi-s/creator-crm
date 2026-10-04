package com.creatorcrm;

import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DeadlineType;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Priority;
import com.creatorcrm.llm.BrandLeads;
import com.creatorcrm.llm.BrandSearchInput;
import com.creatorcrm.llm.ClassificationInput;
import com.creatorcrm.llm.DraftInput;
import com.creatorcrm.llm.DraftText;
import com.creatorcrm.llm.Intent;
import com.creatorcrm.llm.LlmClient;
import com.creatorcrm.llm.MessageAnalysis;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Scripted stand-in for Claude: tests queue the analysis each message should get. */
public class FakeLlm implements LlmClient {
    public final Deque<MessageAnalysis> next = new ArrayDeque<>();
    public int draftCalls;
    public int classifyCalls;
    public DraftInput lastDraftInput;
    /** When set, every classify call throws this (simulates Claude being down). */
    public RuntimeException failWith;
    /** When set, every writeDraft call throws this. */
    public RuntimeException failDraftsWith;

    public static MessageAnalysis analysis(Intent intent, String brand, boolean requiresReply,
                                           List<MessageAnalysis.ExtractedDeadline> deadlines) {
        return new MessageAnalysis(intent != Intent.NOT_BRAND_RELATED, brand, "Maya", intent, OpportunityType.UGC,
                Compensation.PAID, 500, "USD", "$500", "1 UGC video", "", "Fall launch",
                deadlines, List.of(), requiresReply, Priority.MEDIUM, "", "Summary for " + brand);
    }

    public static MessageAnalysis.ExtractedDeadline deadline(DeadlineType type, String date) {
        return new MessageAnalysis.ExtractedDeadline(type, date, "");
    }

    @Override
    public MessageAnalysis classify(ClassificationInput input) {
        classifyCalls++;
        if (failWith != null) throw failWith;
        if (next.isEmpty()) throw new IllegalStateException("No scripted analysis for: " + input.newMessage());
        return next.poll();
    }

    @Override
    public DraftText writeDraft(DraftInput input) {
        draftCalls++;
        lastDraftInput = input;
        if (failDraftsWith != null) throw failDraftsWith;
        return new DraftText("Re: collab", "Hi Maya, thanks! [" + input.draftType() + "]");
    }

    /** What the next brand search returns. */
    public BrandLeads nextLeads = new BrandLeads(List.of());

    @Override
    public BrandLeads findBrands(BrandSearchInput input) {
        return nextLeads;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }
}

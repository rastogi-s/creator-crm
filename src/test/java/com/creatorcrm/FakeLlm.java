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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
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

    public String lastRevisionRequest;
    public DraftText lastRevisionCurrent;
    public DraftInput lastRevisionInput;

    @Override
    public DraftText reviseDraft(DraftInput input, DraftText current, String request) {
        lastRevisionInput = input;
        lastRevisionCurrent = current;
        lastRevisionRequest = request;
        if (failDraftsWith != null) throw failDraftsWith;
        return new DraftText(current.subject(), current.body() + " [" + request + "]");
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

    // ---- Batch API stand-in: tests switch it on, see what was sent, and decide when it finishes.
    public boolean batch;
    public boolean batchDone;
    public final List<Map<String, ClassificationInput>> batches = new ArrayList<>();
    public Function<ClassificationInput, MessageAnalysis> batchAnswer =
            in -> analysis(Intent.NOT_BRAND_RELATED, "", false, List.of());

    @Override
    public boolean supportsBatch() {
        return batch;
    }

    @Override
    public String submitClassifyBatch(Map<String, ClassificationInput> inputs) {
        batches.add(new LinkedHashMap<>(inputs));
        batchDone = false;
        return "batch-" + batches.size();
    }

    @Override
    public Map<String, MessageAnalysis> pollClassifyBatch(String batchId) {
        if (!batchDone) return null;
        Map<String, MessageAnalysis> out = new LinkedHashMap<>();
        batches.get(Integer.parseInt(batchId.substring(6)) - 1).forEach((id, in) -> out.put(id, batchAnswer.apply(in)));
        return out;
    }
}

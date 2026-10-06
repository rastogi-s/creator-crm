package com.creatorcrm.llm;

import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.DeadlineType;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Priority;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** Structured output of the classifier. Empty string / 0 means "not mentioned". */
public record MessageAnalysis(
        @JsonPropertyDescription("True if this message is about brand work: collaborations, sponsorships, UGC, gifting, affiliate/ambassador programs, creator applications, contracts, briefs, product shipping for a collab, content approval, invoices or payments for creator work.")
        boolean brandRelated,
        @JsonPropertyDescription("The brand's name as the brand writes it, not the agency's. Empty if unknown.")
        String brandName,
        @JsonPropertyDescription("Name of the person at the brand/agency/PR team. Empty if unknown.")
        String contactName,
        @JsonPropertyDescription("What this specific message means / asks for.")
        Intent intent,
        OpportunityType opportunityType,
        Compensation compensation,
        @JsonPropertyDescription("Budget as a number if an amount is stated, else 0.")
        double budgetAmount,
        @JsonPropertyDescription("ISO currency code, e.g. USD. Empty if unknown.")
        String currency,
        @JsonPropertyDescription("Budget/payment wording as written, e.g. '$500 + product'. Empty if not mentioned.")
        String budgetText,
        @JsonPropertyDescription("Requested deliverables, e.g. '1 Reel + 3 Stories'. Empty if not mentioned.")
        String deliverables,
        @JsonPropertyDescription("Usage rights / whitelisting / exclusivity terms. Empty if not mentioned.")
        String usageRights,
        @JsonPropertyDescription("Campaign or product name. Empty if not mentioned.")
        String campaign,
        @JsonPropertyDescription("Dates mentioned in this message, resolved to absolute dates.")
        List<ExtractedDeadline> deadlines,
        @JsonPropertyDescription("Important deal terms still unknown after this message, e.g. 'budget', 'usage rights', 'exclusivity', 'payment terms'. Only for real opportunities.")
        List<String> missingInfo,
        @JsonPropertyDescription("True if the creator needs to write back to this message.")
        boolean requiresReply,
        @JsonPropertyDescription("How time-sensitive this is.")
        Priority urgency,
        @JsonPropertyDescription("One imperative action for the creator, max 12 words, naming the brand. E.g. 'Reply to Glow Co with UGC rates'. Empty if none.")
        String suggestedAction,
        @JsonPropertyDescription("Updated 2-5 sentence summary of the whole conversation so far: who, what, money, deliverables, where it stands, what is outstanding.")
        String updatedSummary,
        @JsonPropertyDescription("Only when the creator has something to do: 2-4 plain sentences telling her what the to-do is about, so she can act without reopening the email. Say what is asked, what the opportunity is (program, product, pay or perks), what she needs ready, and any deadline. For a form or application, say what the form asks for and what it is for. Empty if no action.")
        String taskBrief,
        @JsonPropertyDescription("Links in the new message she needs for the to-do: forms, applications, briefs, contracts, product pages, shared folders. Copy each URL exactly as it appears in the message. Skip unsubscribe, tracking-pixel, social-footer and logo links. Empty list if none.")
        List<TaskLink> links,
        @JsonPropertyDescription("Where the whole deal stands right after this message, judged from the whole conversation. See 'Deal stage' in the instructions.")
        StageSeen dealStage) {

    public record TaskLink(
            @JsonPropertyDescription("Short label, e.g. 'Application form' or 'Campaign brief'.")
            String label,
            String url) {}

    public record ExtractedDeadline(
            DeadlineType type,
            @JsonPropertyDescription("YYYY-MM-DD")
            String date,
            String description) {}
}

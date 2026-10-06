package com.creatorcrm.llm;

import com.creatorcrm.domain.Enums.Compensation;
import com.creatorcrm.domain.Enums.OpportunityType;
import com.creatorcrm.domain.Enums.Priority;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Defensive checks on model output before the workflow engine acts on it. */
final class AnalysisValidator {
    private AnalysisValidator() {}

    static MessageAnalysis sanitize(MessageAnalysis a) {
        List<MessageAnalysis.ExtractedDeadline> deadlines = a.deadlines() == null ? List.of() : a.deadlines().stream()
                .filter(d -> d.type() != null && isPlausibleDate(d.date()))
                .map(d -> new MessageAnalysis.ExtractedDeadline(d.type(), d.date(), clip(d.description(), 300)))
                .limit(10)
                .toList();
        double budget = a.budgetAmount();
        if (!(budget >= 0 && budget < 10_000_000)) budget = 0;
        Intent intent = a.intent() == null ? Intent.OTHER : a.intent();
        if (!a.brandRelated()) intent = Intent.NOT_BRAND_RELATED;
        return new MessageAnalysis(
                a.brandRelated(),
                clip(a.brandName(), 120),
                clip(a.contactName(), 120),
                intent,
                a.opportunityType() == null ? OpportunityType.OTHER : a.opportunityType(),
                a.compensation() == null ? Compensation.UNKNOWN : a.compensation(),
                budget,
                clip(a.currency(), 3).toUpperCase(),
                clip(a.budgetText(), 300),
                clip(a.deliverables(), 1000),
                clip(a.usageRights(), 500),
                clip(a.campaign(), 300),
                deadlines,
                a.missingInfo() == null ? List.of() : a.missingInfo().stream().map(s -> clip(s, 80)).limit(8).toList(),
                a.requiresReply(),
                a.urgency() == null ? Priority.MEDIUM : a.urgency(),
                clip(a.suggestedAction(), 200),
                clip(a.updatedSummary(), 2000),
                clip(a.taskBrief(), 1200),
                links(a.links()),
                a.dealStage() == null || !a.brandRelated() ? StageSeen.UNCLEAR : a.dealStage());
    }

    /** Web links only, at most 6; whether each one really is in the message is checked by the workflow. */
    static List<MessageAnalysis.TaskLink> links(List<MessageAnalysis.TaskLink> links) {
        if (links == null) return List.of();
        return links.stream()
                .filter(l -> l != null && l.url() != null && l.url().strip().matches("(?i)https?://[^\\s<>\"]{1,2000}"))
                .map(l -> new MessageAnalysis.TaskLink(clip(l.label(), 80), l.url().strip()))
                .distinct()
                .limit(6)
                .toList();
    }

    private static boolean isPlausibleDate(String s) {
        try {
            LocalDate d = LocalDate.parse(s);
            LocalDate now = LocalDate.now();
            return d.isAfter(now.minusYears(1)) && d.isBefore(now.plusYears(2));
        } catch (DateTimeParseException | NullPointerException e) {
            return false;
        }
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        s = s.strip();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
